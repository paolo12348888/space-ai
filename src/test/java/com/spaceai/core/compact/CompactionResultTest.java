package com.spaceai.core.compact;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per CompactionResult.
 * Verifica la creazione e il comportamento dei risultati di compressione.
 */
@DisplayName("CompactionResult — risultati compressione contesto")
class CompactionResultTest {

    @Test
    @DisplayName("success() crea un risultato positivo con statistiche corrette")
    void success_createsCorrectResult() {
        var result = CompactionResult.success(
            CompactionResult.CompactLayer.MICRO,
            100, 60, "Truncated 40 tool results"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.layer()).isEqualTo(CompactionResult.CompactLayer.MICRO);
        assertThat(result.messagesBefore()).isEqualTo(100);
        assertThat(result.messagesAfter()).isEqualTo(60);
        assertThat(result.description()).contains("40");
    }

    @Test
    @DisplayName("failure() crea un risultato negativo")
    void failure_createsFailedResult() {
        var result = CompactionResult.failure(
            CompactionResult.CompactLayer.SESSION_MEMORY,
            "AI API timeout"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.layer()).isEqualTo(CompactionResult.CompactLayer.SESSION_MEMORY);
        assertThat(result.description()).contains("timeout");
    }

    @Test
    @DisplayName("noAction() crea risultato di nessuna azione")
    void noAction_createsNoOpResult() {
        var result = CompactionResult.noAction(
            CompactionResult.CompactLayer.MICRO,
            "No tool results to truncate"
        );

        assertThat(result.success()).isFalse();
        assertThat(result.messagesBefore()).isZero();
        assertThat(result.messagesAfter()).isZero();
    }

    @Test
    @DisplayName("Tutti e tre i livelli di compressione esistono")
    void allThreeLayersExist() {
        var layers = CompactionResult.CompactLayer.values();
        assertThat(layers).contains(
            CompactionResult.CompactLayer.MICRO,
            CompactionResult.CompactLayer.SESSION_MEMORY,
            CompactionResult.CompactLayer.FULL
        );
    }

    @Test
    @DisplayName("Riduzione calcolata correttamente: (100-60)/100 = 40%")
    void reductionRatio_calculatedCorrectly() {
        var result = CompactionResult.success(
            CompactionResult.CompactLayer.FULL, 100, 60, "Full compact"
        );
        double reduction = 1.0 - ((double) result.messagesAfter() / result.messagesBefore());
        assertThat(reduction).isEqualTo(0.4, org.assertj.core.data.Offset.offset(0.001));
    }
}
