package com.healthcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthcheck.model.*;
import com.healthcheck.transport.HttpResponseData;
import com.healthcheck.transport.HttpTransport;
import com.healthcheck.transport.JavaHttpTransport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import javax.net.ssl.SSLHandshakeException;

@Service
public class HealthCheckService {
    private final Map<UUID, Target> targets = new ConcurrentHashMap<>();
    private final Map<UUID, HealthCheckResult> lastResults = new ConcurrentHashMap<>();
    private final Map<UUID, CircuitBreakerState> circuitBreakers = new ConcurrentHashMap<>();
    private final HealthCheckProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpTransport transport;

    @Autowired
    public HealthCheckService(HealthCheckProperties properties) {
        this(properties, new ObjectMapper(), new JavaHttpTransport());
    }

    public HealthCheckService(HealthCheckProperties properties, ObjectMapper objectMapper, HttpTransport transport) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transport = transport;
    }

    public Target createTarget(CreateTargetRequest request) {
        UUID id = UUID.randomUUID();
        String contentType = request.getContentType();
        if (contentType == null && request.getRequestBody() != null) {
            contentType = "application/json";
        }

        Target target = new Target(
            id,
            request.getName(),
            request.getUrl(),
            request.getMethod(),
            request.getHeaders(),
            request.getRequestBody(),
            contentType,
            request.getTimeout(),
            request.getExpectedStatusMin(),
            request.getExpectedStatusMax(),
            request.isExpectJson(),
            request.getExpectedBodyContains(),
            request.getSlowThreshold(),
            request.getMaxRetries()
        );
        targets.put(id, target);
        circuitBreakers.putIfAbsent(id, new CircuitBreakerState());
        return target;
    }

    public List<Target> listTargets() {
        return new ArrayList<>(targets.values());
    }

    public Target getTarget(UUID id) {
        return targets.get(id);
    }

    public Target updateTarget(UUID id, UpdateTargetRequest request) {
        Target target = targets.get(id);
        if (target == null) {
            return null;
        }
        if (request.getName() != null) {
            target.setName(request.getName());
        }
        if (request.getUrl() != null) {
            target.setUrl(request.getUrl());
        }
        if (request.getMethod() != null) {
            target.setMethod(request.getMethod());
        }
        if (request.getHeaders() != null) {
            target.setHeaders(request.getHeaders());
        }
        if (request.getRequestBody() != null) {
            target.setRequestBody(request.getRequestBody());
            if (request.getContentType() == null && target.getContentType() == null) {
                target.setContentType("application/json");
            }
        }
        if (request.getContentType() != null) {
            target.setContentType(request.getContentType());
        }
        if (request.getTimeout() != null) {
            target.setTimeout(request.getTimeout());
        }
        if (request.getExpectedStatusMin() != null) {
            target.setExpectedStatusMin(request.getExpectedStatusMin());
        }
        if (request.getExpectedStatusMax() != null) {
            target.setExpectedStatusMax(request.getExpectedStatusMax());
        }
        if (request.getExpectJson() != null) {
            target.setExpectJson(request.getExpectJson());
        }
        if (request.getExpectedBodyContains() != null) {
            target.setExpectedBodyContains(request.getExpectedBodyContains());
        }
        if (request.getSlowThreshold() != null) {
            target.setSlowThreshold(request.getSlowThreshold());
        }
        if (request.getMaxRetries() != null) {
            target.setMaxRetries(request.getMaxRetries());
        }
        return target;
    }

    public boolean deleteTarget(UUID id) {
        return targets.remove(id) != null;
    }

    public HealthCheckResult checkTarget(UUID id, boolean force) {
        Target target = targets.get(id);
        if (target == null) {
            return null;
        }

        HealthCheckResult cached = lastResults.get(id);
        if (!force && cached != null && cached.getTimestamp() != null) {
            Duration age = Duration.between(cached.getTimestamp(), Instant.now());
            if (age.compareTo(properties.getCacheTtl()) < 0) {
                HealthCheckResult copy = copyResult(cached);
                copy.setFromCache(true);
                copy.setCachedAt(cached.getTimestamp());
                return copy;
            }
        }

        CircuitBreakerState breaker = circuitBreakers.computeIfAbsent(id, key -> new CircuitBreakerState());
        if (breaker.isOpen()) {
            HealthCheckResult result = baseResult(id);
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.CIRCUIT_OPEN);
            result.setErrorMessage("Circuit breaker open");
            result.setTimestamp(Instant.now());
            lastResults.put(id, result);
            return result;
        }

        HealthCheckResult result = attemptWithRetries(target);
        lastResults.put(id, result);

        if (result.getStatus() == HealthStatus.UP) {
            breaker.recordSuccess();
        } else {
            breaker.recordFailure(properties.getCircuitFailureThreshold(), properties.getCircuitOpenDuration().toMillis());
        }
        return result;
    }

    public Map<UUID, HealthCheckResult> getLastResults() {
        return lastResults;
    }

    public HealthSummaryResponse getSummary() {
        Map<HealthStatus, Long> counts = lastResults.values().stream()
            .collect(Collectors.groupingBy(HealthCheckResult::getStatus, Collectors.counting()));
        return new HealthSummaryResponse(counts, Instant.now());
    }

    @Scheduled(fixedDelayString = "${healthcheck.scheduler-delay:30000}")
    public void scheduledChecks() {
        for (UUID id : targets.keySet()) {
            checkTarget(id, false);
        }
    }

    private HealthCheckResult attemptWithRetries(Target target) {
        int attempts = 0;
        HealthCheckResult last = null;
        int maxAttempts = Math.max(1, target.getMaxRetries() + 1);

        for (int i = 0; i < maxAttempts; i++) {
            attempts++;
            last = executeOnce(target, attempts);
            if (last.getStatus() == HealthStatus.UP) {
                return last;
            }
            if (!isRetryable(last)) {
                return last;
            }
            sleepBackoff(i);
        }
        return last;
    }

    private HealthCheckResult executeOnce(Target target, int attempts) {
        HealthCheckResult result = baseResult(target.getId());
        result.setAttempts(attempts);
        Instant start = Instant.now();

        try {
            HttpResponseData response = transport.execute(target);
            long latencyMs = response.getDuration().toMillis();
            result.setLatencyMs(latencyMs);
            result.setHttpStatus(response.getStatusCode());
            result.setResponseHeaders(response.getHeaders());
            result.setResponseBodyPreview(limitBody(response.getBody()));

            if (latencyMs > target.getSlowThreshold().toMillis()) {
                result.setStatus(HealthStatus.DEGRADED);
                result.setErrorCategory(ErrorCategory.SLOW_RESPONSE);
                result.setErrorMessage("Response exceeded slow threshold");
            } else if (response.getStatusCode() == 401 || response.getStatusCode() == 403) {
                result.setStatus(HealthStatus.DOWN);
                result.setErrorCategory(ErrorCategory.AUTH_FAILURE);
                result.setErrorMessage("Authentication failed");
            } else if (response.getStatusCode() == 429) {
                result.setStatus(HealthStatus.DOWN);
                result.setErrorCategory(ErrorCategory.RATE_LIMIT);
                result.setErrorMessage("Rate limited");
            } else if (response.getStatusCode() < target.getExpectedStatusMin()
                || response.getStatusCode() > target.getExpectedStatusMax()) {
                result.setStatus(HealthStatus.DOWN);
                result.setErrorCategory(ErrorCategory.HTTP_ERROR);
                result.setErrorMessage("Unexpected HTTP status");
            } else if (target.getExpectedBodyContains() != null
                && (response.getBody() == null || !response.getBody().contains(target.getExpectedBodyContains()))) {
                result.setStatus(HealthStatus.DOWN);
                result.setErrorCategory(ErrorCategory.HTTP_ERROR);
                result.setErrorMessage("Response body missing expected content");
            } else if (target.isExpectJson()) {
                try {
                    objectMapper.readTree(response.getBody());
                    result.setStatus(HealthStatus.UP);
                    result.setErrorCategory(ErrorCategory.NONE);
                } catch (Exception jsonEx) {
                    result.setStatus(HealthStatus.DOWN);
                    result.setErrorCategory(ErrorCategory.INVALID_JSON);
                    result.setErrorMessage("Invalid JSON response");
                }
            } else {
                result.setStatus(HealthStatus.UP);
                result.setErrorCategory(ErrorCategory.NONE);
            }
        } catch (HttpTimeoutException ex) {
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.TIMEOUT);
            result.setErrorMessage("Request timed out");
        } catch (UnknownHostException ex) {
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.DNS_FAILURE);
            result.setErrorMessage("DNS resolution failed");
        } catch (SSLHandshakeException ex) {
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.TLS_ERROR);
            result.setErrorMessage("TLS handshake failed");
        } catch (ConnectException ex) {
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.CONNECTION_FAILURE);
            result.setErrorMessage("Connection failed");
        } catch (Exception ex) {
            result.setStatus(HealthStatus.DOWN);
            result.setErrorCategory(ErrorCategory.UNKNOWN);
            result.setErrorMessage(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }

        result.setTimestamp(Instant.now());
        result.setLatencyMs(Duration.between(start, Instant.now()).toMillis());
        return result;
    }

    private boolean isRetryable(HealthCheckResult result) {
        return result.getErrorCategory() == ErrorCategory.TIMEOUT
            || result.getErrorCategory() == ErrorCategory.CONNECTION_FAILURE
            || result.getErrorCategory() == ErrorCategory.DNS_FAILURE
            || result.getErrorCategory() == ErrorCategory.TLS_ERROR
            || result.getErrorCategory() == ErrorCategory.RATE_LIMIT
            || (result.getErrorCategory() == ErrorCategory.HTTP_ERROR && result.getHttpStatus() != null
                && (result.getHttpStatus() >= 500 || result.getHttpStatus() == 408));
    }

    private void sleepBackoff(int attempt) {
        long base = properties.getRetryBaseBackoff().toMillis();
        long delay = base * (long) Math.pow(2, attempt);
        try {
            Thread.sleep(delay);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private HealthCheckResult baseResult(UUID targetId) {
        HealthCheckResult result = new HealthCheckResult();
        result.setTargetId(targetId);
        result.setStatus(HealthStatus.UNKNOWN);
        result.setErrorCategory(ErrorCategory.UNKNOWN);
        return result;
    }

    private HealthCheckResult copyResult(HealthCheckResult source) {
        HealthCheckResult copy = new HealthCheckResult();
        copy.setTargetId(source.getTargetId());
        copy.setStatus(source.getStatus());
        copy.setHttpStatus(source.getHttpStatus());
        copy.setLatencyMs(source.getLatencyMs());
        copy.setResponseBodyPreview(source.getResponseBodyPreview());
        copy.setResponseHeaders(source.getResponseHeaders());
        copy.setErrorCategory(source.getErrorCategory());
        copy.setErrorMessage(source.getErrorMessage());
        copy.setTimestamp(source.getTimestamp());
        copy.setAttempts(source.getAttempts());
        return copy;
    }

    private String limitBody(String body) {
        if (body == null) {
            return null;
        }
        int limit = Math.max(0, properties.getMaxResponseBodyChars());
        if (body.length() <= limit) {
            return body;
        }
        return body.substring(0, limit);
    }
}
