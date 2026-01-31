package com.healthcheck.service;

import com.healthcheck.model.Target;
import com.healthcheck.transport.HttpResponseData;
import com.healthcheck.transport.HttpTransport;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FakeTransport implements HttpTransport {
    private final Map<UUID, Deque<TransportOutcome>> outcomes = new ConcurrentHashMap<>();

    public void enqueue(UUID targetId, TransportOutcome outcome) {
        outcomes.computeIfAbsent(targetId, key -> new ArrayDeque<>()).add(outcome);
    }

    @Override
    public HttpResponseData execute(Target target) throws Exception {
        Deque<TransportOutcome> queue = outcomes.get(target.getId());
        if (queue == null || queue.isEmpty()) {
            return new HttpResponseData(200, "{}", Map.of(), Duration.ofMillis(50));
        }
        TransportOutcome outcome = queue.poll();
        if (outcome.getException() != null) {
            throw outcome.getException();
        }
        return outcome.getResponse();
    }

    public static class TransportOutcome {
        private final HttpResponseData response;
        private final Exception exception;

        public TransportOutcome(HttpResponseData response) {
            this.response = response;
            this.exception = null;
        }

        public TransportOutcome(Exception exception) {
            this.response = null;
            this.exception = exception;
        }

        public HttpResponseData getResponse() {
            return response;
        }

        public Exception getException() {
            return exception;
        }
    }
}
