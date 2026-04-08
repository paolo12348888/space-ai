package com.spaceai.permission;

import com.spaceai.permission.PermissionTypes.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per PermissionTypes.
 * Verifica la creazione di regole, decisioni e comportamento dei record immutabili.
 */
@DisplayName("PermissionTypes — tipi di permesso")
class PermissionTypesTest {

    @Nested
    @DisplayName("PermissionRule")
    class PermissionRuleTests {

        @Test
        @DisplayName("forTool() crea regola con wildcard *")
        void forTool_createsWildcardRule() {
            var rule = PermissionRule.forTool("Bash", PermissionBehavior.ALLOW);
            assertThat(rule.toolName()).isEqualTo("Bash");
            assertThat(rule.ruleContent()).isEqualTo("*");
            assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        }

        @Test
        @DisplayName("forCommand() crea regola con prefisso 'npm:*'")
        void forCommand_createsPrefixRule() {
            var rule = PermissionRule.forCommand("Bash", "npm", PermissionBehavior.ALLOW);
            assertThat(rule.toolName()).isEqualTo("Bash");
            assertThat(rule.ruleContent()).isEqualTo("npm:*");
            assertThat(rule.behavior()).isEqualTo(PermissionBehavior.ALLOW);
        }

        @Test
        @DisplayName("forCommand() con DENY crea regola di blocco")
        void forCommand_denyRule() {
            var rule = PermissionRule.forCommand("Bash", "rm -rf", PermissionBehavior.DENY);
            assertThat(rule.ruleContent()).isEqualTo("rm -rf:*");
            assertThat(rule.behavior()).isEqualTo(PermissionBehavior.DENY);
        }
    }

    @Nested
    @DisplayName("PermissionDecision")
    class PermissionDecisionTests {

        @Test
        @DisplayName("allow() crea decisione ALLOW con motivo")
        void allow_setsCorrectBehavior() {
            var decision = PermissionDecision.allow("Strumento in sola lettura");
            assertThat(decision.isAllowed()).isTrue();
            assertThat(decision.isDenied()).isFalse();
            assertThat(decision.needsAsk()).isFalse();
            assertThat(decision.reason()).isEqualTo("Strumento in sola lettura");
        }

        @Test
        @DisplayName("deny() crea decisione DENY con motivo")
        void deny_setsCorrectBehavior() {
            var decision = PermissionDecision.deny("Comando pericoloso rilevato");
            assertThat(decision.isDenied()).isTrue();
            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.needsAsk()).isFalse();
        }

        @Test
        @DisplayName("ask() crea decisione ASK con regole suggerite")
        void ask_createsSuggestedRules() {
            var decision = PermissionDecision.ask("Bash", "git");
            assertThat(decision.needsAsk()).isTrue();
            assertThat(decision.isAllowed()).isFalse();
            assertThat(decision.toolName()).isEqualTo("Bash");
            assertThat(decision.commandPrefix()).isEqualTo("git");
            assertThat(decision.suggestedRules()).hasSize(1);
            assertThat(decision.suggestedRules().get(0).ruleContent()).isEqualTo("git:*");
        }
    }

    @Nested
    @DisplayName("PermissionMode")
    class PermissionModeTests {

        @Test
        @DisplayName("Tutte le 5 modalità esistono")
        void allFiveModesExist() {
            var modes = PermissionMode.values();
            assertThat(modes).contains(
                PermissionMode.DEFAULT,
                PermissionMode.ACCEPT_EDITS,
                PermissionMode.BYPASS,
                PermissionMode.DONT_ASK,
                PermissionMode.PLAN
            );
        }
    }

    @Nested
    @DisplayName("PermissionChoice")
    class PermissionChoiceTests {

        @Test
        @DisplayName("Tutte le 4 opzioni di scelta esistono")
        void allFourChoicesExist() {
            var choices = PermissionChoice.values();
            assertThat(choices).contains(
                PermissionChoice.ALLOW_ONCE,
                PermissionChoice.ALWAYS_ALLOW,
                PermissionChoice.DENY_ONCE,
                PermissionChoice.ALWAYS_DENY
            );
        }
    }
}
