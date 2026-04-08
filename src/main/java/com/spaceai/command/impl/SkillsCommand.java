package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.context.SkillLoader;

import java.nio.file.Path;
import java.util.List;

/**
 * /skills Comando —— elenca tuttipuòCompetenza。
 * <p>
 * Scansiona evisualizzadaUtente、livello progettoeComandoDirectorycaricaCompetenzaFile。
 */
public class SkillsCommand implements SlashCommand {

    @Override
    public String name() {
        return "skills";
    }

    @Override
    public String description() {
        return "List available skills";
    }

    @Override
    public String execute(String args, CommandContext context) {
        Path projectDir = Path.of(System.getProperty("user.dir"));
        SkillLoader loader = new SkillLoader(projectDir);
        List<SkillLoader.Skill> skills = loader.loadAll();

        StringBuilder sb = new StringBuilder();
        sb.append("\n");
        sb.append(AnsiStyle.bold("  🎯 Available Skills\n"));
        sb.append("  ").append("─".repeat(50)).append("\n\n");

        if (skills.isEmpty()) {
            sb.append(AnsiStyle.dim("  (No available skills)\n\n"));
            sb.append(AnsiStyle.dim("  Skill file locations:\n"));
            sb.append(AnsiStyle.dim("    User:    ~/.space-ai/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    Project: ./.space-ai/skills/*.md\n"));
            sb.append(AnsiStyle.dim("    Command: ./.space-ai/commands/*.md\n"));
        } else {
            for (SkillLoader.Skill skill : skills) {
                sb.append("  ").append(AnsiStyle.cyan("▸ ")).append(AnsiStyle.bold(skill.name()));

                // tag
                String sourceLabel = switch (skill.source()) {
                    case "user" -> AnsiStyle.dim(" [user]");
                    case "project" -> AnsiStyle.dim(" [project]");
                    case "command" -> AnsiStyle.dim(" [command]");
                    default -> AnsiStyle.dim(" [" + skill.source() + "]");
                };
                sb.append(sourceLabel).append("\n");

                if (!skill.description().isEmpty()) {
                    sb.append("    ").append(skill.description()).append("\n");
                }
                if (!skill.whenToUse().isEmpty()) {
                    sb.append("    ").append(AnsiStyle.dim("When: " + skill.whenToUse())).append("\n");
                }
                sb.append("    ").append(AnsiStyle.dim("File: " + skill.filePath())).append("\n");
                sb.append("\n");
            }
            sb.append(AnsiStyle.dim("  Total " + skills.size() + " skills\n"));
        }

        return sb.toString();
    }
}
