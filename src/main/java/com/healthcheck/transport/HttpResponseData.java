package com.healthcheck.transport;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public class HttpResponseData {
    private final int statusCode;
    private final String body;
    private final Map<String, List<String>> headers;
    private final Duration duration;

    public HttpResponseData(int statusCode, String body, Map<String, List<String>> headers, Duration duration) {
        this.statusCode = statusCode;
        this.body = body;
        this.headers = headers;
        this.duration = duration;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getBody() {
        return body;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public Duration getDuration() {
        return duration;
    }
}
