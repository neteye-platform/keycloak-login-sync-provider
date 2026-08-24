package com.wuerthit.keycloak.authenticators.loginsync;

import java.net.URI;
import java.net.URISyntaxException;
import org.keycloak.Config;

/**
 * Immutable login-sync provider settings read from Keycloak's configuration scope.
 *
 * <p>Absent required values leave this configuration unconfigured instead of failing because {@code
 * init()} runs even when the provider is bound to no flow, so throwing would brick unrelated
 * installs on the same server. A malformed value aborts provider startup for the whole server even
 * when the provider is bound to no flow, and this is deliberate, because a malformed value is an
 * operator error that must be seen.
 */
public record LoginSyncConfig(
        String serviceEndpoint,
        String saClientId,
        String saClientSecret,
        String saTokenEndpoint,
        int httpTimeoutMs) {

    public static LoginSyncConfig from(Config.Scope scope) {
        String serviceEndpoint = scope.get(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT);
        String saClientId = scope.get(LoginSyncConstants.CONFIG_SA_CLIENT_ID);
        String saClientSecret = scope.get(LoginSyncConstants.CONFIG_SA_CLIENT_SECRET);
        String saTokenEndpoint = scope.get(LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT);
        String timeoutValue = scope.get(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS);

        validateUrlWhenPresent(serviceEndpoint, LoginSyncConstants.CONFIG_SERVICE_ENDPOINT);
        validateUrlWhenPresent(saTokenEndpoint, LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT);

        return new LoginSyncConfig(
                serviceEndpoint,
                saClientId,
                saClientSecret,
                saTokenEndpoint,
                parseTimeout(timeoutValue));
    }

    public boolean configured() {
        return isPresent(serviceEndpoint)
                && isPresent(saClientId)
                && isPresent(saClientSecret)
                && isPresent(saTokenEndpoint);
    }

    @Override
    public String toString() {
        return "LoginSyncConfig[configured="
                + configured()
                + ", saClientSecret=****, httpTimeoutMs="
                + httpTimeoutMs
                + "]";
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private static int parseTimeout(String value) {
        if (value == null) {
            return LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS;
        }

        try {
            int timeout = Integer.parseInt(value);
            if (timeout <= 0) {
                throw new IllegalStateException(
                        LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS + " must be strictly positive");
            }
            return timeout;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                    LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS + " must be a whole number",
                    exception);
        }
    }

    private static void validateUrlWhenPresent(String value, String key) {
        if (!isPresent(value)) {
            return;
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (!uri.isAbsolute()
                    || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalStateException(key + " must be an absolute HTTP or HTTPS URL");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    key + " must be an absolute HTTP or HTTPS URL", exception);
        }
    }
}
