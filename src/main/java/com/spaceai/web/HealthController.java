package com.spaceai.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Controller HTTP per il health-check di Render.
 * <p>
 * Render richiede che il servizio risponda su una porta HTTP.
 * Questo controller espone un endpoint minimale che conferma
 * che SPACE AI è attivo e funzionante.
 */
@RestController
public class HealthController {

    private static final Instant startTime = Instant.now();

    /**
     * Health check principale — usato da Render per verificare
     * che il servizio sia vivo. Risponde su GET /actuator/health
     */
    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "UP",
            "service", "SPACE AI",
            "version", "0.1.0-SNAPSHOT",
            "uptime_seconds", java.time.Duration.between(startTime, Instant.now()).getSeconds()
        ));
    }

    /**
     * Endpoint root — risponde su GET /
     * Mostra info di base del servizio.
     */
    @GetMapping("/")
    public ResponseEntity<Map<String, Object>> root() {
        return ResponseEntity.ok(Map.of(
            "name", "🌌 SPACE AI",
            "description", "Il tuo compagno di programmazione cosmico",
            "status", "running",
            "version", "0.1.0-SNAPSHOT",
            "api_endpoint", "/actuator/health"
        ));
    }
}
