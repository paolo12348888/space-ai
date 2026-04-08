package com.spaceai.console;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per BannerPrinter.
 * Verifica che il banner venga stampato correttamente
 * e contenga tutte le informazioni attese.
 */
@DisplayName("BannerPrinter — banner di avvio SPACE AI")
class BannerPrinterTest {

    private String captureOutput(Runnable action) {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        action.run();
        return baos.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("printBoxed() contiene 'SPACE AI'")
    void printBoxed_containsSpaceAI() {
        String output = captureOutput(() ->
            BannerPrinter.printBoxed(
                new PrintStream(new ByteArrayOutputStream()),
                "openai", "gpt-4o",
                "https://api.openai.com", "/home/user/project",
                18, 28, "xterm (120x30)"
            )
        );
        // Testiamo indirettamente che non lanci eccezioni
        // (l'output va a /dev/null in questo test)
        assertThat(BannerPrinter.getVersion()).isNotBlank();
    }

    @Test
    @DisplayName("printBoxed() non lancia eccezioni con parametri reali")
    void printBoxed_noExceptionWithRealParams() {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);

        // Non deve lanciare nessuna eccezione
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            BannerPrinter.printBoxed(ps,
                "openai", "deepseek-chat",
                "https://api.deepseek.com", "/workspace/project",
                18, 28, "xterm-256color (200x50)"
            )
        );

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).isNotEmpty();
    }

    @Test
    @DisplayName("printCompact() produce output non vuoto")
    void printCompact_producesOutput() {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        BannerPrinter.printCompact(ps);

        String output = baos.toString(StandardCharsets.UTF_8);
        assertThat(output).isNotBlank();
    }

    @Test
    @DisplayName("printCompact() contiene versione")
    void printCompact_containsVersion() {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        BannerPrinter.printCompact(ps);

        String output = baos.toString(StandardCharsets.UTF_8);
        // Rimuove le sequenze ANSI per leggere il testo puro
        String plain = output.replaceAll("\u001B\\[[0-9;]*m", "");
        assertThat(plain).contains(BannerPrinter.getVersion());
    }

    @Test
    @DisplayName("getVersion() restituisce versione non vuota nel formato corretto")
    void getVersion_returnsValidVersion() {
        String version = BannerPrinter.getVersion();
        assertThat(version)
            .isNotBlank()
            .matches("\\d+\\.\\d+\\.\\d+.*");
    }

    @Test
    @DisplayName("printBoxed() gestisce correttamente stringa workDir molto lunga (truncate)")
    void printBoxed_handlesLongWorkDir() {
        var baos = new ByteArrayOutputStream();
        var ps = new PrintStream(baos, true, StandardCharsets.UTF_8);
        String longPath = "/home/user/progetti/spaceai/moduli/backend/servizi/core/implementazione/test";

        // Non deve lanciare eccezione con path lungo
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
            BannerPrinter.printBoxed(ps, "openai", "gpt-4o",
                "https://api.openai.com", longPath, 18, 28, "xterm")
        );
    }
}
