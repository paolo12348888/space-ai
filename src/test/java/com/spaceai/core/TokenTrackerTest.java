package com.spaceai.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per TokenTracker.
 * Verifica il tracciamento dei token, il calcolo dei costi e le soglie di compressione.
 */
@DisplayName("TokenTracker — monitoraggio utilizzo token")
class TokenTrackerTest {

    private TokenTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new TokenTracker();
        tracker.setContextWindowSize(200_000);
    }

    @Nested
    @DisplayName("Contatori token")
    class CounterTests {

        @Test
        @DisplayName("Stato iniziale: tutti i contatori a zero")
        void initialState_allZero() {
            assertThat(tracker.getTotalInputTokens()).isZero();
            assertThat(tracker.getTotalOutputTokens()).isZero();
            assertThat(tracker.getApiCallCount()).isZero();
        }

        @Test
        @DisplayName("recordUsage() accumula correttamente input e output")
        void recordUsage_accumulates() {
            tracker.recordUsage(1000, 500);
            tracker.recordUsage(2000, 800);

            assertThat(tracker.getTotalInputTokens()).isEqualTo(3000);
            assertThat(tracker.getTotalOutputTokens()).isEqualTo(1300);
            assertThat(tracker.getApiCallCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("reset() azzera tutti i contatori")
        void reset_clearsAll() {
            tracker.recordUsage(5000, 2000);
            tracker.reset();

            assertThat(tracker.getTotalInputTokens()).isZero();
            assertThat(tracker.getTotalOutputTokens()).isZero();
            assertThat(tracker.getApiCallCount()).isZero();
        }
    }

    @Nested
    @DisplayName("Soglie compressione automatica")
    class ThresholdTests {

        @Test
        @DisplayName("Con 0 token usati: shouldAutoCompact = false")
        void noTokensUsed_noAutoCompact() {
            assertThat(tracker.shouldAutoCompact()).isFalse();
        }

        @Test
        @DisplayName("Con 92% token usati: shouldAutoCompact = false (sotto soglia 93%)")
        void below93Percent_noAutoCompact() {
            // Simula lastPromptTokens al 92% della finestra
            long tokens = (long) (200_000 * 0.92);
            tracker.setLastPromptTokens(tokens);
            assertThat(tracker.shouldAutoCompact()).isFalse();
        }

        @Test
        @DisplayName("Con 94% token usati: shouldAutoCompact = true (sopra soglia 93%)")
        void above93Percent_autoCompactRequired() {
            long tokens = (long) (200_000 * 0.94);
            tracker.setLastPromptTokens(tokens);
            assertThat(tracker.shouldAutoCompact()).isTrue();
        }

        @Test
        @DisplayName("Costante AUTO_COMPACT_THRESHOLD_PCT = 0.93")
        void autoCompactThreshold_isCorrect() {
            assertThat(TokenTracker.AUTO_COMPACT_THRESHOLD_PCT).isEqualTo(0.93);
        }

        @Test
        @DisplayName("Costante WARNING_THRESHOLD_PCT = 0.82")
        void warningThreshold_isCorrect() {
            assertThat(TokenTracker.WARNING_THRESHOLD_PCT).isEqualTo(0.82);
        }
    }

    @Nested
    @DisplayName("Calcolo utilizzo percentuale")
    class UsagePercentageTests {

        @Test
        @DisplayName("0 token → 0.0%")
        void noTokens_zeroPercent() {
            assertThat(tracker.getUsagePercentage()).isZero();
        }

        @Test
        @DisplayName("100K token su finestra 200K → 50%")
        void halfWindow_fiftyPercent() {
            tracker.setLastPromptTokens(100_000);
            assertThat(tracker.getUsagePercentage()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("200K token su finestra 200K → 100%")
        void fullWindow_hundredPercent() {
            tracker.setLastPromptTokens(200_000);
            assertThat(tracker.getUsagePercentage()).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("Stima costi")
    class CostEstimationTests {

        @Test
        @DisplayName("Nessun token → costo = 0.0")
        void noTokens_zeroCost() {
            assertThat(tracker.estimateCostUsd()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("1M token input a $3/M → costo = $3.0")
        void oneMilionInputTokens_correctCost() {
            tracker.setModel("deepseek-chat"); // DeepSeek prezzi
            tracker.recordUsage(1_000_000, 0);
            assertThat(tracker.estimateCostUsd()).isEqualTo(3.0, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("1M token output a $15/M → costo = $15.0")
        void oneMilionOutputTokens_correctCost() {
            tracker.setModel("deepseek-chat");
            tracker.recordUsage(0, 1_000_000);
            assertThat(tracker.estimateCostUsd()).isEqualTo(15.0, org.assertj.core.data.Offset.offset(0.01));
        }
    }

    @Nested
    @DisplayName("TokenWarningState")
    class WarningStateTests {

        @Test
        @DisplayName("0% utilizzo → stato NORMAL")
        void noUsage_normalState() {
            assertThat(tracker.getWarningState()).isEqualTo(TokenTracker.TokenWarningState.NORMAL);
        }

        @Test
        @DisplayName("85% utilizzo → stato WARNING")
        void highUsage_warningState() {
            tracker.setLastPromptTokens((long)(200_000 * 0.85));
            assertThat(tracker.getWarningState()).isEqualTo(TokenTracker.TokenWarningState.WARNING);
        }

        @Test
        @DisplayName("95% utilizzo → stato ERROR")
        void veryHighUsage_errorState() {
            tracker.setLastPromptTokens((long)(200_000 * 0.95));
            assertThat(tracker.getWarningState()).isEqualTo(TokenTracker.TokenWarningState.ERROR);
        }

        @Test
        @DisplayName("99% utilizzo → stato BLOCKING")
        void criticalUsage_blockingState() {
            tracker.setLastPromptTokens((long)(200_000 * 0.99));
            assertThat(tracker.getWarningState()).isEqualTo(TokenTracker.TokenWarningState.BLOCKING);
        }
    }
}
