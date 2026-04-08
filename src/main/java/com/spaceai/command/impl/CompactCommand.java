package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.TokenTracker;
import com.spaceai.core.compact.AutoCompactManager;
import com.spaceai.core.compact.FullCompact;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;

import java.util.ArrayList;
import java.util.List;

/**
 * /compact Comando ——  AI generariepilogoCompressionecontesto。
 * <p>
 * Corrisponde a space-ai/src/commands/compact.ts。
 *  FullCompact eseguerealeCompressionelogica。
 */
public class CompactCommand implements SlashCommand {

    @Override
    public String name() {
        return "compact";
    }

    @Override
    public String description() {
        return "Compact conversation context with AI summary";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null) {
            return AnsiStyle.yellow("  ⚠ No active conversation to compact.");
        }

        List<Message> history = context.agentLoop().getMessageHistory();
        int before = history.size();

        if (before <= 3) {
            return AnsiStyle.dim("  Context is already small (" + before + " messages), no compaction needed.");
        }

        TokenTracker tracker = context.agentLoop().getTokenTracker();
        long tokensBefore = tracker.getInputTokens() + tracker.getOutputTokens();

        // preferisce usare FullCompact in AutoCompactManager
        FullCompact fullCompact;
        AutoCompactManager acm = context.agentLoop().getAutoCompactManager();
        if (acm != null) {
            fullCompact = acm.getFullCompact();
        } else {
            fullCompact = new FullCompact(context.agentLoop().getChatModel());
        }

        // eseguecompressione completa
        List<Message> compacted = fullCompact.compact(new ArrayList<>(history));

        if (compacted != null) {
            context.agentLoop().replaceHistory(compacted);
            int after = compacted.size();

            // reimpostacircuit breaker（ManualmenteCompressioneSuccessoDescrizione AI riepilogoFunzionepositivo）
            if (acm != null) {
                acm.resetCircuitBreaker();
            }

            StringBuilder sb = new StringBuilder();
            sb.append(AnsiStyle.green("  ✅ Context compacted")).append("\n");
            sb.append("  Messages: ").append(before).append(" → ").append(after).append("\n");
            if (tokensBefore > 0) {
                sb.append("  Tokens before compaction: ").append(TokenTracker.formatTokens(tokensBefore)).append("\n");
            }
            sb.append(AnsiStyle.dim("  📝 AI summary generated and injected into context"));
            return sb.toString();
        }

        return AnsiStyle.yellow("  ⚠ Compaction failed — AI summary generation failed") + "\n"
                + AnsiStyle.dim("  The conversation history was not modified.");
    }
}
