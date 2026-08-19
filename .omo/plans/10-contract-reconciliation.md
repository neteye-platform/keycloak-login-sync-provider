# 10 - contract reconciliation - Work Plan

```yaml
slug: 10-contract-reconciliation
revision: 2
wave: 0
prerequisites: []
parallel_with: [20-scaffold-reconciliation]
owns_files:
  - docs/DECISIONS.md
  - docs/SYNC-CONTRACT.md
```

## TL;DR (For humans)

**What you'll get.** Two documents that make the updated LLD the single source of truth for the
whole portfolio: `docs/DECISIONS.md`, recording every place the LLD reverses the superseded plan,
and `docs/SYNC-CONTRACT.md`, recording the external receiver contract the plugin depends on but
does not implement.

**Why this approach.** Six of the eight plans encode a decision the old plan got wrong. Writing
the reversals down once, with LLD references, stops each later plan re-litigating them and gives a
reviewer one page to audit the whole reconciliation.

**What it will NOT do.** No code, no build changes, no receiver implementation. Nothing new is
decided here; decisions already made by the LLD and the user are recorded.

**Effort.** 2 implementation todos, plus 2 final verification todos.

**Risk.** Low. The real risk is under-recording: a reversal not written here will be silently
re-introduced by a later plan.

---

## Scope

### In scope

- `docs/DECISIONS.md`: the LLD-vs-old-plan reconciliation table.
- `docs/SYNC-CONTRACT.md`: external syncservice contract assumptions, including the delivery
  semantics the fixed payload implies.

### Out of scope (Must-NOT-Have)

- MUST NOT write Java or create `src/main/resources`.
- MUST NOT modify `pom.xml`, `README.md`, `.gitignore` or any workflow file - those are plan 20's.
- MUST NOT implement, stub or scaffold receiver-side validation.
- MUST NOT record a decision as settled where the LLD leaves it open.
- MUST NOT modify or delete any prior plan file.
- MUST NOT describe REGISTER, UPDATE_PROFILE, retry, backoff or user-token behaviour as supported.

---

## Key decisions

