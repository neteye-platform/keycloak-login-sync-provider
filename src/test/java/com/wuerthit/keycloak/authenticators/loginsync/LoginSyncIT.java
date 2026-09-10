package com.wuerthit.keycloak.authenticators.loginsync;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuerthit.keycloak.authenticators.loginsync.support.BrowserLogin;
import com.wuerthit.keycloak.authenticators.loginsync.support.BrowserLogin.Result;
import com.wuerthit.keycloak.authenticators.loginsync.support.CapturedRequest;
import com.wuerthit.keycloak.authenticators.loginsync.support.KeycloakAdminApi;
import com.wuerthit.keycloak.authenticators.loginsync.support.MockSyncService;
import java.io.File;
import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.RSAPublicKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll; // codespell:ignore afterall
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestClassOrder;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoginSyncIT {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Base64.Decoder BASE64_URL = Base64.getUrlDecoder();

    private static final String ADMIN_USERNAME = "admin";
    private static final String ADMIN_PASSWORD = "admin";
    private static final String SA_REALM = "sync-sa";
    private static final String SA_CLIENT_ID = "login-sync-sa";
    private static final String SA_CLIENT_SECRET = "test-secret";
    private static final int SA_ACCESS_TOKEN_LIFESPAN_SECONDS = 1800;
    private static final String INTERNAL_SA_ISSUER = "http://localhost:8080/realms/" + SA_REALM;
    private static final String PERMISSIONSYNC_AUDIENCE = "permissionsync";

    private static final String LOGIN_CLIENT_ID = "browser-login-client";
    private static final String USERNAME = "integration-user";
    private static final String EMAIL = "integration-user@example.test";
    private static final String PASSWORD = "integration-password";
    private static final String GROUP_ENGINEERING = "/engineering/backend";
    private static final String GROUP_OPERATIONS = "/operations/platform";
    private static final List<String> EXPECTED_GROUPS =
            List.of(GROUP_ENGINEERING, GROUP_OPERATIONS);
    private static final String LOGIN_SYNC_FAILED =
            "We could not complete your sign-in. Please try again later.";
    private static final String FLOW_ALIAS = "browser-with-login-sync";
    private static final String SYNC_PATH = "/api/sync-user";

    private GenericContainer<?> keycloak;
    private MockSyncService mock;
    private KeycloakAdminApi admin;
    private BrowserLogin browser;
    private String keycloakBaseUrl;

    @BeforeAll
    void startFixture() throws Exception {
        mock = new MockSyncService();
        Testcontainers.exposeHostPorts(mock.port());

        String providerJarPath = System.getProperty("provider.jar");
        assertNotNull(
                providerJarPath,
                "The provider.jar system property is not set;"
                        + " run `scripts/test.sh verify`, not `scripts/test.sh test`");
        File providerJar = new File(providerJarPath);
        assertTrue(
                providerJar.isFile(),
                "The provider jar is missing at "
                        + providerJar
                        + "; run `scripts/test.sh verify`, not `scripts/test.sh test`");

        String serviceEndpoint = "http://host.testcontainers.internal:" + mock.port() + SYNC_PATH;
        keycloak =
                new GenericContainer<>(
                                "quay.io/keycloak/keycloak:"
                                        + System.getProperty("keycloak.version"))
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(providerJar.toPath()),
                                "/opt/keycloak/providers/keycloak-login-sync-provider.jar")
                        .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", ADMIN_USERNAME)
                        .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", ADMIN_PASSWORD)
                        .withEnv("KC_HOSTNAME_STRICT", "false")
                        .withEnv("KC_HTTP_ENABLED", "true")
                        .withEnv(
                                "KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT",
                                serviceEndpoint)
                        .withEnv("KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_ID", SA_CLIENT_ID)
                        .withEnv(
                                "KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_CLIENT_SECRET",
                                SA_CLIENT_SECRET)
                        .withEnv(
                                "KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SA_TOKEN_ENDPOINT",
                                INTERNAL_SA_ISSUER + "/protocol/openid-connect/token")
                        .withEnv("KC_SPI_AUTHENTICATOR__LOGIN_SYNC__HTTP_TIMEOUT_MS", "5000")
                        .withEnv("KC_SPI_AUTHENTICATOR__LOGIN_SYNC__ALLOW_INSECURE_HTTP", "true")
                        .withCommand("start-dev")
                        .withExposedPorts(8080)
                        .waitingFor(Wait.forHttp("/realms/master").forPort(8080).forStatusCode(200))
                        .withStartupTimeout(Duration.ofMinutes(3));
        keycloak.start();

        keycloakBaseUrl = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        admin = new KeycloakAdminApi(keycloakBaseUrl, ADMIN_USERNAME, ADMIN_PASSWORD);
        browser = new BrowserLogin(keycloakBaseUrl);

        createServiceAccountRealm();
    }

    /**
     * Creates the shared service-account realm required by env-only SPI configuration.
     *
     * <p>The token endpoint is fixed before Keycloak starts, so this realm is necessarily shared;
     * every scenario group still receives its own isolated LOGIN realm per decision I5.
     */
    private void createServiceAccountRealm() throws Exception {
        admin.createRealm(SA_REALM, SA_ACCESS_TOKEN_LIFESPAN_SECONDS);
        admin.createConfidentialClientWithServiceAccount(SA_REALM, SA_CLIENT_ID, SA_CLIENT_SECRET);
        String loginScope = LoginSyncConstants.PERMISSIONSYNC_SCOPE_PREFIX + LOGIN_CLIENT_ID;
        admin.createClientScope(SA_REALM, loginScope);
        admin.addOptionalClientScopeToClient(SA_REALM, SA_CLIENT_ID, loginScope);
    }

    @AfterAll // codespell:ignore afterall
    void stopFixture() throws Exception {
        try {
            if (admin != null) {
                admin.deleteRealm(SA_REALM);
            }
        } finally {
            if (keycloak != null) {
                keycloak.stop();
            }
            if (mock != null) {
                mock.close();
            }
        }
    }

    @Nested
    @Order(1)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ProviderLoadedGate {

        private static final String REALM = "login-sync-provider-gate";

        @BeforeAll
        void createRealm() throws Exception {
            admin.createRealm(REALM);
        }

        @AfterAll // codespell:ignore afterall
        void deleteRealm() throws Exception {
            admin.deleteRealm(REALM);
        }

        @AfterEach
        void resetMock() {
            mock.reset();
        }

        @Test
        @Order(1)
        void providerIsLoadedBeforeAnyScenarioRuns() throws Exception {
            assertTrue(
                    admin.listAuthenticatorProviders(REALM)
                            .contains(LoginSyncConstants.PROVIDER_ID),
                    "The login-sync authenticator provider must be loaded before scenario tests");
        }
    }

    @Nested
    @Order(2)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class FunctionalScenarios {

        private static final String REALM = "login-sync-functional";

        @BeforeAll
        void createRealm() throws Exception {
            createLoginRealm(REALM);
        }

        @AfterAll // codespell:ignore afterall
        void deleteRealm() throws Exception {
            admin.deleteRealm(REALM);
        }

        @AfterEach
        void resetMock() {
            mock.reset();
        }

        @Test
        @Order(1)
        void acceptedSyncSendsExactPayloadAndAKeycloakIssuedToken() throws Exception {
            mock.setMode(MockSyncService.Mode.OK);
            mock.reset();

            Result result = login(REALM);

            assertTrue(result.succeeded(), "A 200 sync response must permit the browser login");
            assertNotNull(result.code(), "A successful browser login must return a code");
            assertEquals(1, mock.requests().size(), "A successful sync must make one request");
            CapturedRequest request = mock.requests().getFirst();
            assertEquals("POST", request.method());
            assertEquals("/api/sync-user", request.path());
            assertExactPayload(request.body());

            RedactedToken token = bearerToken(request);
            verifyJwt(token.value(), INTERNAL_SA_ISSUER);
        }

        @Test
        @Order(2)
        void noContentResponsePermitsLogin() throws Exception {
            mock.setMode(MockSyncService.Mode.NO_CONTENT);
            mock.reset();

            Result result = login(REALM);

            assertTrue(result.succeeded(), "A 204 sync response must permit the browser login");
            assertEquals(
                    1, mock.requests().size(), "An unchanged sync must still make one request");
        }

        @Test
        @Order(3)
        void serverFailureBlocksLoginWithoutRetry() throws Exception {
            mock.setMode(MockSyncService.Mode.HTTP500);
            mock.reset();

            Result result = login(REALM);

            assertBlocked(result);
            assertEquals(1, mock.requests().size(), "An HTTP 500 sync must not be retried");
        }

        @Test
        @Order(4)
        void rejectedRequestBlocksLoginWithoutRetry() throws Exception {
            mock.setMode(MockSyncService.Mode.HTTP400);
            mock.reset();

            Result result = login(REALM);

            assertBlocked(result);
            assertEquals(1, mock.requests().size(), "An HTTP 400 sync must not be retried");
        }

        @Test
        @Order(5)
        void timeoutBlocksLoginWithoutRetry() throws Exception {
            mock.setMode(MockSyncService.Mode.TIMEOUT);
            mock.reset();

            Result result = login(REALM);

            assertBlocked(result);
            assertEquals(1, mock.requests().size(), "A timed-out sync must not be retried");
        }
    }

    @Nested
    @Order(3)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TokenInvalidationScenarios {

        private static final String REALM = "login-sync-token-invalidation";

        @BeforeAll
        void createRealm() throws Exception {
            createLoginRealm(REALM);
        }

        @AfterAll // codespell:ignore afterall
        void deleteRealm() throws Exception {
            admin.setClientSecret(SA_REALM, SA_CLIENT_ID, SA_CLIENT_SECRET);
            admin.deleteRealm(REALM);
        }

        @AfterEach
        void resetMockAndRestoreSecret() throws Exception {
            mock.reset();
            admin.setClientSecret(SA_REALM, SA_CLIENT_ID, SA_CLIENT_SECRET);
        }

        @Test
        @Order(1)
        void unauthorizedResponseInvalidatesCachedTokenAndNextLoginRefetches() throws Exception {
            mock.setMode(MockSyncService.Mode.OK);
            mock.reset();
            Result firstLogin = login(REALM);
            assertTrue(firstLogin.succeeded(), "The initial login must populate the token cache");
            assertEquals(1, mock.requests().size(), "Login 1 must make exactly one sync request");
            RedactedToken tokenT1 = bearerToken(mock.requests().getFirst());

            admin.rotateClientSecret(SA_REALM, SA_CLIENT_ID);
            mock.setMode(MockSyncService.Mode.HTTP401);
            mock.reset();
            Result secondLogin = login(REALM);
            assertBlocked(secondLogin);
            assertEquals(1, mock.requests().size(), "Login 2 must make exactly one sync request");
            RedactedToken reusedToken = bearerToken(mock.requests().getFirst());
            assertEquals(tokenT1, reusedToken, "Login 2 must reuse the cached token");

            admin.setClientSecret(SA_REALM, SA_CLIENT_ID, SA_CLIENT_SECRET);
            mock.setMode(MockSyncService.Mode.OK);
            mock.reset();
            Result thirdLogin = login(REALM);
            assertTrue(thirdLogin.succeeded(), "Login 3 must succeed after secret restoration");
            assertEquals(1, mock.requests().size(), "Login 3 must make exactly one sync request");
            RedactedToken tokenT2 = bearerToken(mock.requests().getFirst());
            assertNotEquals(tokenT1, tokenT2, "Login 3 must use a newly fetched token T2");
        }
    }

    @Nested
    @Order(4)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class OperationalScenarios {

        private static final String REALM = "login-sync-operational";

        @BeforeAll
        void createRealm() throws Exception {
            createLoginRealm(REALM);
        }

        @AfterAll // codespell:ignore afterall
        void deleteRealm() throws Exception {
            admin.deleteRealm(REALM);
        }

        @AfterEach
        void resetMock() {
            mock.reset();
        }

        @Test
        @Order(1)
        void keycloakRemainsAvailableAndSaturationBlocksWithoutCallingTheReceiver()
                throws Exception {
            mock.setMode(MockSyncService.Mode.BLACKHOLE);
            mock.reset();
            List<Future<Result>> logins = new ArrayList<>();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                try {
                    for (int index = 0;
                            index < LoginSyncConstants.DEFAULT_MAX_CONCURRENT_SYNCS;
                            index++) {
                        logins.add(executor.submit(() -> login(REALM)));
                    }

                    awaitRequestCount(
                            LoginSyncConstants.DEFAULT_MAX_CONCURRENT_SYNCS,
                            Duration.ofSeconds(15));

                    // The parked requests hold every permit only until each 5-second client
                    // timeout;
                    // issue the next login immediately after the request-count barrier so it
                    // reaches
                    // tryAcquire inside that window. A future slow-runner flake here means the
                    // window
                    // was missed, not that saturation became fail-open.
                    Result saturatedLogin = login(REALM);
                    assertBlocked(saturatedLogin);
                    assertEquals(
                            LoginSyncConstants.DEFAULT_MAX_CONCURRENT_SYNCS,
                            mock.requests().size(),
                            "A saturated sync must not call the receiver");

                    HttpResponse<Void> response =
                            HttpClient.newHttpClient()
                                    .send(
                                            HttpRequest.newBuilder(
                                                            URI.create(
                                                                    keycloakBaseUrl
                                                                            + "/realms/master"))
                                                    .timeout(Duration.ofSeconds(2))
                                                    .GET()
                                                    .build(),
                                            HttpResponse.BodyHandlers.discarding());
                    assertEquals(
                            200,
                            response.statusCode(),
                            "Keycloak must remain responsive while sync calls are black-holed");

                    mock.setMode(MockSyncService.Mode.OK);
                    for (Future<Result> pending : logins) {
                        assertBlocked(pending.get(10, TimeUnit.SECONDS));
                    }
                } finally {
                    executor.shutdownNow();
                }
            } finally {
                mock.setMode(MockSyncService.Mode.OK);
            }
        }

        @Test
        @Order(2)
        void containerLogsContainNoSensitiveLoginData() {
            String logs = keycloak.getLogs();
            Pattern sensitive = sensitiveLogPattern();

            assertTrue(
                    sensitive.matcher("prefix eyJabcdefghijklm.signature suffix").find(),
                    "The log-safety matcher must recognize a synthetic JWT");
            assertFalse(
                    sensitive.matcher(logs).find(),
                    "Container logs must not contain credentials, JWTs, email, or group paths");
        }
    }

    @Nested
    @Order(5)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ScopeTargetScenarios {

        private static final String REALM = "login-sync-scope-target";
        private static final String GLPI_CLIENT_ID = "glpi";
        private static final String GRAFANA_CLIENT_ID = "grafana";
        private static final String NO_TARGET_CLIENT_ID = "account-console-like";
        private static final String GLPI_SCOPE = "permissionsync:" + GLPI_CLIENT_ID;
        private static final String GRAFANA_SCOPE = "permissionsync:" + GRAFANA_CLIENT_ID;

        @BeforeAll
        void createRealm() throws Exception {
            createLoginRealm(
                    REALM, List.of(GLPI_CLIENT_ID, GRAFANA_CLIENT_ID, NO_TARGET_CLIENT_ID));
            admin.createClientScope(REALM, GLPI_SCOPE);
            admin.createClientScope(REALM, GRAFANA_SCOPE);
            admin.createClientScope(SA_REALM, GLPI_SCOPE);
            admin.createClientScope(SA_REALM, GRAFANA_SCOPE);
            admin.addOptionalClientScopeToClient(SA_REALM, SA_CLIENT_ID, GLPI_SCOPE);
            admin.addOptionalClientScopeToClient(SA_REALM, SA_CLIENT_ID, GRAFANA_SCOPE);
            admin.addAudienceMapper(
                    SA_REALM, SA_CLIENT_ID, "PermissionSync audience", PERMISSIONSYNC_AUDIENCE);
        }

        @AfterAll // codespell:ignore afterall
        void deleteRealm() throws Exception {
            admin.deleteRealm(REALM);
        }

        @BeforeEach
        void resetMock() {
            mock.setMode(MockSyncService.Mode.OK);
            mock.reset();
        }

        @Test
        @Order(1)
        void targetScopeAndTokenCacheAreIsolatedPerLoginClient() throws Exception {
            Result glpiLogin = browser.login(REALM, GLPI_CLIENT_ID, USERNAME, PASSWORD);

            assertTrue(glpiLogin.succeeded(), "The glpi browser login must succeed");
            assertEquals(1, mock.requests().size(), "The glpi login must make one sync request");
            RedactedToken glpiToken = bearerToken(mock.requests().getFirst());
            JsonNode glpiClaims = verifyJwt(glpiToken.value(), INTERNAL_SA_ISSUER);
            assertTargetClaims(glpiClaims, GLPI_SCOPE);

            mock.reset();
            Result grafanaLogin = browser.login(REALM, GRAFANA_CLIENT_ID, USERNAME, PASSWORD);

            assertTrue(grafanaLogin.succeeded(), "The grafana browser login must succeed");
            assertEquals(1, mock.requests().size(), "The grafana login must make one sync request");
            RedactedToken grafanaToken = bearerToken(mock.requests().getFirst());
            JsonNode grafanaClaims = verifyJwt(grafanaToken.value(), INTERNAL_SA_ISSUER);
            assertTargetClaims(grafanaClaims, GRAFANA_SCOPE);
            assertNotEquals(
                    glpiToken,
                    grafanaToken,
                    "Different target scopes must use distinct cached token generations");
        }

        @Test
        @Order(2)
        void loginWithoutRealmTargetScopeStillSynchronizesWithNoTargetClaim() throws Exception {
            Result login = browser.login(REALM, NO_TARGET_CLIENT_ID, USERNAME, PASSWORD);

            assertTrue(login.succeeded(), "The no-target browser login must succeed");
            assertEquals(1, mock.requests().size(), "The login must make one sync request");
            CapturedRequest request = mock.requests().getFirst();
            JsonNode claims = verifyJwt(bearerToken(request).value(), INTERNAL_SA_ISSUER);
            assertNoTargetClaim(claims);
            assertExactPayload(request.body());
        }
    }

    private void createLoginRealm(String realm) throws Exception {
        createLoginRealm(realm, List.of(LOGIN_CLIENT_ID));
    }

    private void createLoginRealm(String realm, List<String> loginClientIds) throws Exception {
        admin.createRealm(realm);
        for (String loginClientId : loginClientIds) {
            admin.createPublicClient(realm, loginClientId, BrowserLogin.REDIRECT_URI);
        }
        admin.createUser(realm, USERNAME, EMAIL, PASSWORD);
        for (String group : EXPECTED_GROUPS) {
            admin.createGroup(realm, group);
            admin.joinGroupByPath(realm, USERNAME, group);
        }

        admin.copyFlow(realm, "browser", FLOW_ALIAS);
        admin.addExecution(realm, FLOW_ALIAS);
        admin.updateExecutionRequirement(realm, FLOW_ALIAS, LoginSyncConstants.PROVIDER_ID);
        positionLoginSyncAfterFormsSubflow(realm);
        assertLoginSyncFollowsFormsSubflow(realm);
        admin.setBrowserFlow(realm, FLOW_ALIAS);
    }

    private void positionLoginSyncAfterFormsSubflow(String realm) throws Exception {
        ObjectNode forms =
                admin.authenticationExecutions(realm, FLOW_ALIAS).stream()
                        .filter(execution -> execution.path("level").asInt() == 0)
                        .filter(execution -> execution.path("authenticationFlow").asBoolean())
                        .filter(
                                execution ->
                                        execution
                                                .path("displayName")
                                                .asText()
                                                .toLowerCase()
                                                .contains("forms"))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "The copied browser flow has no forms subflow"));
        admin.updateExecutionRequirement(
                realm, FLOW_ALIAS, forms.path("displayName").asText(), "REQUIRED");
        admin.updateExecutionPriority(
                realm,
                FLOW_ALIAS,
                LoginSyncConstants.PROVIDER_ID,
                forms.path("priority").asInt() + 10);
    }

    private void assertLoginSyncFollowsFormsSubflow(String realm) throws Exception {
        List<ObjectNode> executions = admin.authenticationExecutions(realm, FLOW_ALIAS);
        int formsIndex = -1;
        int loginSyncIndex = -1;
        for (int index = 0; index < executions.size(); index++) {
            JsonNode execution = executions.get(index);
            String displayName = execution.path("displayName").asText();
            if (execution.path("level").asInt() == 0
                    && execution.path("authenticationFlow").asBoolean()
                    && displayName.toLowerCase().contains("forms")) {
                formsIndex = index;
            }
            if (execution.path("level").asInt() == 0
                    && LoginSyncConstants.PROVIDER_ID.equals(
                            execution.path("providerId").asText())) {
                loginSyncIndex = index;
                assertEquals(
                        "REQUIRED",
                        execution.path("requirement").asText(),
                        "login-sync must be a REQUIRED execution");
            }
        }
        assertTrue(formsIndex >= 0, "The copied browser flow must contain its forms subflow");
        assertTrue(
                loginSyncIndex > formsIndex,
                "login-sync must be positioned after the forms subflow so a user exists");
    }

    private Result login(String realm) throws Exception {
        return browser.login(realm, LOGIN_CLIENT_ID, USERNAME, PASSWORD);
    }

    private static void assertBlocked(Result result) {
        assertFalse(result.succeeded(), "A failed synchronization must block the browser login");
        assertEquals(500, result.statusCode(), "A blocked login must render HTTP 500");
        assertTrue(
                result.body().contains(LOGIN_SYNC_FAILED),
                "A blocked login must render the loginSyncFailed message");
        assertEquals(null, result.code(), "A blocked login must not return an authorization code");
        assertFalse(
                result.body().contains("code="), "A blocked login must not redirect with code=");
    }

    private static void assertExactPayload(String body) throws Exception {
        JsonNode actual = JSON.readTree(body);
        assertEquals(
                List.of("event_type", "username", "groups"),
                actual.propertyStream().map(java.util.Map.Entry::getKey).toList(),
                "The sync payload must contain exactly the three contract fields in order");

        ObjectNode expected = JSON.createObjectNode();
        expected.put("event_type", "LOGIN");
        expected.put("username", USERNAME);
        for (String group : EXPECTED_GROUPS) {
            expected.withArray("groups").add(group);
        }
        assertEquals(expected, actual, "The sync payload must exactly match the login context");
    }

    private RedactedToken bearerToken(CapturedRequest request) {
        String authorization = request.rawAuthorization();
        assertNotNull(authorization, "The sync request must carry an Authorization header");
        assertTrue(
                authorization.startsWith("Bearer "),
                "The sync request Authorization header must use the bearer scheme");
        return new RedactedToken(authorization.substring("Bearer ".length()));
    }

    private JsonNode verifyJwt(String token, String expectedIssuer) throws Exception {
        String[] parts = token.split("\\.", -1);
        assertEquals(3, parts.length, "The service-account credential must be a compact JWT");
        JsonNode header = JSON.readTree(BASE64_URL.decode(parts[0]));
        assertEquals("RS256", header.path("alg").asText(), "The JWT must declare RS256");
        String kid = header.path("kid").asText();
        assertFalse(kid.isBlank(), "The JWT must identify its signing key");

        HttpResponse<String> certs =
                HttpClient.newHttpClient()
                        .send(
                                HttpRequest.newBuilder(
                                                URI.create(
                                                        keycloakBaseUrl
                                                                + "/realms/"
                                                                + SA_REALM
                                                                + "/protocol/openid-connect/certs"))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, certs.statusCode(), "The service-account JWKS must be available");
        JsonNode jwk =
                JSON.readTree(certs.body())
                        .path("keys")
                        .valueStream()
                        .filter(key -> kid.equals(key.path("kid").asText()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("The JWT kid is absent from JWKS"));

        BigInteger modulus = new BigInteger(1, BASE64_URL.decode(jwk.path("n").asText()));
        BigInteger exponent = new BigInteger(1, BASE64_URL.decode(jwk.path("e").asText()));
        Signature verifier = Signature.getInstance("SHA256withRSA");
        verifier.initVerify(
                KeyFactory.getInstance("RSA")
                        .generatePublic(new RSAPublicKeySpec(modulus, exponent)));
        verifier.update((parts[0] + "." + parts[1]).getBytes(US_ASCII));
        assertTrue(
                verifier.verify(BASE64_URL.decode(parts[2])),
                "The service-account JWT signature must verify against Keycloak's JWKS");

        JsonNode claims = JSON.readTree(BASE64_URL.decode(parts[1]));
        assertTrue(
                claims.path("exp").asLong() > Instant.now().getEpochSecond(),
                "The service-account JWT must expire in the future");
        assertEquals(
                expectedIssuer,
                claims.path("iss").asText(),
                "The service-account JWT must have the expected issuer");
        return claims;
    }

    private static void assertTargetClaims(JsonNode claims, String expectedScope) {
        Set<String> targetScopes = targetScopes(claims);
        assertEquals(
                Set.of(expectedScope),
                targetScopes,
                "The token must contain exactly the requested PermissionSync scope");
        assertEquals(1, targetScopes.size(), "The token must contain one PermissionSync scope");
        assertEquals(
                SA_CLIENT_ID,
                claims.path("client_id").asText(),
                "The token must identify the service-account client");
        assertTrue(
                claims.path("aud").isTextual()
                        ? PERMISSIONSYNC_AUDIENCE.equals(claims.path("aud").asText())
                        : claims.path("aud")
                                .valueStream()
                                .anyMatch(
                                        audience ->
                                                PERMISSIONSYNC_AUDIENCE.equals(audience.asText())),
                "The token must contain the PermissionSync audience");
    }

    private static void assertNoTargetClaim(JsonNode claims) {
        assertEquals(
                Set.of(),
                targetScopes(claims),
                "The token must contain no PermissionSync target scope");
    }

    private static Set<String> targetScopes(JsonNode claims) {
        return Pattern.compile("\\s+")
                .splitAsStream(claims.path("scope").asText())
                .filter(scope -> scope.startsWith(LoginSyncConstants.PERMISSIONSYNC_SCOPE_PREFIX))
                .collect(Collectors.toUnmodifiableSet());
    }

    private void awaitRequestCount(int expected, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        while (mock.requests().size() < expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(20);
        }
        assertEquals(
                expected,
                mock.requests().size(),
                "All concurrent logins must reach the black-holed receiver");
    }

    private static Pattern sensitiveLogPattern() {
        String alternatives =
                String.join(
                        "|",
                        Pattern.quote(SA_CLIENT_SECRET),
                        "Bearer",
                        "eyJ[A-Za-z0-9_-]{10,}",
                        Pattern.quote(EMAIL),
                        Pattern.quote(GROUP_ENGINEERING),
                        Pattern.quote(GROUP_OPERATIONS));
        return Pattern.compile(alternatives);
    }

    private record RedactedToken(String value) {

        @Override
        public String toString() {
            return "[redacted token]";
        }
    }
}
