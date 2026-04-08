package com.spaceai.cli;

import com.spaceai.repl.ReplSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Orchestratore di avvio — corrisponde alla logica di inizializzazione di space-ai/src/main.tsx.
 * <p>
 * Eseguito al termine dell'avvio di Spring Boot, inizializza e avvia la sessione REPL.
 */
@Component
public class SpaceAIRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(SpaceAIRunner.class);

    private final ReplSession replSession;

    public SpaceAIRunner(ReplSession replSession) {
        this.replSession = replSession;
    }

    @Override
    public void run(String... args) {
        log.info("Space AI (Java) starting...");
        replSession.start();
    }
}
