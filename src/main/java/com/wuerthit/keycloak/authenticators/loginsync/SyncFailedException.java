package com.wuerthit.keycloak.authenticators.loginsync;

/**
 * The single failure type of the login-sync transport layer.
 *
 * <p>There is deliberately only ONE exception class: it is NOT split into a retry-eligible family
 * and a terminal family. The LLD (section 3.7, recorded as {@code R-01} in {@code
 * docs/DECISIONS.md}) removed retry, so no caller can act differently on one failure family than on
 * another; such a split would exist only to feed a retry decision that no longer exists.
 *
 * <p>The message is <strong>redacted by construction</strong>: it names the {@link SyncOutcome} and
 * nothing else. It MUST NOT embed the response body, the bearer token, the client secret, the
 * request payload, the user's email, or any group path, because exception messages reach Keycloak's
 * server log and test reports.
 */
public class SyncFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient SyncOutcome outcome;

    public SyncFailedException(SyncOutcome outcome) {
        this(outcome, null);
    }

    public SyncFailedException(SyncOutcome outcome, Throwable cause) {
        // Only the outcome name is rendered; the cause is attached but never inlined here.
        super("login sync failed: " + outcome, cause);
        this.outcome = outcome;
    }

    public SyncOutcome outcome() {
        return outcome;
    }
}
