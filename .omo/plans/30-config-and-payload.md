# 30 - config and payload - Work Plan

```yaml
slug: 30-config-and-payload
revision: 2
wave: 1
prerequisites: [10-contract-reconciliation, 20-scaffold-reconciliation]
parallel_with: []
owns_files:
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncConstants.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncConfig.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/SyncPayload.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/LoginSyncConfigTest.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/SyncPayloadTest.java
```

## TL;DR (For humans)

**What you'll get.** The first production Java: the single configuration seam (`LoginSyncConfig`
over Keycloak's `Config.Scope`), the single home for constants and the provisional endpoint path
(`LoginSyncConstants`), and the byte-exact JSON wire contract (`SyncPayload`) - each with tests
that pin the behaviour.

**Why this approach.** Everything downstream depends on these three types, and three hard
requirements are enforceable only here: the path must change in exactly one place, no secret or
personal data may ever reach a log, and `event_type` must be structurally incapable of being
anything but `LOGIN`.

**What it will NOT do.** No HTTP, no token handling, no circuit breaker, no Keycloak SPI classes.

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Low-medium. The concentrated risk is the JSON wire format: record components cannot be
named `event_type`, so without an explicit `@JsonProperty` on every field the wire format silently
becomes `eventType`/`clientId`.

---

## Scope

### In scope

- `LoginSyncConstants`: provider id, provisional path, nine `Config.Scope` keys, all defaults.
- `LoginSyncConfig`: immutable record from `Config.Scope`, redacting `toString()`, strict
  validation.
- `SyncPayload`: six-field record, structurally immutable `event_type`, redacting `toString()`.
- Unit tests for all three.

### Out of scope (Must-NOT-Have)

- MUST NOT use `System.getenv`.
- MUST NOT add retry counts, backoff, or retryable/non-retryable exception types.
- MUST NOT allow any `event_type` value other than `LOGIN`, nor any REGISTER/UPDATE_PROFILE
  constant.
- MUST NOT implement the SPI, breaker, token provider or HTTP client.
- MUST NOT create `META-INF/services` or theme resources.
- MUST NOT add an external JSON library.
- MUST NOT leave the default record `toString()` on `LoginSyncConfig` or `SyncPayload`.
- MUST NOT hardcode `/api/sync-user` outside the single constant.
- MUST NOT modify `pom.xml`, `README.md`, `.gitignore` or any workflow.

---

## Key decisions

| id | decision | rationale |
|----|----------|-----------|
| C1 | Package root `com.wuerthit.keycloak.authenticators.loginsync` | mirrors the reference repo's groupId-equals-package habit |
| C2 | Config key is `service-endpoint` | user decision R1; the only spelling with measured evidence |
| C3 | Absent **required** config yields `configured() == false` and does not throw | `init()` runs even when the provider is bound to no flow; throwing would brick unrelated installs on the same server |
| C4 | Present-but-**malformed** config throws `IllegalStateException` | a typo'd timeout must fail loudly at startup rather than silently defaulting. Documented consequence: this aborts provider startup server-wide even when unbound - deliberate, because a malformed value is an operator error that must be seen |
| C5 | Bulkhead limit is an internal constant, not a tenth env var | keeps the operator surface at the nine keys the LLD defines |
| C6 | `groups` sorted before serialisation | determinism for the exact-JSON assertion |
| C7 | **All numeric bounds are strictly positive**; error rate is 1..100 | review found `0` was accepted: `cb-window-size=0` breaks the ring buffer and `cb-failure-threshold=0` has no coherent meaning. An error rate of 0 would trip on the first full window regardless of outcomes, so it is rejected too |
| C8 | `SyncPayload` is built by a static factory with a **private canonical constructor** | review found a record's canonical constructor could otherwise accept a non-`LOGIN` event type; immutability must be structural, not documentary |

---

## Verification strategy

Tests-after. All verification is Surefire unit tests plus grep-based structural assertions - no
containers, no network. Per portfolio invariant P1 every check names a command and an expected
result; per P3 record `BASE_SHA` before the first commit.

