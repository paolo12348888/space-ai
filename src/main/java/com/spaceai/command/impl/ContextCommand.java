package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.context.ClaudeMdLoader;
import com.spaceai.context.GitContext;
import com.spaceai.context.SkillLoader;

import java.nio.file.Path;

/**
 * /context Comando —— visualizzaCorrentecontestoInformazione。
 * <p>
 * Visualizza il contesto caricato: SPACE.md, Skills, contesto Git e utilizzo Token.
 */
public class ContextCommand implements SlashCommand {

    @Override
    public String name() {
        return "context";
    }

    @Override
    public String description() {
        return "Show current context (SPACE.md, skills, git)";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  📋 Context Overview\n"));
        sb.append("  ").append("─".repeat(40)).append("\n\n");

        // SPACE.md Stato
        sb.append(AnsiStyle.bold("  SPACE.md:\n"));
        ClaudeMdLoader loader = new ClaudeMdLoader(projectDir);
        String spaceMd = loader.load();
        if (spaceMd.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none loaded) — run /init to create one\n"));
        } else {
            // visualizzariepilogo（prima 200 carattere）
            int lines = spaceMd.split("\n").length;
            sb.append("    ").append(AnsiStyle.green(lines + " lines loaded")).append("\n");
            String preview = spaceMd.length() > 200
                    ? spaceMd.substring(0, 200) + "..."
                    : spaceMd;
            for (String line : preview.split("\n")) {
                sb.append(AnsiStyle.dim("    │ " + line)).append("\n");
            }
        }
        sb.append("\n");

        // Skills Stato
        sb.append(AnsiStyle.bold("  Skills:\n"));
        SkillLoader skillLoader = new SkillLoader(projectDir);
        var skills = skillLoader.loadAll();
        if (skills.isEmpty()) {
            sb.append(AnsiStyle.dim("    (none loaded) — add .md files to .space-ai/skills/\n"));
        } else {
            for (var skill : skills) {
                sb.append("    • ").append(AnsiStyle.cyan(skill.name()));
                if (!skill.description().isEmpty()) {
                    sb.append(AnsiStyle.dim(" — " + skill.description()));
                }
                sb.append(AnsiStyle.dim(" [" + skill.source() + "]")).append("\n");
            }
        }
        sb.append("\n");

        // contesto Git
        sb.append(AnsiStyle.bold("  Git:\n"));
        GitContext git = new GitContext(projectDir).collect();
        if (!git.isGitRepo()) {
            sb.append(AnsiStyle.dim("    (not a git repository)\n"));
        } else {
            sb.append("    Branch: ").append(AnsiStyle.cyan(git.getBranch() != null ? git.getBranch() : "unknown")).append("\n");
            if (git.getStatus() != null) {
                long modifiedCount = git.getStatus().lines()
                        .filter(l -> !l.startsWith("##"))
                        .count();
                if (modifiedCount > 0) {
                    sb.append("    Modified: ").append(AnsiStyle.yellow(modifiedCount + " file(s)")).append("\n");
                } else {
                    sb.append("    Working tree: ").append(AnsiStyle.green("clean")).append("\n");
                }
            }
        }
        sb.append("\n");

        // Token 
        sb.append(AnsiStyle.bold("  System Prompt:\n"));
        String sysPrompt = context.agentLoop().getSystemPrompt();
        int charCount = sysPrompt.length();
        int estimatedTokens = charCount / 4; // 
        sb.append("    Size: ").append(charCount).append(" chars (~").append(estimatedTokens).append(" tokens)\n");

        return sb.toString();
    }
}
