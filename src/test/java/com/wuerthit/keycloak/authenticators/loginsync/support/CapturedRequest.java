package com.wuerthit.keycloak.authenticators.loginsync.support;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.TreeMap;

/**
 * An immutable HTTP request snapshot whose diagnostics cannot disclose bearer credentials.
 *
 * <p>The case-insensitive map preserves HTTP header lookup semantics while copying every value so
 * later mutations by the server cannot change an assertion's evidence.
 */
public record CapturedRequest(
        String method, String path, Map<String, List<String>> headers, String body) {

    public CapturedRequest {
        method = Objects.requireNonNull(method, "method");
        path = Objects.requireNonNull(path, "path");
        headers = immutableHeaders(headers);
        body = Objects.requireNonNull(body, "body");
    }

    /**
     * Returns the unredacted authorization value for cryptographic verification only.
     *
     * <p>Callers MUST NEVER embed this value in an assertion message, exception, or test report.
     */
    public String rawAuthorization() {
        List<String> values = headers.get("Authorization");
        return values == null || values.isEmpty() ? null : values.getFirst();
    }

    @Override
    public String toString() {
        StringJoiner renderedHeaders = new StringJoiner(", ", "{", "}");
        headers.forEach(
                (name, values) -> {
                    if (name.equalsIgnoreCase("Authorization")) {
                        renderedHeaders.add(name + "=" + redactedAuthorization(values));
                    } else {
                        renderedHeaders.add(name + "=" + values);
                    }
                });
        return "CapturedRequest[method="
                + method
                + ", path="
                + path
                + ", headers="
                + renderedHeaders
                + ", body="
                + body
                + "]";
    }

    private static Map<String, List<String>> immutableHeaders(Map<String, List<String>> headers) {
        Objects.requireNonNull(headers, "headers");
        Map<String, List<String>> copy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headers.forEach(
                (name, values) ->
                        copy.put(
                                Objects.requireNonNull(name, "header name"),
                                List.copyOf(Objects.requireNonNull(values, "header values"))));
        return Collections.unmodifiableMap(copy);
    }

    private static String redactedAuthorization(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "****";
        }
        String value = values.getFirst();
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                String scheme = value.substring(0, index);
                return scheme.isEmpty() ? "****" : scheme + " ****";
            }
        }
        return "****";
    }
}
