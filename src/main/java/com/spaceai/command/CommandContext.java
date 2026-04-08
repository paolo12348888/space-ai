package com.spaceai.command;

import com.spaceai.core.AgentLoop;
import com.spaceai.tool.ToolRegistry;

import java.io.PrintStream;

/**
 * Contesto di esecuzione dei comandi.
 */
public record CommandContext(
        AgentLoop agentLoop,
        ToolRegistry toolRegistry,
        CommandRegistry commandRegistry,
        PrintStream out,
        Runnable exitCallback
) {
}
