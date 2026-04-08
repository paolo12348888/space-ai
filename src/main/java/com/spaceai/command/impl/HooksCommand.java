package com.spaceai.command.impl;

import com.spaceai.command.CommandContext;
import com.spaceai.command.SlashCommand;
import com.spaceai.console.AnsiStyle;
import com.spaceai.core.HookManager;
import com.spaceai.core.HookManager.HookRegistration;
import com.spaceai.core.HookManager.HookType;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /hooks Comando —— visualizzac'èregistrato Hook Informazione。
 * <p>
 *  Hook Tiporaggruppamentovisualizza，packageogni Hook nomeepriorità。
 * Sec'èregistra Hook，visualizzasuggerimentoInformazione。
 */
public class HooksCommand implements SlashCommand {

    @Override
    public String name() {
        return "hooks";
    }

    @Override
    public String description() {
        return "Show all registered hooks";
    }

    @Override
    public String execute(String args, CommandContext context) {
        if (context.agentLoop() == null || context.agentLoop().getHookManager() == null) {
            return AnsiStyle.yellow("  ⚠ Hook manager unavailable.");
        }

        HookManager hookManager = context.agentLoop().getHookManager();
        List<HookRegistration> allHooks = hookManager.getHooks();

        // nessunoregistrato Hook
        if (allHooks.isEmpty()) {
            return AnsiStyle.dim("  No hooks registered.");
        }

        // Tiporaggruppamento
        Map<HookType, List<HookRegistration>> grouped = allHooks.stream()
                .collect(Collectors.groupingBy(HookRegistration::type));

        StringBuilder sb = new StringBuilder();
        sb.append(AnsiStyle.bold("\n  📎 Registered Hooks\n"));

        // c'è HookType，
        for (HookType type : HookType.values()) {
            List<HookRegistration> hooks = grouped.get(type);
            sb.append("\n");
            sb.append("  ").append(AnsiStyle.cyan(formatTypeName(type))).append("\n");

            if (hooks == null || hooks.isEmpty()) {
                sb.append("    ").append(AnsiStyle.dim("(none)")).append("\n");
            } else {
                // prioritàOrdinamentodopovisualizza
                hooks.stream()
                        .sorted((a, b) -> Integer.compare(a.priority(), b.priority()))
                        .forEach(hook -> {
                            String priorityStr = AnsiStyle.dim("[priority=" + hook.priority() + "]");
                            sb.append("    • ")
                                    .append(AnsiStyle.bold(hook.name()))
                                    .append("  ")
                                    .append(priorityStr)
                                    .append("\n");
                        });
            }
        }

        // totale
        sb.append("\n  ").append(AnsiStyle.dim("Total: " + allHooks.size() + " hook(s) registered.")).append("\n");
        return sb.toString();
    }

    /**
     * verrà HookType enumerazioneformatocomepuònome。
     *
     * @param type Hook Tipoenumerazione
     * @return formatodopoTiponome
     */
    private String formatTypeName(HookType type) {
        return switch (type) {
            case PRE_TOOL_USE -> "PRE_TOOL_USE (before tool execution)";
            case POST_TOOL_USE -> "POST_TOOL_USE (after tool execution)";
            case PRE_PROMPT -> "PRE_PROMPT (before sending prompt)";
            case POST_RESPONSE -> "POST_RESPONSE (after receiving response)";
        };
    }
}
