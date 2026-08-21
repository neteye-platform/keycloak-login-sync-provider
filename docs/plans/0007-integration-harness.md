# 0007 - integration harness - Work Plan

```yaml
slug: 0007-integration-harness
revision: 3
wave: 5
prerequisites: [0006-authenticator-spi]
parallel_with: []
owns_files:
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/support/MockSyncService.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/support/KeycloakAdminApi.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/support/BrowserLogin.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/support/CapturedRequest.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncIT.java
```

## TL;DR (For humans)

**What you'll get.** End-to-end proof against a real Keycloak 26.7.0 container: the built jar is
deployed into `/opt/keycloak/providers/`, a real browser-flow login is driven through it, and the
service-account token the plugin sends is one **Keycloak itself issued**, verified against the
container's JWKS.

**Why revision 2 restructured the scenarios.** Both reviewers found revision 1's scenarios
mutually contradictory. With `cb-failure-threshold=1`, scenario 2's HTTP 500 necessarily opens the
singleton breaker, so scenario 3 could not also receive a 401 and record "exactly one request" -
it would be skipped while OPEN. Worse, "next login succeeds" cannot prove token invalidation,
because an OPEN breaker produces exactly the same observable result **with no HTTP request at
all**. Scenarios are now grouped into isolated fixtures with per-group breaker settings, and the
401 proof asserts a second POST with a _different_ token rather than a bare login success.

**Also added:** a production-defaults group (threshold 5, window 20). Revision 1 tested only the
degenerate window-size-1 configuration, in which plan 0004's full-window guard is trivially
satisfied - so a real bug at the shipped defaults could pass.

**What it will NOT do.** No mock token endpoint. No direct-grant shortcut. No receiver-side
validation logic. No production code changes.

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Medium-high, dominated by test isolation.

---

## Scope

### In scope

- `MockSyncService`: an in-JVM receiver with `/api/sync-user` and `/__control`, recording requests.
- `CapturedRequest`: a recorded request with a **redacting** `toString()`.
- `KeycloakAdminApi`: hand-rolled admin REST fixture.
- `BrowserLogin`: a real browser-flow login driver.
- `LoginSyncIT`: isolated scenario groups plus cross-cutting assertions.

### Out of scope (Must-NOT-Have)

- MUST NOT let `MockSyncService` implement a token endpoint or issue any token. End-to-end tokens
  come from the **real Keycloak container** via a test service account.
- MUST NOT use a direct-grant / `grant_type=password` request to drive a scenario.
- MUST NOT add `keycloak-admin-client` or `dasniko/testcontainers-keycloak`.
- MUST NOT implement receiver-side JWT validation beyond what an assertion needs.
- MUST NOT assert on a retry. Assert exactly one request per admitted failing sync.
- MUST NOT test REGISTER, UPDATE_PROFILE or the `registration` flow.
- MUST NOT modify anything under `src/main/`, `pom.xml` or `.github/`.
- MUST NOT introduce a second mock-syncservice implementation.
- MUST NOT leave scenario ordering or breaker state implicit.
- MUST NOT print an `Authorization` header value into an assertion message or test report.

---

## Key decisions

| id     | decision                                                                                                                                                                    | rationale                                                                                                                          |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| I1     | Plain `GenericContainer`, `Wait.forHttp("/realms/master")` 200, 3-minute startup                                                                                            | the pattern already proven in `../keycloak-oidc-groups-mapper/.../GroupOIDCMapperIT.java`                                          |
| I2     | Jar path from the `provider.jar` system property                                                                                                                            | already wired into Failsafe by the bootstrap plan                                                                                  |
| I3     | The fixture creates the confidential client and service account at runtime                                                                                                  | the LLD forbids provisioning scripts in the repo but permits test fixtures                                                         |
| I4     | One `MockSyncService` with a `main(String[])`                                                                                                                               | plan 0008's compose stack runs this same class, so the two cannot drift                                                            |
| **I5** | **Scenarios are grouped into isolated nested classes, each with its own container or its own realm plus a fresh breaker, and each group declares its own breaker settings** | **review: a single shared singleton breaker made revision 1's scenarios contradict each other**                                    |
| **I6** | **A production-defaults group (threshold 5, window 20, cooldown 30s) runs alongside the aggressive group**                                                                  | **review: window size 1 makes plan 0004's full-window guard trivially true, so the degenerate config alone could mask a real bug** |
| **I7** | **The 401 proof asserts two POSTs carrying two _different_ tokens, not merely a later successful login**                                                                    | **review: an OPEN breaker yields a successful login with zero requests, so login success alone proves nothing about invalidation** |
| **I8** | **`CapturedRequest.toString()` redacts `Authorization`, and no assertion message embeds a token**                                                                           | **review: a failing assertion would otherwise print a live bearer token into the Surefire report**                                 |

