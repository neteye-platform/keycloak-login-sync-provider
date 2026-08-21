# 60 - authenticator SPI - Work Plan

```yaml
slug: 60-authenticator-spi
revision: 2
wave: 4
prerequisites: [30-config-and-payload, 40-circuit-breaker, 50-token-and-sync-client]
parallel_with: []
owns_files:
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncAuthenticator.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncAuthenticatorFactory.java
  - src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory
  - src/main/resources/theme-resources/messages/messages_en.properties
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncAuthenticatorTest.java
```

## TL;DR (For humans)

**What you'll get.** The Keycloak extension point: a custom `Authenticator` plus factory,
registered via `META-INF/services`, running as a REQUIRED execution in the browser flow after the
forms subflow, assembling the payload from the authenticated user and calling the sync client
inside a settled circuit-breaker permit.

**Why revision 2 rewrote the skip path - the most important change in the portfolio.** Revision 1
used `context.attempted()` for every deliberate skip. That is **wrong and would have broken every
login** whenever the plugin was unconfigured or the flow path was not `authenticate`. Verified
from Keycloak 26.7.0 source: `AuthenticationProcessor.isSuccessful()` returns true **only** for
`ExecutionStatus.SUCCESS`; `DefaultAuthenticationFlow` line 295 gates REQUIRED executions on it and
breaks the loop; `authenticateOnly()` line 1132 then throws `AuthenticationFlowException`. **Every
deliberate skip now calls `context.success()`.**

**Second correction.** `requiresUser() == true` makes Keycloak throw _before_ `authenticate()` is
invoked when no user is set. Revision 1's null-user guard was therefore dead code sold as a safety
net for a misplaced execution. The guard is retained only as defence-in-depth and is documented
honestly: a misplaced execution is **rejected by Keycloak**, not silently skipped.

**What it will NOT do.** No REGISTER, no UPDATE_PROFILE, no `RequiredActionProvider`. No user
token. No retry. No per-realm admin-console configuration.

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Medium-high - this class can break every login in a realm. Hazards are enumerated as
A1-A11 with the failure each prevents.

---

## Scope

### In scope

- `LoginSyncAuthenticatorFactory`: id `login-sync`, config wiring, singleton lifecycle.
- `LoginSyncAuthenticator`: per-request logic, permit settlement, outcome mapping.
- `META-INF/services` registration and the message bundle.
- Mockito unit tests covering every branch.

### Out of scope (Must-NOT-Have)

- MUST NOT call `context.attempted()` anywhere - it fails a REQUIRED execution.
- MUST NOT handle `registration`, REGISTER, UPDATE_PROFILE, or implement a
  `RequiredActionProvider`.
- MUST NOT obtain, construct, sign, decode or forward the user's token; MUST NOT call
  `session.tokens()` or `TokenManager.createClientAccessToken`.
- MUST NOT re-invoke `SyncClient.send` after a failure within one login.
- MUST NOT return `null` from `getConfigProperties()`.
- MUST NOT throw from `init(Config.Scope)` when required configuration is absent.
- MUST NOT construct transport singletons when the configuration is absent.
- MUST NOT perform the HTTP call while holding a lock, nor settle more than one breaker outcome
  per login.
- MUST NOT log the token, payload body, email or group paths.
- MUST NOT modify `pom.xml`, `README.md` or any workflow.

---

## Key decisions

