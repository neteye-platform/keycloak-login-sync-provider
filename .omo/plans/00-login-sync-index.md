# 00 - login-sync portfolio index - Master Plan

```yaml
slug: 00-login-sync-index
kind: index
intent: clear
revision: 2   # revised after dual high-accuracy review (9 Momus lanes + Oracle)
authority: N4-LLD-2.pdf (pages 1-14) + the revised decisions in 10-contract-reconciliation
supersedes:
  - .omo/plans/keycloak-login-sync-provider.md   # fully superseded, do not execute
completed_and_not_replanned:
  - .omo/plans/bootstrap-and-ci.md               # executed 2026-08-18, 5/5 + F1/F2
  - .omo/plans/spike-token-strategy.md           # executed 2026-08-18, 3/3 + F1/F2
```

## TL;DR (For humans)

**What you'll get.** Eight small, independently reviewable work plans that deliver the Keycloak
26.7.0 `login-sync` custom Authenticator: LOGIN-only, synchronous, single-attempt POST to an
external syncservice, authenticated with a service-account JWT via OAuth2 Client Credentials,
guarded by a hand-written circuit breaker.

**Why this ordering.** Revision 2 removed the portfolio's false parallelism. A dual high-accuracy
review proved that plan 40 consumes constants owned by plan 30, and that plan 80 cannot run at all
without plan 70's mock class. Both were declared parallel in revision 1. The chain is now almost
entirely sequential, which is the honest shape of this work.

**What it will NOT do.** No REGISTER, no UPDATE_PROFILE, no `RequiredActionProvider`, no retry or
backoff, no user-token construction, no async/outbox delivery, no receiver-side code, no
Dockerfile, no RPM spec.

**Effort.** 8 plans, 7 waves, 23 implementation todos + 18 final-verification todos.

**Risk.** Medium. The residual risks are concentrated in plans 40, 50 and 60 - breaker permit
settlement, the token-invalidation race, and the Keycloak REQUIRED-flow contract. All three were
found by review and are now specified rather than left to the implementer.

**Decisions.** Reconciliation table in `10-contract-reconciliation.md`. User-confirmed for this
round: config key is `service-endpoint`; the scaffold is reconciled, not rebuilt; `provided`
Jackson pinned to 2.21.2.

---

## Scope

### In scope

- Ordering, dependencies and file ownership of the eight component plans.
- Recording which prior plans are superseded and which are already executed.
- Recording the portfolio-wide invariants every plan must satisfy.

### Out of scope (Must-NOT-Have)

- MUST NOT contain implementation work of its own.
- MUST NOT re-plan `bootstrap-and-ci` or `spike-token-strategy`; both are executed.
- MUST NOT be executed via `$start-work`; execute the numbered plans.

---

## Authority and precedence

1. `N4-LLD-2.pdf` pages 1-14 is the highest authority.
2. The user's revised decisions override the LLD **only** where recorded in
   `10-contract-reconciliation.md` (currently one item: circuit-breaker logging).
3. `.omo/plans/keycloak-login-sync-provider.md` is **superseded in full**; read for technical
   detail only, never for a decision.
4. Where the LLD is silent, the executed spike evidence in `.omo/evidence/` governs.
5. Where a Keycloak runtime behaviour is disputed, verified source at tag 26.7.0 governs.

## Verified Keycloak facts that constrain the portfolio

These were confirmed from Keycloak 26.7.0 source during review and are binding on every plan:

- **`context.attempted()` FAILS a REQUIRED execution.** `AuthenticationProcessor.isSuccessful()`
  returns true only for `ExecutionStatus.SUCCESS`; `DefaultAuthenticationFlow` line 295 gates
  REQUIRED executions on it and breaks the loop, and `authenticateOnly()` line 1132 throws
  `AuthenticationFlowException`. **Every deliberate skip must call `context.success()`.**
- **`requiresUser() == true` throws before `authenticate()` is invoked** when no user is set.
  A misplaced execution (before the forms subflow) is therefore rejected by Keycloak; it does not
  reach a null-user guard inside `authenticate()`.
