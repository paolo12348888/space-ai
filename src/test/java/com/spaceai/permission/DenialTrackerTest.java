package com.spaceai.permission;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per DenialTracker.
 * Verifica la logica di tracciamento dei rifiuti e il fallback al prompt manuale.
 */
@DisplayName("DenialTracker — tracciamento rifiuti permessi")
class DenialTrackerTest {

    private DenialTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new DenialTracker();
    }

    @Test
    @DisplayName("Stato iniziale: nessun rifiuto, nessun fallback")
    void initialState_noFallback() {
        assertThat(tracker.getConsecutiveDenials()).isZero();
        assertThat(tracker.getTotalDenials()).isZero();
        assertThat(tracker.shouldFallbackToPrompting()).isFalse();
    }

    @Test
    @DisplayName("Tre rifiuti consecutivi attivano il fallback")
    void threeConsecutiveDenials_triggersFallback() {
        tracker.recordDenial();
        tracker.recordDenial();
        assertThat(tracker.shouldFallbackToPrompting()).isFalse();

        tracker.recordDenial();
        assertThat(tracker.shouldFallbackToPrompting()).isTrue();
        assertThat(tracker.getConsecutiveDenials()).isEqualTo(3);
    }

    @Test
    @DisplayName("Un successo azzera i rifiuti consecutivi ma non il totale")
    void successResetsConsecutive_notTotal() {
        tracker.recordDenial();
        tracker.recordDenial();
        tracker.recordSuccess();

        assertThat(tracker.getConsecutiveDenials()).isZero();
        assertThat(tracker.getTotalDenials()).isEqualTo(2);
        assertThat(tracker.shouldFallbackToPrompting()).isFalse();
    }

    @Test
    @DisplayName("Venti rifiuti totali attivano il fallback indipendentemente dai consecutivi")
    void twentyTotalDenials_triggersFallback() {
        // Alterna successi e rifiuti: nessun consecutivo supera 3
        for (int i = 0; i < 10; i++) {
            tracker.recordDenial();
            tracker.recordDenial();
            tracker.recordSuccess(); // azzera consecutivi
        }
        // Totale = 20, consecutivi = 2
        assertThat(tracker.getTotalDenials()).isEqualTo(20);
        assertThat(tracker.getConsecutiveDenials()).isEqualTo(2);
        assertThat(tracker.shouldFallbackToPrompting()).isTrue();
    }

    @Test
    @DisplayName("reset() azzera tutto")
    void reset_clearsAllCounters() {
        tracker.recordDenial();
        tracker.recordDenial();
        tracker.recordDenial();
        tracker.reset();

        assertThat(tracker.getConsecutiveDenials()).isZero();
        assertThat(tracker.getTotalDenials()).isZero();
        assertThat(tracker.shouldFallbackToPrompting()).isFalse();
    }

    @Test
    @DisplayName("Costanti soglia hanno valori corretti")
    void constants_correctValues() {
        assertThat(DenialTracker.MAX_CONSECUTIVE_DENIALS).isEqualTo(3);
        assertThat(DenialTracker.MAX_TOTAL_DENIALS).isEqualTo(20);
    }
}
