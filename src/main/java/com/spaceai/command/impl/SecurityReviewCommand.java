package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /security-review Comando —— tramite AI eseguerevisione della sicurezza。
 * <p>
 * ottieniCorrenteprogettoPiù recentecodicemodifiche（{@code git diff HEAD}），
 * invia a AI Modelloeseguesicurezza。
 * <p>
 * package：
 * <ul>
 *   <li>SQL inietta</li>
 *   <li>（XSS）</li>
 *   <li>AutenticazioneeAutorizzazione</li>
 *   <li>Informazione（Chiave、ecc.)</li>
 *   <li>Dipendenzasicurezza</li>
 * </ul>
 */
public class SecurityReviewCommand implements SlashCommand {

    @Override
    public String name() {
        return "security-review";
    }

    @Override
    public String description() {
        return "Review code changes for security vulnerabilities";
    }

    @Override
    public List<String> aliases() {
        return List.of("sec");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable, cannot perform security review.");
        }

        try {
            // ottienicodicemodifiche
            String diffOutput = executeGitDiff();

            if (diffOutput.isBlank()) {
                return AnsiStyle.yellow("  ⚠ No code changes detected.") + "\n"
                        + AnsiStyle.dim("  git diff HEAD returned nothing. Please verify there are commits.");
            }

            // Outputesegueinsuggerimento
            context.out().println(AnsiStyle.magenta("  🔒 Performing security review..."));
            context.out().println(AnsiStyle.dim("  diff size: " + diffOutput.lines().count() + " lines"));
            context.out().println();

            // Buildrevisione della sicurezzaprompt
            String securityPrompt = buildSecurityPrompt(diffOutput);

            // invia a AI eseguerevisione della sicurezza
            String result = context.agentLoop().run(securityPrompt);
            return result;

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Security review failed: " + e.getMessage()) + "\n"
                    + AnsiStyle.dim("  Please ensure the current directory is a Git repository with commit history.");
        }
    }

    /**
     * Esegui {@code git diff HEAD} ottienicodicemodifiche。
     *
     * @return diff Outputcontenuto
     * @throws Exception ComandoesegueFallimentoLanciaeccezione
     */
    private String executeGitDiff() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("git", "diff", "HEAD");
        pb.redirectErrorStream(false);

        Process process = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        // leggeErroreOutput
        String errorOutput;
        try (BufferedReader errorReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            errorOutput = errorReader.lines().collect(Collectors.joining("\n"));
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("git diff HEAD failed (exit=" + exitCode + "): " + errorOutput);
        }

        return output;
    }

    /**
     * Buildrevisione della sicurezzaprompt.
     * <p>
     *  AI dapiùsicurezzasucodicemodificheesegue，
     * edividiclassescopre。
     *
     * @param diffOutput git diff Outputcontenuto
     * @return completorevisione della sicurezzaprompt
     */
    private String buildSecurityPrompt(String diffOutput) {
        return """
                Please perform a comprehensive security review of the following code changes.
                
                ```diff
                %s
                ```
                
                Analyze the code changes for the following security concerns:
                
                ## 1. SQL Injection
                - Are there any raw SQL queries with string concatenation?
                - Are parameterized queries/prepared statements used properly?
                - Is user input sanitized before database operations?
                
                ## 2. Cross-Site Scripting (XSS)
                - Is user input properly escaped before rendering in HTML?
                - Are there any unsafe innerHTML or DOM manipulation patterns?
                - Is output encoding applied correctly?
                
                ## 3. Authentication & Authorization
                - Are authentication checks properly implemented?
                - Are authorization boundaries enforced correctly?
                - Are session tokens handled securely?
                - Are passwords hashed with strong algorithms (bcrypt, Argon2)?
                
                ## 4. Secrets & Sensitive Data
                - Are any API keys, passwords, or tokens hardcoded?
                - Are sensitive data properly encrypted at rest and in transit?
                - Are credentials stored in environment variables or secure vaults?
                - Are there any logging statements that might expose sensitive information?
                
                ## 5. Dependency & Configuration Security
                - Are there any known vulnerable dependencies?
                - Are security headers properly configured?
                - Are CORS policies appropriately restrictive?
                - Is TLS/SSL properly configured?
                
                ## 6. Other Security Concerns
                - Path traversal vulnerabilities
                - Command injection risks
                - Insecure deserialization
                - Race conditions
                - Improper error handling that leaks information
                
                For each issue found, please provide:
                - **Severity**: Critical / High / Medium / Low
                - **Location**: File and line reference
                - **Description**: What the vulnerability is
                - **Recommendation**: How to fix it
                
                If no security issues are found, explicitly state that the changes look secure.
                """.formatted(diffOutput);
    }
}
