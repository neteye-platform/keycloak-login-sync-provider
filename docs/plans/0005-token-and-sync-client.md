# 0005 - token provider and sync client - Work Plan

```yaml
slug: 0005-token-and-sync-client
revision: 3
wave: 3
prerequisites: [0001-contract-reconciliation, 0003-config-and-payload, 0004-circuit-breaker]
parallel_with: []
owns_files:
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/ServiceAccountTokenProvider.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/TokenHandle.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/SyncClient.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/SyncOutcome.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/SyncFailedException.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/ServiceAccountTokenProviderTest.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/SyncClientTest.java
```

## TL;DR (For humans)

**What you'll get.** The transport layer: a cached, single-flight service-account token provider
whose invalidation is **generation-guarded**, and a sync client that makes **exactly one** HTTP
attempt per login and never retries.

**Why revision 2 changed invalidation.** Both reviewers found the same race. Revision 1 said a
401/403 calls `invalidate()`, which clears the cache unconditionally. Under load many logins share
one token; if the service-account key rotates, N concurrent requests all get 401 and all call
`invalidate()` - and a slow thread holding the _old_ token can wipe the _new_ token another thread
just installed, producing a self-sustaining invalidation storm and a thundering herd against
Keycloak's token endpoint. Invalidation is now `invalidateIfCurrent(handle)`: a no-op unless the
caller's own token generation is still the cached one.

**Also fixed:** the token fetch previously had **no timeout at all** on a blocking login path, and
its failure would have been recorded against the _receiver's_ circuit breaker - so an outage of
Keycloak's own token endpoint would open a circuit that claims the receiver is down.

**What it will NOT do.** No retry, no backoff. No user token. No async, queue or outbox.

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Medium. Concentrated in the token cache's concurrency and in keeping the login path
bounded.

---

## Scope

### In scope

- `ServiceAccountTokenProvider`: `client_credentials` fetch, cache, single-flight refresh,
  generation-guarded invalidation, bounded timeouts.
- `TokenHandle`: an opaque token + generation pair.
- `SyncClient`: one-attempt POST, bulkhead, truststore-aware `HttpClient`, lifecycle.
- `SyncOutcome`, `SyncFailedException`.
- Unit tests for both, against a local stub server.

### Out of scope (Must-NOT-Have)

- MUST NOT implement retry, backoff, a retry counter, or a total-time-budget-across-attempts.
- MUST NOT re-send the sync POST within the same login for any status code, including 401/403.
- MUST NOT create `RetryableSyncException` or `NonRetryableSyncException`.
- MUST NOT construct, sign, decode or forward the authenticating user's token.
- MUST NOT add an external HTTP client library or an external JSON library.
- MUST NOT log the raw bearer token, client secret, request body, email or any group path at any
  level.
- MUST NOT implement async, queued or outbox delivery.
- MUST NOT implement the Keycloak SPI classes.
- MUST NOT hardcode the sync path.
- MUST NOT record a token-endpoint failure as a receiver circuit-breaker failure.
- MUST NOT perform the token fetch while holding a bulkhead permit.

---

## Key decisions

