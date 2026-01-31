package com.healthcheck.service;

import java.time.Instant;

public class CircuitBreakerState {
    private int consecutiveFailures;
    private Instant openUntil;

    public boolean isOpen() {
        return openUntil != null && Instant.now().isBefore(openUntil);
    }

    public void recordFailure(int threshold, long openMillis) {
        consecutiveFailures++;
        if (consecutiveFailures >= threshold) {
            openUntil = Instant.now().plusMillis(openMillis);
        }
    }

    public void recordSuccess() {
        consecutiveFailures = 0;
        openUntil = null;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public Instant getOpenUntil() {
        return openUntil;
    }
}
