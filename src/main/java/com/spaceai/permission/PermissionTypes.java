package com.spaceai.permission;

import java.util.List;

/**
 * gestione permessiTipodefinizione —— Corrisponde a space-ai in permissions.ts。
 */
public final class PermissionTypes {

    private PermissionTypes() {}

    /** Permessocomportamento */
    public enum PermissionBehavior {
        ALLOW,  // consenti esecuzione
        DENY,   // esegue
        ASK     // richiedeUtenteconferma
    }

    /** modalità permessi */
    public enum PermissionMode {
        /** PredefinitoModalità：strumenti in sola letturarichiedeUtenteconferma */
        DEFAULT,
        /** Automaticamenteconsenti modifica file, i comandi shell richiedono ancora conferma */
        ACCEPT_EDITS,
        /** saltac'ècontrollo permessi（nonsicurezza） */
        BYPASS,
        /** AutomaticamenteUtente（nessunoModalità） */
        DONT_ASK,
        /** Modalità：Soloanalisinonesegue（c'èstrumenti in sola lettura） */
        PLAN
    }

    /**
     * regola permessi —— definizioneStrumentoeComandoModalitàPermessocomportamento。
     * <p>
     * Esempio：
     * <ul>
     *   <li>{@code PermissionRule("Bash", "npm:*", ALLOW)} — consenti tutti i comandi npm</li>
     *   <li>{@code PermissionRule("Bash", "rm -rf:*", DENY)} —  rm -rf</li>
     *   <li>{@code PermissionRule("Write", "*", ALLOW)} — consenti tutte le scritture su file</li>
     * </ul>
     *
     * @param toolName    Nome dello strumento（ Bash, Write, Edit）
     * @param ruleContent Regolacontenuto，supporta *（ "npm:*", "git:*", "*"）
     * @param behavior    Permessocomportamento
     */
    public record PermissionRule(
            String toolName,
            String ruleContent,
            PermissionBehavior behavior
    ) {
        /** corrisponde strumenti（nessunoComandoModalitàLimitazione） */
        public static PermissionRule forTool(String toolName, PermissionBehavior behavior) {
            return new PermissionRule(toolName, "*", behavior);
        }

        /** corrispondeStrumentoComandoprefisso */
        public static PermissionRule forCommand(String toolName, String prefix, PermissionBehavior behavior) {
            return new PermissionRule(toolName, prefix + ":*", behavior);
        }
    }

    /** decisione permessiRisultato */
    public record PermissionDecision(
            PermissionBehavior behavior,
            String reason,
            String toolName,
            String commandPrefix,
            List<PermissionRule> suggestedRules
    ) {
        public static PermissionDecision allow(String reason) {
            return new PermissionDecision(PermissionBehavior.ALLOW, reason, null, null, List.of());
        }

        public static PermissionDecision deny(String reason) {
            return new PermissionDecision(PermissionBehavior.DENY, reason, null, null, List.of());
        }

        public static PermissionDecision ask(String toolName, String commandPrefix) {
            // generaRegolaUtenteselezione "always allow"
            var suggested = List.of(
                    PermissionRule.forCommand(toolName, commandPrefix, PermissionBehavior.ALLOW)
            );
            return new PermissionDecision(PermissionBehavior.ASK, "Requires user confirmation",
                    toolName, commandPrefix, suggested);
        }

        public boolean isAllowed() {
            return behavior == PermissionBehavior.ALLOW;
        }

        public boolean isDenied() {
            return behavior == PermissionBehavior.DENY;
        }

        public boolean needsAsk() {
            return behavior == PermissionBehavior.ASK;
        }
    }

    /** conferma permessiOpzioni（Utentein UI inselezione） */
    public enum PermissionChoice {
        /** consenti questa esecuzione */
        ALLOW_ONCE,
        /** consentequestoModalità */
        ALWAYS_ALLOW,
        /** Esegui */
        DENY_ONCE,
        /** questoModalità */
        ALWAYS_DENY
    }
}
