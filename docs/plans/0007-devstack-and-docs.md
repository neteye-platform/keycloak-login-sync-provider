# 0007 - dev stack and documentation - Work Plan

```yaml
slug: 0007-devstack-and-docs
revision: 7
wave: 5
prerequisites: [0001-contract-reconciliation, 0005-authenticator-spi, 0006-integration-harness]
parallel_with: []
owns_files:
  - podman-compose.yml
  - .env.example
  - Makefile
  - README.md # create from the currently absent root README baseline
```

## TL;DR (For humans)

**What you'll get.** The local feedback loop from LLD section 6 - a podman-compose stack running
Keycloak with the plugin bind-mounted plus a mock receiver, driven by a Makefile - and the
operator-facing documentation: every environment variable verbatim, the deployment constraint that
makes or breaks the plugin, and an honest limitations section.

**Why revision 2 demoted this plan to a hard dependency.** Revision 1 declared plan 0006 a _soft_
prerequisite while also claiming the two could run in parallel. Both reviewers rejected that: the
compose stack runs plan 0006's `MockSyncService`, so if this plan ran first `make up` could not work
at all. Worse, revision 1 said the mock runs "from the built test artifact" - **the Maven build
produces no test jar**, so that instruction was unimplementable. The stack now mounts
`target/test-classes` directly.

**What it will NOT do.** No Dockerfile, no Containerfile, no RPM spec - packaging is downstream. No
second mock implementation. No Python. No `.gitignore` edit (plan 0002 owns that file).

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Low.

---

## Scope

### In scope

- `podman-compose.yml` + `.env.example`: Keycloak with the built jar bind-mounted, plus the mock.
- `Makefile`: `build`, `deploy`, `up`, `down`, `reset`, `logs`, `test`, `fmt`.
- Create `README.md` from the absent baseline with project, Configuration, Deployment, and
  Limitations sections.

### Out of scope (Must-NOT-Have)

- MUST NOT create a Dockerfile, Containerfile or RPM `.spec`.
- MUST NOT introduce a second mock-syncservice implementation, and MUST NOT add Python.
- MUST NOT edit `.gitignore` - plan 0002 owns it and already ignores `docs/evidence/` and `.env`.
- MUST NOT write a realm/client/role provisioning script; the stack carries configuration only.
- MUST NOT document REGISTER, UPDATE_PROFILE, retry, backoff or user-token behaviour as supported.
- MUST NOT document a config key spelling other than the pinned double-underscore one.
- MUST NOT modify `pom.xml`, anything under `.github/`, or anything under `src/`.
- MUST NOT commit a real secret in `.env.example`.
- MUST create the `## Build and test`, `## Layout`, `## Releasing`, and `## License` sections
  directly; root `README.md` is absent at plan start.

---

## Key decisions

| id     | decision                                                                                                       | rationale                                                                                                                                        |
| ------ | -------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| E1     | One mock implementation, owned by plan 0006, reused here                                                       | two mocks drift, and the drift surfaces in production                                                                                            |
| E2     | No Dockerfile and no RPM spec                                                                                  | LLD section 6; downstream repos own packaging                                                                                                    |
| E3     | `.env.example` committed with placeholders; `.env` ignored by plan 0002                                        | operators get a template without a secret entering git history                                                                                   |
| E4     | The README documents the five operator-facing keys only                                                        | the bulkhead and token timeouts are internal constants                                                                                           |
| E5     | The execution-position constraint gets its own section                                                         | it is the single misconfiguration that breaks every login in a realm                                                                             |
| **E6** | **The mock runs from mounted `target/test-classes`, not a test jar**                                           | **review: the Maven build produces no test jar, so revision 1's instruction was unimplementable**                                                |
| **E7** | **Plan 0006 is a hard prerequisite; this plan is not parallel with anything**                                  | **review: the stack cannot start without plan 0006's `MockSyncService`**                                                                         |
| **E8** | **The Limitations section must state the at-most-once, non-deduplicable, non-correlatable delivery semantics** | **review: the fixed six-field payload has no idempotency or correlation identifier, and operators must know before an incident, not during one** |

---

## Verification strategy

1. **Stack gate:** `make up` brings both services healthy with the provider loaded; `make reset`
   leaves nothing behind.
