package com.spaceai.repl;

import com.spaceai.command.CommandRegistry;
import com.spaceai.command.SlashCommand;
import com.spaceai.tool.ToolRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;

import java.util.List;

/**
 * Tab completamento — Corrisponde a space-ai inComandocompletamentologica。
 * <p>
 * supporta:
 * <ul>
 *   <li>comando slashcompletamento（Input / dopo Tab）</li>
 *   <li>Nome dello strumentocompletamento（inDebugodirettamentecitazione）</li>
 * </ul>
 */
public class SpaceAICompleter implements Completer {

    private final CommandRegistry commandRegistry;
    private final ToolRegistry toolRegistry;

    public SpaceAICompleter(CommandRegistry commandRegistry, ToolRegistry toolRegistry) {
        this.commandRegistry = commandRegistry;
        this.toolRegistry = toolRegistry;
    }

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        String buffer = line.line().substring(0, line.cursor());

        if (buffer.startsWith("/")) {
            // comando slashcompletamento
            completeCommands(buffer, candidates);
        }
    }

    /** completamentocomando slash */
    private void completeCommands(String buffer, List<Candidate> candidates) {
        String prefix = buffer.substring(1).toLowerCase();

        for (SlashCommand cmd : commandRegistry.getCommands()) {
            String name = cmd.name();
            if (name.startsWith(prefix)) {
                candidates.add(new Candidate(
                        "/" + name,          // completamento
                        name,                // visualizzatesto
                        "Commands",          // raggruppamento
                        cmd.description(),   // descrizione（suggerimento）
                        null,                // dopo
                        null,                // parola chiave
                        true                 // completocompletamento
                ));
            }
            // corrisponde anche agli alias
            for (String alias : cmd.aliases()) {
                if (alias.startsWith(prefix)) {
                    candidates.add(new Candidate(
                            "/" + alias,
                            alias + " → " + name,
                            "Aliases",
                            cmd.description(),
                            null, null, true
                    ));
                }
            }
        }
    }
}
