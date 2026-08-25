package com.wuerthit.keycloak.authenticators.loginsync.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A receiver-only HTTP fixture shared by the integration tests and local compose stack.
 *
 * <p>Tokens come from the real Keycloak container per the LLD; this fixture only records sync
 * requests and supplies deterministic receiver outcomes.
 */
public final class MockSyncService implements Closeable {
    private static final String SYNC_USER_PATH = "/api/sync-user";
    private static final String CONTROL_PATH = "/__control";
    private static final int DEFAULT_MAIN_PORT = 8081;
    private static final long TIMEOUT_DELAY_MILLIS = 15_000;
    private static final Pattern MODE_BODY =
            Pattern.compile("\\{\\s*\"mode\"\\s*:\\s*\"([^\"]+)\"\\s*}");

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<CapturedRequest> requests = new CopyOnWriteArrayList<>();
    private final List<HttpExchange> parkedExchanges = new ArrayList<>();
    private final Object modeLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile Mode mode = Mode.OK;

    public enum Mode {
        OK("ok", 200),
        CREATED("created", 201),
        HTTP400("http400", 400),
        HTTP401("http401", 401),
        HTTP403("http403", 403),
        HTTP500("http500", 500),
        TIMEOUT("timeout", -1),
        BLACKHOLE("blackhole", -1);

        private final String wireName;
        private final int statusCode;

        Mode(String wireName, int statusCode) {
            this.wireName = wireName;
            this.statusCode = statusCode;
        }

        private static Mode fromWireName(String value) {
            for (Mode candidate : values()) {
                if (candidate.wireName.equals(value.toLowerCase(Locale.ROOT))) {
                    return candidate;
                }
            }
            throw new IllegalArgumentException("unsupported mode: " + value);
        }
    }

    public MockSyncService() throws IOException {
        this(0);
    }

