package com.workflowplatform.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Production managed-HTTPS executor with per-hop SSRF and bounded-response controls. */
@Component
public final class ManagedHttpWorkerNodeExecutor implements WorkerNodeExecutor {

    private static final int MAXIMUM_REDIRECTS = 5;
    private static final int MAXIMUM_RESPONSE_LIMIT = 10 * 1024 * 1024;
    private static final int HTTP_SEE_OTHER = 303;
    private static final String INPUT_EXPRESSION = "input";
    private static final Set<Integer> ALLOWED_PORTS = Set.of(-1, 443, 8443);
    private static final Set<String> ALLOWED_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> FORBIDDEN_HEADERS =
            Set.of(
                    "authorization",
                    "connection",
                    "content-length",
                    "cookie",
                    "host",
                    "proxy-authorization",
                    "set-cookie",
                    "transfer-encoding");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,100}");

    private final ObjectMapper objectMapper;
    private final EndpointPolicy endpointPolicy;
    private final HttpExchange exchange;
    private final int maximumResponseBytes;

    @Autowired
    public ManagedHttpWorkerNodeExecutor(
            ObjectMapper jsonMapper,
            @Value("${workflow.worker.http.allowed-hosts:}") String configuredAllowedHosts,
            @Value("${workflow.worker.http.maximum-response-bytes:4096}")
                    int configuredMaximumResponseBytes) {
        this(
                jsonMapper,
                parseHosts(configuredAllowedHosts),
                configuredMaximumResponseBytes,
                host -> List.of(InetAddress.getAllByName(host)),
                new JdkHttpExchange());
    }

    ManagedHttpWorkerNodeExecutor(
            ObjectMapper jsonMapper,
            Set<String> allowedHosts,
            int responseBytes,
            AddressResolver resolver,
            HttpExchange httpExchange) {
        objectMapper = Objects.requireNonNull(jsonMapper, "jsonMapper");
        endpointPolicy =
                new EndpointPolicy(allowedHosts, Objects.requireNonNull(resolver, "resolver"));
        exchange = Objects.requireNonNull(httpExchange, "httpExchange");
        if (responseBytes < 1 || responseBytes > MAXIMUM_RESPONSE_LIMIT) {
            throw new IllegalArgumentException("managed HTTP response limit is invalid");
        }
        maximumResponseBytes = responseBytes;
    }

    @Override
    public boolean supports(String nodeType) {
        return "http".equals(nodeType);
    }

    @Override
    public WorkerTaskStore.NodeExecutionResult execute(WorkerTaskStore.ClaimedTask task) {
        try {
            JsonNode config = objectMapper.readTree(task.configSnapshot());
            URI target = target(config);
            String method = config.path("method").asText("").toUpperCase(Locale.ROOT);
            if (!ALLOWED_METHODS.contains(method)) {
                return failure("HTTP_METHOD_INVALID", "managed HTTP method is invalid", false);
            }
            JsonNode input = readInput(task.inputPayload());
            Map<String, String> headers = headers(config.path("headers"), input);
            byte[] body = requestBody(config.path("bodyExpression").asText(""), input);
            Duration timeout = configuredTimeout(config.path("_timeout").asText("PT30S"));
            Response response =
                    exchange.execute(
                            new Request(target, method, headers, body, timeout),
                            endpointPolicy,
                            maximumResponseBytes);
            if (response.body().length > maximumResponseBytes) {
                return failure(
                        "HTTP_RESPONSE_TOO_LARGE",
                        "managed HTTP response exceeded its limit",
                        false);
            }
            if (response.status() < 200 || response.status() >= 300) {
                boolean retryable =
                        response.status() == 408
                                || response.status() == 429
                                || response.status() >= 500;
                return failure(
                        "HTTP_DOWNSTREAM_ERROR",
                        "managed endpoint returned a non-success status",
                        retryable,
                        retryable ? retryAfter(response.retryAfter()) : null);
            }
            byte[] selected =
                    selectResult(config.path("resultExpression").asText(""), response.body());
            String reference =
                    "inline:" + Base64.getUrlEncoder().withoutPadding().encodeToString(selected);
            return WorkerTaskStore.NodeExecutionResult.success(reference, sha256(selected));
        } catch (DeniedEndpointException exception) {
            return failure("HTTP_ENDPOINT_DENIED", "managed endpoint was denied by policy", false);
        } catch (InvalidNodeConfigurationException | IllegalArgumentException exception) {
            return failure("NODE_CONFIG_INVALID", "managed HTTP configuration is invalid", false);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("HTTP_INTERRUPTED", "managed HTTP execution was interrupted", true);
        } catch (IOException exception) {
            return failure("HTTP_TRANSPORT_ERROR", "managed HTTP transport failed", true);
        }
    }

    private URI target(JsonNode config) {
        String endpoint = config.path("_managedEndpoint").asText("");
        if (endpoint.isBlank()) {
            throw new InvalidNodeConfigurationException("managed endpoint snapshot is missing");
        }
        URI base = URI.create(endpoint);
        String path = config.path("pathTemplate").asText("");
        if (path.startsWith("//") || URI.create(path.isBlank() ? "." : path).isAbsolute()) {
            throw new InvalidNodeConfigurationException("HTTP path template must be relative");
        }
        URI target = path.isBlank() ? base : base.resolve(path);
        endpointPolicy.validate(target);
        return target;
    }

    private JsonNode readInput(String inputPayload) throws IOException {
        if (inputPayload == null) {
            return objectMapper.createObjectNode();
        }
        JsonNode input = objectMapper.readTree(inputPayload);
        if (!input.isObject()) {
            throw new InvalidNodeConfigurationException("HTTP input must be an object");
        }
        return input;
    }

    private Map<String, String> headers(JsonNode configuredHeaders, JsonNode input) {
        if (configuredHeaders.isMissingNode()) {
            return Map.of();
        }
        if (!configuredHeaders.isObject() || configuredHeaders.size() > 50) {
            throw new InvalidNodeConfigurationException("HTTP headers are invalid");
        }
        Map<String, String> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        configuredHeaders
                .fields()
                .forEachRemaining(
                        field -> {
                            String normalized = field.getKey().toLowerCase(Locale.ROOT);
                            if (!HEADER_NAME.matcher(field.getKey()).matches()
                                    || FORBIDDEN_HEADERS.contains(normalized)) {
                                throw new InvalidNodeConfigurationException(
                                        "HTTP header is denied");
                            }
                            JsonNode value = expression(field.getValue().asText(), input);
                            if (!value.isValueNode() || value.isNull()) {
                                throw new InvalidNodeConfigurationException(
                                        "HTTP header expression must resolve to a scalar");
                            }
                            String text = value.asText();
                            if (text.length() > 2000
                                    || text.indexOf('\r') >= 0
                                    || text.indexOf('\n') >= 0) {
                                throw new InvalidNodeConfigurationException(
                                        "HTTP header value is invalid");
                            }
                            result.put(field.getKey(), text);
                        });
        return Map.copyOf(result);
    }

    private byte[] requestBody(String bodyExpression, JsonNode input) throws IOException {
        if (bodyExpression.isBlank()) {
            return new byte[0];
        }
        return objectMapper.writeValueAsBytes(expression(bodyExpression, input));
    }

    private JsonNode expression(String expression, JsonNode input) {
        if (INPUT_EXPRESSION.equals(expression)) {
            return input;
        }
        if (!expression.startsWith(INPUT_EXPRESSION + ".")) {
            throw new InvalidNodeConfigurationException("HTTP mapping expression is unsupported");
        }
        JsonNode value = input;
        for (String segment : expression.substring(INPUT_EXPRESSION.length() + 1).split("\\.")) {
            if (!segment.matches("[A-Za-z][A-Za-z0-9_]{0,79}")) {
                throw new InvalidNodeConfigurationException("HTTP mapping path is invalid");
            }
            value = value.path(segment);
        }
        if (value.isMissingNode()) {
            throw new InvalidNodeConfigurationException("HTTP mapping path is missing");
        }
        return value;
    }

    private byte[] selectResult(String expression, byte[] responseBody) throws IOException {
        if (expression.isBlank() || "response.body".equals(expression)) {
            return responseBody.clone();
        }
        if (!expression.startsWith("response.body.")) {
            throw new InvalidNodeConfigurationException("HTTP result expression is unsupported");
        }
        JsonNode value = objectMapper.readTree(responseBody);
        for (String segment : expression.substring("response.body.".length()).split("\\.")) {
            value = value.path(segment);
        }
        if (value.isMissingNode()) {
            throw new InvalidNodeConfigurationException("HTTP result path is missing");
        }
        return objectMapper.writeValueAsBytes(value);
    }

    private static Duration configuredTimeout(String value) {
        Duration timeout = Duration.parse(value);
        if (timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new InvalidNodeConfigurationException("HTTP timeout is invalid");
        }
        return timeout;
    }

    private static WorkerTaskStore.NodeExecutionResult failure(
            String code, String summary, boolean retryable) {
        return WorkerTaskStore.NodeExecutionResult.failure(code, summary, retryable);
    }

    private static WorkerTaskStore.NodeExecutionResult failure(
            String code, String summary, boolean retryable, Duration retryAfter) {
        return WorkerTaskStore.NodeExecutionResult.failure(code, summary, retryable, retryAfter);
    }

    private static Duration retryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds <= 0 ? null : Duration.ofSeconds(Math.min(seconds, 86_400L));
        } catch (NumberFormatException ignored) {
            try {
                Duration delay =
                        Duration.between(
                                Instant.now(),
                                ZonedDateTime.parse(
                                                value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME)
                                        .toInstant());
                if (delay.isNegative() || delay.isZero()) {
                    return null;
                }
                return delay.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : delay;
            } catch (DateTimeParseException invalidDate) {
                return null;
            }
        }
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Set<String> parseHosts(String configured) {
        return java.util.Arrays.stream(configured.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @FunctionalInterface
    interface AddressResolver {
        List<InetAddress> resolve(String host) throws UnknownHostException;
    }

    @FunctionalInterface
    interface HttpExchange {
        Response execute(Request request, EndpointPolicy policy, int maximumBytes)
                throws IOException, InterruptedException;
    }

    record Request(
            URI uri, String method, Map<String, String> headers, byte[] body, Duration timeout) {
        Request {
            headers = Map.copyOf(headers);
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    record Response(int status, byte[] body, String retryAfter) {
        Response(int status, byte[] body) {
            this(status, body, null);
        }

        Response {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("HTTP response status is invalid");
            }
            body = body.clone();
        }

        @Override
        public byte[] body() {
            return body.clone();
        }
    }

    static final class EndpointPolicy {
        private final Set<String> allowedHosts;
        private final AddressResolver resolver;

        EndpointPolicy(Set<String> hosts, AddressResolver addressResolver) {
            allowedHosts =
                    hosts.stream()
                            .map(host -> host.toLowerCase(Locale.ROOT))
                            .collect(Collectors.toUnmodifiableSet());
            resolver = addressResolver;
        }

        void validate(URI uri) {
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || !ALLOWED_PORTS.contains(uri.getPort())
                    || !allowedHosts.contains(host)) {
                throw new DeniedEndpointException("managed endpoint URI is denied");
            }
            try {
                List<InetAddress> addresses = resolver.resolve(host);
                if (addresses.isEmpty() || addresses.stream().anyMatch(EndpointPolicy::notGlobal)) {
                    throw new DeniedEndpointException("managed endpoint address is denied");
                }
            } catch (UnknownHostException exception) {
                throw new DeniedEndpointException("managed endpoint DNS failed", exception);
            }
        }

        private static boolean notGlobal(InetAddress address) {
            if (address.isAnyLocalAddress()
                    || address.isLoopbackAddress()
                    || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress()
                    || address.isMulticastAddress()) {
                return true;
            }
            byte[] bytes = address.getAddress();
            if (address instanceof Inet4Address) {
                int first = Byte.toUnsignedInt(bytes[0]);
                int second = Byte.toUnsignedInt(bytes[1]);
                return first == 0
                        || first == 10
                        || first == 127
                        || first >= 224
                        || (first == 100 && second >= 64 && second <= 127)
                        || (first == 169 && second == 254)
                        || (first == 172 && second >= 16 && second <= 31)
                        || (first == 192 && (second == 0 || second == 168))
                        || (first == 198 && (second == 18 || second == 19 || second == 51))
                        || (first == 203 && second == 0);
            }
            return address instanceof Inet6Address && (bytes[0] & 0xfe) == 0xfc;
        }
    }

    static final class JdkHttpExchange implements HttpExchange {
        private final HttpClient client =
                HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .connectTimeout(Duration.ofSeconds(10))
                        .build();

        @Override
        public Response execute(Request request, EndpointPolicy policy, int maximumBytes)
                throws IOException, InterruptedException {
            URI current = request.uri();
            String method = request.method();
            byte[] body = request.body();
            final byte[] emptyBody = new byte[0];
            for (int redirects = 0; redirects <= MAXIMUM_REDIRECTS; redirects++) {
                policy.validate(current);
                HttpRequest.Builder builder =
                        HttpRequest.newBuilder(current).timeout(request.timeout());
                request.headers().forEach(builder::header);
                if (body.length > 0) {
                    builder.header("Content-Type", "application/json");
                }
                builder.method(
                        method,
                        body.length == 0
                                ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
                HttpResponse<InputStream> response =
                        client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status < 300 || status >= 400) {
                    return new Response(
                            status,
                            readBounded(response.body(), maximumBytes),
                            response.headers().firstValue("retry-after").orElse(null));
                }
                String location = response.headers().firstValue("location").orElse("");
                if (location.isBlank() || redirects == MAXIMUM_REDIRECTS) {
                    throw new IOException("managed HTTP redirect is invalid or exceeds its limit");
                }
                current = current.resolve(location);
                if (status == HTTP_SEE_OTHER) {
                    method = "GET";
                    body = emptyBody;
                }
            }
            throw new IOException("managed HTTP redirect limit was exceeded");
        }

        private static byte[] readBounded(InputStream input, int maximumBytes) throws IOException {
            try (input) {
                byte[] bytes = input.readNBytes(maximumBytes + 1);
                if (bytes.length > maximumBytes) {
                    throw new ResponseTooLargeException();
                }
                return bytes;
            }
        }
    }

    private static final class InvalidNodeConfigurationException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        InvalidNodeConfigurationException(String message) {
            super(message);
        }
    }

    private static final class DeniedEndpointException extends SecurityException {
        private static final long serialVersionUID = 1L;

        DeniedEndpointException(String message) {
            super(message);
        }

        DeniedEndpointException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class ResponseTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