- Blocking I/O in `authenticate()` is safe: the JAX-RS application is annotated `@Blocking`, so
  this runs on a Quarkus worker thread.

## Repository state these plans build on

- `pom.xml` at `0.1.0` (Java 21, Keycloak 26.7.0, `keycloak-spi-bom`, Spotless AOSP at `validate`,
  Surefire, Failsafe with `provider.jar`).
- All five workflows exist and pass `actionlint`; `release.yaml` is present and pom-driven.
- Full hygiene layer, dual licence, `SECURITY.md`, `scripts/test.sh` all exist.
- `src/main/java` and `src/test/java` contain **only** `.gitkeep`. No production Java, no
  `src/main/resources`, no `META-INF/services`.
- Branch `NEPROD-2580-Spike-Hosting-of-container-images`, tip `afad642`.

No plan recreates build, CI, licence or hygiene files.

## Portfolio-wide invariants

Every plan must satisfy all of these; each plan's F-wave verifies its own compliance.

- **P1 - executable verification.** Every acceptance criterion and every final-verification todo
  names a concrete tool and command with an expected exit status or expected output. The word
  "confirm" alone is not a verification step.
- **P2 - evidence is untracked.** Evidence files are written to `.omo/evidence/`, which plan 20
  adds to `.gitignore`. A plan's "touched exactly N files" audit counts **tracked** files only and
  must exclude `.omo/`.
- **P3 - baseline SHA.** Before its first commit, each plan records its start commit as
  `BASE_SHA`; every non-regression diff is `git diff --name-only $BASE_SHA..HEAD`. No plan may use
  an undefined `<base>`.
- **P4 - skip means success.** Any deliberate skip in the Authenticator calls `context.success()`.
- **P5 - one settlement per login.** Exactly one breaker settlement per logical sync, on every
  path including exceptions.
- **P6 - no retry.** One HTTP attempt per logical sync, proven by a request-counter assertion.
- **P7 - repo-clean QA.** Every QA-failure step that mutates a file restores it and re-runs the
  check to prove the restoration, ending with a clean `git status` for tracked files.

## Environment notes

- **`prek`, not `pre-commit`** (`prek run --all-files`); plain `pre-commit` fails on this config.
- **prek only inspects git-tracked files** - `git add` before linting or the hook passes vacuously.
- **markdownlint runs on defaults** - MD013 enforces 80 columns.
- **`mvn` is not on PATH** and the host JDK is 25, not 21 - use `scripts/test.sh`.
- **Leave the `repo-commons` pin `740d307...` alone.**

---

## Verification strategy

The portfolio is verified by its members; this index is verified only by F1 and F2 below, which
are executable scripts rather than prose confirmations.

---

## Execution strategy

Revision 2 removed two false parallelism claims. The honest graph is:

| wave | plan | parallel with | depends on |
|------|------|---------------|-----------|
| 0 | `10-contract-reconciliation` | `20` | - |
| 0 | `20-scaffold-reconciliation` | `10` | - |
| 1 | `30-config-and-payload` | none | 10, 20 |
| 2 | `40-circuit-breaker` | none | 30 |
| 3 | `50-token-and-sync-client` | none | 30, 40 |
| 4 | `60-authenticator-spi` | none | 30, 40, 50 |
| 5 | `70-integration-harness` | none | 60 |
| 6 | `80-devstack-and-docs` | none | 70 |

```
10 ─┐
    ├─> 30 ─> 40 ─> 50 ─> 60 ─> 70 ─> 80
20 ─┘
```

**Why parallelism shrank.** Review established two real couplings that revision 1 denied:
plan 40 consumes `LoginSyncConstants` (defaults and `CIRCUIT_OPEN_LOG_MARKER`) owned by plan 30,
so it cannot compile first; and plan 80's compose stack runs plan 70's `MockSyncService`, so it
cannot come up first. Only wave 0 is genuinely parallel, because plans 10 and 20 share no file
and neither compiles code.

### File-ownership matrix