    public MockSyncService(int port) throws IOException {
        executor = Executors.newVirtualThreadPerTaskExecutor();
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(SYNC_USER_PATH, this::syncUser);
        server.createContext(CONTROL_PATH, this::control);
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    public void setMode(Mode newMode) {
        Objects.requireNonNull(newMode, "newMode");
        List<HttpExchange> exchangesToClose = List.of();
        synchronized (modeLock) {
            mode = newMode;
            if (newMode != Mode.BLACKHOLE && !parkedExchanges.isEmpty()) {
                exchangesToClose = List.copyOf(parkedExchanges);
                parkedExchanges.clear();
            }
        }
        exchangesToClose.forEach(HttpExchange::close);
    }

    public void setMode(String newMode) {
        setMode(Mode.fromWireName(Objects.requireNonNull(newMode, "newMode")));
    }

    public List<CapturedRequest> requests() {
        return List.copyOf(requests);
    }

    public void reset() {
        requests.clear();
    }

    private void syncUser(HttpExchange exchange) throws IOException {
        capture(exchange);
        if (!acceptsPostAtExactPath(exchange, SYNC_USER_PATH)) {
            return;
        }

        Mode selectedMode = mode;
        if (selectedMode == Mode.BLACKHOLE) {
            if (park(exchange)) {
                return;
            }
            selectedMode = mode;
        }
        if (selectedMode == Mode.TIMEOUT) {
            delayPastClientTimeout(exchange);
            return;
        }
        respondJson(exchange, selectedMode.statusCode, "{}");
    }

    private void control(HttpExchange exchange) throws IOException {
        String body = capture(exchange);
        if (!acceptsPostAtExactPath(exchange, CONTROL_PATH)) {
            return;
        }

        Matcher matcher = MODE_BODY.matcher(body);
        if (!matcher.matches()) {
            respondText(exchange, 400, "Expected a JSON object containing mode");
            return;
        }
        try {
            setMode(matcher.group(1));
            respondJson(exchange, 200, "{\"mode\":\"" + mode.wireName + "\"}");
        } catch (IllegalArgumentException exception) {
            respondText(exchange, 400, exception.getMessage());
        }
    }

    private String capture(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        requests.add(
                new CapturedRequest(
                        exchange.getRequestMethod(),
                        exchange.getRequestURI().getPath(),
                        exchange.getRequestHeaders(),
                        body));
        return body;
    }

    private static boolean acceptsPostAtExactPath(HttpExchange exchange, String expectedPath)
            throws IOException {
        if (!exchange.getRequestURI().getPath().equals(expectedPath)) {
            respondText(exchange, 404, "Not found");
            return false;
        }
        if (!exchange.getRequestMethod().equals("POST")) {
            exchange.getResponseHeaders().add("Allow", "POST");
            respondText(exchange, 405, "Method not allowed");
            return false;
        }
        return true;
    }

    private boolean park(HttpExchange exchange) {
        synchronized (modeLock) {
            if (closed.get() || mode != Mode.BLACKHOLE) {
                return false;
            }
            parkedExchanges.add(exchange);
            return true;
        }
    }

    private static void delayPastClientTimeout(HttpExchange exchange) {
        try {
            Thread.sleep(TIMEOUT_DELAY_MILLIS);
            respondJson(exchange, 200, "{}");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.close();
        } catch (IOException exception) {
            exchange.close();
        }
    }

    private static void respondJson(HttpExchange exchange, int status, String body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        respond(exchange, status, body);
    }

    private static void respondText(HttpExchange exchange, int status, String body)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        respond(exchange, status, body);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, payload.length);
        exchange.getResponseBody().write(payload);
        exchange.close();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<HttpExchange> exchangesToClose;
        synchronized (modeLock) {
            exchangesToClose = List.copyOf(parkedExchanges);
            parkedExchanges.clear();
        }
        exchangesToClose.forEach(HttpExchange::close);
        server.stop(0);
        executor.shutdownNow();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--selftest")) {
            try {
                runSelfTest();
                System.out.println("SELFTEST SUCCESS: all assertions passed");
            } catch (Throwable failure) {
                System.err.println("SELFTEST FAILURE: " + failure.getMessage());
                System.exit(1);
            }
            return;
        }

        int port = configuredPort(args);
        MockSyncService service = new MockSyncService(port);
        CountDownLatch shutdown = new CountDownLatch(1);
        Runtime.getRuntime()
                .addShutdownHook(
                        new Thread(
                                () -> {
                                    service.close();
                                    shutdown.countDown();
                                },
                                "mock-syncservice-shutdown"));
        System.out.println("MockSyncService listening on port " + service.port());
        shutdown.await();
    }

    private static int configuredPort(String[] args) {
        if (args.length > 1) {
            throw new IllegalArgumentException("expected at most one port argument");
        }
        String value =
                args.length == 1
                        ? args[0]
                        : System.getenv()
                                .getOrDefault("MOCK_PORT", String.valueOf(DEFAULT_MAIN_PORT));
        return Integer.parseInt(value);
    }

    private static void runSelfTest() throws Exception {
        System.out.println("SELFTEST: starting receiver on an ephemeral port");
        try (MockSyncService service = new MockSyncService()) {
            HttpClient client =
                    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            URI baseUri = URI.create("http://127.0.0.1:" + service.port());

            System.out.println("SELFTEST: asserting the default response is 200");
            HttpResponse<String> initial = sendSync(client, baseUri, "{\"username\":\"before\"}");
            require(initial.statusCode() == 200, "default receiver response was not 200");

            System.out.println("SELFTEST: switching to http500 through the control path");
            HttpRequest control =
                    HttpRequest.newBuilder(baseUri.resolve(CONTROL_PATH))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString("{\"mode\":\"http500\"}"))
                            .build();
            HttpResponse<String> controlResponse =
                    client.send(control, HttpResponse.BodyHandlers.ofString());
            require(controlResponse.statusCode() == 200, "control request was not accepted");
            HttpResponse<String> changed = sendSync(client, baseUri, "{\"username\":\"after\"}");
            require(changed.statusCode() == 500, "runtime mode switch did not produce 500");

            System.out.println("SELFTEST: asserting recorded headers and body are exposed");
            CapturedRequest recorded =
                    service.requests().stream()
                            .filter(request -> request.body().contains("after"))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("sync request was not recorded"));
            require(
                    "selftest-value".equals(recorded.headers().get("X-Selftest").getFirst()),
                    "recorded header was unavailable");
            require(
                    "{\"username\":\"after\"}".equals(recorded.body()),
                    "recorded body was unavailable");

            System.out.println("SELFTEST: asserting reset empties the request log");
            service.reset();
            require(service.requests().isEmpty(), "reset did not empty the request log");

            System.out.println("SELFTEST: asserting authorization diagnostics are redacted");
            CapturedRequest sensitive =
                    new CapturedRequest(
                            "POST",
                            SYNC_USER_PATH,
                            Map.of("Authorization", List.of("Bearer abc.def.ghi")),
                            "{}");
            String rendered = sensitive.toString();
            require(!rendered.contains("abc.def.ghi"), "full credential appeared in diagnostics");
            require(!rendered.contains("def"), "credential fragment appeared in diagnostics");
        }
    }

    private static HttpResponse<String> sendSync(HttpClient client, URI baseUri, String body)
            throws IOException, InterruptedException {
        HttpRequest request =
                HttpRequest.newBuilder(baseUri.resolve(SYNC_USER_PATH))
                        .header("Authorization", "Bearer selftest-secret")
                        .header("Content-Type", "application/json")
                        .header("X-Selftest", "selftest-value")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