2. **Docs gate:** `markdownlint-cli2` exits 0. prek inspects only **git-tracked** files, so
   `git add` before linting; markdownlint defaults enforce MD013 at 80 columns.
3. **Content gate:** greps prove every required env var and warning is present verbatim, and
   explicitly **reject** the single-underscore spelling, which would otherwise pass a naive
   substring check.

Per invariant P1 every check names a command; per P3 record `BASE_SHA`.

---

## Execution strategy

| wave | todos  | depends on |
| ---- | ------ | ---------- |
| 1    | 1      | -          |
| 2    | 2      | 1          |
| 3    | 3      | 1, 2       |
| F    | F1, F2 | 1, 2, 3    |

Todo 3 comes last so the documented Makefile targets and env names match what exists.

---

## Todos

- [ ] 1. `podman-compose.yml` + `.env.example`: the local stack - expect Keycloak to boot with the provider loaded against the mock

  **References:** `LLD.pdf` section 6; plan 0006's `MockSyncService.main` (E1, E6); the
  user-confirmed spelling recorded by plan 0001.
  **Details:** Two services.
  - **keycloak**: `quay.io/keycloak/keycloak:26.7.0`, command `start-dev` (no `kc.sh build` -
    measured), `./target` bind-mounted into `/opt/keycloak/providers`, plus
    `KC_BOOTSTRAP_ADMIN_USERNAME`/`PASSWORD`, `KC_HOSTNAME_STRICT=false`, `KC_HTTP_ENABLED=true`,
    and the five SPI variables in the verbatim pinned spelling:
    `KC_SPI_AUTHENTICATOR__LOGIN_SYNC__SERVICE_ENDPOINT`,
    `..._SA_CLIENT_ID`, `..._SA_CLIENT_SECRET`, `..._SA_TOKEN_ENDPOINT`,
    `..._HTTP_TIMEOUT_MS` (write each in full, not abbreviated). All values come from `.env`.
  - **mock-syncservice**: an `eclipse-temurin` JRE image running plan 0006's `MockSyncService.main`
    **from a bind-mounted `./target/test-classes`** on the classpath (E6) - the build produces no
    test jar, so do not reference one. No Python, no second implementation (E1). Publish its port
    so `/__control` is reachable from the host for manual mode switching.
    Create `.env.example` with every variable and **placeholder** values only (E3). Do **not** edit
    `.gitignore`; plan 0002 already ignores `.env`. Add a comment stating the stack carries
    configuration only and MUST NOT provision realms, clients or roles.
    **Acceptance criteria:** `podman-compose -f podman-compose.yml config` exits 0.
    `grep -c 'KC_SPI_AUTHENTICATOR__LOGIN_SYNC__' podman-compose.yml` returns 5.
    `grep -c 'KC_SPI_AUTHENTICATOR_LOGIN_SYNC_' podman-compose.yml` returns 0 - the single-underscore
    form must be absent. `grep -c 'test-classes' podman-compose.yml` returns >= 1.
    `grep -rciE 'jar' podman-compose.yml | grep -qv 'test-jar'` - assert no test-jar reference.
    `git check-ignore -q .env` exits 0 (inherited from plan 0002).
    **QA happy:** `make up && make logs` shows both services healthy and the container log contains
    the `login-sync` provider; then `make down`. Evidence: `docs/evidence/0007-compose-up.log`.
    **QA failure:** run `grep -rnE '(password|secret)\s*=\s*\S+' .env.example` and assert every value
    matches a placeholder pattern such as `CHANGEME` or `<...>`; then assert
    `git check-ignore -q .env` exits 0, proving a real `.env` cannot be committed. Evidence: `docs/evidence/0007-compose-secrets.log`.
    **Commit:** `chore: add podman-compose local development stack`

