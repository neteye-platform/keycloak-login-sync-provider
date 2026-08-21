# Login Sync Plan Portfolio

```yaml
slug: login-sync-plan-portfolio
kind: index
intent: clear
revision: 7
authority: N4-LLD-2.pdf (pages 1-14) + the revised decisions in 0001-contract-reconciliation.md
```

> This unnumbered README is the portfolio landing page and shared planning guidance. It is not an
> executable plan. The component plans begin at `0001` so file order is unambiguous and one-based.
> Historical plans and spike evidence are absent from this repository and are not authority.

## TL;DR (For humans)

**What you'll get.** Eight small, independently reviewable component plans (`0001` through `0008`)
that deliver the Keycloak
26.7.0 `login-sync` custom Authenticator: LOGIN-only, synchronous, single-attempt POST to an
external syncservice, authenticated with a service-account JWT via OAuth2 Client Credentials,
guarded by a hand-written circuit breaker.

**Why this ordering.** Revision 2 removed the portfolio's false parallelism. A dual high-accuracy
review proved that plan 0004 consumes constants owned by plan 0003, and that plan 0008 cannot run
at all without plan 0007's mock class. Both were declared parallel in revision 1. The chain is now
almost entirely sequential, which is the honest shape of this work.

**What it will NOT do.** No REGISTER, no UPDATE_PROFILE, no `RequiredActionProvider`, no retry or
backoff, no user-token construction, no async/outbox delivery, no receiver-side code, no
Dockerfile, no RPM spec.

**Effort.** 8 plans, 7 waves, 19 implementation todos + 2 completed-baseline re-audits + 16
final-verification todos.

**Risk.** Medium. The residual risks are concentrated in plans 0004, 0005 and 0006 - breaker permit
settlement, the token-invalidation race, and the Keycloak REQUIRED-flow contract. All three were
found by review and are now specified rather than left to the implementer.

**Decisions.** Reconciliation table in
[0001-contract-reconciliation.md](0001-contract-reconciliation.md). User-confirmed for this round:
config key is `service-endpoint`; the scaffold is reconciled, not rebuilt; `provided` Jackson is
pinned to 2.21.2; and the LLD/user decision selects service-account Client Credentials rather
than reuse of the logging-in user's JWT. Absent spike artifacts are not authority for that decision.

---

## Scope

### In scope

- Ordering, dependencies and file ownership of the eight component plans.
- Recording the portfolio-wide invariants every plan must satisfy.

### Out of scope (Must-NOT-Have)

- MUST NOT contain implementation work of its own.
- MUST NOT be executed via `$start-work`; execute the numbered plans.

---

## Authority and precedence

1. `N4-LLD-2.pdf` pages 1-14 is the highest authority.
2. The user's revised decisions override the LLD **only** where recorded in
   [0001-contract-reconciliation.md](0001-contract-reconciliation.md).
3. Historical plans and spike evidence are absent from this repository and are not authority.
4. Where a Keycloak runtime behaviour is disputed, verified source at tag 26.7.0 governs.

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

- `pom.xml` at `0.1.0` (Java 21, Keycloak 26.7.0, provided Jackson 2.21.2, Spotless AOSP at
  `validate`, Surefire, Failsafe with `provider.jar`).
- All five workflows exist; every `release.yaml` run resolves the POM version's `v<version>`
  Release. An existing Release is a no-op; when absent, it builds the jar, retains an existing tag
  or creates an absent one, then creates the Release.
- Full hygiene layer, dual licence, `SECURITY.md`, `scripts/test.sh` all exist.
- `src/main/java` and `src/test/java` contain **only** `.gitkeep`. No Java implementation,
  `src/main/resources`, or `META-INF/services` exists.
- Plan 0002 records the completed immutable POM-version release guard, Jackson pin, and ignore
  rules, then re-audits them without re-implementing them. Later plans do not recreate build, CI,
  licence, or hygiene files.

## Portfolio-wide invariants

Every plan must satisfy all of these; each plan's F-wave verifies its own compliance.

- **P1 - executable verification.** Every acceptance criterion and every final-verification todo
  names a concrete tool and command with an expected exit status or expected output. The word
  "confirm" alone is not a verification step.