| id | decision | rationale |
|----|----------|-----------|
| R1 | Config property is `service-endpoint`, env `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT` | user decision; the only spelling with measured evidence |
| R2 | LLD 4.3.1 names it `endpoint`; that is **flagged for alignment**, not adopted | user instruction; document and implementation must be reconciled by the LLD owner |
| R3 | `provided` Jackson pinned to 2.21.2 | matches what Keycloak 26.7.0 actually ships |
| R4 | Circuit-breaker logging deviates from LLD 3.2 | user instruction supersedes; recorded as an explicit deviation |
| R5 | D21 (reuse the user's JWT) is **closed as not viable**; D24 void | LLD 3.3 decided Client Credentials, and the spike proved the user token unreachable |
| R6 | Delivery semantics are **at-most-once, non-deduplicable, non-correlatable** | consequence of the LLD-fixed six-field payload; must be documented, not discovered in production |

---

## Verification strategy

Documentation only, so verification is command-based and agent-executable. Per portfolio
invariant P1, every check below names a tool and an expected exit status. Per P2, evidence lives
in the gitignored `.omo/evidence/`, so it is excluded from the "exactly two tracked files" audit.
Both documents must be `git add`-ed before prek runs, because prek only inspects tracked files.

---

## Execution strategy

| wave | todos | depends on |
|------|-------|-----------|
| 1 | 1, 2 | - |
| F | F1, F2 | 1, 2 |

Todos 1 and 2 touch different files and may be done in either order. Record `BASE_SHA=$(git rev-parse HEAD)`
before the first commit (portfolio invariant P3).

---

## Todos

- [ ] 1. `docs/DECISIONS.md`: record the full LLD-vs-old-plan reconciliation - expect all 12 reversal rows present and greppable

  **References:** `N4-LLD-2.pdf` sections 3.1, 3.2, 3.3, 3.6, 3.7, 4.3, 4.3.1, 4.4;
  `.omo/plans/keycloak-login-sync-provider.md` (superseded, for the "old" column only);
  `.omo/evidence/spike-01-token-probes.log`; `.omo/evidence/spike-02-config-scope.log`.
  **Details:** Create `docs/DECISIONS.md` as a table, one row per reversal, each stating the
  old-plan behaviour, the replacing LLD rule with its section number, and the resulting action.
  Each row MUST begin with a stable identifier `R-01` .. `R-12` at the start of the row so the
  audit can grep for it deterministically. Required rows:
  - `R-01 Retry deleted.` LLD 3.7: no retry for unavailable/timeout, 5xx, or 4xx. Action: single
    attempt; the retryable/non-retryable exception split is replaced by one `SyncFailedException`.
  - `R-02 401/403 policy.` LLD 3.7. Action: the current request fails; the cached service-account
    token is invalidated so the **next** login re-fetches; the sync POST is **not** repeated in
    the same login. State this in exactly these terms, and add the sentence "This is not a retry:
    no second POST occurs within one login." so the wording cannot be read as retry-with-refresh.
  - `R-03 REGISTER dropped.` LLD 3.6. `event_type` is the constant `LOGIN`.
  - `R-04 UPDATE_PROFILE dropped.` LLD 3.6; would require a `RequiredActionProvider`.
  - `R-05 Token strategy closed.` LLD 3.3 + 3.1; cite the spike's verbatim
    `ClientSessionContext ... is null` NPE. Service-account JWT only; `client_id` stays a payload
    field.
  - `R-06 Config mechanism.` LLD 4.3 note discards `System.getenv`; 4.3.1 adopts `Config.Scope`.
  - `R-07 Config key name.` Implementation uses `service-endpoint`; LLD 4.3.1 says `endpoint`.
    Mark the row `ACTION REQUIRED: LLD owner`.
  - `R-08 Breaker logging deviation.` LLD 3.2 asks for a WARN per skipped sync; action is WARN
    only on the transition into OPEN with marker `LOGIN_SYNC_CIRCUIT_OPEN`, skips at DEBUG.
    Record the reason: a per-skip WARN floods the log during the very outage it describes.
  - `R-09 Event loss.` Events skipped while OPEN are lost and never replayed; permitting logins
    during an outage is an availability trade-off, not a security control.
  - `R-10 Jackson pin.` `provided` Jackson to 2.21.2; implemented by plan 20.
  - `R-11 Additions beyond the LLD.` The bulkhead, the truststore-derived `SSLContext`, and the
    per-JVM singleton documentation, each with the failure it prevents.
  - `R-12 Keycloak REQUIRED-flow contract.` Verified from Keycloak 26.7.0 source:
    `AuthenticationProcessor.isSuccessful()` returns true only for `ExecutionStatus.SUCCESS`, so
    `context.attempted()` in a REQUIRED execution **fails the login**. Action: every deliberate
    skip calls `context.success()`. Also record that `requiresUser()==true` throws before
    `authenticate()` is invoked when no user is set, so a misplaced execution is rejected by
    Keycloak rather than skipping.
  Close with a "Superseded plans" note stating `.omo/plans/keycloak-login-sync-provider.md` is
  superseded in full and must not be executed.
  **Acceptance criteria:** run `for i in $(seq -w 1 12); do grep -q "R-$i" docs/DECISIONS.md || echo "MISSING R-$i"; done`
  and expect **no output**. Run `grep -c 'ACTION REQUIRED: LLD owner' docs/DECISIONS.md` expecting
  `1`. Run `grep -q 'This is not a retry' docs/DECISIONS.md` expecting exit 0. Run
  `grep -q 'context.success()' docs/DECISIONS.md` expecting exit 0. Run
  `markdownlint-cli2 docs/DECISIONS.md` expecting exit 0.
  **QA happy:** `git add docs/DECISIONS.md && prek run markdownlint-cli2 --all-files` exits 0 and
  the R-01..R-12 loop prints nothing. Evidence: `.omo/evidence/10-decisions-lint.log`.
  **QA failure:** delete the `R-07` row, re-run the loop, and confirm it prints exactly
  `MISSING R-07` and the check fails; restore the row, re-run, and confirm no output. Then confirm
  `git status --porcelain docs/` is clean. Evidence: `.omo/evidence/10-decisions-grep-fail.log`.
  **Commit:** `docs: reconcile implementation decisions with updated LLD`

- [ ] 2. `docs/SYNC-CONTRACT.md`: document the external receiver contract and its delivery semantics - expect every undecided item flagged and the deduplication limit stated

  **References:** `N4-LLD-2.pdf` sections 3.3, 3.4, 4.4, 5; decision R6.
  **Details:** Create `docs/SYNC-CONTRACT.md` describing what the plugin sends and what it assumes
  of the receiver. Required content:
  - **Request.** `POST {service-endpoint}/api/sync-user`; headers
    `Authorization: Bearer <service-account-jwt>` and `Content-Type: application/json`. State that
    this token authenticates the **technical caller**, is **not the logging-in user's** token, and
    is never constructed by the plugin.
  - **Body.** The exact six fields from LLD 4.4 with an example: `event_type` (always `LOGIN`),
    `client_id`, `username`, `email`, `groups` (full paths), `timestamp` (ISO-8601 UTC).
  - **Responses.** 200 and 201 succeed. 400, 401, 403, 500, timeout and IO are each a
    single-attempt failure with no retry.
  - **Receiver-side assumptions (NOT IMPLEMENTED HERE).** Under a heading containing the literal
    words `NOT IMPLEMENTED`, state that the receiver is assumed to validate the signature against
    Keycloak's **cached JWKS** public keys, and check `exp`, expected `iss`, expected `aud` if
    configured, an accepted signing algorithm, and a **least-privilege** technical role or scope.
  - **Open integration decisions.** A list stating that the exact role name, the exact claim
    carrying it, and the audience value are `NOT decided` and MUST be finalized before real
    deployment. Note LLD Open point 3 (receiver ownership) is still open, and that
    `/api/sync-user` is provisional and isolated in `LoginSyncConstants.SYNC_USER_PATH`, which is
    why the project stays at `0.x`.
  - **Delivery semantics (R6) - required, and easy to omit.** State explicitly that the payload
    carries **no event id, request id, correlation id or idempotency key**, and that `timestamp`
    is truncated to whole seconds. Therefore: two genuine logins by the same user to the same
    client within one second produce **byte-identical** bodies; the receiver **cannot** distinguish
    a duplicate delivery from two real logins and **must not** deduplicate on payload equality;
    delivery is **at-most-once** (a failed or skipped sync is never replayed); and an operator
    **cannot** reliably correlate a Keycloak-side log line with a receiver-side log line. State
    that the payload MUST NOT be extended without a contract revision agreed with the receiver
    owner, and that if correlation later becomes necessary the agreed mechanism should be a
    transport header rather than a body field, so the LLD-fixed body stays intact.
  **Acceptance criteria:** run
  `for s in 'JWKS' 'exp' 'iss' 'aud' 'least-privilege' 'NOT decided' 'NOT IMPLEMENTED' '/api/sync-user' 'SYNC_USER_PATH' 'not the logging-in user' 'at-most-once' 'must not' 'byte-identical'; do grep -qF "$s" docs/SYNC-CONTRACT.md || echo "MISSING $s"; done`
  and expect **no output**. Run `grep -cE '^\s*(public|private|def|func) ' docs/SYNC-CONTRACT.md`
  expecting `0`, proving no receiver implementation leaked in. Run
  `markdownlint-cli2 docs/SYNC-CONTRACT.md` expecting exit 0.
  **QA happy:** `git add docs/SYNC-CONTRACT.md && prek run markdownlint-cli2 --all-files` exits 0
  and the string loop prints nothing. Evidence: `.omo/evidence/10-contract-lint.log`.
  **QA failure:** remove the `at-most-once` sentence, re-run the loop, confirm it prints
  `MISSING at-most-once`; restore it, re-run, confirm no output and a clean
  `git status --porcelain docs/`. Evidence: `.omo/evidence/10-contract-noimpl.log`.
  **Commit:** `docs: document external syncservice contract and delivery semantics`

## Final verification wave

- [ ] F1. Reconciliation completeness audit (executable). Run the `R-01..R-12` presence loop from todo 1 expecting no output; run `grep -cE 'section [0-9]' docs/DECISIONS.md` expecting at least 8, proving rows cite LLD sections; run `grep -q 'ACTION REQUIRED: LLD owner' docs/DECISIONS.md` expecting exit 0; run `grep -q 'context.success()' docs/DECISIONS.md` expecting exit 0. Record each command with its exit status. Evidence: `.omo/evidence/10-F1-reconciliation.md`.

- [ ] F2. Scope fidelity audit (executable). With `BASE_SHA` recorded per invariant P3, run `git diff --name-only $BASE_SHA..HEAD` and assert the output is exactly the two lines `docs/DECISIONS.md` and `docs/SYNC-CONTRACT.md` (evidence under `.omo/` is gitignored per P2 and must not appear). Run `git diff --name-only $BASE_SHA..HEAD -- pom.xml README.md .gitignore .github src` expecting empty output. Run `grep -rniE 'resilience4j|okhttp|RequiredActionProvider|retryable' docs/` and assert every hit lies on a line also matching `-iE 'removed|out of scope|replaced|deleted'`. Evidence: `.omo/evidence/10-F2-scope.md`.

## Commit strategy

Two commits, both `docs:`. Neither touches `pom.xml`, so no release workflow can fire.

## Success criteria

1. All 12 reversals are recorded with LLD references and machine-checkable identifiers.
2. The `endpoint` vs `service-endpoint` mismatch is flagged for the LLD owner.
3. The receiver contract is documented as assumptions, with role/claim/audience marked undecided.
4. At-most-once delivery, non-deduplicability and non-correlatability are stated explicitly.
5. Exactly two tracked files were created; nothing else changed.
