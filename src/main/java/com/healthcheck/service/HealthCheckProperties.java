package com.healthcheck.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "healthcheck")
public class HealthCheckProperties {
    private Duration cacheTtl = Duration.ofSeconds(15);
    private int circuitFailureThreshold = 3;
    private Duration circuitOpenDuration = Duration.ofSeconds(30);
    private Duration retryBaseBackoff = Duration.ofMillis(200);
    private Duration schedulerDelay = Duration.ofSeconds(30);
    private int maxResponseBodyChars = 2048;

    public Duration getCacheTtl() {
        return cacheTtl;
    }

    public void setCacheTtl(Duration cacheTtl) {
        this.cacheTtl = cacheTtl;
    }

    public int getCircuitFailureThreshold() {
        return circuitFailureThreshold;
    }

    public void setCircuitFailureThreshold(int circuitFailureThreshold) {
        this.circuitFailureThreshold = circuitFailureThreshold;
    }

    public Duration getCircuitOpenDuration() {
        return circuitOpenDuration;
    }

    public void setCircuitOpenDuration(Duration circuitOpenDuration) {
        this.circuitOpenDuration = circuitOpenDuration;
    }

    public Duration getRetryBaseBackoff() {
        return retryBaseBackoff;
    }

    public void setRetryBaseBackoff(Duration retryBaseBackoff) {
        this.retryBaseBackoff = retryBaseBackoff;
    }

    public Duration getSchedulerDelay() {
        return schedulerDelay;
    }

    public void setSchedulerDelay(Duration schedulerDelay) {
        this.schedulerDelay = schedulerDelay;
    }

    public int getMaxResponseBodyChars() {
        return maxResponseBodyChars;
    }

    public void setMaxResponseBodyChars(int maxResponseBodyChars) {
        this.maxResponseBodyChars = maxResponseBodyChars;
    }
}
