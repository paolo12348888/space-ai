package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * /review Comando —— tramite AI eseguerevisione del codice。
 * <p>
 * supportatre tipi diModalità：
 * <ul>
 *   <li>nessunoParametri：Correntenon ancoramodifiche（{@code git diff}）</li>
 *   <li>{@code --staged}：giàmodifiche（{@code git diff --staged}）</li>
 *   <li>specificatoFilePercorso：Filemodifiche</li>
 * </ul>
 * <p>
 * Ottieni diff contenutodopo，invia a AI Modelloeseguerevisione del codice。
 */
public class ReviewCommand implements SlashCommand {

    @Override
    public String name() {
        return "review";
    }

    @Override
    public String description() {
        return "Review code changes using AI";
    }

    @Override
    public List<String> aliases() {
        return List.of("rev");
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.red("  ✗ AgentLoop unavailable, cannot perform code review.");
        }

        String trimmedArgs = args != null ? args.trim() : "";

        try {
            // Costruisci git diff Comando
            List<String> command = buildGitDiffCommand(trimmedArgs);
            String diffOutput = executeGitCommand(command);

            // Controlla diff secomevuoto
            if (diffOutput.isBlank()) {
                return AnsiStyle.yellow("  ⚠ No code changes detected.") + "\n"
                        + AnsiStyle.dim("  Tip: Use --staged to review staged changes, or specify a file path.");
            }

            // Buildsuggerimento
            String reviewPrompt = buildReviewPrompt(trimmedArgs, diffOutput);

            // Outputesegueinsuggerimento
            context.out().println(AnsiStyle.cyan("  🔍 Reviewing code changes..."));
            context.out().println(AnsiStyle.dim("  diff size: " + diffOutput.lines().count() + " lines"));
            context.out().println();

            // invia a AI esegue
            String result = context.agentLoop().run(reviewPrompt);
            return result;

        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Code review failed: " + e.getMessage()) + "\n"
                    + AnsiStyle.dim("  Please ensure the current directory is a Git repository.");
        }
    }

    /**
     * in base aParametriCostruisci git diff Comando。
     *
     * @param args UtenteInputParametri
     * @return git diff ComandoLista
     */
    private List<String> buildGitDiffCommand(String args) {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.add("diff");

        if (args.contains("--staged")) {
            // giàmodifiche
            command.add("--staged");
        } else if (!args.isEmpty() && !args.startsWith("-")) {
            // specificatoFile
            command.add("--");
            command.add(args);
        }
        // Predefinito：non ancoramodifiche（nessunoesternoParametri）

        return command;
    }

    /**
     * Esegui git ComandoeritornaOutput。
     *
     * @param command ComandoLista
     * @return ComandoOutput
     * @throws Exception esegueFallimentoLanciaeccezione
     */
    private String executeGitCommand(List<String> command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
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
            throw new RuntimeException("git diff failed (exit=" + exitCode + "): " + errorOutput);
        }

        return output;
    }

    /**
     * Buildrevisione del codiceprompt.
     *
     * @param args       UtenteInputParametri
     * @param diffOutput git diff Outputcontenuto
     * @return completoprompt
     */
    private String buildReviewPrompt(String args, String diffOutput) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Please review these code changes:\n\n");

        // descrizioneintervallo
        if (args.contains("--staged")) {
            prompt.append("(Staged changes)\n\n");
        } else if (!args.isEmpty() && !args.startsWith("-")) {
            prompt.append("(Changes in file: ").append(args).append(")\n\n");
        } else {
            prompt.append("(Unstaged changes)\n\n");
        }

        prompt.append("```diff\n");
        prompt.append(diffOutput);
        prompt.append("\n```\n\n");
        prompt.append("Please provide a thorough code review covering:\n");
        prompt.append("1. **Correctness** — Are there any bugs or logic errors?\n");
        prompt.append("2. **Code Quality** — Is the code clean, readable, and well-structured?\n");
        prompt.append("3. **Performance** — Are there any performance concerns?\n");
        prompt.append("4. **Security** — Are there any security vulnerabilities?\n");
        prompt.append("5. **Best Practices** — Does the code follow established patterns and conventions?\n");
        prompt.append("6. **Suggestions** — What improvements would you recommend?\n");

        return prompt.toString();
    }
}
