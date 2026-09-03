package com.wuerthit.keycloak.authenticators.loginsync;

/**
 * Shared configuration constants for the login-sync provider.
 *
 * <p>The {@code service-endpoint} configuration key maps to {@code
 * KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT}; the double underscore is empirically
 * required because the single-underscore form does not resolve.
 */
public final class LoginSyncConstants {
    public static final String PROVIDER_ID = "login-sync";

    public static final String CONFIG_SERVICE_ENDPOINT = "service-endpoint";
    public static final String CONFIG_SA_CLIENT_ID = "sa-client-id";
    public static final String CONFIG_SA_CLIENT_SECRET = "sa-client-secret";
    public static final String CONFIG_SA_TOKEN_ENDPOINT = "sa-token-endpoint";
    public static final String CONFIG_HTTP_TIMEOUT_MS = "http-timeout-ms";

    /** Opt-out that permits plain-HTTP endpoints in a private test/dev network. */
    public static final String CONFIG_ALLOW_INSECURE_HTTP = "allow-insecure-http";

    public static final int DEFAULT_HTTP_TIMEOUT_MS = 5000;

    /** Plan 0004 bulkhead limit; deliberately not operator-facing. */
    public static final int DEFAULT_MAX_CONCURRENT_SYNCS = 32;

    /** Bounds for plan 0004's bounded token fetch. */
    public static final int DEFAULT_TOKEN_TIMEOUT_MS = 5000;

    /** Bounds for plan 0004's bounded token fetch. */
    public static final int DEFAULT_CONNECT_TIMEOUT_MS = 2000;

    public static final String EVENT_TYPE_LOGIN = "LOGIN";

    private LoginSyncConstants() {
        // Not instantiable.
    }
}