- [ ] 2. `Makefile`: the local feedback loop - expect `make reset` to leave nothing behind

  **References:** `LLD.pdf` section 6; `scripts/test.sh` for containerised Maven.
  **Details:** Targets, each over existing tooling. Note `mvn` is **not** on PATH and the host JDK
  is 25 while the build targets 21, so every Maven invocation goes through `scripts/test.sh`.
  - `build` - `scripts/test.sh clean package`. Because the mock runs from `target/test-classes`,
    this target MUST also compile test classes; use `scripts/test.sh clean test-compile package`
    or an equivalent that guarantees `target/test-classes` exists.
  - `deploy` - rebuild, then restart the Keycloak service so the new jar loads.
  - `up` / `down` - bring the stack up and down.
  - `reset` - `down`, remove volumes, clean `target/`.
  - `logs` - follow both services' logs.
  - `test` - `scripts/test.sh clean verify`.
  - `fmt` - `scripts/test.sh spotless:apply`.
    Declare all targets `.PHONY`. Add no target that provisions a realm, client or role.
    **Acceptance criteria:** `make -n build deploy up down reset logs test fmt` prints a command for
    each of the eight targets and exits 0. `make build` produces
    `target/keycloak-login-sync-provider-0.1.0.jar` **and** a non-empty `target/test-classes/`.
    `grep -c '^\.PHONY' Makefile` >= 1. `grep -cE '^\s+mvn ' Makefile` returns 0 - all Maven calls go
    through `scripts/test.sh`.
    **QA happy:** `make build && make up && make logs` succeeds and shows the provider loaded; then
    `make down`. Evidence: `docs/evidence/0007-make-targets.log`.
    **QA failure:** `make reset`, then assert `podman ps -a --format '{{.Names}}' | grep -c login-sync`
    returns 0 and `test ! -d target` exits 0, proving the reset is complete. Evidence: `docs/evidence/0007-make-reset.log`.
    **Commit:** `chore: add Makefile for the local development loop`

- [ ] 3. `README.md` (create it from the absent baseline): project, Configuration, Deployment, and Limitations - expect all five variables verbatim and every hazard documented

  **References:** `docs/DECISIONS.md` and `docs/SYNC-CONTRACT.md` from plan 0001 (a hard
  prerequisite); the pinned spelling; decisions E4, E5, E8.
  **Details:** Create the root README with concise `## Build and test`, `## Layout`, `## Releasing`,
  and `## License` sections, then add the three sections below. No prior root README is an input.
  Wrap at 80 columns.
  - **`## Configuration`**: a table of the five operator-facing variables, each written
    **verbatim** in the pinned spelling with its `Config.Scope` key and default (endpoint, client
    id, client secret and token endpoint are required; timeout 5000). State that configuration is
    read through `Config.Scope`, that the **double** underscore between segments is required and the
    single-underscore form does not resolve, and that plain `start-dev` resolves these without a
    separate `kc.sh build`. Note that a missing required value degrades the provider to a no-op
    that permits logins, while a malformed value deliberately aborts provider startup.
  - **`## Deployment`** (E5): the `login-sync` execution MUST be a top-level **REQUIRED** execution
    in the browser flow, placed **after the forms subflow**, because the plugin needs an
    authenticated user. State that placing it earlier is **rejected by Keycloak** - because
    `requiresUser()` is true, Keycloak raises an error before the authenticator runs - so a
    misplacement is a hard failure, not a silent skip. State that the plugin needs a dedicated
    confidential client with a service account, that provisioning it is out of scope for this
    repository, and that the exact least-privilege role, claim and audience are integration
    decisions to be finalized before real deployment (cross-reference `docs/SYNC-CONTRACT.md`).
  - **`## Limitations`**: state that (a) the token cache is per-JVM, therefore **per-node in a
    cluster and shared across all realms** for one service endpoint; (b) **there is no retry and no
    buffering** - a single timeout or 5xx fails that login; (c) the sync is **fail-closed**: a
    receiver failure blocks the login, and this is an availability trade-off, **not a security
    control**; (d) only **LOGIN** is supported - REGISTER and UPDATE_PROFILE are out of scope;
    (e) `/api/sync-user` is provisional, which is why the version is `0.x`; and (f) **(E8)** the
    payload carries no event, request, correlation or idempotency identifier and its timestamp is
    second-truncated, so delivery is **at-most-once**, the receiver **cannot deduplicate** on
    payload equality, and Keycloak-side and receiver-side log lines **cannot be reliably
    correlated** during an incident.
    **Acceptance criteria:** `markdownlint-cli2 README.md` exits 0.
    `grep -c 'KC_SPI_AUTHENTICATOR__LOGIN_SYNC__' README.md` returns >= 5.
    `grep -c 'KC_SPI_AUTHENTICATOR_LOGIN_SYNC_' README.md` returns 0.
    Run `for s in 'after the forms subflow' 'fail-closed' 'not a security control' 'no retry' 'REQUIRED' 'at-most-once' 'cannot deduplicate' 'per-node'; do grep -qF "$s" README.md || echo "MISSING $s"; done`
    expecting **no output**. `grep -c 'token-strategy spike' README.md` returns 0.
    `grep -ciE 'REGISTER is supported|UPDATE_PROFILE is supported' README.md` returns 0.
    **QA happy:** `git add README.md && prek run markdownlint-cli2 --all-files` exits 0 and the
    string loop prints nothing. The `git add` is mandatory - prek inspects only tracked files, so an
    untracked broken README passes vacuously. Evidence: `docs/evidence/0007-readme-lint.log`.
    **QA failure:** delete the "fail-closed" sentence, re-run the loop, confirm it prints
    `MISSING fail-closed`; restore it, re-run, confirm no output and a clean
    `git status --porcelain README.md`. Evidence: `docs/evidence/0007-readme-grep-fail.log`.
    **Commit:** `docs: document configuration, deployment constraints and limitations`

