package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(15)
class ServiceAccountTokenProviderTest {
    private static final Instant INITIAL_TIME = Instant.parse("2026-01-01T00:00:00Z");
    private static final String TOKEN_SENTINEL = "TOKEN_SENTINEL";
    private static final String SECRET_SENTINEL = "SECRET_SENTINEL";
    private static final String EXPECTED_FORM =
            "grant_type=client_credentials&client_id=client+id%2B%26&client_secret=secret+%2B%3D%26";

    private final List<ServiceAccountTokenProvider> providers = new ArrayList<>();
    private final List<ExecutorService> executors = new ArrayList<>();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        providers.forEach(ServiceAccountTokenProvider::close);
        if (server != null) {
            server.stop(0);
        }
        executors.forEach(ExecutorService::shutdownNow);
    }

    @Test
    void reusesCachedTokenAcrossManyAcquisitions() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, ignored -> tokenResponse("cached-token", 300));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));

        List<TokenHandle> handles = new ArrayList<>();
        for (int call = 0; call < 20; call++) {
            handles.add(provider.acquire());
        }

        assertEquals(1, requestCount.get());
        assertEquals(1, handles.stream().map(TokenHandle::generation).distinct().count());
        assertEquals("cached-token", handles.getFirst().token());
    }

    @Test
    void refreshesTokenAfterClockPassesRefreshMargin() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, request -> tokenResponse("token-" + request, 120));
        MutableClock clock = new MutableClock(INITIAL_TIME);
        ServiceAccountTokenProvider provider = provider(clock);
        TokenHandle first = provider.acquire();

        clock.advance(Duration.ofSeconds(61));
        TokenHandle second = provider.acquire();

        assertEquals(2, requestCount.get());
        assertNotEquals(first.generation(), second.generation());
        assertEquals("token-2", second.token());
    }

    @Test
    void coalescesThirtyTwoConcurrentAcquisitionsIntoOneRequest() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, ignored -> tokenResponse("shared-token", 300));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));
        ExecutorService callers = register(Executors.newFixedThreadPool(32));
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TokenHandle>> results = new ArrayList<>();
        for (int caller = 0; caller < 32; caller++) {
            results.add(
                    callers.submit(
                            () -> {
                                assertTrue(startGate.await(2, TimeUnit.SECONDS));
                                return provider.acquire();
                            }));
        }

        startGate.countDown();
        List<TokenHandle> handles = new ArrayList<>();
        for (Future<TokenHandle> result : results) {
            handles.add(result.get(10, TimeUnit.SECONDS));
        }

        assertEquals(1, requestCount.get());
        assertEquals(32, handles.size());
        assertEquals(1, handles.stream().map(TokenHandle::generation).distinct().count());
    }

    @Test
    void reportsTokenUnavailableAfterOneServerFailure() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, ignored -> new StubResponse(500, "failure"));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));

        SyncFailedException failure = assertThrows(SyncFailedException.class, provider::acquire);

        assertEquals(SyncOutcome.TOKEN_UNAVAILABLE, failure.outcome());
        assertEquals(1, requestCount.get());
    }

    @ParameterizedTest
    @MethodSource("immediatelyExpiredResponses")
    void refetchesWhenExpiryIsMissingOrNonPositive(String responseBody) throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, ignored -> new StubResponse(200, responseBody));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));

        TokenHandle first = provider.acquire();
        TokenHandle second = provider.acquire();

        assertEquals(2, requestCount.get());
        assertNotEquals(first.generation(), second.generation());
    }

    @Test
    void staleGenerationCannotEvictNewerCachedToken() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, request -> tokenResponse("generation-" + request, 300));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));
        TokenHandle generationOne = provider.acquire();
        provider.invalidateIfCurrent(generationOne);

        ExecutorService otherLogin = register(Executors.newSingleThreadExecutor());
        TokenHandle generationTwo = otherLogin.submit(provider::acquire).get(5, TimeUnit.SECONDS);
        provider.invalidateIfCurrent(generationOne);
        TokenHandle followingLogin = provider.acquire();

        assertNotEquals(generationOne.generation(), generationTwo.generation());
        assertEquals(generationTwo.generation(), followingLogin.generation());
        assertEquals(2, requestCount.get());
    }

    @Test
    void rejectedUnconditionalInvalidationEvictsNewerCachedToken() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, request -> tokenResponse("generation-" + request, 300));
        ServiceAccountTokenProvider provider =
                registerProvider(
                        new FaultyUnconditionalProvider(
                                config("secret +=&"), Clock.fixed(INITIAL_TIME, ZoneOffset.UTC)));
        TokenHandle generationOne = provider.acquire();
        provider.invalidateIfCurrent(generationOne);

        ExecutorService otherLogin = register(Executors.newSingleThreadExecutor());
        TokenHandle generationTwo = otherLogin.submit(provider::acquire).get(5, TimeUnit.SECONDS);
        provider.invalidateIfCurrent(generationOne);
        TokenHandle followingLogin = provider.acquire();

        assertNotEquals(generationOne.generation(), generationTwo.generation());
        assertNotEquals(generationTwo.generation(), followingLogin.generation());
        assertEquals(3, requestCount.get());
    }

    @Test
    void evictsFailedRefreshSoNextCallFetchesAgain() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(
                requestCount,
                request ->
                        request == 1
                                ? new StubResponse(500, "failure")
                                : tokenResponse("recovered-token", 300));
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));

        SyncFailedException failure = assertThrows(SyncFailedException.class, provider::acquire);
        TokenHandle recovered = provider.acquire();

        assertEquals(SyncOutcome.TOKEN_UNAVAILABLE, failure.outcome());
        assertEquals("recovered-token", recovered.token());
        assertEquals(2, requestCount.get());
    }

    @Test
    void completesFollowersAndEvictsRefreshWhenLeaderThrowsError() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(requestCount, request -> tokenResponse("token-" + request, 300));
        FetchFailureClock clock = new FetchFailureClock();
        ServiceAccountTokenProvider provider = provider(clock);
        ExecutorService callers = register(Executors.newFixedThreadPool(2));
        Future<TokenHandle> leader = callers.submit(provider::acquire);
        assertTrue(clock.awaitFailurePoint());
        Future<TokenHandle> follower = callers.submit(provider::acquire);

        ExecutionException leaderFailure =
                assertThrows(ExecutionException.class, () -> leader.get(3, TimeUnit.SECONDS));
        ExecutionException followerFailure =
                assertThrows(ExecutionException.class, () -> follower.get(3, TimeUnit.SECONDS));
        TokenHandle recovered = provider.acquire();

        assertInstanceOf(FetchFailure.class, leaderFailure.getCause());
        SyncFailedException unavailable =
                assertInstanceOf(SyncFailedException.class, followerFailure.getCause());
        assertSame(SyncOutcome.TOKEN_UNAVAILABLE, unavailable.outcome());
        assertEquals("token-2", recovered.token());
        assertEquals(2, requestCount.get());
    }

    @Test
    void timesOutOneSlowTokenRequestWithoutHanging() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(
                requestCount,
                ignored -> {
                    try {
                        Thread.sleep(LoginSyncConstants.DEFAULT_TOKEN_TIMEOUT_MS + 1_000L);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return tokenResponse("late-token", 300);
                });
        ServiceAccountTokenProvider provider = provider(Clock.fixed(INITIAL_TIME, ZoneOffset.UTC));
        long startedAt = System.nanoTime();

        SyncFailedException failure = assertThrows(SyncFailedException.class, provider::acquire);

        assertEquals(SyncOutcome.TOKEN_UNAVAILABLE, failure.outcome());
        assertEquals(1, requestCount.get());
        assertTrue(
                Duration.ofNanos(System.nanoTime() - startedAt).compareTo(Duration.ofSeconds(8))
                        < 0);
    }

    @Test
    void keepsTokenAndSecretOutOfRenderings() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        startServer(
                requestCount,
                "grant_type=client_credentials&client_id=client+id%2B%26&client_secret=SECRET_SENTINEL",
                ignored -> tokenResponse(TOKEN_SENTINEL, 300));
        LoginSyncConfig config = config(SECRET_SENTINEL);
        ServiceAccountTokenProvider provider =
                registerProvider(new ServiceAccountTokenProvider(config));
        TokenHandle handle = provider.acquire();
        SyncFailedException failure =
                new SyncFailedException(
                        SyncOutcome.TOKEN_UNAVAILABLE,
                        new IOException("redacted transport failure"));
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));

        List<String> renderings =
                List.of(
                        handle.toString(),
                        provider.toString(),
                        failure.getMessage(),
                        stackTrace.toString());

        renderings.forEach(
                rendered -> {
                    assertFalse(rendered.contains(TOKEN_SENTINEL), "raw token leaked");
                    assertFalse(rendered.contains(SECRET_SENTINEL), "client secret leaked");
                });
        assertEquals("TokenHandle[generation=1]", handle.toString());
    }

    private static Stream<String> immediatelyExpiredResponses() {
        return Stream.of(
                "{\"access_token\":\"without-expiry\"}",
                tokenResponse("zero-expiry", 0).body(),
                tokenResponse("negative-expiry", -1).body());
    }

    private void startServer(
            AtomicInteger requestCount, IntFunction<StubResponse> responseForRequest)
            throws IOException {
        startServer(requestCount, EXPECTED_FORM, responseForRequest);
    }

    private void startServer(
            AtomicInteger requestCount,
            String expectedForm,
            IntFunction<StubResponse> responseForRequest)
            throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        ExecutorService serverExecutor = register(Executors.newCachedThreadPool());
        server.setExecutor(serverExecutor);
        server.createContext(
                "/token",
                exchange -> {
                    int request = requestCount.incrementAndGet();
                    StubResponse response;
                    try {
                        assertTokenRequest(exchange, expectedForm);
                        response = responseForRequest.apply(request);
                    } catch (AssertionError assertion) {
                        response = new StubResponse(400, "invalid request");
                    }
                    writeResponse(exchange, response);
                });
        server.start();
    }

    private static void assertTokenRequest(HttpExchange exchange, String expectedForm)
            throws IOException {
        assertEquals("POST", exchange.getRequestMethod());
        assertEquals(
                "application/x-www-form-urlencoded",
                exchange.getRequestHeaders().getFirst("Content-Type"));
        assertEquals(
                expectedForm,
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    private static void writeResponse(HttpExchange exchange, StubResponse response)
            throws IOException {
        byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(response.status(), body.length);
            exchange.getResponseBody().write(body);
        } finally {
            exchange.close();
        }
    }

    private ServiceAccountTokenProvider provider(Clock clock) {
        return registerProvider(new ServiceAccountTokenProvider(config("secret +=&"), clock));
    }

    private LoginSyncConfig config(String secret) {
        return new LoginSyncConfig(
                "http://unused.invalid",
                "client id+&",
                secret,
                "http://"
                        + server.getAddress().getHostString()
                        + ":"
                        + server.getAddress().getPort()
                        + "/token",
                LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS);
    }

    private <T extends ServiceAccountTokenProvider> T registerProvider(T provider) {
        providers.add(provider);
        return provider;
    }

    private <T extends ExecutorService> T register(T executor) {
        executors.add(executor);
        return executor;
    }

    private static StubResponse tokenResponse(String token, long expiresIn) {
        return new StubResponse(
                200, "{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresIn + "}");
    }

    private record StubResponse(int status, String body) {}

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> current;

        private MutableClock(Instant initialTime) {
            current = new AtomicReference<>(initialTime);
        }

        private void advance(Duration duration) {
            current.updateAndGet(instant -> instant.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current.get();
        }
    }

    private static final class FetchFailureClock extends Clock {
        private final AtomicReference<Thread> leader = new AtomicReference<>();
        private final AtomicInteger leaderCalls = new AtomicInteger();
        private final AtomicInteger followerCalls = new AtomicInteger();
        private final CountDownLatch failurePointReached = new CountDownLatch(1);
        private final CountDownLatch followerCheckedCache = new CountDownLatch(1);

        private boolean awaitFailurePoint() throws InterruptedException {
            return failurePointReached.await(3, TimeUnit.SECONDS);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            Thread current = Thread.currentThread();
            leader.compareAndSet(null, current);
            if (leader.get() == current) {
                if (leaderCalls.incrementAndGet() == 3) {
                    failurePointReached.countDown();
                    try {
                        if (!followerCheckedCache.await(3, TimeUnit.SECONDS)) {
                            throw new AssertionError("follower did not join refresh");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("failure injection interrupted", exception);
                    }
                    throw new FetchFailure();
                }
            } else if (followerCalls.incrementAndGet() == 2) {
                followerCheckedCache.countDown();
            }
            return INITIAL_TIME;
        }
    }

    private static final class FetchFailure extends Error {
        private static final long serialVersionUID = 1L;
    }

    // Deliberately models the rejected revision-1 unconditional cache clearing.
    private static final class FaultyUnconditionalProvider extends ServiceAccountTokenProvider {
        private final AtomicReference<TokenHandle> current = new AtomicReference<>();

        private FaultyUnconditionalProvider(LoginSyncConfig config, Clock clock) {
            super(config, clock);
        }

        @Override
        public TokenHandle acquire() {
            TokenHandle handle = super.acquire();
            current.set(handle);
            return handle;
        }

        @Override
        public void invalidateIfCurrent(TokenHandle ignored) {
            super.invalidateIfCurrent(current.get());
        }
    }
}