| plan | owns |
|------|------|
| 10 | `docs/DECISIONS.md`, `docs/SYNC-CONTRACT.md` |
| 20 | `pom.xml`, `.gitignore`, `src/main/resources/.gitkeep`, one stale line of `README.md` |
| 30 | `LoginSyncConstants`, `LoginSyncConfig`, `SyncPayload` + their tests |
| 40 | `CircuitBreaker`, `CircuitState`, `Permit` + their tests |
| 50 | `ServiceAccountTokenProvider`, `SyncClient`, `SyncOutcome`, `SyncFailedException`, `TokenHandle` + their tests |
| 60 | `LoginSyncAuthenticator`, `LoginSyncAuthenticatorFactory`, `META-INF/services/...`, `theme-resources/messages/messages_en.properties` + test |
| 70 | `src/test/java/.../support/*`, `LoginSyncIT` |
| 80 | `podman-compose.yml`, `.env.example`, `Makefile`, `README.md` sections |

`.gitignore` is owned **solely by plan 20**, which adds both `.omo/` and `.env`; plan 80 must not
edit it. `README.md` is touched by plans 20 and 80, six waves apart.

---

## Todos

- [ ] 1. `.omo/evidence/index-00-graph.md`: record the executable dependency-graph proof this index asserts - expect the recorded graph to match every plan's front matter

  **References:** the wave table and file-ownership matrix above; each plan's `prerequisites`,
  `parallel_with` and `owns_files` front-matter blocks.
  **Details:** Write a short script (shell or python, kept in `/tmp`, not committed) that parses
  the `prerequisites`, `parallel_with` and `owns_files` YAML blocks from
  `.omo/plans/[1-8]0-*.md` and emits: (a) the derived dependency edges, (b) any pair of plans that
  declare each other parallel while also declaring a prerequisite relationship, and (c) any file
  declared in two different plans' `owns_files`. Record its output verbatim in the evidence file.
  **Acceptance criteria:** the script exits 0; its output lists exactly the eight edges in the
  wave table above; it reports **zero** parallel-versus-prerequisite contradictions; and it
  reports **zero** files owned by two plans.
  **QA happy:** run the script and diff its edge list against the wave table; the diff is empty.
  Evidence: `.omo/evidence/index-00-graph.md`.
  **QA failure:** temporarily add `owns_files: [pom.xml]` to a copy of plan 30 in `/tmp`, re-run
  the script against the copy, and confirm it reports a duplicate-ownership collision with
  plan 20; discard the copy. This proves the collision detector is not vacuous. Evidence: `.omo/evidence/index-00-graph-collision.md`.
  **Commit:** `docs: record portfolio dependency graph proof`

## Final verification wave

- [ ] F1. Portfolio consistency audit. Run: (a) `ls .omo/plans/[0-8]0-*.md | wc -l` expecting `9`; (b) for each plan, `grep -c '^- \[ \] [0-9]\+\.' <plan>` and `grep -c '^- \[ \] F[0-9]\+\.' <plan>`, expecting the per-plan counts 10=2/2, 20=3/2, 30=3/2, 40=2/2, 50=3/2, 60=3/2, 70=3/2, 80=3/2, 00=1/2, totalling 23 implementation and 18 final rows; (c) the todo-1 graph script, expecting zero contradictions and zero ownership collisions. All three must pass. Evidence: `.omo/evidence/index-F1-consistency.md`.

- [ ] F2. Supersession and invariant audit. Run `grep -l 'keycloak-login-sync-provider\.md' .omo/plans/[1-8]0-*.md` and confirm every hit is within a line also matching `-i 'supersed'`. Run `grep -Lc 'context.success()' .omo/plans/60-authenticator-spi.md` expecting a match (P4 encoded). Run `grep -c 'BASE_SHA' .omo/plans/20-scaffold-reconciliation.md` expecting at least 1 (P3 encoded). Evidence: `.omo/evidence/index-F2-supersession.md`.

## Commit strategy

One commit: `docs: add login-sync plan portfolio index`.

## Success criteria

1. Every component plan is independently executable with zero interview context.
2. The declared dependency graph is machine-verified against each plan's front matter.
3. No file is owned by two plans; no plan pair is both parallel and dependent.
4. No plan carries a decision the updated LLD reverses, or a Keycloak behaviour that source
   contradicts.
