package com.wuerthit.keycloak.authenticators.loginsync;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable body for a login synchronization request.
 *
 * <p>Because this body has no event, request, correlation, or idempotency identifier and its
 * timestamp is truncated to seconds, two genuine logins by the same user to the same client in one
 * second serialize byte-identically. The receiver cannot deduplicate by payload equality and
 * delivery is at-most-once.
 */
@JsonPropertyOrder({"event_type", "client_id", "username", "email", "groups", "timestamp"})
public record SyncPayload(
        @JsonProperty("client_id") String clientId,
        @JsonProperty("username") String username,
        @JsonProperty("email") String email,
        @JsonProperty("groups") List<String> groups,
        @JsonProperty("timestamp") String timestamp) {

    public SyncPayload {
        groups =
                groups == null
                        ? List.of()
                        : groups.stream().sorted(Comparator.naturalOrder()).toList();
        if (timestamp != null) {
            timestamp = Instant.parse(timestamp).truncatedTo(ChronoUnit.SECONDS).toString();
        }
    }

    public static SyncPayload login(
            String clientId,
            String username,
            String email,
            List<String> groups,
            Instant occurredAt) {
        return new SyncPayload(
                clientId,
                username,
                email,
                groups,
                occurredAt.truncatedTo(ChronoUnit.SECONDS).toString());
    }

    @Override
    public String toString() {
        return "SyncPayload[event_type="
                + eventType()
                + ", client_id="
                + clientId
                + ", groupCount="
                + groups.size()
                + "]";
    }

    @JsonProperty("event_type")
    public String eventType() {
        return LoginSyncConstants.EVENT_TYPE_LOGIN;
    }
}
