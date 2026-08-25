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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public class ServiceAccountTokenProvider implements AutoCloseable {
    private static final Duration REFRESH_MARGIN = Duration.ofSeconds(60);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final LoginSyncConfig config;
    private final Clock clock;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();
    private final AtomicLong nextGeneration = new AtomicLong();
    private final Object refreshLock = new Object();

    private CompletableFuture<CachedToken> inFlightRefresh;

    public ServiceAccountTokenProvider(LoginSyncConfig config) {
        this(config, Clock.systemUTC());
    }

    ServiceAccountTokenProvider(LoginSyncConfig config, Clock clock) {
        this.config = Objects.requireNonNull(config, "config");
        this.clock = Objects.requireNonNull(clock, "clock");
        httpExecutor = Executors.newCachedThreadPool();
        httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(
                                Duration.ofMillis(LoginSyncConstants.DEFAULT_CONNECT_TIMEOUT_MS))
                        .executor(httpExecutor)
                        .build();
    }

    public TokenHandle acquire() {
        Instant now = clock.instant();
        CachedToken current = cachedToken.get();
        if (isReusable(current, now)) {
            return current.handle();
        }

        CompletableFuture<CachedToken> refresh;
        boolean leader;
        synchronized (refreshLock) {
            current = cachedToken.get();
            if (isReusable(current, clock.instant())) {
                return current.handle();
            }
            if (inFlightRefresh == null) {
                inFlightRefresh = new CompletableFuture<>();
                leader = true;
            } else {
                leader = false;
            }
            refresh = inFlightRefresh;
        }

        if (leader) {
            completeRefresh(refresh);
        }
        return awaitRefresh(refresh).handle();
    }

    public void invalidateIfCurrent(TokenHandle handle) {
        Objects.requireNonNull(handle, "handle");
        cachedToken.updateAndGet(
                current ->
                        current != null && current.handle().generation() == handle.generation()
                                ? null
                                : current);
    }

    @Override
    public void close() {
        httpExecutor.shutdownNow();
    }

    @Override
    public String toString() {
        CachedToken current = cachedToken.get();
        return current == null
                ? "ServiceAccountTokenProvider[cachedGeneration=none]"
                : "ServiceAccountTokenProvider[cachedGeneration="
                        + current.handle().generation()
                        + "]";
    }

    private void completeRefresh(CompletableFuture<CachedToken> refresh) {
        try {
            CachedToken fetched = fetchToken();
            cachedToken.set(fetched);
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
            synchronized (refreshLock) {
                if (inFlightRefresh == refresh) {
                    inFlightRefresh = null;
                }
            }
        }
    }

    private CachedToken fetchToken() {
        // Retry and backoff are deliberately REMOVED per LLD 3.7 and R-01.
        try {
            HttpRequest request =
                    HttpRequest.newBuilder(URI.create(config.saTokenEndpoint()))
                            .timeout(Duration.ofMillis(LoginSyncConstants.DEFAULT_TOKEN_TIMEOUT_MS))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(formBody()))
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

    private String formBody() {
        return "grant_type="
                + encode("client_credentials")
                + "&client_id="
                + encode(config.saClientId())
                + "&client_secret="
                + encode(config.saClientSecret());
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

    private record CachedToken(TokenHandle handle, Instant expiresAt) {}
}
