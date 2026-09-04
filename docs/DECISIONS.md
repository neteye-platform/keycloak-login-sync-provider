# Login Sync Implementation Decisions

This document reconciles the updated `LLD.pdf` with the superseded plan that preceded it. Each
row records one reversal: what the old plan said, which LLD rule replaces it, and the action the
implementation must take. Rows carry stable identifiers `R-01` through `R-11` so an audit can
grep for them.

Authority order is the one stated in `docs/plans/README.md`: `LLD.pdf` pages 1-14 first, the
user's revised decisions where recorded here, then verified Keycloak 26.7.0 source where a
runtime behaviour is disputed.

## Reconciliation table

| reversal | old-plan behaviour | replacing LLD rule | resulting action |
| --- | --- | --- | --- |
| R-01 Retry deleted. | Failed syncs were retried with a backoff, and exceptions were split into a retryable and a non-retryable family. | LLD section 3.7: no retry for service-unavailable or timeout, no retry for 5xx, no retry for 4xx. | Exactly one HTTP attempt per logical sync, and the retryable/non-retryable exception split is replaced by one `SyncFailedException`. |
| R-02 401/403 policy. | An authentication rejection triggered a token refresh followed by a second POST. | LLD section 3.7 treats 401 and 403 as a terminal single-attempt failure. | The current request fails; the cached service-account token is invalidated so the next login re-fetches it; the sync POST is not repeated in the same login. This is not a retry: no second POST occurs within one login. |
| R-03 REGISTER dropped. | The provider also fired on registration and emitted a `REGISTER` event type. | LLD section 3.6 scopes the provider to login only. | `event_type` is the constant `LOGIN`. Registration is out of scope and no longer emitted. |
| R-04 UPDATE_PROFILE dropped. | Profile updates produced their own sync event. | LLD section 3.6 fixes the payload to a single login event. | Dropped: catching a profile update would require a `RequiredActionProvider`, which is removed from scope. |
| R-05 Token strategy closed. | The logging-in user's own JWT was to be reused, or forged, as the outbound credential. | LLD section 3.3 with section 3.1, together with the user decision, selects a service-account JWT obtained via OAuth2 Client Credentials. | Only the service-account JWT authenticates the sync call; the plugin never constructs a user token. `client_id` remains an ordinary payload field. |
| R-06 Config mechanism. | Configuration was read from process environment variables via `System.getenv`. | The LLD section 4.3 note discards `System.getenv`; section 4.3.1 adopts Keycloak's `Config.Scope`. | Read every setting through `Config.Scope` in the factory, so Keycloak's own SPI configuration layering and its environment mapping apply. |
| R-07 Config key name. | No single spelling was fixed, so documents and code disagreed. | LLD section 4.3.1 names the property `endpoint`; the implementation uses `service-endpoint`, environment variable `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT`. | Implement `service-endpoint`. The mismatch with the LLD is flagged, not silently resolved: `ACTION REQUIRED: LLD owner` must align section 4.3.1 with the implemented key. |
| R-08 Jackson pin. | Jackson was pulled in transitively at an unpinned version and risked shading a different copy into the jar. | Recorded by the completed baseline plan `0002-scaffold-reconciliation`, consistent with the packaging rules in LLD section 4.3. | `provided` Jackson is pinned to 2.21.2, which matches what Keycloak 26.7.0 actually ships, so the runtime copy and the compile-time copy agree. |
| R-09 Additions beyond the LLD. | Nothing bounded concurrent outbound calls, TLS trust material was implicit, and client lifetime was unstated. | Not specified by the LLD; added deliberately and recorded here rather than left implicit. | Three additions. A bulkhead caps concurrent in-flight sync calls, so a slow receiver cannot exhaust Keycloak's worker threads and stall unrelated logins. A truststore-derived `SSLContext` pins the trust material for the receiver's certificate, so a private or internal certificate authority works without disabling verification. Per-JVM singleton documentation records that the HTTP client and the token cache are shared process-wide, so a reader does not assume per-request instances and reintroduce a connection leak. |
| R-10 Keycloak REQUIRED-flow contract. | A deliberate skip called `context.attempted()`, on the assumption that it left the login untouched. | Verified from Keycloak 26.7.0 source: `AuthenticationProcessor.isSuccessful()` returns true only for `ExecutionStatus.SUCCESS`, so `context.attempted()` in a REQUIRED execution fails the login. | Every deliberate skip calls `context.success()`. Additionally, `requiresUser() == true` throws before `authenticate()` is invoked when no user is set, so a misplaced execution is rejected by Keycloak rather than skipping quietly; no null-user guard inside `authenticate()` can compensate. |
| R-11 Flow-path whitelist widened (decision A6). | Only the `authenticate` flow path synchronized, so an identity-provider login never reached the sync. | Verified from Keycloak 26.7.0 source: an identity-provider login is completed through the post-broker-login flow (`IdpConfig.getPostBrokerLoginFlowId`, dispatched by `IdentityBrokerService.finishOrRedirectToPostBrokerLogin`), which runs under the `post-broker-login` flow path. | The `getFlowPath()` guard is a two-path whitelist: `LoginActionsService.AUTHENTICATE_PATH` and `LoginActionsService.POST_BROKER_LOGIN_PATH` synchronize. The guard is widened rather than removed, so `reset-credentials`, `first-broker-login`, `registration`, a null path, and any unknown future value still skip with `context.success()` and no wrong `event_type` is emitted. |

## Historical artifacts

Prior plans and spike evidence are absent from this repository. They must not be treated as
authority, cited as justification, or reconstructed from memory. Where a decision above reverses
an earlier position, the authority is the LLD section named in the row, the user decision recorded
here, or verified Keycloak 26.7.0 source. Command output written under the ignored
`docs/evidence/` directory demonstrates that a check ran; it is not tracked authority either.
