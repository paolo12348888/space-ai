package com.spaceai.console;

import java.io.PrintStream;

/**
 * caricaanimazione（Spinner）—— Corrisponde a space-ai/src/components/Spinner.tsx。
 * <p>
 * inattende AI Rispostavisualizzarotazioneanimazione。
 */
public class SpinnerAnimation {

    private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
    private static final int INTERVAL_MS = 80;

    private final PrintStream out;
    private volatile boolean running;
    private Thread thread;
    private String message = "Thinking";

    public SpinnerAnimation(PrintStream out) {
        this.out = out;
    }

    /** Avvia spinner */
    public void start(String message) {
        if (running) return;
        this.message = message;
        this.running = true;

        thread = Thread.ofVirtual().name("spinner").start(() -> {
            int idx = 0;
            while (running) {
                out.print(AnsiStyle.clearLine());
                out.print(AnsiStyle.CYAN + "  " + FRAMES[idx % FRAMES.length]
                        + " " + AnsiStyle.RESET + AnsiStyle.dim(this.message));
                out.flush();
                idx++;
                try {
                    Thread.sleep(INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            // Cancella spinner 
            out.print(AnsiStyle.clearLine());
            out.flush();
        });
    }

    /** Ferma spinner */
    public void stop() {
        running = false;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Aggiornamentomessaggio */
    public void updateMessage(String newMessage) {
        this.message = newMessage;
    }

    public boolean isRunning() {
        return running;
    }
}