The three highest-value assertions are negative: exact-JSON equality (catches a missing
`@JsonProperty`), the redaction tests (catch a reintroduced default `toString()`), and the
compile-level proof that a non-`LOGIN` event type cannot be constructed.

---

## Execution strategy

| wave | todos | depends on |
|------|-------|-----------|
| 1 | 1 | - |
| 2 | 2, 3 | 1 |
| F | F1, F2 | 1, 2, 3 |

Todo 1 lands first; todos 2 and 3 both read its constants.

---

## Todos

- [ ] 1. `LoginSyncConstants.java`: the single home for the provisional path and every default - expect `/api/sync-user` to appear exactly once in main

  **References:** `docs/SYNC-CONTRACT.md` (created by plan 10, a hard prerequisite) for the
  provisional-path rationale; `N4-LLD-2.pdf` sections 3.2 and 4.3.1 for defaults;
  `.omo/evidence/spike-02-config-scope.log` for the pinned spelling.
  **Details:** A `final` class with a private constructor. It holds:
  - `PROVIDER_ID = "login-sync"`.
  - `SYNC_USER_PATH = "/api/sync-user"`, commented as a **provisional contract** per LLD Open
    point 3 and marked the single change point.
  - The nine dashed keys exactly: `service-endpoint`, `sa-client-id`, `sa-client-secret`,
    `sa-token-endpoint`, `http-timeout-ms`, `cb-failure-threshold`, `cb-error-rate-threshold`,
    `cb-window-size`, `cb-cooldown-seconds`.
  - Defaults: `DEFAULT_HTTP_TIMEOUT_MS = 5000`, `DEFAULT_CB_FAILURE_THRESHOLD = 5`,
    `DEFAULT_CB_ERROR_RATE_THRESHOLD = 50`, `DEFAULT_CB_WINDOW_SIZE = 20`,
    `DEFAULT_CB_COOLDOWN_SECONDS = 30`.
  - `DEFAULT_MAX_CONCURRENT_SYNCS = 32` (plan 50's bulkhead), commented as deliberately not
    operator-facing (C5).
  - `DEFAULT_TOKEN_TIMEOUT_MS = 5000` and `DEFAULT_CONNECT_TIMEOUT_MS = 2000` for plan 50's
    bounded token fetch - review found the token call had no specified timeout at all.
  - `EVENT_TYPE_LOGIN = "LOGIN"` - the only event type emitted.
  - `CIRCUIT_OPEN_LOG_MARKER = "LOGIN_SYNC_CIRCUIT_OPEN"` for plan 40's WARN.
  Add a class comment naming the env convention verbatim - `service-endpoint` maps to
  `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT` - and noting the double underscore is
  empirically required because the single-underscore form does not resolve.
  **Acceptance criteria:** `scripts/test.sh clean verify` exits 0.
  `grep -rc '"/api/sync-user"' src/main/java | awk -F: '{s+=$2} END {exit !(s==1)}'` exits 0
  (exactly one occurrence). `grep -rc 'System.getenv' src/main/java` totals 0.
  `grep -rciE 'REGISTER|UPDATE_PROFILE' src/main/java` totals 0.
  **QA happy:** `scripts/test.sh clean verify` exits 0 and the single-occurrence check exits 0.
  Evidence: `.omo/evidence/30-constants-verify.log`.
  **QA failure:** add a second `"/api/sync-user"` literal in a scratch class; the
  single-occurrence check must exit non-zero; delete the scratch class, re-run, confirm exit 0 and
  a clean `git status --porcelain src/`. Evidence: `.omo/evidence/30-constants-single-point.log`.
  **Commit:** `feat: add login sync constants and configuration keys`

- [ ] 2. `LoginSyncConfig.java` + test: the immutable Config.Scope seam - expect defaults applied, zero and negative values rejected, and the secret never rendered

  **References:** todo 1's constants; `org.keycloak.Config.Scope` (an interface, hence trivially
  mockable - the reason LLD 4.3 rejected `System.getenv`); decisions C3, C4, C7.
  **Details:** An immutable `record` with a static factory `from(Config.Scope scope)`. Read the
  nine keys via the constants, never a string literal.
  - Missing **required** values (`service-endpoint`, `sa-client-id`, `sa-client-secret`,
    `sa-token-endpoint`) produce `configured() == false`. Do **not** throw (C3).
  - Missing optional values fall back to the `DEFAULT_*` constants.
  - Present-but-malformed values throw `IllegalStateException` (C4): unparseable URL, non-numeric
    number, or any violation of C7 - `http-timeout-ms`, `cb-failure-threshold`, `cb-window-size`
    and `cb-cooldown-seconds` MUST be **strictly greater than zero**, and
    `cb-error-rate-threshold` MUST be in the inclusive range **1..100**. Zero is invalid for all
    of them; review found revision 1 rejected only negatives.
  - **`toString()` MUST be overridden** to redact `saClientSecret` with a fixed mask that does not
    encode its length.
  - Javadoc MUST state the C4 consequence: a malformed value aborts provider startup for the whole
    server even when the provider is bound to no flow, and that this is deliberate.
  **Tests:** each of the five defaults applied when absent; **each** of `http-timeout-ms=0`,
  `cb-failure-threshold=0`, `cb-window-size=0`, `cb-cooldown-seconds=0`,
  `cb-error-rate-threshold=0` and `cb-error-rate-threshold=101` throws `IllegalStateException`;
  each negative equivalent throws; an unparseable URL throws; absent required keys give
  `configured() == false` **without** throwing; `toString()` contains neither the secret nor any
  4-or-more-character substring of it; a fully-populated scope yields every field intact.
  **Acceptance criteria:** `scripts/test.sh test -Dtest=LoginSyncConfigTest` exits 0 with all of
  the above asserted. `grep -c 'System.getenv' src/main/java/**/LoginSyncConfig.java` returns 0.
  **QA happy:** `scripts/test.sh test -Dtest=LoginSyncConfigTest` exits 0. Evidence: `.omo/evidence/30-config-test.log`.
  **QA failure:** delete the `toString()` override; re-run and confirm the redaction test fails
  with the secret visible in the failure message; restore it, re-run, confirm exit 0 and a clean
  `git status --porcelain src/`. Evidence: `.omo/evidence/30-config-redaction-fail.log`.
  **Commit:** `feat: add Config.Scope-backed immutable settings`

