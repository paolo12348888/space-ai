package com.spaceai.permission;

import java.util.List;
import java.util.Set;

/**
 * comandi pericolosiModalitàrileva —— puòc'è shell Comando。
 * <p>
 * in BYPASS Modalitàsottoemette anchesuComandoAvviso。
 */
public final class DangerousPatterns {

    private DangerousPatterns() {}

    /**  shell Comandoprefisso（nondivididimensionecorrisponde） */
    private static final List<String> DANGEROUS_BASH_PREFIXES = List.of(
            "rm -rf /",
            "rm -rf ~",
            "rm -rf .",
            "rm -r /",
            "rmdir /s",
            "del /f /s /q",
            "format ",
            "mkfs.",
            "dd if=",
            "> /dev/sda",
            "chmod -R 777 /",
            "chown -R",
            ":(){:|:&};:"       // fork bomb
    );

    /** codiceesegueModalità */
    private static final List<String> CODE_EXECUTION_PATTERNS = List.of(
            "eval ",
            "exec ",
            "python -c",
            "python3 -c",
            "node -e",
            "ruby -e",
            "perl -e",
            "| sh",
            "| bash",
            "| zsh",
            "| powershell",
            "| pwsh",
            "curl | sh",
            "wget | sh",
            "Invoke-Expression",
            "iex ",
            "Start-Process",
            "Add-Type"
    );

    /** inRegolacorrispondeinAutomaticamenteStrumento */
    private static final Set<String> DANGEROUS_TOOL_WILDCARDS = Set.of(
            "Bash",         // non deve consentire tutti i comandi bash
            "Bash(*)",
            "PowerShell",
            "PowerShell(*)"
    );

    /**
     * rilevaComandosepackageschemi pericolosi
     *
     * @param command shell Comandotesto
     * @return Seritornadescrizione，noRestituisce null
     */
    public static String detectDangerous(String command) {
        if (command == null || command.isBlank()) return null;
        String lower = command.toLowerCase().trim();

        for (String prefix : DANGEROUS_BASH_PREFIXES) {
            if (lower.startsWith(prefix.toLowerCase()) || lower.contains(prefix.toLowerCase())) {
                return "Dangerous command detected: " + prefix.trim();
            }
        }

        for (String pattern : CODE_EXECUTION_PATTERNS) {
            if (lower.contains(pattern.toLowerCase())) {
                return "Code execution pattern detected: " + pattern.trim();
            }
        }

        return null;
    }

    /**
     * rilevasecomeStrumentoRegola
     * <p>
     * inUtenteaggiungein "always allow" Regola。
     */
    public static boolean isDangerousWildcard(String ruleStr) {
        return DANGEROUS_TOOL_WILDCARDS.contains(ruleStr);
    }

    /**
     * ottienibrevedescrizione
     */
    public static String getDangerLevel(String command) {
        String reason = detectDangerous(command);
        if (reason == null) return "LOW";
        if (reason.contains("Dangerous command")) return "HIGH";
        return "MEDIUM";
    }
}
