package com.wuerthit.keycloak.authenticators.loginsync;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLContext;

public class ServiceAccountTokenProvider implements AutoCloseable {
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LoginSyncConfig config;
    private final Clock clock;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final ConcurrentHashMap<String, ScopeSlot> scopeSlots = new ConcurrentHashMap<>();
    private final AtomicLong nextGeneration = new AtomicLong();

    public ServiceAccountTokenProvider(LoginSyncConfig config) {
        this(config, Clock.systemUTC(), null);
    }

    ServiceAccountTokenProvider(LoginSyncConfig config, Clock clock) {
        this(config, clock, null);
    }

    /**
     * Builds the token transport with the receiver trust context.
     *
     * <p>The token endpoint carries the service-account client secret, so it must use Keycloak's
     * truststore. A {@code null} context falls back to the JVM default without weakening
     * certificate or hostname verification.
     */
    ServiceAccountTokenProvider(
            LoginSyncConfig config, Clock clock, SSLContext truststoreSslContext) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        SSLContext sslContext = truststoreSslContext;
        if (sslContext == null) {
            try {
                sslContext = SSLContext.getDefault();
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("default TLS context is unavailable", exception);
            }
        }
        httpExecutor = Executors.newCachedThreadPool();
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofMillis(LoginSyncConstants.DEFAULT_CONNECT_TIMEOUT_MS))
                        .executor(httpExecutor)
                        .sslContext(sslContext)
                        .build();
    }

    /**
     * Acquires a token for {@code scope}, isolated from every other scope's cache and refresh.
     *
     * @param scope the OAuth2 scope to request; {@code null} or blank means no scope is requested,
     *     and is canonicalized to the empty-string cache key
     * @return a reusable or newly fetched token handle for that scope
     */
    public TokenHandle acquire(String scope) {
        ScopeSlot slot = slotFor(scope);
        Instant now = clock.instant();
        CachedToken current = slot.cachedToken.get();
        if (isReusable(current, now)) {
            return current.handle();
        }

        CompletableFuture<CachedToken> refresh;
        boolean leader;
        synchronized (slot.refreshLock) {
            current = slot.cachedToken.get();
            if (isReusable(current, clock.instant())) {
                return current.handle();
            }
            if (slot.inFlightRefresh == null) {
                slot.inFlightRefresh = new CompletableFuture<>();
                leader = true;
            } else {
                leader = false;
            }
            refresh = slot.inFlightRefresh;
        }

        if (leader) {
            completeRefresh(slot, scope, refresh);
        }
        return awaitRefresh(refresh).handle();
    }

    /**
     * Invalidates {@code handle} only when it is the current token cached for {@code scope}.
     *
     * <p>Tokens cached for other scopes are never inspected or evicted.
     *
     * @param scope the OAuth2 scope whose token may be invalidated; {@code null} or blank means the
     *     canonical empty-string cache key
     * @param handle the token handle to invalidate when its generation is current
     */
    public void invalidateIfCurrent(String scope, TokenHandle handle) {
        ScopeSlot slot = slotFor(scope);
        Objects.requireNonNull(handle, "handle");
        slot.cachedToken.updateAndGet(
                current ->
                        current != null && current.handle().generation() == handle.generation()
                                ? null
                                : current);
    }

    @Override
    public void close() {
        httpClient.close();
        httpExecutor.shutdownNow();
    }

    @Override
    public String toString() {
        return "ServiceAccountTokenProvider[cachedScopes=" + scopeSlots.size() + "]";
    }

    private void completeRefresh(
            ScopeSlot slot, String scope, CompletableFuture<CachedToken> refresh) {
        try {
            CachedToken fetched = fetchToken(scope);
            slot.cachedToken.set(fetched);
            refresh.complete(fetched);
        } catch (Throwable failure) {
            refresh.completeExceptionally(failure);
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("unexpected checked token refresh failure", failure);
        } finally {
            synchronized (slot.refreshLock) {
                if (slot.inFlightRefresh == refresh) {
                    slot.inFlightRefresh = null;
                }
            }
        }
    }

    private CachedToken fetchToken(String scope) {
        // Retry and backoff are deliberately REMOVED per LLD 3.7 and R-01.
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(config.saTokenEndpoint()))
                            .timeout(Duration.ofMillis(LoginSyncConstants.DEFAULT_TOKEN_TIMEOUT_MS))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            formBody(validateScope(scope))))
                            .build();
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw unavailable("token endpoint rejected request");
            }
            return parseToken(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw unavailable("token endpoint request interrupted");
        } catch (IOException | RuntimeException exception) {
            if (exception instanceof SyncFailedException failure) {
                throw failure;
            }
            throw unavailable("token endpoint request failed");
        }
    }

    private CachedToken parseToken(String responseBody) throws IOException {
        JsonNode response = OBJECT_MAPPER.readTree(responseBody);
        JsonNode tokenNode = response.get("access_token");
        if (tokenNode == null || !tokenNode.isTextual() || tokenNode.textValue().isBlank()) {
            throw unavailable("token endpoint response omitted access token");
        }

        JsonNode expiresInNode = response.get("expires_in");
        long expiresIn =
                expiresInNode != null && expiresInNode.canConvertToLong()
                        ? expiresInNode.longValue()
                        : 0;
        Instant fetchedAt = clock.instant();
        TokenHandle handle =
                new TokenHandle(tokenNode.textValue(), nextGeneration.incrementAndGet());
        return new CachedToken(
                handle, expiresIn > 0 ? fetchedAt.plusSeconds(expiresIn) : fetchedAt);
    }

    private String formBody(String scope) {
        String body =
                "grant_type="
                        + encode("client_credentials")
                        + "&client_id="
                        + encode(config.saClientId())
                        + "&client_secret="
                        + encode(config.saClientSecret());
        return scope.equals("") ? body : body + "&scope=" + encode(scope);
    }

    private ScopeSlot slotFor(String scope) {
        String validatedScope = validateScope(scope);
        return scopeSlots.computeIfAbsent(validatedScope, key -> new ScopeSlot());
    }

    private static String validateScope(String scope) {
        return scope == null || scope.isBlank() ? "" : scope;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isReusable(CachedToken token, Instant now) {
        return token != null && now.isBefore(token.expiresAt().minus(REFRESH_MARGIN));
    }

    private static CachedToken awaitRefresh(CompletableFuture<CachedToken> refresh) {
        try {
            return refresh.join();
        } catch (CompletionException exception) {
            if (exception.getCause() instanceof SyncFailedException failure) {
                throw failure;
            }
            throw unavailable("token refresh failed");
        }
    }

    private static SyncFailedException unavailable(String redactedCause) {
        return new SyncFailedException(
                SyncOutcome.TOKEN_UNAVAILABLE, new IllegalStateException(redactedCause));
    }

    private static final class ScopeSlot {
        private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
        private final Object refreshLock = new Object();

        private CompletableFuture<CachedToken> inFlightRefresh;
    }

    private record CachedToken(TokenHandle handle, Instant expiresAt) {}
}
