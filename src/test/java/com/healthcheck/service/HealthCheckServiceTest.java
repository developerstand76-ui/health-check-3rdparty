package com.healthcheck.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.healthcheck.model.*;
import com.healthcheck.transport.HttpResponseData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLHandshakeException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class HealthCheckServiceTest {
    private HealthCheckService service;
    private FakeTransport transport;
    private HealthCheckProperties properties;

    @BeforeEach
    void setup() {
        properties = new HealthCheckProperties();
        properties.setCacheTtl(Duration.ofSeconds(30));
        properties.setCircuitFailureThreshold(2);
        properties.setCircuitOpenDuration(Duration.ofSeconds(60));
        properties.setRetryBaseBackoff(Duration.ofMillis(1));
        transport = new FakeTransport();
        service = new HealthCheckService(properties, new ObjectMapper(), transport);
    }

    private Target createDefaultTarget(boolean expectJson) {
        CreateTargetRequest request = new CreateTargetRequest();
        request.setName("Test API");
        request.setUrl("https://example.com/health");
        request.setMethod(HttpMethod.GET);
        request.setHeaders(Map.of());
        request.setTimeout(Duration.ofSeconds(1));
        request.setExpectedStatusMin(200);
        request.setExpectedStatusMax(299);
        request.setExpectJson(expectJson);
        request.setSlowThreshold(Duration.ofMillis(500));
        request.setMaxRetries(1);
        return service.createTarget(request);
    }

    @Test
    void timeoutIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(new HttpTimeoutException("timeout")));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getStatus()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.TIMEOUT);
    }

    @Test
    void dnsFailureIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(new UnknownHostException("dns")));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.DNS_FAILURE);
    }

    @Test
    void tlsFailureIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(new SSLHandshakeException("tls")));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.TLS_ERROR);
    }

    @Test
    void connectionFailureIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(new ConnectException("conn")));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.CONNECTION_FAILURE);
    }

    @Test
    void authFailureIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(401, "unauthorized", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.AUTH_FAILURE);
    }

    @Test
    void rateLimitIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(429, "rate", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.RATE_LIMIT);
    }

    @Test
    void invalidJsonIsHandled() {
        Target target = createDefaultTarget(true);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(200, "not-json", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.INVALID_JSON);
    }

    @Test
    void non2xxIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(500, "server error", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.HTTP_ERROR);
    }

    @Test
    void slowResponseIsHandled() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(200, "ok", Map.of(), Duration.ofMillis(1000))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getErrorCategory()).isEqualTo(ErrorCategory.SLOW_RESPONSE);
    }

    @Test
    void retryEventuallySucceeds() {
        Target target = createDefaultTarget(false);
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(new HttpTimeoutException("timeout")));
        transport.enqueue(target.getId(), new FakeTransport.TransportOutcome(
            new HttpResponseData(200, "ok", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult result = service.checkTarget(target.getId(), true);

        assertThat(result.getStatus()).isEqualTo(HealthStatus.UP);
        assertThat(result.getAttempts()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void cachedResultIsUsed() {
        Target target = createDefaultTarget(false);
        UUID id = target.getId();
        transport.enqueue(id, new FakeTransport.TransportOutcome(
            new HttpResponseData(200, "ok", Map.of(), Duration.ofMillis(50))));

        HealthCheckResult first = service.checkTarget(id, true);
        HealthCheckResult cached = service.checkTarget(id, false);

        assertThat(cached.isFromCache()).isTrue();
        assertThat(cached.getCachedAt()).isNotNull();
        assertThat(cached.getStatus()).isEqualTo(first.getStatus());
    }

    @Test
    void circuitBreakerOpensAfterFailures() {
        Target target = createDefaultTarget(false);
        UUID id = target.getId();
        transport.enqueue(id, new FakeTransport.TransportOutcome(new HttpTimeoutException("timeout")));
        transport.enqueue(id, new FakeTransport.TransportOutcome(new HttpTimeoutException("timeout")));

        HealthCheckResult first = service.checkTarget(id, true);
        HealthCheckResult second = service.checkTarget(id, true);
        HealthCheckResult third = service.checkTarget(id, true);

        assertThat(first.getStatus()).isEqualTo(HealthStatus.DOWN);
        assertThat(second.getStatus()).isEqualTo(HealthStatus.DOWN);
        assertThat(third.getErrorCategory()).isEqualTo(ErrorCategory.CIRCUIT_OPEN);
    }
}