- **P2 - evidence is untracked.** Ignored `docs/evidence/` stores command output and observed
  results from a verification run. It demonstrates that named checks were executed, but is not
  tracked authority. A plan's "touched exactly N files" audit counts **tracked** files only and
  must exclude generated evidence.
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
- **`docs/.markdownlint-cli2.yaml` disables MD013** for planning documents.
- **Use `scripts/test.sh` for Maven**; it supplies Java 21 when local Maven is unavailable.
- **Leave the `repo-commons` pin `740d307...` alone.**

---

## Shared Planning And Verification Conventions

Each numbered plan owns its scope and acceptance criteria. This README owns only shared planning
conventions: evidence location, baseline discipline, the dependency graph, and validation.

- Ignored `docs/evidence/` stores command output and observed results from a verification run. It
  demonstrates that named checks were executed, but is not tracked authority.
- Historical plans and spike artifacts absent from this repository are not authority.
- Record `BASE_SHA=$(git rev-parse HEAD)` before a plan's first commit. Scope audits compare
  `git diff --name-only $BASE_SHA..HEAD` and exclude ignored generated evidence.
- Each acceptance criterion names a concrete command and expected result. A prose-only
  "confirmation" is not verification.

### Validation Commands

Run the following before handing off planning, workflow, or baseline changes:

```sh
git diff --check
actionlint .github/workflows/*.yaml
scripts/test.sh clean verify
prek run markdownlint-cli2 --all-files
test "$(rg --files docs/plans -g '[0-9][0-9][0-9][0-9]-*.md' | wc -l)" -eq 8
git check-ignore -q docs/evidence/probe .env
```

---

## Execution Strategy

The eight executable component-plan documents are deliberately sequential after the initial
parallel wave. Plan 0002 contains only completed-baseline re-audits.

| wave | plan                                                            | parallel with | depends on             |
| ---- | --------------------------------------------------------------- | ------------- | ---------------------- |
| 0    | [0001-contract-reconciliation](0001-contract-reconciliation.md) | `0002`        | -                      |
| 0    | [0002-scaffold-reconciliation](0002-scaffold-reconciliation.md) | `0001`        | completed baseline     |
| 1    | [0003-config-and-payload](0003-config-and-payload.md)           | none          | `0001`, `0002`         |
| 2    | [0004-circuit-breaker](0004-circuit-breaker.md)                 | none          | `0003`                 |
| 3    | [0005-token-and-sync-client](0005-token-and-sync-client.md)     | none          | `0001`, `0003`, `0004` |
| 4    | [0006-authenticator-spi](0006-authenticator-spi.md)             | none          | `0003`, `0004`, `0005` |
| 5    | [0007-integration-harness](0007-integration-harness.md)         | none          | `0006`                 |
| 6    | [0008-devstack-and-docs](0008-devstack-and-docs.md)             | none          | `0001`, `0006`, `0007` |

```text
0001 -> 0003, 0005, 0008
0002 -> 0003
0003 -> 0004, 0005, 0006
0004 -> 0005, 0006
0005 -> 0006
0006 -> 0007, 0008
0007 -> 0008
```

Plan 0004 consumes constants from plan 0003, and plan 0008 consumes plan 0007's mock class.
Those constraints leave only plans 0001 and 0002 parallel.

### File Ownership Matrix

| plan | owns                                                                    |
| ---- | ----------------------------------------------------------------------- |
| 0001 | `docs/DECISIONS.md`, `docs/SYNC-CONTRACT.md`                            |
| 0002 | `.github/workflows/release.yaml`, `pom.xml`, `.gitignore`               |
| 0003 | `LoginSyncConstants`, `LoginSyncConfig`, `SyncPayload`, and their tests |
| 0004 | `CircuitBreaker`, `CircuitState`, `Permit`, and their tests             |
| 0005 | token provider, sync client, outcome types, and their tests             |
| 0006 | Authenticator SPI classes, services, message bundle, and test           |
| 0007 | test support classes and `LoginSyncIT`                                  |
| 0008 | `podman-compose.yml`, `.env.example`, `Makefile`, and root `README.md`  |

`docs/plans/README.md` is shared guidance, not a component-plan output. Root `README.md` does not
exist in the current skeleton; only plan 0008 creates it. Plan 0002 alone owns `.gitignore`.

## Portfolio Success Criteria

1. The eight numbered plans are independently executable with no interview context.
2. Plan front matter and this graph agree on every prerequisite and parallel relationship.
3. Evidence is generated only under ignored `docs/evidence/`; unavailable artifacts are not cited.
4. No owned file appears in two component plans.