| id      | decision                                                                                                              | failure it prevents                                                                                                                |
| ------- | --------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| **A0**  | **Every deliberate skip calls `context.success()`, never `context.attempted()`**                                      | **verified from source: ATTEMPTED fails a REQUIRED execution and throws, breaking every login in the realm**                       |
| A1      | `requiresUser()` returns `true`                                                                                       | the execution must not run before a user exists                                                                                    |
| A2      | `configuredFor(...)` returns `true` unconditionally                                                                   | returning `false` with `isUserSetupAllowed()` false yields a CREDENTIAL_SETUP_REQUIRED failure that kills every login              |
| A3      | `action(...)` throws `IllegalStateException`                                                                          | this authenticator never issues a challenge; reaching `action` is a logic error                                                    |
| A4      | `getConfigProperties()` returns `Collections.emptyList()`, `isConfigurable()` false                                   | unambiguously safe; per-realm config is out of scope for v1                                                                        |
| A5      | Missing configuration logs one ERROR and calls `context.success()`                                                    | `init()` runs even when bound to no flow; throwing would brick unrelated installs, and `attempted()` would break logins            |
| A6      | `getFlowPath()` mapped **exhaustively** with a `success()` skip default                                               | `reset-credentials`, `first-broker-login` and any future path must skip without emitting a wrong `event_type`                      |
| A7      | Breaker, token provider and sync client are per-JVM singletons owned by the factory                                   | per-request instances would give every login its own breaker                                                                       |
| A8      | Blocking HTTP in `authenticate()` is safe                                                                             | Keycloak's JAX-RS application is `@Blocking`, so this runs on a worker thread                                                      |
| **A9**  | **The permit is settled exactly once in a `finally`, including on `SyncFailedException` and any `RuntimeException`**  | **an unsettled trial permit strands the breaker in HALF_OPEN forever, permanently disabling sync**                                 |
| **A10** | **The settlement value comes from `SyncOutcome.breakerOutcome()`; the flow outcome from `SyncOutcome.blocksLogin()`** | **review: revision 1 left saturation's breaker attribution undefined, so local load could open the breaker on a healthy receiver** |
| **A11** | **Transport singletons are not constructed when the config is absent**                                                | **constructing an `HttpClient` against a null endpoint at `postInit` would fail server startup**                                   |

---

## Verification strategy

Tests-after, with Mockito mocking `AuthenticationFlowContext`, `UserModel`, `KeycloakSession`,
`AuthenticationSessionModel` and `ClientModel`, plus a real `CircuitBreaker` with an injected time
source and a stubbed `SyncClient`.

Every branch gets a named test. Every skip branch additionally asserts
`verify(context).success()`, `verify(context, never()).attempted()` and
`verify(syncClient, never()).send(any())` - the `never()).attempted()` assertion is the direct
regression guard for the defect that revision 1 shipped.

Jar-packaging assertions run against the built artifact, because a resource missing from the jar is
invisible to Keycloak however correct the source tree looks.

Per invariant P1 every check names a command; per P3 record `BASE_SHA`.

---

## Execution strategy

| wave | todos  | depends on |
| ---- | ------ | ---------- |
| 1    | 1      | -          |
| 2    | 2      | 1          |
| 3    | 3      | 1, 2       |
| F    | F1, F2 | 1, 2, 3    |

---

## Todos

