package com.healthcheck.model;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Duration;
import java.util.Map;

@Schema(description = "Request to create a new health check target")
public class CreateTargetRequest {
    @NotBlank
    @Schema(description = "Target name", example = "Healthy API")
    private String name;

    @NotBlank
    @Pattern(regexp = "https?://.+", message = "url must start with http:// or https://")
    @Schema(description = "API endpoint URL", example = "https://httpbin.org/status/200")
    private String url;

    @NotNull
    @Schema(description = "HTTP method", example = "GET", 
            allowableValues = {"GET", "POST", "PUT", "DELETE", "PATCH"})
    private HttpMethod method = HttpMethod.GET;

    @Schema(description = "Custom HTTP headers (optional)")
    private Map<String, String> headers;

    @Schema(description = "Request body for POST/PUT/DELETE (optional)")
    private String requestBody;

    @Schema(description = "Content-Type header (auto-set to application/json if requestBody exists)")
    private String contentType;

    @Schema(description = "Request timeout duration", example = "3s")
    private Duration timeout = Duration.ofSeconds(3);

    @Schema(description = "Minimum acceptable HTTP status code", example = "200")
    private int expectedStatusMin = 200;

    @Schema(description = "Maximum acceptable HTTP status code", example = "299")
    private int expectedStatusMax = 299;

    @Schema(description = "Expect response to be valid JSON", example = "false")
    private boolean expectJson = false;

    @Schema(description = "Required substring in response body (optional)")
    private String expectedBodyContains;

    @Schema(description = "Response time threshold for DEGRADED status", example = "2s")
    private Duration slowThreshold = Duration.ofSeconds(2);

    @Min(0)
    @Schema(description = "Number of retries on failure", example = "2")
    private int maxRetries = 2;

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
