package com.wuerthit.keycloak.authenticators.loginsync.support;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Drives the real username/password browser flow without using the direct-grant shortcut.
 *
 * <p>A direct grant never runs browser-flow authenticators, so it cannot prove that login-sync was
 * invoked or that a failed synchronization blocked the login. This driver instead loads Keycloak's
 * login form, submits it, and follows the resulting redirects by hand until the configured callback
 * receives an authorization code or Keycloak renders a terminal response.
 */
public class BrowserLogin {

    /** Where the flow is expected to land; no server needs to listen there. */
    public static final String REDIRECT_URI = "http://localhost:9999/callback";

    private static final int MAX_HOPS = 12;
    private static final Pattern FORM_ACTION =
            Pattern.compile(
                    "<form\\b[^>]*\\baction\\s*=\\s*(['\"])(.*?)\\1",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private final String keycloakBaseUrl;

    public BrowserLogin(String keycloakBaseUrl) {
        this.keycloakBaseUrl = stripTrailingSlash(keycloakBaseUrl);
    }

    /**
     * Performs one independent browser login with a fresh state and cookie jar.
     *
     * <p>A rendered error page is a scenario result, not a broken redirect chain, so it is returned
     * with its status and body. Missing redirect locations and a chain that exceeds the fixed hop
     * limit are infrastructure failures and therefore throw.
     */
    public Result login(String realm, String clientId, String username, String password)
            throws IOException, InterruptedException {
        HttpClient http =
                HttpClient.newBuilder()
                        .cookieHandler(new PlainCookieJar())
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
        String state = UUID.randomUUID().toString();
        URI authorizationUri = authorizationUri(realm, clientId, state);
        HttpResponse<String> loginPage = get(http, authorizationUri);

        int hops = 0;
        while (isRedirect(loginPage) && hops < MAX_HOPS) {
            URI location = redirectLocation(authorizationUri, loginPage);
            Result callback = callbackResult(location, loginPage.statusCode());
            if (callback != null) {
                return callback;
            }
            authorizationUri = location;
            loginPage = get(http, authorizationUri);
            hops++;
        }
        if (hops == MAX_HOPS && isRedirect(loginPage)) {
            throw hopLimitExceeded();
        }

        URI formAction = formAction(authorizationUri, loginPage);
        HttpResponse<String> response = postCredentials(http, formAction, username, password);
        hops++;

        while (hops <= MAX_HOPS) {
            if (!isRedirect(response)) {
                return Result.failure(response.statusCode(), response.body());
            }

            URI location = redirectLocation(formAction, response);
            Result callback = callbackResult(location, response.statusCode());
            if (callback != null) {
                return callback;
            }
            if (hops == MAX_HOPS) {
                break;
            }

            formAction = location;
            response = get(http, formAction);
            hops++;
        }

        throw hopLimitExceeded();
    }

    private URI authorizationUri(String realm, String clientId, String state) {
        return URI.create(
                keycloakBaseUrl
                        + "/realms/"
                        + pathSegment(realm)
                        + "/protocol/openid-connect/auth"
                        + "?client_id="
                        + queryValue(clientId)
                        + "&redirect_uri="
                        + queryValue(REDIRECT_URI)
                        + "&response_type=code"
                        + "&scope=openid"
                        + "&state="
                        + queryValue(state));
    }

    private static HttpResponse<String> get(HttpClient http, URI uri)
            throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> postCredentials(
            HttpClient http, URI action, String username, String password)
            throws IOException, InterruptedException {
        String form =
                "username="
                        + queryValue(username)
                        + "&password="
                        + queryValue(password)
                        + "&credentialId=";
        return http.send(
                HttpRequest.newBuilder(action)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static URI formAction(URI pageUri, HttpResponse<String> response) {
        if (response.statusCode() / 100 != 2) {
            throw new AssertionError(
                    "Expected a Keycloak login form at "
                            + pageUri
                            + " but received HTTP "
                            + response.statusCode()
                            + ". Body:\n"
                            + response.body());
        }
        Matcher matcher = FORM_ACTION.matcher(response.body());
        if (!matcher.find()) {
            throw new AssertionError(
                    "No login form action found at "
                            + pageUri
                            + " after HTTP "
                            + response.statusCode()
                            + ". Body:\n"
                            + response.body());
        }
        return pageUri.resolve(decodeHtmlAttribute(matcher.group(2)));
    }

    private static URI redirectLocation(URI current, HttpResponse<String> response) {
        String location =
                response.headers()
                        .firstValue("Location")
                        .orElseThrow(
                                () ->
                                        new AssertionError(
                                                "Redirect from "
                                                        + current
                                                        + " returned HTTP "
                                                        + response.statusCode()
                                                        + " without a Location header. Body:\n"
                                                        + response.body()));
        return current.resolve(location);
    }

    private static Result callbackResult(URI location, int redirectStatus) {
        if (!isCallback(location)) {
            return null;
        }
        String code = queryParameter(location, "code");
        if (code != null && !code.isBlank()) {
            return Result.success(code, redirectStatus);
        }
        return Result.failure(redirectStatus, location.toString());
    }

    private static boolean isCallback(URI uri) {
        URI callback = URI.create(REDIRECT_URI);
        return callback.getScheme().equalsIgnoreCase(uri.getScheme())
                && callback.getAuthority().equalsIgnoreCase(uri.getAuthority())
                && callback.getPath().equals(uri.getPath());
    }

    private static String queryParameter(URI uri, String name) {
        String query = uri.getRawQuery();
        if (query == null) {
            return null;
        }
        for (String field : query.split("&")) {
            String[] parts = field.split("=", 2);
            if (decode(parts[0]).equals(name)) {
                return parts.length == 1 ? "" : decode(parts[1]);
            }
        }
        return null;
    }

    private static boolean isRedirect(HttpResponse<?> response) {
        return response.statusCode() / 100 == 3;
    }

    private static AssertionError hopLimitExceeded() {
        return new AssertionError("The login flow did not settle within " + MAX_HOPS + " hops");
    }

    private static String decodeHtmlAttribute(String value) {
        return value.replace("&amp;", "&")
                .replace("&#38;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
    }

    private static String queryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String pathSegment(String value) {
        return queryValue(value).replace("+", "%20");
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String stripTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public record Result(boolean succeeded, String code, int statusCode, String body) {

        private static Result success(String code, int statusCode) {
            return new Result(true, code, statusCode, "");
        }

        private static Result failure(int statusCode, String body) {
            return new Result(false, null, statusCode, body);
        }
    }

    /**
     * A cookie jar that keeps every cookie and replays it while ignoring cookie attributes.
     *
     * <p>{@code CookieManager} cannot be used: Keycloak marks {@code KC_RESTART} as {@code Secure;
     * SameSite=None}, and a secure cookie is never sent over the plain HTTP used to reach the test
     * container. Ignoring attributes is safe because each jar belongs to one login and one host.
     */
    private static class PlainCookieJar extends CookieHandler {

        private final Map<String, String> cookies = new LinkedHashMap<>();

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) {
            responseHeaders.forEach(
                    (header, values) -> {
                        if (header == null || !header.equalsIgnoreCase("Set-Cookie")) {
                            return;
                        }
                        for (String value : values) {
                            String pair = value.split(";", 2)[0];
                            int split = pair.indexOf('=');
                            if (split < 0) {
                                continue;
                            }
                            String name = pair.substring(0, split).trim();
                            String content = pair.substring(split + 1).trim();
                            if (content.isEmpty()) {
                                cookies.remove(name);
                            } else {
                                cookies.put(name, content);
                            }
                        }
                    });
        }

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) {
            if (cookies.isEmpty()) {
                return Map.of();
            }
            String header =
                    cookies.entrySet().stream()
                            .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                            .collect(Collectors.joining("; "));
            return Map.of("Cookie", List.of(header));
        }
    }
}
