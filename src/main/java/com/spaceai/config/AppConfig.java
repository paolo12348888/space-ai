package com.spaceai.config;

import com.spaceai.command.CommandRegistry;
import com.spaceai.command.impl.*;
import com.spaceai.context.ClaudeMdLoader;
import com.spaceai.context.GitContext;
import com.spaceai.context.SkillLoader;
import com.spaceai.context.SystemPromptBuilder;
import com.spaceai.core.AgentLoop;
import com.spaceai.core.TaskManager;
import com.spaceai.core.TokenTracker;
import com.spaceai.core.compact.AutoCompactManager;
import com.spaceai.mcp.McpManager;
import com.spaceai.permission.PermissionRuleEngine;
import com.spaceai.permission.PermissionSettings;
import com.spaceai.plugin.OutputStylePlugin;
import com.spaceai.plugin.PluginManager;
import com.spaceai.repl.ReplSession;
import com.spaceai.tool.ToolContext;
import com.spaceai.tool.ToolRegistry;
import com.spaceai.tool.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * Classe di configurazione applicazione — assemblaggio Spring Bean.
 * <p>
 * Gestione centralizzata della creazione di tutti i componenti e dell'iniezione delle dipendenze.
 * Cambia il provider API tramite space-ai.provider.
 * Default: openai (compatibile DeepSeek, Qwen, ModelScope e qualsiasi API OpenAI-compatible).
 * Alternativa: anthropic (API nativa Anthropic).
 */
@Configuration
public class AppConfig {

    private static final Logger log = LoggerFactory.getLogger(AppConfig.class);

    @Value("${space-ai.provider:openai}")
    private String provider;

    @Bean
    public ToolContext toolContext() {
        return ToolContext.defaultContext();
    }

    @Bean
    public TaskManager taskManager() {
        return new TaskManager();
    }

    @Bean
    public McpManager mcpManager() {
        McpManager manager = new McpManager();
        try {
            manager.loadFromConfig();
        } catch (Exception e) {
            log.warn("MCP config loading failed (ignorable): {}", e.getMessage());
        }
        return manager;
    }

    @Bean
    public PluginManager pluginManager(ToolContext toolContext) {
        PluginManager manager = new PluginManager(toolContext);
        // Registra i plugin integrati
        var stylePlugin = new OutputStylePlugin();
        stylePlugin.initialize(new com.spaceai.plugin.PluginContext(
                toolContext, System.getProperty("user.dir"), stylePlugin.id()));
        // Carica i plugin esterni
        manager.loadAll();
        return manager;
    }

    @Bean
    public ToolRegistry toolRegistry(TaskManager taskManager, McpManager mcpManager,
                                     ToolContext toolContext) {
        // Registra TaskManager e McpManager in ToolContext per l'utilizzo da parte degli strumenti
        toolContext.set("TASK_MANAGER", taskManager);
        toolContext.set("MCP_MANAGER", mcpManager);

        ToolRegistry registry = new ToolRegistry();
        registry.registerAll(
                new BashTool(),
                new FileReadTool(),
                new FileWriteTool(),
                new FileEditTool(),
                new GlobTool(),
                new GrepTool(),
                new ListFilesTool(),
                new WebFetchTool(),
                new TodoWriteTool(),
                new AgentTool(),
                new NotebookEditTool(),
                new WebSearchTool(),
                new AskUserQuestionTool(),
                // P2: strumenti di gestione attività
                new TaskCreateTool(),
                new TaskGetTool(),
                new TaskListTool(),
                new TaskUpdateTool(),
                // P2: strumenti di configurazione
                new ConfigTool()
        );

        // P2: registra il ponte MCP strumenti (mappa gli strumenti MCP remoti come strumenti locali)
        for (var client : mcpManager.getClients().values()) {
            for (var mcpTool : client.getTools()) {
                registry.register(new McpToolBridge(client.getServerName(), mcpTool));
            }
        }

        return registry;
    }

    @Bean
    public CommandRegistry commandRegistry(PluginManager pluginManager, PermissionSettings permissionSettings) {
        ConfigCommand configCommand = new ConfigCommand(permissionSettings);
        CommandRegistry registry = new CommandRegistry();
        registry.registerAll(
                // Comandi base
                new HelpCommand(),
                new ClearCommand(),
                new CompactCommand(),
                new CostCommand(),
                new ModelCommand(),
                new StatusCommand(),
                new ContextCommand(),
                new InitCommand(),
                configCommand,
                new HistoryCommand(),
                // Comandi P0
                new DiffCommand(),
                new VersionCommand(),
                new SkillsCommand(),
                new MemoryCommand(),
                new CopyCommand(),
                // Comandi P1
                new ResumeCommand(),
                new ExportCommand(),
                new CommitCommand(),
                // Comandi P2
                new HooksCommand(),
                new ReviewCommand(),
                new StatsCommand(),
                new BranchCommand(),
                new RewindCommand(),
                new TagCommand(),
                new SecurityReviewCommand(),
                new McpCommand(),
                new PluginCommand(),
                // Exit dopo
                new ExitCommand()
        );

        // P2: registra i comandi forniti dai plugin
        pluginManager.registerCommands(registry);

        return registry;
    }

