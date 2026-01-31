package com.healthcheck.transport;

import com.healthcheck.model.HttpMethod;
import com.healthcheck.model.Target;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class JavaHttpTransport implements HttpTransport {
    private final HttpClient httpClient;

    public JavaHttpTransport() {
        this.httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    @Override
    public HttpResponseData execute(Target target) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(target.getUrl()))
            .timeout(target.getTimeout());

        if (target.getContentType() != null && !target.getContentType().isBlank()) {
            builder.header("Content-Type", target.getContentType());
        }

        if (target.getHeaders() != null) {
            for (Map.Entry<String, String> entry : target.getHeaders().entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }

        Optional<String> body = Optional.ofNullable(target.getRequestBody());
        if (target.getMethod() == HttpMethod.HEAD) {
            builder.method("HEAD", HttpRequest.BodyPublishers.noBody());
        } else if (target.getMethod() == HttpMethod.POST) {
            builder.POST(body.map(HttpRequest.BodyPublishers::ofString)
                .orElseGet(HttpRequest.BodyPublishers::noBody));
        } else if (target.getMethod() == HttpMethod.PUT) {
            builder.PUT(body.map(HttpRequest.BodyPublishers::ofString)
                .orElseGet(HttpRequest.BodyPublishers::noBody));
        } else if (target.getMethod() == HttpMethod.DELETE) {
            if (body.isPresent()) {
                builder.method("DELETE", HttpRequest.BodyPublishers.ofString(body.get()));
            } else {
                builder.DELETE();
            }
        } else {
            builder.GET();
        }

        HttpRequest request = builder.build();
        Instant start = Instant.now();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        Duration duration = Duration.between(start, Instant.now());

        return new HttpResponseData(response.statusCode(), response.body(), response.headers().map(), duration);
    }
}
