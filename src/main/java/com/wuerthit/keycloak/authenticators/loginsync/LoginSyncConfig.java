package com.wuerthit.keycloak.authenticators.loginsync;

import java.net.URI;
import java.net.URISyntaxException;
import org.jboss.logging.Logger;
import org.keycloak.Config;

/**
 * Immutable login-sync provider settings read from Keycloak's configuration scope.
 *
 * <p>Absent required values leave this configuration unconfigured instead of failing because {@code
 * init()} runs even when the provider is bound to no flow, so throwing would brick unrelated
 * installs on the same server. A malformed value aborts provider startup for the whole server even
 * when the provider is bound to no flow, and this is deliberate, because a malformed value is an
 * operator error that must be seen.
 *
 * @param serviceEndpoint the complete receiver URL, including its path
 */
public record LoginSyncConfig(
        String serviceEndpoint,
        String saClientId,
        String saClientSecret,
        String saTokenEndpoint,
        int httpTimeoutMs) {

    private static final Logger LOG = Logger.getLogger(LoginSyncConfig.class);

    public static LoginSyncConfig from(Config.Scope scope) {
        String serviceEndpoint = scope.get(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT);
        String saClientId = scope.get(LoginSyncConstants.CONFIG_SA_CLIENT_ID);
        String saClientSecret = scope.get(LoginSyncConstants.CONFIG_SA_CLIENT_SECRET);
        String saTokenEndpoint = scope.get(LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT);
        String timeoutValue = scope.get(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS);
        boolean allowInsecureHttp =
                Boolean.parseBoolean(scope.get(LoginSyncConstants.CONFIG_ALLOW_INSECURE_HTTP));

        if (allowInsecureHttp) {
            LOG.warnf(
                    "%s is enabled: the credential-bearing endpoints (%s and %s) permit cleartext "
                            + "HTTP transport of the service-account secret and the sync JWT. "
                            + "Enable it only within a private test/dev network.",
                    LoginSyncConstants.CONFIG_ALLOW_INSECURE_HTTP,
                    LoginSyncConstants.CONFIG_SERVICE_ENDPOINT,
                    LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT);
        }

        validateUrlWhenPresent(
                serviceEndpoint, LoginSyncConstants.CONFIG_SERVICE_ENDPOINT, allowInsecureHttp);
        validateUrlWhenPresent(
                saTokenEndpoint, LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT, allowInsecureHttp);

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

    private static void validateUrlWhenPresent(
            String value, String key, boolean allowInsecureHttp) {
        if (!isPresent(value)) {
            return;
        }

        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            boolean http = scheme != null && scheme.equalsIgnoreCase("http");
            if (!uri.isAbsolute()
                    || scheme == null
                    || uri.getHost() == null
                    || !(http || scheme.equalsIgnoreCase("https"))) {
                throw new IllegalStateException(key + " must be an absolute HTTP or HTTPS URL");
            }
            if (http && !allowInsecureHttp) {
                throw new IllegalStateException(
                        key
                                + " must be an HTTPS URL; plain HTTP is rejected unless "
                                + LoginSyncConstants.CONFIG_ALLOW_INSECURE_HTTP
                                + " is enabled");
            }
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(
                    key + " must be an absolute HTTP or HTTPS URL", exception);
        }
    }
}
