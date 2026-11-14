package com.estatevista.common.health;

import java.time.Instant;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal environment health endpoint used to verify frontend-to-backend
 * connectivity. It is not a business endpoint and exposes no application data.
 */
@RestController
@RequestMapping("/api/v1/health")
public class HealthController {

    @GetMapping
    public HealthResponse health() {
        return new HealthResponse("UP", "estatevista-api", Instant.now());
    }

    public record HealthResponse(String status, String service, Instant timestamp) {
    }
}
