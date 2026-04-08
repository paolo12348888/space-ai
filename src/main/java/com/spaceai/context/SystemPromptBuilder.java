package com.spaceai.context;

/**
 * Costruttore del prompt di sistema — corrisponde a space-ai/src/prompts.ts.
 * <p>
 * Assembla il prompt di sistema completo, includendo istruzioni principali,
 * informazioni sull'ambiente, SPACE.md, Skills, contesto Git e altro.
 */
public class SystemPromptBuilder {

    private String workDir;
    private String osName;
    private String userName;
    private String spaceMdContent;
    private String customInstructions;
    private String skillsSummary;
    private String gitSummary;

    public SystemPromptBuilder() {
        this.workDir = System.getProperty("user.dir");
        this.osName  = System.getProperty("os.name");
        this.userName = System.getProperty("user.name");
    }

    public SystemPromptBuilder workDir(String workDir) {
        this.workDir = workDir;
        return this;
    }

    public SystemPromptBuilder spaceMd(String content) {
        this.spaceMdContent = content;
        return this;
    }

    public SystemPromptBuilder customInstructions(String instructions) {
        this.customInstructions = instructions;
        return this;
    }

    public SystemPromptBuilder skills(String skillsSummary) {
        this.skillsSummary = skillsSummary;
        return this;
    }

    public SystemPromptBuilder git(String gitSummary) {
        this.gitSummary = gitSummary;
        return this;
    }

    /**
     * Costruisce il prompt di sistema completo.
     */
    public String build() {
        StringBuilder sb = new StringBuilder();

        // ── Identità SPACE AI ──────────────────────────────────────────────
        sb.append("""
                You are SPACE AI, an advanced AI coding assistant operating as a CLI agent.
                You are an interactive command-line tool that helps users with software
                engineering tasks using precision, creativity, and speed.
                Use the provided tools to help the user with their request.

                """);

        // ── Ambiente ───────────────────────────────────────────────────────
        sb.append("# Environment\n");
        sb.append("- Working directory: ").append(workDir).append("\n");
        sb.append("- OS: ").append(osName).append("\n");
        sb.append("- User: ").append(userName).append("\n");
        sb.append("- Shell: ").append(System.getenv().getOrDefault("SHELL",
                System.getenv().getOrDefault("COMSPEC", "unknown"))).append("\n");
        sb.append("\n");

        // ── Linee guida comportamentali ────────────────────────────────────
        sb.append("""
                # Guidelines
                - Be concise in responses, but thorough in implementation
                - Always verify changes work before considering a task done
                - Use tools to explore the codebase before making changes
                - When writing code, follow existing patterns and conventions
                - Ask for clarification when requirements are ambiguous
                - When making file edits, always use the Edit tool with exact string matching
                - Prefer editing existing files over creating new ones

                """);

        // ── Contesto Git ───────────────────────────────────────────────────
        if (gitSummary != null && !gitSummary.isBlank()) {
            sb.append(gitSummary).append("\n");
        }

        // ── Contenuto SPACE.md (istruzioni progetto) ───────────────────────
        if (spaceMdContent != null && !spaceMdContent.isBlank()) {
            sb.append("# Project Instructions (SPACE.md)\n");
            sb.append(spaceMdContent).append("\n\n");
        }

        // ── Skills disponibili ─────────────────────────────────────────────
        if (skillsSummary != null && !skillsSummary.isBlank()) {
            sb.append(skillsSummary).append("\n");
        }

        // ── Istruzioni personalizzate ──────────────────────────────────────
        if (customInstructions != null && !customInstructions.isBlank()) {
            sb.append("# Custom Instructions\n");
            sb.append(customInstructions).append("\n\n");
        }

        return sb.toString();
    }
}
