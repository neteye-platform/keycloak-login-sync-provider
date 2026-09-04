package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class SyncClientTest {
    private static final String SYNC_PATH = "/api/sync-user";
    private static final String TOKEN_SENTINEL = "TOKEN_SENTINEL";
    private static final String SECRET_SENTINEL = "SECRET_SENTINEL";
    private static final String EXPECTED_BODY =
            "{\"event_type\":\"LOGIN\",\"client_id\":\"internal-portal\","
                    + "\"username\":\"jdoe\",\"email\":\"jdoe@example.com\","
                    + "\"groups\":[\"/staff\",\"/staff/engineering\"],"
                    + "\"timestamp\":\"2026-08-24T09:15:32Z\"}";

    private final List<SyncClient> clients = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        clients.forEach(SyncClient::close);
        if (server != null) {
            server.stop(0);
        }
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void definesEveryOutcomeAndItsExactLoginVerdict() {
        EnumMap<SyncOutcome, Boolean> expected = new EnumMap<>(SyncOutcome.class);
        expected.put(SyncOutcome.SUCCESS, false);
        expected.put(SyncOutcome.REJECTED, true);
        expected.put(SyncOutcome.UNAUTHORIZED, true);
        expected.put(SyncOutcome.SERVER_ERROR, true);
        expected.put(SyncOutcome.TIMEOUT, true);
        expected.put(SyncOutcome.TRANSPORT_ERROR, true);
        expected.put(SyncOutcome.TOKEN_UNAVAILABLE, true);
        expected.put(SyncOutcome.SATURATED, true);

        assertEquals(8, SyncOutcome.values().length);
        assertEquals(EnumSet.allOf(SyncOutcome.class), expected.keySet());
        expected.forEach(
                (outcome, blocksLogin) -> assertEquals(blocksLogin, outcome.blocksLogin()));
    }

    @Test
    void exposesUncheckedFailureOutcomeWithoutLeakingCauseMessage() {
        String secret = "BODY_TOKEN_SECRET_SENTINEL";
        IllegalStateException cause = new IllegalStateException(secret);

        SyncFailedException failure = new SyncFailedException(SyncOutcome.SERVER_ERROR, cause);

        assertTrue(RuntimeException.class.isAssignableFrom(SyncFailedException.class));
        assertSame(SyncOutcome.SERVER_ERROR, failure.outcome());
        assertSame(cause, failure.getCause());
        assertEquals("login sync failed: SERVER_ERROR", failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
    }

    @Test
    void returnsSuccessFor200() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(200, 0));
        SyncClient client =
                client(new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SUCCESS, outcome);
        assertRequestCount(requestCount, 1);
    }

    @Test
    void returnsSuccessFor201() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(201, 0));
        SyncClient client =
                client(new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SUCCESS, outcome);
        assertRequestCount(requestCount, 1);
    }

    @Test
    void returnsRejectedFor400AfterOneAttempt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(400, 0));
        SyncClient client =
                client(new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.REJECTED, outcome);
        assertRequestCount(requestCount, 1);
    }

    @Test
    void returnsUnauthorizedFor401AndInvalidatesUsedHandle() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(401, 0));
        TokenHandle usedHandle = new TokenHandle(TOKEN_SENTINEL, 41);
        StubTokenProvider tokenProvider = new StubTokenProvider(config(), usedHandle);
        SyncClient client = client(tokenProvider);

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.UNAUTHORIZED, outcome);
        assertRequestCount(requestCount, 1);
        assertEquals(1, tokenProvider.invalidationCount());
        assertSame(usedHandle, tokenProvider.invalidatedHandle());
        assertEquals(41, tokenProvider.invalidatedHandle().generation());
    }

    @Test
    void returnsUnauthorizedFor403AndInvalidatesUsedHandle() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(403, 0));
        TokenHandle usedHandle = new TokenHandle(TOKEN_SENTINEL, 43);
        StubTokenProvider tokenProvider = new StubTokenProvider(config(), usedHandle);
        SyncClient client = client(tokenProvider);

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.UNAUTHORIZED, outcome);
        assertRequestCount(requestCount, 1);
        assertEquals(1, tokenProvider.invalidationCount());
        assertSame(usedHandle, tokenProvider.invalidatedHandle());
        assertEquals(43, tokenProvider.invalidatedHandle().generation());
    }

    @Test
    void returnsServerErrorFor500AfterOneAttempt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(500, 0));
        SyncClient client =
                client(new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SERVER_ERROR, outcome);
        assertRequestCount(requestCount, 1);
    }

    @Test
    void rejectedOneRetryFixtureMakesTwo500Attempts() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(500, 0));
        StubTokenProvider tokenProvider =
                new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1));
        SyncClient client =
                register(new FaultyOneRetrySyncClient(config(), new ObjectMapper(), tokenProvider));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SERVER_ERROR, outcome);
        assertRequestCount(requestCount, 2);
    }

    @Test
    void returnsTimeoutAfterOneAttempt() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(200, 500));
        LoginSyncConfig timeoutConfig = config(100);
        SyncClient client =
                client(
                        timeoutConfig,
                        new StubTokenProvider(timeoutConfig, new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.TIMEOUT, outcome);
        assertRequestCount(requestCount, 1);
    }

    @Test
    void blocksTheLoginWhenBulkheadIsSaturatedRegardless() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startSyncServer(requestCount, new StubResponse(200, 0));
        StubTokenProvider tokenProvider =
                new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 1));
        SyncClient client =
                register(new SyncClient(config(), new ObjectMapper(), null, 0, tokenProvider));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SATURATED, outcome);
        assertRequestCount(requestCount, 0);
        assertEquals(1, tokenProvider.acquisitionCount());
        assertTrue(outcome.blocksLogin());
    }

    @Test
    void sendsRequiredHeadersAndExactPayload() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        startSyncServer(
                requestCount,
                exchange -> {
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                    body.set(
                            new String(
                                    exchange.getRequestBody().readAllBytes(),
                                    StandardCharsets.UTF_8));
                    return new StubResponse(200, 0);
                });
        SyncClient client =
                client(new StubTokenProvider(config(), new TokenHandle(TOKEN_SENTINEL, 7)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SUCCESS, outcome);
        assertEquals("Bearer " + TOKEN_SENTINEL, authorization.get());
        assertEquals("application/json", contentType.get());
        assertEquals(EXPECTED_BODY, body.get());
    }

    @Test
    void sendsRequestToTheConfiguredEndpointPathVerbatim() throws Exception {
        String customPath = "/custom/receiver-path";
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestPath = new AtomicReference<>();
        startSyncServer(
                requestCount,
                exchange -> {
                    requestPath.set(exchange.getRequestURI().getPath());
                    return new StubResponse(200, 0);
                },
                customPath);
        LoginSyncConfig customConfig =
                config(LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS, customPath);
        SyncClient client =
                client(
                        customConfig,
                        new StubTokenProvider(customConfig, new TokenHandle(TOKEN_SENTINEL, 1)));

        SyncOutcome outcome = client.send(payload());

        assertSame(SyncOutcome.SUCCESS, outcome);
        assertEquals(customPath, requestPath.get());
    }

    @Test
    void tokenFetchedBeforeSemaphore() throws Exception {
        int fullPermitCount = 1;
        AtomicInteger requestCount = new AtomicInteger();
        CountDownLatch tokenRequestStarted = new CountDownLatch(1);
        CountDownLatch releaseTokenResponse = new CountDownLatch(1);
        startSyncServer(
                requestCount,
                exchange -> {
                    if (exchange.getRequestURI().getPath().equals("/token")) {
                        tokenRequestStarted.countDown();
                        if (!releaseTokenResponse.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("token response was not released");
                        }
                        return new StubResponse(
                                200, 0, "{\"access_token\":\"TOKEN_SENTINEL\",\"expires_in\":300}");
                    }
                    return new StubResponse(200, 0);
                },
                true);
        ServiceAccountTokenProvider tokenProvider = new ServiceAccountTokenProvider(config());
        SyncClient client =
                register(
                        new SyncClient(
                                config(),
                                new ObjectMapper(),
                                null,
                                fullPermitCount,
                                tokenProvider));
        ExecutorService caller = register(Executors.newSingleThreadExecutor());

        CompletableFuture<SyncOutcome> outcome =
                CompletableFuture.supplyAsync(() -> client.send(payload()), caller);
        try {
            assertTrue(tokenRequestStarted.await(3, TimeUnit.SECONDS));
            assertEquals(fullPermitCount, client.availablePermits());
        } finally {
            releaseTokenResponse.countDown();
        }

        assertSame(SyncOutcome.SUCCESS, outcome.get(5, TimeUnit.SECONDS));
    }

    private void startSyncServer(AtomicInteger requestCount, StubResponse response)
            throws IOException {
        startSyncServer(requestCount, ignored -> response);
    }

    private void startSyncServer(AtomicInteger requestCount, Responder responder)
            throws IOException {
        startSyncServer(requestCount, responder, false);
    }

    private void startSyncServer(AtomicInteger requestCount, Responder responder, String syncPath)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(register(Executors.newCachedThreadPool()));
        server.createContext(
                syncPath,
                exchange -> {
                    requestCount.incrementAndGet();
                    respond(exchange, responder);
                });
        server.start();
    }

    private void startSyncServer(
            AtomicInteger requestCount, Responder responder, boolean includeTokenEndpoint)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(register(Executors.newCachedThreadPool()));
        server.createContext(
                SYNC_PATH,
                exchange -> {
                    requestCount.incrementAndGet();
                    respond(exchange, responder);
                });
        if (includeTokenEndpoint) {
            server.createContext("/token", exchange -> respond(exchange, responder));
        }
        server.start();
    }

    private static void respond(HttpExchange exchange, Responder responder) throws IOException {
        try {
            writeResponse(exchange, responder.respond(exchange));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            exchange.close();
        }
    }

    private static void writeResponse(HttpExchange exchange, StubResponse response)
            throws IOException {
        if (response.delayMillis() > 0) {
            try {
                Thread.sleep(response.delayMillis());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private SyncClient client(ServiceAccountTokenProvider tokenProvider) {
        return client(config(), tokenProvider);
    }

    private SyncClient client(LoginSyncConfig config, ServiceAccountTokenProvider tokenProvider) {
        return register(new SyncClient(config, new ObjectMapper(), null, 1, tokenProvider));
    }

    private <T extends SyncClient> T register(T client) {
        clients.add(client);
        return client;
    }

    private <T extends ExecutorService> T register(T executor) {
        executors.add(executor);
        return executor;
    }

    private LoginSyncConfig config() {
        return config(LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS);
    }

    private LoginSyncConfig config(int timeoutMillis) {
        return config(timeoutMillis, SYNC_PATH);
    }

    private LoginSyncConfig config(int timeoutMillis, String syncPath) {
        String baseUrl =
                "http://"
                        + server.getAddress().getHostString()
                        + ":"
                        + server.getAddress().getPort();
        return new LoginSyncConfig(
                baseUrl + syncPath,
                "sync-client",
                SECRET_SENTINEL,
                baseUrl + "/token",
                timeoutMillis);
    }

    private static SyncPayload payload() {
        return SyncPayload.login(
                "internal-portal",
                "jdoe",
                "jdoe@example.com",
                List.of("/staff/engineering", "/staff"),
                Instant.parse("2026-08-24T09:15:32Z"));
    }

    private static void assertRequestCount(AtomicInteger counter, int expected) {
        assertEquals(expected, counter.get());
    }

    @FunctionalInterface
    private interface Responder {
        StubResponse respond(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private record StubResponse(int status, long delayMillis, String body) {
        private StubResponse(int status, long delayMillis) {
            this(status, delayMillis, "{}");
        }
    }

    private static class StubTokenProvider extends ServiceAccountTokenProvider {
        private final TokenHandle handle;
        private final AtomicInteger acquisitions = new AtomicInteger();
        private final AtomicInteger invalidations = new AtomicInteger();
        private final AtomicReference<TokenHandle> invalidatedHandle = new AtomicReference<>();

        private StubTokenProvider(LoginSyncConfig config, TokenHandle handle) {
            super(config);
            this.handle = handle;
        }

        @Override
        public TokenHandle acquire() {
            acquisitions.incrementAndGet();
            return handle;
        }

        @Override
        public void invalidateIfCurrent(TokenHandle usedHandle) {
            invalidations.incrementAndGet();
            invalidatedHandle.set(usedHandle);
        }

        private int acquisitionCount() {
            return acquisitions.get();
        }

        private int invalidationCount() {
            return invalidations.get();
        }

        private TokenHandle invalidatedHandle() {
            return invalidatedHandle.get();
        }
    }

    // Deliberately models the rejected one-retry transport fixture from the QA-failure drill.
    private static final class FaultyOneRetrySyncClient extends SyncClient {
        private FaultyOneRetrySyncClient(
                LoginSyncConfig config,
                ObjectMapper objectMapper,
                ServiceAccountTokenProvider tokenProvider) {
            super(config, objectMapper, null, 1, tokenProvider);
        }

        @Override
        public SyncOutcome send(SyncPayload payload) {
            SyncOutcome firstAttempt = super.send(payload);
            return firstAttempt == SyncOutcome.SERVER_ERROR ? super.send(payload) : firstAttempt;
        }
    }
}