    /**
     * Seleziona il ChatModel in base alla configurazione space-ai.provider.
     */
    @Bean
    public ChatModel activeChatModel(
            @Qualifier("openAiChatModel") ChatModel openAiModel,
            @Qualifier("anthropicChatModel") ChatModel anthropicModel) {

        if ("anthropic".equalsIgnoreCase(provider)) {
            log.info("Using Anthropic native API (optional provider)");
            return anthropicModel;
        } else {
            log.info("Using OpenAI compatible API");
            return openAiModel;
        }
    }

    @Bean
    public ProviderInfo providerInfo() {
        String baseUrl;
        String model;

        if ("anthropic".equalsIgnoreCase(provider)) {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.anthropic.com");
            model = System.getenv().getOrDefault("AI_MODEL", "claude-sonnet-4-20250514"); // ID API Anthropic
        } else {
            baseUrl = System.getenv().getOrDefault("AI_BASE_URL", "https://api.deepseek.com");
            model = System.getenv().getOrDefault("AI_MODEL", "deepseek-chat");
        }

        return new ProviderInfo(provider, baseUrl, model);
    }

    @Bean
    public PermissionSettings permissionSettings() {
        PermissionSettings settings = new PermissionSettings();
        settings.load();
        return settings;
    }

    @Bean
    public PermissionRuleEngine permissionRuleEngine(PermissionSettings permissionSettings) {
        return new PermissionRuleEngine(permissionSettings);
    }

    @Bean
    public AutoCompactManager autoCompactManager(ChatModel activeChatModel, TokenTracker tokenTracker) {
        return new AutoCompactManager(activeChatModel, tokenTracker);
    }

    @Bean
    public TokenTracker tokenTracker(ProviderInfo info) {
        TokenTracker tracker = new TokenTracker();
        tracker.setModel(info.model());
        return tracker;
    }

    @Bean
    public String systemPrompt() {
        Path projectDir = Path.of(System.getProperty("user.dir"));

        ClaudeMdLoader spaceMdLoader = new ClaudeMdLoader(projectDir);
        String spaceMd = spaceMdLoader.load();

        SkillLoader skillLoader = new SkillLoader(projectDir);
        skillLoader.loadAll();
        String skillsSummary = skillLoader.buildSkillsSummary();

        GitContext gitContext = new GitContext(projectDir).collect();
        String gitSummary = gitContext.buildSummary();

        return new SystemPromptBuilder()
                .spaceMd(spaceMd)
                .skills(skillsSummary)
                .git(gitSummary)
                .build();
    }

    @Bean
    public AgentLoop agentLoop(ChatModel activeChatModel, ToolRegistry toolRegistry,
                               ToolContext toolContext, String systemPrompt, TokenTracker tokenTracker,
                               PluginManager pluginManager, PermissionRuleEngine permissionRuleEngine,
                               AutoCompactManager autoCompactManager) {
        AgentLoop mainLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt, tokenTracker);

        // Inietta il motore dei permessi e il gestore di compressione automatica
        mainLoop.setPermissionEngine(permissionRuleEngine);
        mainLoop.setAutoCompactManager(autoCompactManager);

        // Registra la factory dell'Agent secondario
        toolContext.set(AgentTool.AGENT_FACTORY_KEY,
                (java.util.function.Function<String, String>) prompt -> {
                    AgentLoop subLoop = new AgentLoop(activeChatModel, toolRegistry, toolContext, systemPrompt);
                    return subLoop.run(prompt);
                });

        // Registra PluginManager in ToolContext
        toolContext.set("PLUGIN_MANAGER", pluginManager);

        return mainLoop;
    }

    @Bean
    public ReplSession replSession(AgentLoop agentLoop, ToolRegistry toolRegistry,
                                   CommandRegistry commandRegistry, ProviderInfo providerInfo) {
        return new ReplSession(agentLoop, toolRegistry, commandRegistry, providerInfo);
    }

    /** Informazioni sul provider API */
    public record ProviderInfo(String provider, String baseUrl, String model) {
    }
}