| id      | decision                                                                                                                                            | rationale                                                                                                                                                            |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| T1      | One `SyncFailedException` carrying a `SyncOutcome`                                                                                                  | with retry deleted there is no behavioural difference between failure classes                                                                                        |
| T2      | 401/403: fail this login, `invalidateIfCurrent(handle)`, do **not** re-POST                                                                         | LLD 3.7; a rotated key self-heals on the next login instead of blocking every login until natural expiry                                                             |
| T3      | 60-second refresh margin before `exp`                                                                                                               | avoids sending a token that expires in flight                                                                                                                        |
| T4      | Missing or non-positive `expires_in` means "expires immediately"                                                                                    | the opposite choice yields a permanently stale token that silently stops working                                                                                     |
| T5      | Single-flight refresh                                                                                                                               | 200 concurrent logins on an expired token must produce one token request                                                                                             |
| T6      | Bulkhead `tryAcquire` failure is a **skip that permits the login**, never a queued wait                                                             | a black-holed receiver would otherwise exhaust Keycloak's worker threads before the breaker trips                                                                    |
| T7      | `HttpClient` builds its `SSLContext` from Keycloak's truststore                                                                                     | a raw `HttpClient` ignores `KC_TRUSTSTORE_PATHS` and would fail only in production against an internal CA                                                            |
| T8      | A token-endpoint failure fails the current login as `TOKEN_UNAVAILABLE`                                                                             | LLD 3.7                                                                                                                                                              |
| **T9**  | **`invalidateIfCurrent(TokenHandle)` replaces `invalidate()`**                                                                                      | **review: unconditional invalidation lets a stale 401 evict a freshly installed valid token, causing an invalidation storm**                                         |
| **T10** | **The token fetch happens OUTSIDE the sync bulkhead, with its own bounded connect and request timeouts**                                            | **review: revision 1 left it unbounded (a hang risk on a blocking login path) and inside the bulkhead (a slow Keycloak consumes the receiver's concurrency budget)** |
| **T11** | **`TOKEN_UNAVAILABLE` settles the breaker permit as `ABANDONED`, not `FAILURE`**                                                                    | **review: attributing Keycloak's own outage to the receiver's breaker opens a circuit that misdescribes reality**                                                    |
| **T12** | **A failed single-flight future must be evicted, never cached**                                                                                     | **a memoised failed future would make every subsequent login fail permanently**                                                                                      |
| **T13** | **Explicit ordering: breaker permit -> bounded token fetch (outside bulkhead) -> bulkhead `tryAcquire` -> sync POST -> settle permit in `finally`** | **removes the last ordering ambiguity between plans 0004, 0005 and 0006**                                                                                            |

---

## Verification strategy

**TDD for `ServiceAccountTokenProvider`**; tests-after for `SyncClient`.

Unit tests run against a local in-JVM `com.sun.net.httpserver.HttpServer`. This is permitted:
the LLD restricts _end-to-end_ token issuance to the real Keycloak container, which plan 0007
honours. Unit tests may stub the token endpoint locally.

The single most important assertion is a **request counter equal to 1** - it is what proves the
retry policy was removed rather than renamed. The second is the interleaving test that proves a
stale invalidation cannot evict a newer token.

Per invariant P1 every check names a command and expected result; per P3 record `BASE_SHA`.

---

## Execution strategy

| wave | todos  | depends on |
| ---- | ------ | ---------- |
| 1    | 1      | -          |
| 2    | 2      | 1          |
| 3    | 3      | 1, 2       |
| F    | F1, F2 | 1, 2, 3    |

Strictly sequential.

---

## Todos

- [ ] 1. `SyncOutcome.java` + `SyncFailedException.java`: the single failure surface - expect no retryable/non-retryable split anywhere

  **References:** `docs/DECISIONS.md` rows `R-01` and `R-02` (plan 0001 is a hard prerequisite);
  `N4-LLD-2.pdf` sections 3.7 and 4.4.
  **Details:** `SyncOutcome` is an enum with exactly these nine constants: `SUCCESS` (200/201),
  `REJECTED` (4xx other than 401/403), `UNAUTHORIZED` (401/403), `SERVER_ERROR` (5xx), `TIMEOUT`,
  `TRANSPORT_ERROR` (IO), `TOKEN_UNAVAILABLE`, `SKIPPED_CIRCUIT_OPEN`, `SKIPPED_SATURATED`.
  Each constant MUST carry two boolean properties used by plan 0006 so the mapping is data, not
  scattered `switch` logic: `blocksLogin()` and `breakerOutcome()` returning the plan-40
  settlement (`SUCCESS`, `FAILURE` or `ABANDONED`). Required values:
  `SUCCESS` -> blocks=false, breaker=SUCCESS. `REJECTED`, `SERVER_ERROR`, `TIMEOUT`,
  `TRANSPORT_ERROR`, `UNAUTHORIZED` -> blocks=true, breaker=FAILURE.
  `TOKEN_UNAVAILABLE` -> blocks=true, breaker=**ABANDONED** (T11).
  `SKIPPED_CIRCUIT_OPEN` and `SKIPPED_SATURATED` -> blocks=false, breaker=**ABANDONED** (T6, B9).
  `SyncFailedException` is unchecked, carries the `SyncOutcome`, and has a **redacted** message
  that MUST NOT embed the response body, token or payload. Add a comment stating there is
  deliberately no retryable/non-retryable distinction because the LLD removed retry.
  **Acceptance criteria:** both types compile. `grep -rciE 'retryable|nonretryable' src/main/java`
  totals 0. A unit test asserts `SyncOutcome.values().length == 9` and asserts the exact
  `blocksLogin()`/`breakerOutcome()` pair for **every** constant, so no outcome is left unmapped.
  `scripts/test.sh clean verify` exits 0.
  **QA happy:** `scripts/test.sh clean verify` exits 0 and the retryable grep totals 0. Evidence: `docs/evidence/0005-outcome-verify.log`.
  **QA failure:** add a tenth enum constant without updating the test; the
  `values().length == 9` and per-constant mapping assertions must fail, proving no outcome can be
  added without an explicit mapping decision; remove it, re-run, confirm exit 0 and clean
  `git status --porcelain src/`. Evidence: `docs/evidence/0005-outcome-exhaustive.log`.
  **Commit:** `feat: add exhaustive sync outcome taxonomy and failure type`

- [ ] 2. `ServiceAccountTokenProvider.java` + `TokenHandle.java` + test: generation-guarded cached token - expect one token request under 32 concurrent callers and no stale eviction

  **References:** `N4-LLD-2.pdf` sections 3.3 and 3.7; `LoginSyncConfig` from plan 0003 for
  `sa-client-id`, `sa-client-secret`, `sa-token-endpoint`, `DEFAULT_TOKEN_TIMEOUT_MS` and
  `DEFAULT_CONNECT_TIMEOUT_MS`; decisions T3, T4, T5, T8, T9, T10, T12.
  **Details:** TDD. POST `grant_type=client_credentials` as
  `application/x-www-form-urlencoded` to `sa-token-endpoint` with the service account's id and
  secret; parse `access_token` and `expires_in` with the `provided` Jackson.
  - `TokenHandle` is an immutable pair of the token string and an opaque monotonically increasing
    **generation**. `acquire()` returns a `TokenHandle`; callers never see a bare string they can
    later invalidate blindly.
  - Cache with a **60-second** refresh margin (T3). Missing, zero or negative `expires_in` means
    **expires immediately** (T4).
  - **Single-flight** (T5): concurrent callers observing an expired token produce exactly ONE
    request. If the in-flight refresh **fails, evict it immediately** - never cache a failed
    future, or every later login fails permanently (T12).
  - **`invalidateIfCurrent(TokenHandle handle)`** (T9): atomically clears the cache **only if** the
    cached generation still equals `handle.generation()`. Otherwise it is a no-op. This is the fix
    for the invalidation storm.
  - **Bounded timeouts** (T10): apply `DEFAULT_CONNECT_TIMEOUT_MS` and `DEFAULT_TOKEN_TIMEOUT_MS`
    to the token request. An unbounded token call on a blocking login path is a hang risk.
  - **No retry**: a token-endpoint failure raises `SyncFailedException` with
    `SyncOutcome.TOKEN_UNAVAILABLE` after exactly one attempt (T8).
  - The raw token MUST NOT appear in any log, exception message or `toString()`; `TokenHandle`
    overrides `toString()` to render only the generation.
    **Tests:** a cached token is reused (counter stays 1 over many calls); a near-expiry token
    refreshes; **32 concurrent callers on an expired token trigger exactly one request**; a token
    endpoint 500 raises `TOKEN_UNAVAILABLE` after exactly one attempt; a response without
    `expires_in` refetches next call; **the interleaving test** - acquire handle G1, invalidate it,
    let another thread install G2, then have the delayed G1 holder call
    `invalidateIfCurrent(G1)` and assert **G2 remains cached**; a failed refresh future is evicted so
    the next call retries the fetch (this is a _new fetch on a new call_, not a retry within one
    call); a request that exceeds the token timeout fails rather than hanging; no log, exception
    message or `toString()` contains the token or secret.
    **Acceptance criteria:** `scripts/test.sh test -Dtest=ServiceAccountTokenProviderTest` exits 0
    with all of the above, including the G1/G2 interleaving assertion.
    **QA happy:** the suite exits 0. Evidence: `docs/evidence/0005-token-test.log`.
    **QA failure:** replace `invalidateIfCurrent` with an unconditional clear in a committed faulty
    test fixture; the G1/G2 interleaving test must fail showing G2 evicted; point the test back at
    the real implementation and confirm it passes. No production source is edited, so
    `git status --porcelain` stays clean. Evidence: `docs/evidence/0005-token-invalidation-race.log`.
    **Commit:** `feat: add generation-guarded cached service-account token provider`

- [ ] 3. `SyncClient.java` + test: one-attempt POST with bulkhead - expect exactly one HTTP attempt for every failure mode

  **References:** `N4-LLD-2.pdf` sections 3.7 and 4.4; `LoginSyncConstants.SYNC_USER_PATH` and
  `DEFAULT_MAX_CONCURRENT_SYNCS`; decisions T1, T2, T6, T7, T10, T13.
  **Details:** POST to `{serviceEndpoint}{SYNC_USER_PATH}` with `Authorization: Bearer <jwt>` and
  `Content-Type: application/json`, body the serialised `SyncPayload`.
  - **Ordering (T13).** `send(payload)` performs, in this exact order: acquire the token via
    `tokenProvider.acquire()` **before** touching the semaphore (T10); then
    `semaphore.tryAcquire()` with **zero timeout**; then the POST; releasing the semaphore in a
    `finally`. The breaker permit is acquired and settled by plan 0006 around this whole call.
  - **200 and 201 are success.** Everything else - 4xx, 5xx, timeout, IO - is a single-attempt
    failure. **No retry, no backoff, ever.**
  - **401/403 (T2):** map to `UNAUTHORIZED`, call `tokenProvider.invalidateIfCurrent(handle)` with
    the handle **this call used**, and return. **Do not re-POST in this login.**
  - **Bulkhead (T6):** `tryAcquire` failure returns `SKIPPED_SATURATED` without an HTTP call.
  - The `HttpClient` is built **once**, with the per-request timeout from `http-timeout-ms` and a
    separately bounded connect timeout, `SSLContext` from Keycloak's truststore (T7);
    `close()` shuts down its executor.
  - `send` returns a `SyncOutcome`; it does not own the breaker.
  - No log statement at any level may contain the token, payload body, email or group path. Add a
    comment forbidding JDK `java.net.http` header-level diagnostic logging
    (`jdk.httpclient.HttpClient.log`) in production, since it would print the `Authorization`
    header.
    **Tests** (local stub): 200 succeeds; 201 succeeds; **400 makes exactly one attempt** yielding
    `REJECTED`; **401 makes exactly one attempt**, yields `UNAUTHORIZED`, and calls
    `invalidateIfCurrent` exactly once with the handle used; 403 likewise; **500 makes exactly one
    attempt** yielding `SERVER_ERROR`; a timeout makes exactly one attempt yielding `TIMEOUT`;
    semaphore exhaustion yields `SKIPPED_SATURATED` with **zero** HTTP calls; the request carries a
    `Bearer`-prefixed `Authorization` header and `Content-Type: application/json`; the captured
    body equals the exact `SyncPayload` JSON; and a test asserting the token fetch occurs **before**
    the semaphore is acquired (assert the semaphore's available permits are untouched while a
    deliberately slow token endpoint is responding).
    **Acceptance criteria:** `scripts/test.sh test -Dtest=SyncClientTest` exits 0 with the stub's
    request counter asserted **exactly 1** in each of the 400/401/403/500/timeout cases and
    **exactly 0** in the saturated case.
    **QA happy:** the suite exits 0. Evidence: `docs/evidence/0005-syncclient-test.log`.
    **QA failure:** in a committed faulty test fixture, wrap the POST in a one-retry loop; the 500
    test must fail reporting a request count of 2; point back at the real implementation and confirm
    the count is 1. This is the assertion that proves the LLD's no-retry rule is enforced. Evidence: `docs/evidence/0005-syncclient-noretry.log`.
    **Commit:** `feat: add one-attempt sync client with bulkhead`

## Final verification wave

- [ ] F1. No-retry and ordering audit (executable). Run `scripts/test.sh test -Dtest=SyncClientTest,ServiceAccountTokenProviderTest` expecting exit 0. Assert the per-case request counters (1 for 400/401/403/500/timeout, 0 for saturated) appear as explicit assertions: `grep -cE 'assert.*[Rr]equestCount.*1' SyncClientTest.java` >= 5. Run `grep -rniE '\bretry\b|backoff|exponential' src/main/java` and assert every hit is a comment describing retry as removed. Run `grep -c 'invalidateIfCurrent' SyncClient.java` >= 1 and `grep -c 'invalidate()' src/main/java -r` == 0, proving the unguarded form does not exist. Assert the ordering test name is present: `grep -c 'tokenFetchedBeforeSemaphore\|beforeBulkhead' SyncClientTest.java` >= 1. Evidence: `docs/evidence/0005-F1-noretry.md`.

- [ ] F2. Secret-safety, bounds and dependency audit (executable). Run a test populating the stub with sentinels `TOKEN_SENTINEL` and `SECRET_SENTINEL`, force each failure mode, then assert `grep -rc 'TOKEN_SENTINEL\|SECRET_SENTINEL' target/surefire-reports/` returns 0 - proving a failing test cannot print a token. Run `grep -c 'DEFAULT_TOKEN_TIMEOUT_MS\|DEFAULT_CONNECT_TIMEOUT_MS' ServiceAccountTokenProvider.java` >= 2, proving the token call is bounded. Run `scripts/test.sh -q dependency:tree` and assert no `okhttp`, `httpclient`, `resteasy-client` or `resilience4j` appears and no `compile`-scope third-party dependency exists. With `BASE_SHA` per P3, `git diff --name-only $BASE_SHA..HEAD` equals this plan's seven owned files. Evidence: `docs/evidence/0005-F2-secrets.md`.

## Commit strategy

Three commits, one per todo, all `feat:`. `pom.xml` untouched.

## Success criteria

1. Every failure mode makes exactly one HTTP attempt, proven by counter assertions.
2. A stale 401 cannot evict a newer token - proven by the G1/G2 interleaving test.
3. 32 concurrent callers on an expired token produce exactly one token request, and a failed
   refresh is evicted rather than cached.
4. The token fetch is bounded by connect and request timeouts and happens outside the bulkhead.
5. `TOKEN_UNAVAILABLE` never records a receiver breaker failure.
6. Every `SyncOutcome` has an explicit `blocksLogin()` and `breakerOutcome()` mapping.
7. No token, secret, body, email or group path is loggable or reachable in test reports.