- [ ] 3. `SyncPayload.java` + test: the byte-exact wire contract - expect exact JSON, structurally immutable LOGIN, and a redacted `toString()`

  **References:** `N4-LLD-2.pdf` section 4.4; `docs/SYNC-CONTRACT.md` from plan 10; todo 1's
  `EVENT_TYPE_LOGIN`; decisions C6, C8.
  **Details:** A `record` serialised by the `provided` Jackson to exactly six fields in this order:
  `event_type`, `client_id`, `username`, `email`, `groups`, `timestamp`.
  - **Every component needs an explicit `@JsonProperty`.** Without it the wire silently becomes
    `eventType`/`clientId`. This is the highest-value assertion in the plan.
  - **Structural immutability of `event_type` (C8):** the canonical constructor MUST be private,
    and the only public creator is a static factory whose signature **does not accept an event
    type** - it sets `LoginSyncConstants.EVENT_TYPE_LOGIN` itself. Documentation alone is
    insufficient; a record's canonical constructor is otherwise public and would accept anything.
  - `timestamp` is `Instant.truncatedTo(ChronoUnit.SECONDS).toString()`, e.g.
    `2026-08-13T10:15:00Z`. The `Instant`/`Clock` is injected through the factory so tests are
    deterministic; do not call `Instant.now()` inline.
  - `groups` is an unmodifiable, **sorted** list of full group paths (C6).
  - **`toString()` MUST be overridden** to emit only `event_type`, `client_id` and outcome
    metadata. The record default renders the email and every group path, violating the logging
    constraint the first moment a payload is interpolated into a log or exception message.
  - Class Javadoc MUST record the delivery consequence from `docs/SYNC-CONTRACT.md`: because the
    body carries no event/request/correlation/idempotency identifier and the timestamp is
    second-truncated, two genuine logins by the same user to the same client within one second
    serialise **byte-identically**; the receiver cannot deduplicate on payload equality and
    delivery is at-most-once.
  **Tests:** the serialised string equals an exact expected JSON literal for a fixed payload and
  fixed clock; `timestamp` matches `^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$`; groups serialise
  sorted regardless of input order; an empty group list serialises as `[]` not `null`;
  `toString()` contains neither the email nor any group path; and a **compile-level or
  reflection-level** test asserting no public constructor or factory accepts an event-type
  argument, so a non-`LOGIN` payload cannot be constructed.
  **Acceptance criteria:** `scripts/test.sh test -Dtest=SyncPayloadTest` exits 0 with all of the
  above asserted, including exact-JSON equality and the no-public-event-type-parameter assertion.
  **QA happy:** `scripts/test.sh test -Dtest=SyncPayloadTest` exits 0. Evidence: `.omo/evidence/30-payload-test.log`.
  **QA failure:** remove the `@JsonProperty` from the `event_type` component; re-run and confirm
  the exact-JSON test fails showing `eventType` in the actual output; restore it, re-run, confirm
  exit 0 and a clean `git status --porcelain src/`. Evidence: `.omo/evidence/30-payload-jsonproperty-fail.log`.
  **Commit:** `feat: add sync payload with explicit wire contract`

