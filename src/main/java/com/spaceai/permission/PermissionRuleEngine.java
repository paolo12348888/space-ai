package com.spaceai.permission;

import com.spaceai.permission.PermissionTypes.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Motore di regole dei permessi — prende decisioni sui permessi in base a regole, modalità e attributi degli strumenti.
 * <p>
 * Flusso decisionale:
 * <ol>
 *   <li>Controlla la modalità globale (BYPASS → tutto consentito, DONT_ASK → rifiuta quelli che richiedono conferma)</li>
 *   <li>Controlla le regole alwaysDeny → DENY se corrisponde</li>
 *   <li>Controlla le regole alwaysAllow → ALLOW se corrisponde</li>
 *   <li>Strumenti in sola lettura → ALLOW</li>
 *   <li>Operazioni su file in modalità ACCEPT_EDITS → ALLOW</li>
 *   <li>Controlla comandi pericolosi → ASK forzato</li>
 *   <li>Predefinito → ASK</li>
 * </ol>
 */
public class PermissionRuleEngine {

    private static final Set<String> FILE_EDIT_TOOLS = Set.of("Write", "Edit", "NotebookEdit");
    private static final Set<String> READ_ONLY_TOOLS = Set.of(
            "Read", "Glob", "Grep", "ListFiles", "WebFetch", "WebSearch",
            "TodoRead", "TaskGet", "TaskList", "AskUserQuestion"
    );

    private final PermissionSettings settings;

    public PermissionRuleEngine(PermissionSettings settings) {
        this.settings = settings;
    }

    /**
     * Valuta i permessi per la chiamata strumento
     *
     * @param toolName   Nome dello strumento
     * @param input      Parametri dello strumento
     * @param isReadOnly Se lo strumento è in sola lettura
     * @return decisione permessi
     */
    public PermissionDecision evaluate(String toolName, Map<String, Object> input, boolean isReadOnly) {
        PermissionMode mode = settings.getCurrentMode();

        // Modalità BYPASS: tutto consentito
        if (mode == PermissionMode.BYPASS) {
            return PermissionDecision.allow("Bypass mode enabled");
        }

        // Modalità PLAN: consente solo strumenti in sola lettura
        if (mode == PermissionMode.PLAN) {
            if (isReadOnly || READ_ONLY_TOOLS.contains(toolName)) {
                return PermissionDecision.allow("Read-only tool allowed in plan mode");
            }
            return PermissionDecision.deny("Plan mode: execution disabled (analysis only)");
        }

        // Ottieni il contenuto del comando (per la corrispondenza comandi Bash/PowerShell)
        String command = extractCommand(toolName, input);

        // Controlla tutte le regole persistenti
        List<PermissionRule> rules = settings.getAllRules();

        // 1. Controlla le regole alwaysDeny
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.DENY && matchesRule(rule, toolName, command)) {
                return PermissionDecision.deny("Denied by rule: " + PermissionSettings.formatRule(rule));
            }
        }

        // 2. Controlla le regole alwaysAllow
        for (var rule : rules) {
            if (rule.behavior() == PermissionBehavior.ALLOW && matchesRule(rule, toolName, command)) {
                return PermissionDecision.allow("Allowed by rule: " + PermissionSettings.formatRule(rule));
            }
        }

        // 3. Gli strumenti in sola lettura vengono consentiti direttamente
        if (isReadOnly || READ_ONLY_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("Read-only tool");
        }

        // 4. Modalità ACCEPT_EDITS: strumenti di operazioni su file consentiti automaticamente
        if (mode == PermissionMode.ACCEPT_EDITS && FILE_EDIT_TOOLS.contains(toolName)) {
            return PermissionDecision.allow("File edits auto-allowed in accept-edits mode");
        }

        // 5. Modalità DONT_ASK: rifiuto automatico
        if (mode == PermissionMode.DONT_ASK) {
            return PermissionDecision.deny("Auto-denied in dont-ask mode");
        }

        // 6. Controlla comandi pericolosi (ASK forzato con avviso)
        if (command != null) {
            String danger = DangerousPatterns.detectDangerous(command);
            if (danger != null) {
                String prefix = extractCommandPrefix(command);
                return new PermissionDecision(
                        PermissionBehavior.ASK,
                        "⚠ DANGEROUS: " + danger,
                        toolName, prefix, List.of()
                );
            }
        }

        // 7. Predefinito: richiede conferma utente
        String prefix = extractCommandPrefix(command);
        return PermissionDecision.ask(toolName, prefix);
    }

    /**
     * Applica le modifiche ai permessi in base alla scelta dell'utente
     */
    public void applyChoice(PermissionChoice choice, String toolName, String command) {
        String prefix = extractCommandPrefix(command);
        switch (choice) {
            case ALWAYS_ALLOW -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.ALLOW)
                        : PermissionRule.forTool(toolName, PermissionBehavior.ALLOW);
                // Controlla se è un carattere jolly pericoloso
                String ruleStr = PermissionSettings.formatRule(rule);
                if (!DangerousPatterns.isDangerousWildcard(ruleStr)) {
                    settings.addUserRule(rule);
                }
            }
            case ALWAYS_DENY -> {
                var rule = prefix != null
                        ? PermissionRule.forCommand(toolName, prefix, PermissionBehavior.DENY)
                        : PermissionRule.forTool(toolName, PermissionBehavior.DENY);
                settings.addUserRule(rule);
            }
            case ALLOW_ONCE, DENY_ONCE -> {
                // Operazione singola, non persistente
            }
        }
    }

    // ── Metodo di corrispondenza interno ──

    /** Controlla se la regola corrisponde allo strumento e al comando correnti */
    boolean matchesRule(PermissionRule rule, String toolName, String command) {
        // Salta direttamente se il nome dello strumento non corrisponde
        if (!rule.toolName().equalsIgnoreCase(toolName)) return false;

        String content = rule.ruleContent();
        // Il carattere jolly * corrisponde a tutti i comandi
        if ("*".equals(content)) return true;

        // Modalità corrispondenza prefisso: npm:* corrisponde ai comandi che iniziano con "npm"
        if (content.endsWith(":*") && command != null) {
            String prefix = content.substring(0, content.length() - 2);
            return command.toLowerCase().startsWith(prefix.toLowerCase());
        }

        // Corrispondenza esatta
        return content.equalsIgnoreCase(command);
    }

    /** Estrae il testo del comando dai parametri dello strumento */
    private String extractCommand(String toolName, Map<String, Object> input) {
        if (input == null) return null;
        return switch (toolName) {
            case "Bash" -> (String) input.get("command");
            case "Write" -> (String) input.get("file_path");
            case "Edit" -> (String) input.get("file_path");
            default -> null;
        };
    }

    /** Estrae il prefisso del comando (parte prima del primo spazio) */
    private String extractCommandPrefix(String command) {
        if (command == null || command.isBlank()) return null;
        String trimmed = command.trim();
        int space = trimmed.indexOf(' ');
        return space > 0 ? trimmed.substring(0, space) : trimmed;
    }
}
