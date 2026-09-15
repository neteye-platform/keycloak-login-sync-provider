package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.keycloak.Config;

class LoginSyncConfigTest {
    private static final String SERVICE_ENDPOINT = "https://sync.example.test";
    private static final String CLIENT_ID = "sync-client";
    private static final String TOKEN_ENDPOINT = "https://identity.example.test/token";

    @Test
    void appliesTimeoutAndRelatedDefaultsWhenOptionalValuesAreAbsent() {
        LoginSyncConfig config = LoginSyncConfig.from(scope(Map.of()));

        assertEquals(LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS, config.httpTimeoutMs());
        assertEquals(5000, LoginSyncConstants.DEFAULT_HTTP_TIMEOUT_MS);
        assertEquals(32, LoginSyncConstants.DEFAULT_MAX_CONCURRENT_SYNCS);
        assertEquals(5000, LoginSyncConstants.DEFAULT_TOKEN_TIMEOUT_MS);
        assertEquals(2000, LoginSyncConstants.DEFAULT_CONNECT_TIMEOUT_MS);
    }

    @Test
    void rejectsZeroTimeout() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(Map.of(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS, "0"))));
    }

    @Test
    void rejectsNegativeTimeout() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(Map.of(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS, "-1"))));
    }

    @Test
    void rejectsNonNumericTimeout() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(Map.of(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS, "abc"))));
    }

    @Test
    void rejectsUnparsableServiceEndpoint() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SERVICE_ENDPOINT,
                                                "not a url"))));
    }

    @Test
    void rejectsUnparsableTokenEndpoint() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT,
                                                "not a url"))));
    }

    @Test
    void rejectsServiceEndpointWithoutHost() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SERVICE_ENDPOINT,
                                                "https:/receiver"))));
    }

    @Test
    void rejectsTokenEndpointWithoutHost() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT,
                                                "https:/identity.example.test/token"))));
    }

    @Test
    void rejectsHttpServiceEndpointByDefault() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SERVICE_ENDPOINT,
                                                "http://sync.example.test"))));
    }

    @Test
    void rejectsHttpTokenEndpointByDefault() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        LoginSyncConfig.from(
                                scope(
                                        Map.of(
                                                LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT,
                                                "http://identity.example.test/token"))));
    }

    @Test
    void permitsHttpEndpointsWhenInsecureHttpIsEnabled() {
        Map<String, String> values = new HashMap<>();
        values.put(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT, "http://sync.example.test");
        values.put(LoginSyncConstants.CONFIG_SA_CLIENT_ID, CLIENT_ID);
        values.put(LoginSyncConstants.CONFIG_SA_CLIENT_SECRET, "long-secret-value");
        values.put(
                LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT, "http://identity.example.test/token");
        values.put(LoginSyncConstants.CONFIG_ALLOW_INSECURE_HTTP, "true");

        assertDoesNotThrow(() -> LoginSyncConfig.from(scope(values)));
    }

    @Test
    void stillRejectsHttpWhenInsecureHttpIsExplicitlyFalse() {
        Map<String, String> values = new HashMap<>();
        values.put(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT, "http://sync.example.test");
        values.put(LoginSyncConstants.CONFIG_ALLOW_INSECURE_HTTP, "false");

        assertThrows(IllegalStateException.class, () -> LoginSyncConfig.from(scope(values)));
    }

    @Test
    void isUnconfiguredWhenAllRequiredValuesAreAbsent() {
        LoginSyncConfig config = assertDoesNotThrow(() -> LoginSyncConfig.from(scope(Map.of())));

        assertFalse(config.configured());
    }

    @Test
    void isUnconfiguredWhenServiceEndpointIsAbsent() {
        assertUnconfiguredWhenMissing(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT);
    }

    @Test
    void isUnconfiguredWhenClientIdIsAbsent() {
        assertUnconfiguredWhenMissing(LoginSyncConstants.CONFIG_SA_CLIENT_ID);
    }

    @Test
    void isUnconfiguredWhenClientSecretIsAbsent() {
        assertUnconfiguredWhenMissing(LoginSyncConstants.CONFIG_SA_CLIENT_SECRET);
    }

    @Test
    void isUnconfiguredWhenTokenEndpointIsAbsent() {
        assertUnconfiguredWhenMissing(LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT);
    }

    @Test
    void preservesFullyPopulatedValuesAndReportsConfigured() {
        Map<String, String> values = configuredValues("long-secret-value");
        values.put(LoginSyncConstants.CONFIG_HTTP_TIMEOUT_MS, "9000");

        LoginSyncConfig config = LoginSyncConfig.from(scope(values));

        assertTrue(config.configured());
        assertEquals(SERVICE_ENDPOINT, config.serviceEndpoint());
        assertEquals(CLIENT_ID, config.saClientId());
        assertEquals("long-secret-value", config.saClientSecret());
        assertEquals(TOKEN_ENDPOINT, config.saTokenEndpoint());
        assertEquals(9000, config.httpTimeoutMs());
    }

    @Test
    void redactsTheSecretAndEveryLongSubstring() {
        String secret = "SECRET_SENTINEL";
        LoginSyncConfig config = LoginSyncConfig.from(scope(configuredValues(secret)));
        String rendered = config.toString();

        assertFalse(rendered.contains(secret));
        for (int start = 0; start < secret.length(); start++) {
            for (int end = start + 4; end <= secret.length(); end++) {
                assertFalse(rendered.contains(secret.substring(start, end)));
            }
        }
    }

    @Test
    void usesTheSameMaskForSecretsOfDifferentLengths() {
        LoginSyncConfig shortSecret = LoginSyncConfig.from(scope(configuredValues("tiny")));
        LoginSyncConfig longSecret =
                LoginSyncConfig.from(scope(configuredValues("a-much-longer-secret-value")));

        assertEquals(shortSecret.toString(), longSecret.toString());
        assertTrue(shortSecret.toString().contains("saClientSecret=****"));
        assertNotEquals(
                shortSecret.saClientSecret().length(), longSecret.saClientSecret().length());
    }

    private static void assertUnconfiguredWhenMissing(String missingKey) {
        Map<String, String> values = configuredValues("long-secret-value");
        values.remove(missingKey);

        LoginSyncConfig config = assertDoesNotThrow(() -> LoginSyncConfig.from(scope(values)));

        assertFalse(config.configured());
    }

    private static Map<String, String> configuredValues(String secret) {
        Map<String, String> values = new HashMap<>();
        values.put(LoginSyncConstants.CONFIG_SERVICE_ENDPOINT, SERVICE_ENDPOINT);
        values.put(LoginSyncConstants.CONFIG_SA_CLIENT_ID, CLIENT_ID);
        values.put(LoginSyncConstants.CONFIG_SA_CLIENT_SECRET, secret);
        values.put(LoginSyncConstants.CONFIG_SA_TOKEN_ENDPOINT, TOKEN_ENDPOINT);
        return values;
    }

    private static Config.Scope scope(Map<String, String> values) {
        Config.Scope scope = mock(Config.Scope.class);
        when(scope.get(anyString()))
                .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
        return scope;
    }
}