## Final verification wave

- [ ] F1. Contract audit (executable). Run `scripts/test.sh test -Dtest=SyncPayloadTest,LoginSyncConfigTest` expecting exit 0. Serialise a fixed payload in a test and assert `objectMapper.readTree(json).fieldNames()` equals exactly `[event_type, client_id, username, email, groups, timestamp]` - no extras, none missing. Assert via reflection that `SyncPayload` exposes no public constructor or static factory taking an event-type argument. Assert each default equals the LLD value by `grep -E 'DEFAULT_(HTTP_TIMEOUT_MS|CB_FAILURE_THRESHOLD|CB_ERROR_RATE_THRESHOLD|CB_WINDOW_SIZE|CB_COOLDOWN_SECONDS)' LoginSyncConstants.java` and comparing to `5000, 5, 50, 20, 30`. Evidence: `.omo/evidence/30-F1-contract.md`.

- [ ] F2. Log-safety and scope audit (executable). Run `grep -rc 'System.getenv' src/main/java` expecting total 0. Run `grep -rciE 'retry|backoff|REGISTER|UPDATE_PROFILE|resilience4j' src/main/java` expecting total 0. Run the exactly-one `/api/sync-user` check from todo 1 expecting exit 0. Run a test that builds a config and a payload populated with sentinel values `SECRET_SENTINEL`, `email@sentinel.test` and `/GROUP_SENTINEL`, calls `toString()` on both, and asserts none of the three sentinels appears; then assert `grep -rc 'SECRET_SENTINEL' target/surefire-reports/` returns 0. With `BASE_SHA` per P3, run `git diff --name-only $BASE_SHA..HEAD` and assert the set equals this plan's five owned files. Evidence: `.omo/evidence/30-F2-logsafety.md`.

## Commit strategy

Three commits, one per todo, all `feat:`. `pom.xml` is untouched, so no release fires.

## Success criteria

1. `scripts/test.sh clean verify` exits 0 with all unit tests green.
2. The serialised payload matches LLD 4.4 byte-for-byte, with exactly six fields.
3. A non-`LOGIN` `event_type` is structurally impossible to construct.
4. Zero and negative numeric configuration values are rejected.
5. Neither the secret, the email, nor any group path can be rendered by `toString()` or appear in
   test reports.
6. `/api/sync-user` and every threshold change in exactly one place.
