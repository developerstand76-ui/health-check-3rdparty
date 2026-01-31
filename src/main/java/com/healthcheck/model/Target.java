package com.healthcheck.model;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public class Target {
    private final UUID id;
    private String name;
    private String url;
    private HttpMethod method;
    private Map<String, String> headers;
    private String requestBody;
    private String contentType;
    private Duration timeout;
    private int expectedStatusMin;
    private int expectedStatusMax;
    private boolean expectJson;
    private String expectedBodyContains;
    private Duration slowThreshold;
    private int maxRetries;

    public Target(UUID id, String name, String url, HttpMethod method, Map<String, String> headers,
                  String requestBody, String contentType, Duration timeout, int expectedStatusMin,
                  int expectedStatusMax, boolean expectJson, String expectedBodyContains,
                  Duration slowThreshold, int maxRetries) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.method = method;
        this.headers = headers;
        this.requestBody = requestBody;
        this.contentType = contentType;
        this.timeout = timeout;
        this.expectedStatusMin = expectedStatusMin;
        this.expectedStatusMax = expectedStatusMax;
        this.expectJson = expectJson;
        this.expectedBodyContains = expectedBodyContains;
        this.slowThreshold = slowThreshold;
        this.maxRetries = maxRetries;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public HttpMethod getMethod() {
        return method;
    }

    public void setMethod(HttpMethod method) {
        this.method = method;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers;
    }

    public String getRequestBody() {
        return requestBody;
    }

    public void setRequestBody(String requestBody) {
        this.requestBody = requestBody;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Duration getTimeout() {
        return timeout;
    }

    public void setTimeout(Duration timeout) {
        this.timeout = timeout;
    }

    public int getExpectedStatusMin() {
        return expectedStatusMin;
    }

    public void setExpectedStatusMin(int expectedStatusMin) {
        this.expectedStatusMin = expectedStatusMin;
    }

    public int getExpectedStatusMax() {
        return expectedStatusMax;
    }

    public void setExpectedStatusMax(int expectedStatusMax) {
        this.expectedStatusMax = expectedStatusMax;
    }

    public boolean isExpectJson() {
        return expectJson;
    }

    public void setExpectJson(boolean expectJson) {
        this.expectJson = expectJson;
    }

    public String getExpectedBodyContains() {
        return expectedBodyContains;
    }

    public void setExpectedBodyContains(String expectedBodyContains) {
        this.expectedBodyContains = expectedBodyContains;
    }

    public Duration getSlowThreshold() {
        return slowThreshold;
    }

    public void setSlowThreshold(Duration slowThreshold) {
        this.slowThreshold = slowThreshold;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }
}
