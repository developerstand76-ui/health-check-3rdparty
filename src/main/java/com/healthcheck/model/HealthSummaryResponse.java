package com.healthcheck.model;

import java.time.Instant;
import java.util.Map;

public class HealthSummaryResponse {
    private Map<HealthStatus, Long> statusCounts;
    private Instant lastUpdated;

    public HealthSummaryResponse(Map<HealthStatus, Long> statusCounts, Instant lastUpdated) {
        this.statusCounts = statusCounts;
        this.lastUpdated = lastUpdated;
    }

    public Map<HealthStatus, Long> getStatusCounts() {
        return statusCounts;
    }

    public void setStatusCounts(Map<HealthStatus, Long> statusCounts) {
        this.statusCounts = statusCounts;
    }

    public Instant getLastUpdated() {
        return lastUpdated;
    }

    public void setLastUpdated(Instant lastUpdated) {
        this.lastUpdated = lastUpdated;
    }
}