## Final verification wave

- [ ] F1. Dev-stack audit (executable). Run, recording exit statuses: `make build` (expect 0, and `test -d target/test-classes` exits 0); `make up` (expect 0); `podman ps --format '{{.Names}} {{.Status}}'` showing both services `Up`; `podman logs <keycloak> | grep -c 'login-sync'` >= 1. Then drive the documented manual check: `curl -sf -X POST localhost:<mockport>/__control -d '{"mode":"http500"}'`, perform a browser login with `curl` following redirects, and assert an HTTP 500 with `loginSyncFailed` in the body; switch back with `{"mode":"ok"}` and assert the login yields a `code=` parameter. Finish with `make reset`, then `podman ps -a --format '{{.Names}}' | grep -c login-sync` expecting 0 and `test ! -d target` expecting 0. Evidence: `docs/evidence/0007-F1-devstack.md`.

- [ ] F2. Documentation and scope audit (executable). Run the eight-string presence loop from todo 3 expecting no output. Run `grep -c 'KC_SPI_AUTHENTICATOR__LOGIN_SYNC__' README.md` expecting >= 5 and `grep -c 'KC_SPI_AUTHENTICATOR_LOGIN_SYNC_' README.md` expecting 0. Run `find . -path ./.git -prune -o \( -iname 'Dockerfile*' -o -iname 'Containerfile*' -o -name '*.spec' -o -name '*.py' \) -print` expecting no output. Run `grep -rc 'class MockSyncService' src/ | awk -F: '{s+=$2} END {exit !(s==1)}'` expecting exit 0, proving exactly one mock implementation. With `BASE_SHA` per P3, `git diff --name-only $BASE_SHA..HEAD` equals exactly `{podman-compose.yml, .env.example, Makefile, README.md}` - in particular `.gitignore`, `pom.xml`, `.github/` and `src/` must not appear. Evidence: `docs/evidence/0007-F2-scope.md`.

## Commit strategy

Three commits: two `chore:` and one `docs:`. `pom.xml` untouched, so no release fires.
After F1 and F2 pass, restore the commented `push` trigger in `.github/workflows/release.yaml` by
uncommenting the `push` block for `main` and `pom.xml`; do that only after the plan is fully
verified so automatic push releases resume at handoff.

## Success criteria

1. `make up` gives a working local login against the mock; `make reset` leaves nothing behind.
2. The mock runs from mounted `target/test-classes`; no test jar is referenced.
3. All five environment variables are documented verbatim, and the single-underscore form is
   explicitly absent.
4. The execution-position constraint is documented as a hard failure, not a silent skip.
5. The fail-closed behaviour and at-most-once, non-deduplicable, non-correlatable delivery are
   stated.
6. No Dockerfile, no RPM spec, no Python, no second mock, no provisioning script, no `.gitignore`
   edit.
