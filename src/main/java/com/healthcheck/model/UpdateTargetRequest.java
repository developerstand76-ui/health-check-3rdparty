package com.healthcheck.model;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.Map;

public class UpdateTargetRequest {
    private String name;

    @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
    private String url;

    private HttpMethod method;

    private Map<String, String> headers;

    private String requestBody;

    private String contentType;

    private Duration timeout;

    @Min(100)
    private Integer expectedStatusMin;

    @Min(100)
    private Integer expectedStatusMax;

    private Boolean expectJson;

    private String expectedBodyContains;

    private Duration slowThreshold;

    @Min(0)
    private Integer maxRetries;

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

    public Integer getExpectedStatusMin() {
        return expectedStatusMin;
    }

    public void setExpectedStatusMin(Integer expectedStatusMin) {
        this.expectedStatusMin = expectedStatusMin;
    }

    public Integer getExpectedStatusMax() {
        return expectedStatusMax;
    }

    public void setExpectedStatusMax(Integer expectedStatusMax) {
        this.expectedStatusMax = expectedStatusMax;
    }

    public Boolean getExpectJson() {
        return expectJson;
    }

    public void setExpectJson(Boolean expectJson) {
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

    public Integer getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(Integer maxRetries) {
        this.maxRetries = maxRetries;
    }
}