- [ ] 1. `LoginSyncAuthenticatorFactory.java` + `META-INF/services` + `messages_en.properties`: register the provider - expect `login-sync` present in the built jar

  **References:** `org.keycloak.authentication.AuthenticatorFactory`; `LoginSyncConstants.PROVIDER_ID`
  and `LoginSyncConfig` from plan 30; `CircuitBreaker` from plan 40;
  `ServiceAccountTokenProvider`/`SyncClient` from plan 50; decisions A4, A5, A7, A11.
  **Details:**
  - `getId()` returns `LoginSyncConstants.PROVIDER_ID`.
  - `init(Config.Scope)` builds `LoginSyncConfig.from(config)`. If not `configured()`, record that
    and log **one** ERROR naming the missing keys - do **not** throw (A5).
  - `postInit(...)` constructs the singleton `CircuitBreaker`, `ServiceAccountTokenProvider` and
    `SyncClient` **only when `configured()` is true** (A11); otherwise leave them null and have
    `create()` return an authenticator that takes the unconfigured path.
  - `create(session)` returns a lightweight authenticator holding those references; it must not
    construct them per request.
  - `getRequirementChoices()` returns `{ REQUIRED, DISABLED }` only.
  - `isConfigurable()` false; `getConfigProperties()` returns `Collections.emptyList()` (A4);
    `isUserSetupAllowed()` false.
  - `getDisplayType()`, `getReferenceCategory()`, `getHelpText()` describe a LOGIN-only sync and
    must not promise REGISTER or UPDATE_PROFILE.
  - `close()` closes the `SyncClient` if non-null.
  - `src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
    contains exactly one line: the factory FQCN.
  - `src/main/resources/theme-resources/messages/messages_en.properties` defines `loginSyncFailed`
    with a user-facing sentence; without it Keycloak renders the raw key to the end user.
    **Acceptance criteria:** `scripts/test.sh clean package` exits 0.
    `unzip -p target/keycloak-login-sync-provider-0.1.0.jar META-INF/services/org.keycloak.authentication.AuthenticatorFactory`
    prints exactly the factory FQCN and nothing else.
    `unzip -l target/keycloak-login-sync-provider-0.1.0.jar | grep -c 'theme-resources/messages/messages_en.properties'`
    returns 1. A unit test asserts `getConfigProperties()` is non-null and empty and that
    `getRequirementChoices()` contains exactly REQUIRED and DISABLED. A unit test asserts that with
    an unconfigured `Config.Scope`, `init()` does not throw and no `SyncClient` is constructed.
    **QA happy:** both `unzip` commands print the expected contents. Evidence: `.omo/evidence/60-jar-contents.log`.
    **QA failure:** rename the services file, rebuild, and confirm the `unzip -p` assertion fails
    because the entry is absent; restore it, rebuild, confirm it passes and `git status --porcelain`
    is clean. Evidence: `.omo/evidence/60-jar-missing-services.log`.
    **Commit:** `feat: add login sync authenticator factory and SPI registration`

- [ ] 2. `LoginSyncAuthenticator.java`: per-request logic with guaranteed permit settlement - expect every skip to call `success()` and never `attempted()`

  **References:** `org.keycloak.authentication.Authenticator`; `AuthenticationFlowContext`;
  `UserModel.getGroupsStream()` with `org.keycloak.models.utils.KeycloakModelUtils.buildGroupPath`;
  `SyncPayload` from plan 30; `SyncOutcome` from plan 50; decisions A0, A1, A2, A3, A6, A8, A9,
  A10.
  **The verified constraint that shapes this class:** in Keycloak 26.7.0,
  `AuthenticationProcessor.isSuccessful()` (lines 780-784) returns true only for
  `ExecutionStatus.SUCCESS`; `DefaultAuthenticationFlow` line 295 requires it for REQUIRED
  executions and breaks the loop otherwise; `authenticateOnly()` line 1132 then throws. Therefore
  `context.attempted()` **fails the login** and MUST NOT be used.
  **Details:**
  - `requiresUser()` true (A1); `configuredFor(...)` true (A2); `setRequiredActions(...)` no-op;
    `action(...)` throws `IllegalStateException` (A3); `close()` no-op.
  - `authenticate(context)` in this exact order:
    1. If not `configured()` - log ERROR once and **`context.success()`** (A5). Return.
    2. Map `context.getFlowPath()` **exhaustively** (A6): only `authenticate` proceeds. Every other
       value - `registration`, `reset-credentials`, `first-broker-login`, and any unknown future
       value - falls to a default branch that logs DEBUG and calls **`context.success()`**. Return.
    3. Defence-in-depth: if `context.getUser()` is null or the authentication session's client is
       null, log DEBUG and **`context.success()`**. Return. **Document honestly** that this is not
       the guard against a misplaced execution: because `requiresUser()` is `true`, Keycloak throws
       before `authenticate()` is invoked when no user is set, so a misplacement before the forms
       subflow is **rejected by Keycloak**, not silently skipped.
    4. Collect `username`, `email` and full group paths via `user.getGroupsStream()` +
       `KeycloakModelUtils.buildGroupPath`, **inside the session and before the HTTP call**, plus
       `client_id` from the authentication session's client.
    5. Build the `SyncPayload` (`event_type` fixed to `LOGIN` by construction).
    6. `Permit permit = breaker.acquirePermit();` If `!permit.allowsSync()`, settle it
       `ABANDONED`, log DEBUG, and `context.success()` - the login proceeds unsynced. Return.
    7. Otherwise, in a `try { ... } catch (SyncFailedException e) { outcome = e.outcome(); }
