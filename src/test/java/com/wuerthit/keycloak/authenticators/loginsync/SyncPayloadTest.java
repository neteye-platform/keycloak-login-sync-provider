package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPayloadTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void serializesTheExactWireContract() throws Exception {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of("/staff/engineering", "/staff"),
                        Instant.parse("2026-08-24T09:15:32Z"));

        String json = OBJECT_MAPPER.writeValueAsString(payload);

        assertEquals(
                "{\"event_type\":\"LOGIN\",\"client_id\":\"internal-portal\",\"username\":\"jdoe\",\"email\":\"jdoe@example.com\",\"groups\":[\"/staff\",\"/staff/engineering\"],\"timestamp\":\"2026-08-24T09:15:32Z\"}",
                json);
    }

    @Test
    void serializesOnlyTheSixContractFieldsInOrder() throws Exception {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of("/staff"),
                        Instant.parse("2026-08-24T09:15:32Z"));

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(payload));
        List<String> fields = new ArrayList<>();
        json.fieldNames().forEachRemaining(fields::add);

        assertEquals(
                List.of("event_type", "client_id", "username", "email", "groups", "timestamp"),
                fields);
    }

    @Test
    void truncatesTimestampToWholeSeconds() throws Exception {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of(),
                        Instant.parse("2026-08-24T09:15:32.987654321Z"));

        String timestamp =
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(payload))
                        .get("timestamp")
                        .asText();

        assertEquals("2026-08-24T09:15:32Z", timestamp);
        assertTrue(timestamp.matches("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z$"));
    }

    @Test
    void canonicalConstructorTruncatesTimestampToWholeSeconds() throws Exception {
        SyncPayload payload =
                new SyncPayload(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of(),
                        Instant.parse("2026-08-24T09:15:32.123456789Z").toString());

        String timestamp =
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(payload))
                        .get("timestamp")
                        .asText();

        assertEquals("2026-08-24T09:15:32Z", timestamp);
    }

    @Test
    void sortsGroupsOnTheWireAndDefensivelyMakesThemUnmodifiable() throws Exception {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of("/staff/engineering", "/staff"),
                        Instant.parse("2026-08-24T09:15:32Z"));

        assertEquals(
                "[\"/staff\",\"/staff/engineering\"]",
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(payload))
                        .get("groups")
                        .toString());
        assertThrows(UnsupportedOperationException.class, () -> payload.groups().add("/other"));
    }

    @Test
    void serializesEmptyAndNullGroupsAsAnEmptyArray() throws Exception {
        SyncPayload emptyGroups =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of(),
                        Instant.parse("2026-08-24T09:15:32Z"));
        SyncPayload nullGroups =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        null,
                        Instant.parse("2026-08-24T09:15:32Z"));

        assertEquals(
                "[]",
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(emptyGroups))
                        .get("groups")
                        .toString());
        assertEquals(
                "[]",
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(nullGroups))
                        .get("groups")
                        .toString());
    }

    @Test
    void serializesNullEmailAsPresentJsonNull() throws Exception {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        null,
                        List.of(),
                        Instant.parse("2026-08-24T09:15:32Z"));

        JsonNode json = OBJECT_MAPPER.readTree(OBJECT_MAPPER.writeValueAsString(payload));

        assertTrue(json.has("email"));
        assertTrue(json.get("email").isNull());
    }

    @Test
    void redactsEmailAndGroupPathsFromToString() {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of("/staff/engineering"),
                        Instant.parse("2026-08-24T09:15:32Z"));

        String rendered = payload.toString();

        assertFalse(rendered.contains("jdoe@example.com"));
        assertFalse(rendered.contains("/staff/engineering"));
    }

    @Test
    void hasNoPublicNonLoginConstructionPath() throws Exception {
        assertEquals(5, SyncPayload.class.getRecordComponents().length);
        assertTrue(
                java.util.Arrays.stream(SyncPayload.class.getRecordComponents())
                        .noneMatch(
                                component ->
                                        component
                                                .getName()
                                                .toLowerCase(java.util.Locale.ROOT)
                                                .contains("event")));

        for (java.lang.reflect.Constructor<?> constructor :
                SyncPayload.class.getDeclaredConstructors()) {
            assertEquals(
                    List.of(String.class, String.class, String.class, List.class, String.class),
                    List.of(constructor.getParameterTypes()));
        }

        Method login =
                SyncPayload.class.getDeclaredMethod(
                        "login",
                        String.class,
                        String.class,
                        String.class,
                        List.class,
                        Instant.class);
        assertTrue(Modifier.isPublic(login.getModifiers()));
        assertTrue(Modifier.isStatic(login.getModifiers()));
        assertEquals(SyncPayload.class, login.getReturnType());
        assertEquals(3, countParametersOfType(login, String.class));
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "jdoe@example.com",
                        List.of(),
                        Instant.parse("2026-08-24T09:15:32Z"));
        assertEquals(LoginSyncConstants.EVENT_TYPE_LOGIN, payload.eventType());
        assertEquals(
                LoginSyncConstants.EVENT_TYPE_LOGIN,
                OBJECT_MAPPER
                        .readTree(OBJECT_MAPPER.writeValueAsString(payload))
                        .get("event_type")
                        .asText());

        for (Method method : SyncPayload.class.getMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && Modifier.isStatic(method.getModifiers())
                    && method.getReturnType().equals(SyncPayload.class)) {
                assertEquals("login", method.getName());
                assertEquals(3, countParametersOfType(method, String.class));
            }
        }
    }

    @Test
    void redactsSentinelValuesFromToString() {
        SyncPayload payload =
                SyncPayload.login(
                        "internal-portal",
                        "jdoe",
                        "email@sentinel.test",
                        List.of("/GROUP_SENTINEL"),
                        Instant.parse("2026-08-24T09:15:32Z"));

        String rendered = payload.toString();

        assertFalse(rendered.contains("email@sentinel.test"));
        assertFalse(rendered.contains("/GROUP_SENTINEL"));
    }

    private static long countParametersOfType(Method method, Class<?> type) {
        return java.util.Arrays.stream(method.getParameterTypes()).filter(type::equals).count();
    }
}