---

## Verification strategy

Failsafe (`*IT`, `mvn verify`), Testcontainers, real Keycloak.

Three classes of proof:

1. **Functional** - the right payload reaches the receiver with a real Keycloak-issued bearer token
   that verifies against the container's JWKS.
2. **Behavioural** - exactly one attempt per admitted failing sync, zero requests while OPEN, login
   blocked when the breaker is closed, and full recovery.
3. **Operational** - no secret in the logs or test reports, and Keycloak stays responsive under a
   black-holed receiver.

The definitive anti-flake check is running the suite twice consecutively.

Per invariant P1 every check names a command; per P3 record `BASE_SHA`.

---

## Execution strategy

| wave | todos  | depends on |
| ---- | ------ | ---------- |
| 1    | 1, 2   | -          |
| 2    | 3      | 1, 2       |
| F    | F1, F2 | 1, 2, 3    |

Todos 1 and 2 are independent test-support code; todo 3 consumes both.

---

## Todos

- [ ] 1. `MockSyncService.java` + `CapturedRequest.java`: the receiver-only mock - expect runtime mode switching, an assertable log, and redacted capture

  **References:** `../keycloak-oidc-groups-mapper/src/test/java/.../support/MockOidcProvider.java`
  for the in-JVM `com.sun.net.httpserver.HttpServer` pattern; `docs/SYNC-CONTRACT.md`;
  decisions I4, I8.
  **Details:** An in-JVM HTTP server exposing exactly two paths:
  - `POST /api/sync-user` - the receiver, responding per the current mode.
  - `POST /__control` with body `{"mode":"..."}` - switches mode at runtime. Modes: `ok` (200),
    `created` (201), `http400`, `http401`, `http403`, `http500`, `timeout` (sleep well past the
    client timeout), `blackhole` (accept and never respond).
    It records every request into a thread-safe list of `CapturedRequest` (method, path, headers,
    body), with `reset()` for per-scenario isolation and accessors `port()`, `setMode()`,
    `requests()`.
    **`CapturedRequest.toString()` MUST redact the `Authorization` header value** (I8), rendering
    only its scheme and a fixed mask, so a failing assertion cannot print a live token into the
    Surefire report. Provide a separate explicit accessor for tests that genuinely need the raw
    token (scenario 1's JWKS check), and require those tests never to embed it in an assertion
    message.
    **It MUST NOT implement a token endpoint** or return anything resembling an `access_token`. Add
    a comment stating tokens come from the real Keycloak container per the LLD.
    Expose `main(String[])` so plan 0008's compose stack runs this identical class (I4).
    **Acceptance criteria:** a self-test asserts switching to `http500` changes the response code
    from 200 to 500 without a restart; that a recorded request exposes headers and body; that
    `reset()` empties the log; and that `new CapturedRequest(...withAuthHeader("Bearer abc.def.ghi")).toString()`
    contains neither `abc.def.ghi` nor the substring `def`. `grep -cE 'access_token|"/token"' MockSyncService.java`
    returns 0.
    **QA happy:** the self-test passes. Evidence: `docs/evidence/0007-mock-selftest.log`.
    **QA failure:** add a scratch token endpoint to the mock; the `access_token` grep assertion must
    fail; remove it, re-run, confirm 0 and a clean `git status --porcelain src/test`. Evidence: `docs/evidence/0007-mock-notoken.log`.
    **Commit:** `test: add receiver-only mock syncservice with redacted request capture`

- [ ] 2. `KeycloakAdminApi.java` + `BrowserLogin.java`: fixture and login driver - expect a real browser-flow login with distinguishable success and failure

  **References:** `../keycloak-oidc-groups-mapper/src/test/java/.../support/{KeycloakAdminApi,BrokeredLogin}.java`;
  decision I3.
  **Details:**
  - `KeycloakAdminApi`: adapt the reference helper (`java.net.http` + Jackson, admin token from
    `admin-cli` against `/realms/master/protocol/openid-connect/token`). Operations required:
    `createRealm`, `deleteRealm`, `createUser` (with email and password), `createGroup` and
    `joinGroupByPath` (including a **nested** path so full-path assertions are meaningful),
    `createPublicClient`, **`createConfidentialClientWithServiceAccount`** returning its secret
    (I3), `assignServiceAccountRole`, `rotateClientSecret` (needed to make the 401 scenario
    realistic), `copyFlow`, `addExecution`, `updateExecutionRequirement` (set REQUIRED),
    `setBrowserFlow`, and `listAuthenticatorProviders` for the provider-loaded gate.
  - `BrowserLogin`: drives a **real browser-flow** login - GET the authorization endpoint, scrape
    the login form action, POST credentials, follow redirects with `Redirect.NEVER`, a manual hop
    loop capped at 12, and a cookie jar tolerating `Secure; SameSite=None` on Keycloak's
    `KC_RESTART` cookie. Return a result distinguishing **success** (authorization `code` present
    on the callback) from **failure** (HTTP 500 plus the rendered error body).
    **A direct-grant/password token request MUST NOT be used** - it bypasses browser-flow
    authenticators and would make every scenario pass while asserting nothing.
    **Acceptance criteria:** both compile; `listAuthenticatorProviders` returns a parsed list;
    `createConfidentialClientWithServiceAccount` returns a usable id and secret; `BrowserLogin`
    returns success-with-code for valid credentials and failure-with-body for a blocked login.
    `grep -c 'grant_type=password' BrowserLogin.java` returns 0.
    **QA happy:** exercised by todo 3; compiles clean under Spotless. Evidence: `docs/evidence/0007-harness-compile.log`.
    **QA failure:** the `grant_type=password` grep returns 0 for `BrowserLogin`, proving the driver
    goes through the browser flow. Evidence: `docs/evidence/0007-harness-nodirectgrant.log`.
    **Commit:** `test: add Keycloak admin fixture and browser-flow login driver`

- [ ] 3. `LoginSyncIT.java`: isolated scenario groups with real Keycloak-issued tokens - expect `verify` green twice consecutively

  **References:** todos 1 and 2; `GroupOIDCMapperIT.java` for the container pattern; decisions
  I1, I2, I5, I6, I7.
  **Container setup:** `GenericContainer` on
  `quay.io/keycloak/keycloak:` + `System.getProperty("keycloak.version")`; jar from
  `System.getProperty("provider.jar")` via `MountableFile.forHostPath` into
  `/opt/keycloak/providers/`, asserting the file exists first with a message telling the operator
  to run `verify`, not `test`; env `KC_BOOTSTRAP_ADMIN_USERNAME`/`PASSWORD`,
  `KC_HOSTNAME_STRICT=false`, `KC_HTTP_ENABLED=true`; command `start-dev` with **no** `kc.sh build`
  (the supported Keycloak development startup mode); wait on `Wait.forHttp("/realms/master")`
  200, 3-minute timeout. Mock exposed via `Testcontainers.exposeHostPorts`, addressed as
  `host.testcontainers.internal`. SPI env vars use the verbatim pinned spelling, e.g.
  `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT`.
  **Fixture:** per group, create a realm; create a confidential client with a service account and
  point `sa-client-id`/`sa-client-secret`/`sa-token-endpoint` at it and at the **container's own**
  token endpoint; create a public login client; create a user with an email and a **nested** group
  membership; copy the browser flow, add `login-sync` as **REQUIRED positioned after the forms
  subflow**, and bind the copy as the realm's browser flow.
  **Isolation (I5).** Organise as `@Nested` groups. **Each group gets its own container instance**
  (or, where a container restart is too slow, its own realm plus a container restart between
  groups), so no group inherits another's breaker state. Within a group, order is explicit via
  `@TestMethodOrder`, and `mock.reset()` runs between scenarios. Every group documents in a comment
  the breaker state it requires on entry.
  **Group A - functional (breaker effectively disabled: threshold high, window 20):**
  1. Mode `ok` - login succeeds; the captured body equals the exact six-field payload with the
     expected username, email, full **nested** group paths and `client_id`; the `Authorization`
     header is `Bearer`-prefixed; and the token **verifies against the container's JWKS** with a
     future `exp` and the expected `iss`. Do not embed the token in any assertion message (I8).
  2. Mode `created` (201) - login succeeds.
  3. Mode `http500` - login blocked: HTTP 500, body contains the rendered `loginSyncFailed`
     message, no `code=` on the redirect, and **exactly one** request recorded.
  4. Mode `http400` - login blocked, **exactly one** request.
  5. Mode `timeout` - login blocked, **exactly one** request.
     **Group B - token invalidation (I7) (breaker effectively disabled):**
  6. Mode `ok`; perform login 1 and capture token T1. Rotate the service-account secret via the
     admin API and set mode `http401`; perform login 2 - assert it is **blocked** and that
     **exactly one** request was made in that login, carrying T1. Restore the secret in the
     plugin's configuration, set mode `ok`, and perform login 3 - assert a **second POST occurred**
     carrying a token **T2 != T1**. This proves invalidation happened and the next login
     re-fetched, which a bare "next login succeeded" cannot show, because an OPEN breaker yields a
     successful login with zero requests.
     **Group C - breaker transitions, aggressive (threshold 1, window 1, cooldown 1s, timeout 250ms):**
  7. `http500` opens the breaker; assert the `LOGIN_SYNC_CIRCUIT_OPEN` marker appears **exactly
     once** in `keycloak.getLogs()` for that transition.
  8. While OPEN, a further login **succeeds** and records **zero** requests at the mock.
  9. Mode `ok`, cooldown elapsed - the trial closes the circuit; a subsequent login syncs again.
  10. Cooldown elapsed with the mock still failing - the trial fails, **exactly one** request is
      recorded for that trial, and the circuit reopens.
      **Group D - production defaults (I6) (threshold 5, window 20, cooldown 30s):**
  11. With mode `http500`, perform four logins - assert the breaker is still CLOSED, each login is
      blocked, and **each recorded exactly one** request. The fifth login opens the breaker. This
      exercises the full-window/consecutive-threshold logic that window-size 1 trivially bypasses.
      **Cross-cutting assertions:**
  - **Provider-loaded gate:** `listAuthenticatorProviders` contains `login-sync`. Run first; if it
    fails, every other result is meaningless.
  - **Log safety:** `keycloak.getLogs()` contains none of the service-account secret, the literal
    `Bearer`, a raw JWT matching `eyJ[A-Za-z0-9_-]{10,}`, the test user's email, or any group
    path. Include a **matcher non-vacuity check**: assert the same matcher _does_ fire against a
    synthetic string containing a fake JWT, proving the regex works.
  - **Availability:** with mode `blackhole` and 20 concurrent logins in flight,
    `GET /realms/master` returns 200 within 2 seconds.
    **Acceptance criteria:** `scripts/test.sh clean verify` exits 0 with every group and every
    cross-cutting assertion passing.
    **QA happy:** `scripts/test.sh clean verify` exits 0. Evidence: `docs/evidence/0007-it-verify.log`.
    **QA failure:** (a) run the full suite twice consecutively; both green, proving isolation is real
    and not order luck. (b) Point the plugin at a fabricated static bearer string via a committed
    faulty fixture; scenario 1's JWKS verification must fail, proving that assertion is not vacuous;
    restore and confirm green, with `git status --porcelain` clean. Evidence: `docs/evidence/0007-it-rerun.log`.
    **Commit:** `test: add end-to-end browser-flow integration scenarios`

## Final verification wave

- [ ] F1. Token-authenticity and flow-authenticity audit (executable). Run `scripts/test.sh clean verify` expecting exit 0. Assert scenario 1's JWKS verification exists and is non-vacuous by running the faulty-fixture variant and expecting failure. Run `grep -cE 'access_token|"/token"' MockSyncService.java` expecting 0. Run `grep -rc 'grant_type=password' src/test/java` expecting 0. Run `grep -c 'T2' LoginSyncIT.java` >= 1 and confirm the group-B assertion compares two distinct tokens. Evidence: `docs/evidence/0007-F1-token-authenticity.md`.

- [ ] F2. Behaviour, isolation and log-safety audit (executable). Assert every admitted failing sync has an explicit exactly-one-request assertion: `grep -cE 'assert.*requests\(\).size\(\).*1|assertEquals\(1, .*requests' LoginSyncIT.java` >= 7 (scenarios 3, 4, 5, 6, 10 and the four group-D logins). Assert the OPEN scenario has an explicit zero-request assertion: `grep -cE 'assertEquals\(0, .*requests' LoginSyncIT.java` >= 1. Run the suite twice consecutively expecting both green. Run the log-safety scan and its matcher non-vacuity check. Run `grep -rc 'Bearer ' target/surefire-reports/ 2>/dev/null` expecting 0, proving no token reached a report. With `BASE_SHA` per P3, `git diff --name-only $BASE_SHA..HEAD` equals this plan's five owned files and includes nothing under `src/main`, `pom.xml` or `.github/`. Evidence: `docs/evidence/0007-F2-behaviour.md`.

## Commit strategy

Three commits, all `test:`. Nothing under `src/main/`, `pom.xml` or `.github/` is touched.

## Success criteria

1. `scripts/test.sh clean verify` exits 0 from a clean tree, twice in a row.
2. The plugin's bearer token is proven Keycloak-issued via JWKS verification.
3. Every admitted failing sync records exactly one request; every OPEN skip records zero.
4. Token invalidation is proven by two POSTs with two different tokens, not by a bare login.
5. Breaker transitions are exercised at both aggressive and production-default settings.
6. Keycloak stays responsive under a black-holed receiver with 20 concurrent logins.
7. No secret, token, email or group path appears in container logs or Surefire reports.
