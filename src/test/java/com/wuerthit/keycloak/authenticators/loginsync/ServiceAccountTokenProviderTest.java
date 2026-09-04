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
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
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
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
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
    private static final char[] TEST_KEY_PASSWORD = "changeit".toCharArray();

    // This throwaway self-signed PKCS12 exists only to serve loopback HTTPS in this test; it is not
    // a real-system credential and grants access to nothing. Its certificate has CN=localhost,
    // SANs localhost and 127.0.0.1, and validity 2026-08-26 through 2036-08-23. The deliberately
    // cert-pinned RecordingTrustManager does not check validity, so expiry cannot silently break
    // the test; the dates instead make future fixture diagnosis explicit.
    private static final String TEST_SERVER_KEYSTORE =
            "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";

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

    @Test
    void usesSuppliedSslContextForTokenEndpointHandshake() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        X509Certificate serverCertificate = startHttpsServer(requestCount);
        RecordingTrustManager trustManager = new RecordingTrustManager(serverCertificate);
        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, new TrustManager[] {trustManager}, new SecureRandom());
        ServiceAccountTokenProvider provider =
                registerProvider(
                        new ServiceAccountTokenProvider(
                                config("secret +=&"),
                                Clock.fixed(INITIAL_TIME, ZoneOffset.UTC),
                                clientContext));

        TokenHandle handle = provider.acquire();

        assertEquals("tls-token", handle.token());
        assertEquals(1, requestCount.get());
        assertTrue(trustManager.wasConsulted());
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

    private X509Certificate startHttpsServer(AtomicInteger requestCount) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(
                new ByteArrayInputStream(Base64.getDecoder().decode(TEST_SERVER_KEYSTORE)),
                TEST_KEY_PASSWORD);
        KeyManagerFactory keyManagerFactory =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, TEST_KEY_PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keyManagerFactory.getKeyManagers(), null, new SecureRandom());

        HttpsServer httpsServer =
                HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server = httpsServer;
        httpsServer.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        httpsServer.setExecutor(register(Executors.newCachedThreadPool()));
        httpsServer.createContext(
                "/token",
                exchange -> {
                    requestCount.incrementAndGet();
                    assertTokenRequest(exchange, EXPECTED_FORM);
                    writeResponse(exchange, tokenResponse("tls-token", 300));
                });
        httpsServer.start();
        return (X509Certificate) keyStore.getCertificate("token-server");
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
        String scheme = server instanceof HttpsServer ? "https" : "http";
        return new LoginSyncConfig(
                "http://unused.invalid",
                "client id+&",
                secret,
                scheme
                        + "://"
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

    private static final class RecordingTrustManager implements X509TrustManager {
        private final X509Certificate trustedCertificate;
        private final AtomicInteger consultations = new AtomicInteger();

        private RecordingTrustManager(X509Certificate trustedCertificate) {
            this.trustedCertificate = trustedCertificate;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("client certificates are not trusted");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            consultations.incrementAndGet();
            if (chain.length == 0 || !trustedCertificate.equals(chain[0])) {
                throw new CertificateException("unexpected token endpoint certificate");
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[] {trustedCertificate};
        }

        private boolean wasConsulted() {
            return consultations.get() > 0;
        }
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
