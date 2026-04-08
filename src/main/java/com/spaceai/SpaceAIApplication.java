package com.spaceai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Punto di ingresso principale di Space AI Java.
 * <p>
 * Corrisponde a space-ai/src/entrypoints/cli.tsx
 * Avvio come applicazione Spring Boot con server Web disabilitato (modalità CLI pura).
 */
@SpringBootApplication
public class SpaceAIApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpaceAIApplication.class, args);
    }
}
