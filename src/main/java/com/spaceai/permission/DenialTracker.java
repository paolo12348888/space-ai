package com.spaceai.permission;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tracker dei rifiuti —— etotalePermessoconteggio。
 * <p>
 * Corrisponde a space-ai  denialTracking.ts。
 * asoglia（3 ）ototaleasoglia（20 ），
 * fallbackaManualmentesuggerimentoModalità， auto/plan Modalitàsottonessunociclo。
 */
public class DenialTracker {

    private static final Logger log = LoggerFactory.getLogger(DenialTracker.class);

    /** soglia —— dopofallback */
    public static final int MAX_CONSECUTIVE_DENIALS = 3;

    /** totalesoglia —— dopofallback */
    public static final int MAX_TOTAL_DENIALS = 20;

    private int consecutiveDenials = 0;
    private int totalDenials = 0;

    /** recorduna volta */
    public void recordDenial() {
        consecutiveDenials++;
        totalDenials++;
        if (shouldFallbackToPrompting()) {
            log.warn("Denial threshold reached: {} consecutive, {} total — consider switching to manual mode",
                    consecutiveDenials, totalDenials);
        }
    }

    /** recorduna voltaSuccesso（reimposta，manonreimpostatotale） */
    public void recordSuccess() {
        consecutiveDenials = 0;
    }

    /**
     * sefallbackaManualmentesuggerimentoModalità。
     *  >= 3 ototale >= 20 Restituisce true。
     */
    public boolean shouldFallbackToPrompting() {
        return consecutiveDenials >= MAX_CONSECUTIVE_DENIALS
                || totalDenials >= MAX_TOTAL_DENIALS;
    }

    /** reimposta */
    public void reset() {
        consecutiveDenials = 0;
        totalDenials = 0;
    }

    public int getConsecutiveDenials() {
        return consecutiveDenials;
    }

    public int getTotalDenials() {
        return totalDenials;
    }
}
