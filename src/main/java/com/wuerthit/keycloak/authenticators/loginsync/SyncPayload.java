package com.wuerthit.keycloak.authenticators.loginsync;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.Comparator;
import java.util.List;

/**
 * Immutable body for a login synchronization request.
 *
 * <p>Per the PermissionSync inbound contract (ADR-0001) this body carries exactly {@code
 * event_type}, {@code username} and {@code groups}: the receiver rejects unknown fields, so no
 * client identifier, e-mail or timestamp may be serialized. Because this body has no event,
 * request, correlation, or idempotency identifier and no timestamp, two genuine logins by the same
 * user in identical groups serialize byte-identically. The receiver cannot deduplicate by payload
 * equality and delivery is at-most-once.
 */
@JsonPropertyOrder({"event_type", "username", "groups"})
public record SyncPayload(
        @JsonProperty("username") String username, @JsonProperty("groups") List<String> groups) {

    public SyncPayload {
        groups =
                groups == null
                        ? List.of()
                        : groups.stream().sorted(Comparator.naturalOrder()).toList();
    }

    public static SyncPayload login(String username, List<String> groups) {
        return new SyncPayload(username, groups);
    }

    @Override
    public String toString() {
        return "SyncPayload[event_type=" + eventType() + ", groupCount=" + groups.size() + "]";
    }

    @JsonProperty("event_type")
    public String eventType() {
        return LoginSyncConstants.EVENT_TYPE_LOGIN;
    }
}
