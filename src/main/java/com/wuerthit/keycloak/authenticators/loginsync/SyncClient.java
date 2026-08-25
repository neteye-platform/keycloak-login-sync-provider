package com.wuerthit.keycloak.authenticators.loginsync;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import javax.net.ssl.SSLContext;

/**
 * Single-attempt client for the external login-sync receiver.
 *
 * <p>Per R-09 this client is constructed once per JVM so its connection pool, executor, and
 * bulkhead are shared process-wide rather than leaked per request.
 *
 * <p>The nullable {@link SSLContext} constructor seam is deliberate: plan 0005 must pass the
 * truststore-derived context obtained from Keycloak's truststore provider. A null value uses the
 * JVM default only as a fallback; it does not mean Keycloak trust material may be ignored in
 * production.
 */
public class SyncClient implements AutoCloseable {
    private final LoginSyncConfig config;
    private final ObjectMapper objectMapper;
    private final ServiceAccountTokenProvider tokenProvider;
    private final ExecutorService httpExecutor;
    private final HttpClient httpClient;
    private final Semaphore semaphore;
    private final URI targetUri;

    public SyncClient(
            LoginSyncConfig config, ObjectMapper objectMapper, SSLContext truststoreSslContext) {
        this(
                config,
                objectMapper,
                truststoreSslContext,
                LoginSyncConstants.DEFAULT_MAX_CONCURRENT_SYNCS,
                new ServiceAccountTokenProvider(config));
    }

    SyncClient(
            LoginSyncConfig config,
            ObjectMapper objectMapper,
            SSLContext truststoreSslContext,
            int permitCount,
            ServiceAccountTokenProvider tokenProvider) {
        this.config = Objects.requireNonNull(config, "config");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        semaphore = new Semaphore(permitCount);
        targetUri = URI.create(config.serviceEndpoint() + LoginSyncConstants.SYNC_USER_PATH);
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

    public SyncOutcome send(SyncPayload payload) {
        TokenHandle handle;
        try {
            handle = tokenProvider.acquire();
        } catch (SyncFailedException failure) {
            return failure.outcome();
        }

        if (!semaphore.tryAcquire()) {
            return SyncOutcome.SKIPPED_SATURATED;
        }

        try {
            // Retry and backoff are deliberately REMOVED per LLD 3.7 and R-01.
            HttpRequest request =
                    HttpRequest.newBuilder(targetUri)
                            .timeout(Duration.ofMillis(config.httpTimeoutMs()))
                            .header("Authorization", "Bearer " + handle.token())
                            .header("Content-Type", "application/json")
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            objectMapper.writeValueAsString(
                                                    Objects.requireNonNull(payload, "payload"))))
                            .build();
            HttpResponse<Void> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int statusCode = response.statusCode();
            if (statusCode == 200 || statusCode == 201) {
                return SyncOutcome.SUCCESS;
            }
            if (statusCode == 401 || statusCode == 403) {
                tokenProvider.invalidateIfCurrent(handle);
                return SyncOutcome.UNAUTHORIZED;
            }
            if (statusCode >= 400 && statusCode < 500) {
                return SyncOutcome.REJECTED;
            }
            if (statusCode >= 500 && statusCode < 600) {
                return SyncOutcome.SERVER_ERROR;
            }
            return SyncOutcome.TRANSPORT_ERROR;
        } catch (HttpTimeoutException exception) {
            return SyncOutcome.TIMEOUT;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return SyncOutcome.TRANSPORT_ERROR;
        } catch (IOException exception) {
            return SyncOutcome.TRANSPORT_ERROR;
        } finally {
            semaphore.release();
        }
    }

    int availablePermits() {
        return semaphore.availablePermits();
    }

    @Override
    public void close() {
        httpExecutor.shutdownNow();
        tokenProvider.close();
    }

    // Never enable jdk.httpclient.HttpClient.log header diagnostics in production: it exposes
    // Authorization values.
}
