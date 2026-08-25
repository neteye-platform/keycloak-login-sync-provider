package com.wuerthit.keycloak.authenticators.loginsync;

/**
 * The exhaustive result taxonomy of a single logical login synchronization.
 *
 * <p>These constants are deliberately NOT split into a retry-eligible family and a terminal family,
 * here or anywhere else in this provider. The LLD (section 3.7, recorded as {@code R-01} in {@code
 * docs/DECISIONS.md}) removed retry entirely: exactly one HTTP attempt is made per logical sync, so
 * no failure class differs behaviourally from another, and such a split would exist only to feed a
 * retry decision that no longer exists.
 *
 * <p>{@link #blocksLogin()} is carried as data on each constant rather than being recomputed by a
 * {@code switch} in the Authenticator, so adding an outcome forces an explicit login-verdict
 * decision at the point of definition. Per portfolio invariant {@code P5} the provider is
 * fail-closed: every admitted failure blocks the login.
 */
public enum SyncOutcome {

    /** The receiver accepted the sync: HTTP 200 or 201. */
    SUCCESS(false),

    /** The receiver rejected the request: a 4xx other than 401 or 403. */
    REJECTED(true),

    /** The receiver refused the service-account credential: HTTP 401 or 403. */
    UNAUTHORIZED(true),

    /** The receiver failed: any 5xx. */
    SERVER_ERROR(true),

    /** The single attempt exceeded the configured request timeout. */
    TIMEOUT(true),

    /** The single attempt failed at the transport layer with an I/O error. */
    TRANSPORT_ERROR(true),

    /** The service-account token endpoint could not supply a token. */
    TOKEN_UNAVAILABLE(true),

    /** The bulkhead was saturated, so no HTTP call was made at all. */
    SKIPPED_SATURATED(false);

    private final boolean blocksLogin;

    SyncOutcome(boolean blocksLogin) {
        this.blocksLogin = blocksLogin;
    }

    /**
     * Whether this outcome must block the login.
     *
     * <p>Consumed by the Authenticator in plan 0005. A deliberate skip does not block; every
     * admitted failure does.
     *
     * @return {@code true} when the login must fail because of this outcome
     */
    public boolean blocksLogin() {
        return blocksLogin;
    }
}
