package com.workflowplatform.controlplane.runtime;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Managed HTTP node boundary with HTTPS/host/port/response-size enforcement. */
public final class ManagedHttpNodePlugin implements NodePlugin {

    private static final Set<Integer> ALLOWED_PORTS = Set.of(-1, 443, 8443);
    private final Set<String> allowedHosts;
    private final int maximumResponseBytes;
    private final HttpClientPort client;

    public ManagedHttpNodePlugin(Set<String> hosts, int responseBytes, HttpClientPort httpClient) {
        allowedHosts = Set.copyOf(hosts);
        maximumResponseBytes = responseBytes;
        client = Objects.requireNonNull(httpClient, "httpClient");
        if (allowedHosts.isEmpty() || maximumResponseBytes < 1) {
            throw new IllegalArgumentException("managed HTTP policy is incomplete");
        }
    }

    @Override
    public String type() {
        return "http";
    }

    @Override
    public NodeExecutionResult execute(NodeExecutionContext context) {
        Object endpoint = context.input().get("endpoint");
        if (!(endpoint instanceof String endpointText)) {
            return NodeExecutionResult.failure(
                    "HTTP_ENDPOINT_INVALID", "managed endpoint is missing", false);
        }
        URI uri = URI.create(endpointText);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equals(uri.getScheme())
                || uri.getUserInfo() != null
                || !ALLOWED_PORTS.contains(uri.getPort())
                || !allowedHosts.contains(host)) {
            return NodeExecutionResult.failure(
                    "HTTP_ENDPOINT_DENIED", "managed endpoint is denied", false);
        }
        HttpResponse response = client.exchange(uri, context.input(), context.timeout());
        if (response.body().length > maximumResponseBytes) {
            return NodeExecutionResult.failure(
                    "HTTP_RESPONSE_TOO_LARGE", "response exceeds configured limit", false);
        }
        boolean success = response.status() >= 200 && response.status() < 300;
        return success
                ? NodeExecutionResult.success(
                        Map.of("status", response.status(), "body", response.body()))
                : NodeExecutionResult.failure(
                        "HTTP_DOWNSTREAM_ERROR",
                        "downstream returned an error",
                        response.status() >= 500);
    }

    @FunctionalInterface
    public interface HttpClientPort {
        HttpResponse exchange(URI uri, Map<String, Object> input, java.time.Duration timeout);
    }

    public record HttpResponse(int status, byte[] body) {
        public HttpResponse {
            body = body.clone();
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("invalid HTTP response status");
            }
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }
}
