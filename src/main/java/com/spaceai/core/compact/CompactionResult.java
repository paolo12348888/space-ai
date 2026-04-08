package com.spaceai.core.compact;

/**
 * CompressioneoperazioneRisultatodati。
 *
 * @param success         seSuccesso
 * @param layer           esegueCompressionelivello
 * @param messagesBefore  Compressioneprimamessaggio
 * @param messagesAfter   Compressionedopomessaggio
 * @param summary         AI generatoriepilogo（puòè null）
 * @param reason          Risultato/descrizione
 */
public record CompactionResult(
        boolean success,
        CompactLayer layer,
        int messagesBefore,
        int messagesAfter,
        String summary,
        String reason
) {

    /** Compressionelivello */
    public enum CompactLayer {
        /** micro-compressione：clippingvecchio tool_result contenuto */
        MICRO,
        /** Session Memory：AI riepilogovecchiomessaggio， */
        SESSION_MEMORY,
        /** compressione completa：AI riepilogoTutto，PTL Riprova */
        FULL,
        /** UtenteManualmenteattivacompressione completa */
        MANUAL
    }

    public static CompactionResult success(CompactLayer layer, int before, int after, String summary) {
        return new CompactionResult(true, layer, before, after, summary,
                "Compacted from " + before + " to " + after + " messages");
    }

    public static CompactionResult noAction(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }

    public static CompactionResult failure(CompactLayer layer, String reason) {
        return new CompactionResult(false, layer, 0, 0, null, reason);
    }
}
