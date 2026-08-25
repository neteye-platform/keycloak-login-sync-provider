package com.wuerthit.keycloak.authenticators.loginsync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class SyncClientTest {

    @Test
    void definesEveryOutcomeAndItsExactLoginVerdict() {
        EnumMap<SyncOutcome, Boolean> expected = new EnumMap<>(SyncOutcome.class);
        expected.put(SyncOutcome.SUCCESS, false);
        expected.put(SyncOutcome.REJECTED, true);
        expected.put(SyncOutcome.UNAUTHORIZED, true);
        expected.put(SyncOutcome.SERVER_ERROR, true);
        expected.put(SyncOutcome.TIMEOUT, true);
        expected.put(SyncOutcome.TRANSPORT_ERROR, true);
        expected.put(SyncOutcome.TOKEN_UNAVAILABLE, true);
        expected.put(SyncOutcome.SKIPPED_SATURATED, false);

        assertEquals(8, SyncOutcome.values().length);
        assertEquals(EnumSet.allOf(SyncOutcome.class), expected.keySet());
        expected.forEach(
                (outcome, blocksLogin) -> assertEquals(blocksLogin, outcome.blocksLogin()));
    }

    @Test
    void exposesUncheckedFailureOutcomeWithoutLeakingCauseMessage() {
        String secret = "BODY_TOKEN_SECRET_SENTINEL";
        IllegalStateException cause = new IllegalStateException(secret);

        SyncFailedException failure = new SyncFailedException(SyncOutcome.SERVER_ERROR, cause);

        assertTrue(RuntimeException.class.isAssignableFrom(SyncFailedException.class));
        assertSame(SyncOutcome.SERVER_ERROR, failure.outcome());
        assertSame(cause, failure.getCause());
        assertEquals("login sync failed: SERVER_ERROR", failure.getMessage());
        assertFalse(failure.getMessage().contains(secret));
    }
}
