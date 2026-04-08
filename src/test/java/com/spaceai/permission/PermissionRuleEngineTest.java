package com.spaceai.permission;

import com.spaceai.permission.PermissionTypes.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per PermissionRuleEngine.
 * Verifica il flusso decisionale in tutte le modalità e con regole personalizzate.
 */
@DisplayName("PermissionRuleEngine — motore regole permessi")
class PermissionRuleEngineTest {

    private PermissionSettings settings;
    private PermissionRuleEngine engine;

    @BeforeEach
    void setUp() {
        // Usa directory temporanea per evitare scritture su disco reale
        settings = new PermissionSettings(
            Path.of(System.getProperty("java.io.tmpdir")),
            Path.of(System.getProperty("java.io.tmpdir"))
        );
        engine = new PermissionRuleEngine(settings);
    }

    @Nested
    @DisplayName("Modalità DEFAULT")
    class DefaultModeTests {

        @Test
        @DisplayName("Strumenti in sola lettura → ALLOW automatico")
        void readOnlyTools_alwaysAllowed() {
            var result = engine.evaluate("Read", Map.of("path", "/file.txt"), true);
            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("Grep, Glob, ListFiles → ALLOW automatico")
        void searchTools_alwaysAllowed() {
            assertThat(engine.evaluate("Grep", Map.of(), true).isAllowed()).isTrue();
            assertThat(engine.evaluate("Glob", Map.of(), true).isAllowed()).isTrue();
            assertThat(engine.evaluate("ListFiles", Map.of(), true).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("Bash con comando normale → ASK")
        void bashNormalCommand_requiresAsk() {
            var result = engine.evaluate("Bash", Map.of("command", "ls -la"), false);
            assertThat(result.needsAsk()).isTrue();
        }

        @Test
        @DisplayName("Bash con comando pericoloso → ASK con avviso DANGEROUS")
        void bashDangerousCommand_askWithWarning() {
            var result = engine.evaluate("Bash", Map.of("command", "rm -rf /tmp/test"), false);
            assertThat(result.needsAsk()).isTrue();
            assertThat(result.reason()).contains("DANGEROUS");
        }

        @Test
        @DisplayName("Write su file → ASK")
        void writeFile_requiresAsk() {
            var result = engine.evaluate("Write", Map.of("file_path", "/src/Main.java"), false);
            assertThat(result.needsAsk()).isTrue();
        }
    }

    @Nested
    @DisplayName("Modalità BYPASS")
    class BypassModeTests {

        @BeforeEach
        void enableBypass() {
            settings.setMode(PermissionMode.BYPASS);
        }

        @Test
        @DisplayName("BYPASS: qualsiasi strumento → ALLOW")
        void bypass_allowsEverything() {
            assertThat(engine.evaluate("Bash", Map.of("command", "rm -rf /tmp"), false).isAllowed()).isTrue();
            assertThat(engine.evaluate("Write", Map.of("file_path", "/etc/hosts"), false).isAllowed()).isTrue();
            assertThat(engine.evaluate("Edit", Map.of(), false).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Modalità PLAN")
    class PlanModeTests {

        @BeforeEach
        void enablePlan() {
            settings.setMode(PermissionMode.PLAN);
        }

        @Test
        @DisplayName("PLAN: strumenti in sola lettura → ALLOW")
        void plan_allowsReadOnly() {
            assertThat(engine.evaluate("Read", Map.of(), true).isAllowed()).isTrue();
            assertThat(engine.evaluate("Grep", Map.of(), true).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("PLAN: strumenti di scrittura → DENY")
        void plan_deniesWriteTools() {
            var result = engine.evaluate("Bash", Map.of("command", "git status"), false);
            assertThat(result.isDenied()).isTrue();
            assertThat(result.reason()).containsIgnoringCase("plan");
        }
    }

    @Nested
    @DisplayName("Modalità ACCEPT_EDITS")
    class AcceptEditsModeTests {

        @BeforeEach
        void enableAcceptEdits() {
            settings.setMode(PermissionMode.ACCEPT_EDITS);
        }

        @Test
        @DisplayName("ACCEPT_EDITS: Write/Edit/NotebookEdit → ALLOW automatico")
        void acceptEdits_allowsFileTools() {
            assertThat(engine.evaluate("Write", Map.of("file_path", "/file.java"), false).isAllowed()).isTrue();
            assertThat(engine.evaluate("Edit", Map.of("file_path", "/file.java"), false).isAllowed()).isTrue();
            assertThat(engine.evaluate("NotebookEdit", Map.of(), false).isAllowed()).isTrue();
        }

        @Test
        @DisplayName("ACCEPT_EDITS: Bash → ASK (shell comandi ancora richiedono conferma)")
        void acceptEdits_bashStillRequiresAsk() {
            var result = engine.evaluate("Bash", Map.of("command", "mvn test"), false);
            assertThat(result.needsAsk()).isTrue();
        }
    }

    @Nested
    @DisplayName("Modalità DONT_ASK")
    class DontAskModeTests {

        @BeforeEach
        void enableDontAsk() {
            settings.setMode(PermissionMode.DONT_ASK);
        }

        @Test
        @DisplayName("DONT_ASK: strumenti non-readonly → DENY automatico")
        void dontAsk_deniesNonReadOnly() {
            var result = engine.evaluate("Bash", Map.of("command", "ls"), false);
            assertThat(result.isDenied()).isTrue();
        }

        @Test
        @DisplayName("DONT_ASK: strumenti in sola lettura → ALLOW")
        void dontAsk_allowsReadOnly() {
            assertThat(engine.evaluate("Read", Map.of(), true).isAllowed()).isTrue();
        }
    }

    @Nested
    @DisplayName("Regole personalizzate")
    class CustomRuleTests {

        @Test
        @DisplayName("Regola alwaysAllow su prefisso 'git' → ALLOW per 'git status'")
        void customAllowRule_gitPrefix() {
            settings.addSessionRule(PermissionRule.forCommand("Bash", "git", PermissionBehavior.ALLOW));
            var result = engine.evaluate("Bash", Map.of("command", "git status"), false);
            assertThat(result.isAllowed()).isTrue();
        }

        @Test
        @DisplayName("Regola alwaysDeny su 'npm' → DENY per 'npm install'")
        void customDenyRule_npmPrefix() {
            settings.addSessionRule(PermissionRule.forCommand("Bash", "npm", PermissionBehavior.DENY));
            var result = engine.evaluate("Bash", Map.of("command", "npm install express"), false);
            assertThat(result.isDenied()).isTrue();
        }

        @Test
        @DisplayName("Regola DENY ha priorità su ALLOW per stesso strumento")
        void denyTakesPriorityOverAllow() {
            // DENY registrata prima
            settings.addSessionRule(PermissionRule.forCommand("Bash", "rm", PermissionBehavior.DENY));
            settings.addSessionRule(PermissionRule.forTool("Bash", PermissionBehavior.ALLOW));
            var result = engine.evaluate("Bash", Map.of("command", "rm file.txt"), false);
            assertThat(result.isDenied()).isTrue();
        }
    }

    @Nested
    @DisplayName("matchesRule — corrispondenza regole")
    class MatchesRuleTests {

        @Test
        @DisplayName("Wildcard * corrisponde a qualsiasi comando")
        void wildcardMatchesAll() {
            var rule = PermissionRule.forTool("Bash", PermissionBehavior.ALLOW);
            assertThat(engine.matchesRule(rule, "Bash", "any command here")).isTrue();
        }

        @Test
        @DisplayName("Prefisso 'npm:*' corrisponde a 'npm install'")
        void prefixMatchesCommand() {
            var rule = PermissionRule.forCommand("Bash", "npm", PermissionBehavior.ALLOW);
            assertThat(engine.matchesRule(rule, "Bash", "npm install")).isTrue();
            assertThat(engine.matchesRule(rule, "Bash", "yarn install")).isFalse();
        }

        @Test
        @DisplayName("Tool name non corrispondente → false")
        void wrongToolName_noMatch() {
            var rule = PermissionRule.forTool("Bash", PermissionBehavior.ALLOW);
            assertThat(engine.matchesRule(rule, "Write", "any")).isFalse();
        }

        @Test
        @DisplayName("Corrispondenza case-insensitive sul nome strumento")
        void caseInsensitiveToolName() {
            var rule = PermissionRule.forTool("bash", PermissionBehavior.ALLOW);
            assertThat(engine.matchesRule(rule, "Bash", "ls")).isTrue();
        }
    }
}
