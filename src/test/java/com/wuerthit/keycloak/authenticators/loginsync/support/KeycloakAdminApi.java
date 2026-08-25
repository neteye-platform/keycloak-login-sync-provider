package com.wuerthit.keycloak.authenticators.loginsync.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wuerthit.keycloak.authenticators.loginsync.LoginSyncConstants;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A thin wrapper over the Keycloak Admin REST API used to build integration-test realms.
 *
 * <p>This deliberately uses plain {@link HttpClient} and Jackson rather than {@code
 * keycloak-admin-client}: the harness needs only a small, explicit set of calls, and keeping the
 * RESTEasy client stack out of the test classpath makes failures and dependency conflicts easier to
 * diagnose.
 */
public class KeycloakAdminApi {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ADMIN_CLIENT_ID = "admin-cli";

    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;
    private final String username;
    private final String password;

    public KeycloakAdminApi(String baseUrl, String username, String password) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.username = username;
        this.password = password;
    }

    public void createRealm(String realm) throws IOException, InterruptedException {
        post("/admin/realms", Map.of("realm", realm, "enabled", true));
    }

    public void deleteRealm(String realm) throws IOException, InterruptedException {
        sendSuccessful("DELETE", realmPath(realm), null);
    }

    public String createUser(String realm, String username, String email, String password)
            throws IOException, InterruptedException {
        Map<String, Object> credential =
                Map.of("type", "password", "value", password, "temporary", false);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", username);
        user.put("email", email);
        user.put("enabled", true);
        user.put("emailVerified", true);
        user.put("credentials", List.of(credential));
        return postForId(realmPath(realm) + "/users", user);
    }

    /**
     * Creates every missing-looking segment of a fresh nested group path and returns the leaf id.
     *
     * <p>The integration harness creates a new realm per fixture, so group paths are fresh by
     * construction. Children use Keycloak's dedicated {@code /groups/{parentId}/children} endpoint;
     * creating only the leaf at the root would make full-path assertions such as {@code
     * /parent/child} vacuous.
     */
    public String createGroup(String realm, String groupPath)
            throws IOException, InterruptedException {
        List<String> segments = groupSegments(groupPath);
        String parentId = null;
        for (String segment : segments) {
            String path =
                    parentId == null
                            ? realmPath(realm) + "/groups"
                            : realmPath(realm) + "/groups/" + pathSegment(parentId) + "/children";
            parentId = postForId(path, Map.of("name", segment));
        }
        return parentId;
    }

    public void joinGroupByPath(String realm, String username, String groupPath)
            throws IOException, InterruptedException {
        String userId = requireUserId(realm, username);
        String groupId = findGroupIdByPath(realm, groupPath);
        sendSuccessful(
                "PUT",
                realmPath(realm)
                        + "/users/"
                        + pathSegment(userId)
                        + "/groups/"
                        + pathSegment(groupId),
                null);
    }

    public String createPublicClient(String realm, String clientId, String redirectUri)
            throws IOException, InterruptedException {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("enabled", true);
        client.put("publicClient", true);
        client.put("standardFlowEnabled", true);
        client.put("directAccessGrantsEnabled", false);
        client.put("redirectUris", List.of(redirectUri));
        client.put("webOrigins", List.of("*"));
        return postForId(realmPath(realm) + "/clients", client);
    }

    /**
     * Creates the confidential service-account client used by the provider and returns both values
     * that the provider configuration needs.
     */
    public ClientCredentials createConfidentialClientWithServiceAccount(
            String realm, String clientId) throws IOException, InterruptedException {
        Map<String, Object> client = new LinkedHashMap<>();
        client.put("clientId", clientId);
        client.put("enabled", true);
        client.put("publicClient", false);
        client.put("clientAuthenticatorType", "client-secret");
        client.put("serviceAccountsEnabled", true);
        client.put("standardFlowEnabled", false);
        client.put("directAccessGrantsEnabled", false);

        String id = postForId(realmPath(realm) + "/clients", client);
        String secret =
                requiredText(
                        get(realmPath(realm) + "/clients/" + pathSegment(id) + "/client-secret"),
                        "value",
                        "client secret for " + clientId);
        return new ClientCredentials(clientId, secret);
    }

    public void assignServiceAccountRole(String realm, String serviceClientId, String roleName)
            throws IOException, InterruptedException {
        assignServiceAccountRole(realm, serviceClientId, "realm-management", roleName);
    }

    public void assignServiceAccountRole(
            String realm, String serviceClientId, String roleClientId, String roleName)
            throws IOException, InterruptedException {
        String serviceClientUuid = requireClientUuid(realm, serviceClientId);
        String roleClientUuid = requireClientUuid(realm, roleClientId);
        JsonNode serviceAccount =
                get(
                        realmPath(realm)
                                + "/clients/"
                                + pathSegment(serviceClientUuid)
                                + "/service-account-user");
        String userId =
                requiredText(serviceAccount, "id", "service account for " + serviceClientId);
        JsonNode role =
                get(
                        realmPath(realm)
                                + "/clients/"
                                + pathSegment(roleClientUuid)
                                + "/roles/"
                                + pathSegment(roleName));

        post(
                realmPath(realm)
                        + "/users/"
                        + pathSegment(userId)
                        + "/role-mappings/clients/"
                        + pathSegment(roleClientUuid),
                List.of(role));
    }

    public String rotateClientSecret(String realm, String clientId)
            throws IOException, InterruptedException {
        String clientUuid = requireClientUuid(realm, clientId);
        JsonNode credential =
                post(
                        realmPath(realm) + "/clients/" + pathSegment(clientUuid) + "/client-secret",
                        Map.of());
        return requiredText(credential, "value", "rotated secret for " + clientId);
    }

    public void copyFlow(String realm, String flowAlias, String newName)
            throws IOException, InterruptedException {
        post(
                realmPath(realm) + "/authentication/flows/" + pathSegment(flowAlias) + "/copy",
                Map.of("newName", newName));
    }

    public String addExecution(String realm, String flowAlias)
            throws IOException, InterruptedException {
        return addExecution(realm, flowAlias, LoginSyncConstants.PROVIDER_ID);
    }

    public String addExecution(String realm, String flowAlias, String providerId)
            throws IOException, InterruptedException {
        return postForId(
                executionsPath(realm, flowAlias) + "/execution", Map.of("provider", providerId));
    }

    public void updateExecutionRequirement(String realm, String flowAlias, String provider)
            throws IOException, InterruptedException {
        updateExecutionRequirement(realm, flowAlias, provider, "REQUIRED");
    }

    /**
     * Updates a flow execution selected by provider id or display name.
     *
     * <p>The full execution representation is sent back so its index, level and priority remain
     * visible and unchanged; todo 3 can inspect the same representation when checking placement.
     */
    public void updateExecutionRequirement(
            String realm, String flowAlias, String provider, String requirement)
            throws IOException, InterruptedException {
        ObjectNode execution =
                authenticationExecutions(realm, flowAlias).stream()
                        .filter(candidate -> executionMatches(candidate, provider))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "No execution for provider or display name '"
                                                        + provider
                                                        + "' in flow "
                                                        + flowAlias));
        execution.put("requirement", requirement);
        put(executionsPath(realm, flowAlias), execution);
    }

    public List<ObjectNode> authenticationExecutions(String realm, String flowAlias)
            throws IOException, InterruptedException {
        List<ObjectNode> executions = new ArrayList<>();
        for (JsonNode execution : get(executionsPath(realm, flowAlias))) {
            executions.add((ObjectNode) execution.deepCopy());
        }
        return List.copyOf(executions);
    }

    /** Binds the copied flow; without this, browser logins continue through the built-in flow. */
    public void setBrowserFlow(String realm, String flowAlias)
            throws IOException, InterruptedException {
        ObjectNode representation = (ObjectNode) get(realmPath(realm));
        representation.put("browserFlow", flowAlias);
        put(realmPath(realm), representation);
    }

    public List<String> listAuthenticatorProviders(String realm)
            throws IOException, InterruptedException {
        List<String> providerIds = new ArrayList<>();
        for (JsonNode provider :
                get(realmPath(realm) + "/authentication/authenticator-providers")) {
            providerIds.add(requiredText(provider, "id", "authenticator provider"));
        }
        return List.copyOf(providerIds);
    }

    public String findUserId(String realm, String username)
            throws IOException, InterruptedException {
        JsonNode users =
                get(realmPath(realm) + "/users?exact=true&username=" + queryValue(username));
        return users.isEmpty() ? null : requiredText(users.get(0), "id", "user " + username);
    }

    public List<String> findUserGroupPaths(String realm, String username)
            throws IOException, InterruptedException {
        String userId = requireUserId(realm, username);
        List<String> paths = new ArrayList<>();
        for (JsonNode group : get(realmPath(realm) + "/users/" + pathSegment(userId) + "/groups")) {
            paths.add(requiredText(group, "path", "group membership for " + username));
        }
        return List.copyOf(paths);
    }

    public String findClientUuid(String realm, String clientId)
            throws IOException, InterruptedException {
        JsonNode clients = get(realmPath(realm) + "/clients?clientId=" + queryValue(clientId));
        return clients.isEmpty() ? null : requiredText(clients.get(0), "id", "client " + clientId);
    }

    private String accessToken() throws IOException, InterruptedException {
        // This password grant obtains only the admin-cli fixture token. BrowserLogin never uses a
        // direct grant because that would bypass the authenticator under test.
        String form =
                "grant_type="
                        + "password"
                        + "&client_id="
                        + ADMIN_CLIENT_ID
                        + "&username="
                        + queryValue(username)
                        + "&password="
                        + queryValue(password);
        HttpResponse<String> response =
                http.send(
                        HttpRequest.newBuilder(
                                        URI.create(
                                                baseUrl
                                                        + "/realms/master/protocol/openid-connect/token"))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .POST(HttpRequest.BodyPublishers.ofString(form))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
        requireStatus("POST admin-cli token", response, 200);
        return requiredText(JSON.readTree(response.body()), "access_token", "admin token response");
    }

    private HttpResponse<String> send(String method, String path, Object body)
            throws IOException, InterruptedException {
        HttpRequest.BodyPublisher payload =
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body));
        return http.send(
                HttpRequest.newBuilder(URI.create(baseUrl + path))
                        .header("Authorization", "Bearer " + accessToken())
                        .header("Content-Type", "application/json")
                        .method(method, payload)
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> sendSuccessful(String method, String path, Object body)
            throws IOException, InterruptedException {
        HttpResponse<String> response = send(method, path, body);
        requireSuccessful(method + " " + path, response);
        return response;
    }

    private JsonNode post(String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> response = sendSuccessful("POST", path, body);
        return response.body().isBlank() ? JSON.nullNode() : JSON.readTree(response.body());
    }

    private String postForId(String path, Object body) throws IOException, InterruptedException {
        HttpResponse<String> response = sendSuccessful("POST", path, body);
        String location =
                response.headers()
                        .firstValue("Location")
                        .orElseThrow(
                                () ->
                                        new IOException(
                                                "POST "
                                                        + path
                                                        + " failed: HTTP "
                                                        + response.statusCode()
                                                        + ", missing Location header. Body:\n"
                                                        + response.body()));
        return location.substring(location.lastIndexOf('/') + 1);
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = send("GET", path, null);
        requireStatus("GET " + path, response, 200);
        return JSON.readTree(response.body());
    }

    private void put(String path, Object body) throws IOException, InterruptedException {
        sendSuccessful("PUT", path, body);
    }

    private String requireUserId(String realm, String username)
            throws IOException, InterruptedException {
        String userId = findUserId(realm, username);
        if (userId == null) {
            throw new IOException("No user named " + username + " in realm " + realm);
        }
        return userId;
    }

    private String requireClientUuid(String realm, String clientId)
            throws IOException, InterruptedException {
        String clientUuid = findClientUuid(realm, clientId);
        if (clientUuid == null) {
            throw new IOException("No client named " + clientId + " in realm " + realm);
        }
        return clientUuid;
    }

    private String findGroupIdByPath(String realm, String groupPath)
            throws IOException, InterruptedException {
        JsonNode group = get(realmPath(realm) + "/group-by-path/" + groupPath(groupPath));
        return requiredText(group, "id", "group " + groupPath);
    }

    private static boolean executionMatches(JsonNode execution, String provider) {
        return provider.equals(execution.path("providerId").asText())
                || provider.equals(execution.path("displayName").asText());
    }

    private static List<String> groupSegments(String groupPath) {
        List<String> segments =
                List.of(groupPath.split("/")).stream()
                        .filter(segment -> !segment.isBlank())
                        .toList();
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("Group path must contain at least one segment");
        }
        return segments;
    }

    private static String groupPath(String groupPath) {
        return groupSegments(groupPath).stream()
                .map(KeycloakAdminApi::pathSegment)
                .reduce((left, right) -> left + "/" + right)
                .orElseThrow();
    }

    private static String requiredText(JsonNode node, String field, String description)
            throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IOException("Missing " + field + " in " + description + ": " + node);
        }
        return value.asText();
    }

    private static void requireSuccessful(String operation, HttpResponse<String> response)
            throws IOException {
        if (response.statusCode() / 100 != 2) {
            throw failure(operation, response);
        }
    }

    private static void requireStatus(
            String operation, HttpResponse<String> response, int expectedStatus)
            throws IOException {
        if (response.statusCode() != expectedStatus) {
            throw failure(operation, response);
        }
    }

    private static IOException failure(String operation, HttpResponse<String> response) {
        return new IOException(
                operation
                        + " failed: HTTP "
                        + response.statusCode()
                        + ". Body:\n"
                        + response.body());
    }

    private static String realmPath(String realm) {
        return "/admin/realms/" + pathSegment(realm);
    }

    private static String executionsPath(String realm, String flowAlias) {
        return realmPath(realm) + "/authentication/flows/" + pathSegment(flowAlias) + "/executions";
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pathSegment(String value) {
        return queryValue(value).replace("+", "%20");
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record ClientCredentials(String id, String secret) {}
}
