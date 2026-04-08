package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * /copy Comando —— verràuna volta AI copiaaSistema。
 * <p>
 * estrae il contenuto testuale dell'ultimo AssistantMessage dalla cronologia messaggi,
 * copia tramite l'API degli appunti AWT.
 */
public class CopyCommand implements SlashCommand {

    @Override
    public String name() {
        return "copy";
    }

    @Override
    public String description() {
        return "Copy last AI response to clipboard";
    }

    @Override
    public String execute(String args, CommandContext context) {
        // trova l'ultimo messaggio assistente nella cronologia messaggi
        List<Message> history = context.agentLoop().getMessageHistory();
        String lastResponse = null;

        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage assistant) {
                String text = assistant.getText();
                if (text != null && !text.isBlank()) {
                    lastResponse = text;
                    break;
                }
            }
        }

        if (lastResponse == null) {
            return AnsiStyle.yellow("  ⚠ No AI response to copy");
        }

        try {
            //  AWT 
            StringSelection selection = new StringSelection(lastResponse);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);

            int charCount = lastResponse.length();
            int lineCount = (int) lastResponse.lines().count();
            return AnsiStyle.green("  ✓ Copied to clipboard")
                    + AnsiStyle.dim(" (" + charCount + " chars, " + lineCount + " lines)");
        } catch (java.awt.HeadlessException e) {
            // nessuno（ SSH）non può AWT 
            return AnsiStyle.yellow("  ⚠ Clipboard not supported (headless mode)\n")
                    + AnsiStyle.dim("    Tip: Run in a graphical terminal to use this feature");
        } catch (Exception e) {
            return AnsiStyle.red("  ✗ Copy failed: " + e.getMessage());
        }
    }
}