catch (RuntimeException e) { outcome = TRANSPORT_ERROR; } finally { permit.complete(outcome.breakerOutcome()); }`
       structure, call `SyncClient.send(payload)` **outside any lock** and capture the
       `SyncOutcome`. The `finally` guarantees **exactly one** settlement on every path (A9) -
       without it a thrown exception strands the sole HALF_OPEN probe permit forever.
    8. Outcome mapping is **data-driven** (A10): if `outcome.blocksLogin()` is false call
       `context.success()`; otherwise call
       `context.failure(AuthenticationFlowError.INTERNAL_ERROR,
context.form().setError("loginSyncFailed").createErrorPage(Response.Status.INTERNAL_SERVER_ERROR),
"login_sync_failed", "loginSyncFailed")`. Do not re-derive the mapping with a local
       `switch`; the enum owns it, so a new outcome cannot be added without a decision.
  - Logging: never the token, payload body, email or group path. The only WARN in the feature is
    the breaker's transition into OPEN, emitted by the breaker.
  - Class Javadoc records A8 and the A0 constraint with its source references.
    **Acceptance criteria:** compiles; `scripts/test.sh clean verify` exits 0.
    `grep -c 'attempted()' src/main/java -r` returns **0**.
    `grep -rcE 'session\.tokens\(\)|createClientAccessToken' src/main/java` returns 0.
    `grep -c 'finally' LoginSyncAuthenticator.java` >= 1.
    **QA happy:** `scripts/test.sh clean verify` exits 0 and the `attempted()` grep returns 0.
    Evidence: `.omo/evidence/60-authenticator-verify.log`.
    **QA failure:** `grep -rc 'session.tokens()' src/main/java` returns 0, proving no user-token path
    exists. Evidence: `.omo/evidence/60-authenticator-notoken.log`.
    **Commit:** `feat: add login sync authenticator`

- [ ] 3. `LoginSyncAuthenticatorTest.java`: cover every branch - expect each skip to assert `success()`, never `attempted()`, and no sync call

  **References:** todos 1 and 2; Mockito 5.12.0 at test scope.
  **Details:** Mock the SPI context types; stub `SyncClient`; use a real `CircuitBreaker` with an
  injected time source. Required cases, each asserting
  `verify(context, never()).attempted()` in addition to its own expectation:
  - **Happy path:** `verify(context).success()` and the captured payload carries the expected
    username, email, sorted group paths, `client_id` and `event_type == "LOGIN"`.
  - **Unconfigured:** `verify(context).success()`, no sync call.
  - **Null user:** `verify(context).success()`, no sync call.
  - **Null client:** `verify(context).success()`, no sync call.
  - **Flow path `registration`:** `success()`, no sync call.
  - **Flow path `reset-credentials`:** `success()`, no sync call.
  - **Flow path `first-broker-login`:** `success()`, no sync call.
  - **Unknown/novel flow path:** `success()`, no sync call - proving the default is a real default.
  - **Sync failure, breaker closed:** `verify(context).failure(eq(INTERNAL_ERROR), any(), eq("login_sync_failed"), eq("loginSyncFailed"))`.
  - **Sync failure, breaker OPEN:** `success()` and no sync call.
  - **`SKIPPED_SATURATED`:** `success()`, and the breaker records **no sample** - assert the
    window and consecutive-failure counter are unchanged.
  - **`TOKEN_UNAVAILABLE`:** the login is blocked, and the breaker records **no sample** (A10/T11).
  - **`SyncClient.send` throws `SyncFailedException`:** the permit is still settled exactly once -
    assert via a spying breaker that `complete` was called once.
  - **`SyncClient.send` throws an unexpected `RuntimeException`:** the permit is still settled
    exactly once and the login is blocked.
  - **HALF_OPEN trial that throws:** assert a subsequent caller can still obtain a trial permit,
    proving the slot was released and the breaker did not strand.
  - **`action(...)`:** throws `IllegalStateException`.
    **Acceptance criteria:** `scripts/test.sh test -Dtest=LoginSyncAuthenticatorTest` exits 0 with
    all sixteen cases asserted.
    **QA happy:** the suite exits 0. Evidence: `.omo/evidence/60-authenticator-test.log`.
    **QA failure:** in a committed faulty test fixture, replace the flow-path default branch with a
    fall-through to the sync; the unknown-flow-path test must fail; point back at the real
    implementation and confirm it passes. Separately, change one skip branch's fixture to call
    `attempted()` and confirm the `never()).attempted()` assertion fails - proving the regression
    guard for the revision-1 defect is live. Evidence: `.omo/evidence/60-authenticator-flowpath-fail.log`.
    **Commit:** `test: cover every authenticator branch`

## Final verification wave

- [ ] F1. SPI correctness audit (executable). Run `scripts/test.sh clean package` expecting exit 0. Run `unzip -p target/keycloak-login-sync-provider-0.1.0.jar META-INF/services/org.keycloak.authentication.AuthenticatorFactory` expecting exactly the factory FQCN. Run `unzip -l target/... | grep -c messages_en.properties` expecting 1. Run `scripts/test.sh test -Dtest=LoginSyncAuthenticatorTest` expecting exit 0. Run `grep -rc 'attempted()' src/main/java` expecting **0** - the single most important regression check in this plan. Run `grep -c 'Collections.emptyList()' LoginSyncAuthenticatorFactory.java` expecting >= 1. Evidence: `.omo/evidence/60-F1-spi.md`.

- [ ] F2. Scope, settlement and safety audit (executable). Run `grep -rcE 'session\.tokens\(\)|createClientAccessToken' src/main/java` expecting 0. Run `grep -rciE 'REGISTER|UPDATE_PROFILE|RequiredActionProvider' src/main/java` expecting 0. Run `grep -c 'synchronized' LoginSyncAuthenticator.java` expecting 0. Assert by named test that `permit.complete(...)` is invoked exactly once on the success, failure, thrown-`SyncFailedException` and thrown-`RuntimeException` paths (`grep -c 'settledExactlyOnce' LoginSyncAuthenticatorTest.java` >= 4). Run a sentinel test populating email `email@sentinel.test` and group `/GROUP_SENTINEL`, force a failure, then assert `grep -rc 'sentinel' target/surefire-reports/ target/*.log 2>/dev/null` returns 0. With `BASE_SHA` per P3, `git diff --name-only $BASE_SHA..HEAD` equals this plan's five owned files. Evidence: `.omo/evidence/60-F2-scope.md`.

## Commit strategy

Three commits: two `feat:` and one `test:`. `pom.xml` untouched, so no release fires.

## Success criteria

1. `context.attempted()` appears nowhere in main; every deliberate skip calls `context.success()`.
2. The built jar registers `login-sync` and ships the message bundle.
3. Only the `authenticate` flow path syncs; every other path skips with no sync call.
4. The breaker permit is settled exactly once on every path, including thrown exceptions, and a
   thrown HALF_OPEN trial does not strand the breaker.
5. Saturation and token-unavailability record no receiver failure sample.
6. A sync failure with the breaker closed blocks the login with a rendered `loginSyncFailed` page.
7. The plugin never touches the authenticating user's token.
