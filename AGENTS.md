# Repository Guidance

## Purpose And Current State

This repository provides the Keycloak `login-sync` Authenticator. The provider
is implemented under `src/main/java/`, with its `AuthenticatorFactory` service
file and `messages_en.properties` under `src/main/resources/`. Unit tests and
the Testcontainers `LoginSyncIT` live under `src/test/java/`. The local dev
stack (`podman-compose.yml`, `Makefile`, `.env.example`) and a root
`README.md` all exist, and the release `push` trigger is active.

## Build And Test

- Run Maven only through `scripts/test.sh`; it uses local Maven when
  available and otherwise a Java 21 Maven container.
- `scripts/test.sh clean verify` is the full unit and integration-test gate.
- Integration goals (`verify`, `integration-test`, `failsafe:integration-test`)
  require a reachable container socket on both the local-Maven and
  containerised paths; without one the script prints guidance and exits 1.
- Java 21 is required. Spotless AOSP runs during Maven `validate`; Surefire runs
  `*Test` and Failsafe runs `*IT` during `verify`.

## Releases

- The release workflow runs on `push` to `main`; `workflow_dispatch` is also
  active.
- Each release workflow run resolves the POM version's `v<version>` Release. An
  existing Release is a no-op. When absent, it builds the POM version's jar,
  retains an existing tag or creates an absent one, then creates the Release.
- Do not change release triggers, pinned action revisions, artifact naming, or
  token permissions without an explicit release-workflow change.

## Plans And Evidence

- [docs/plans/README.md](docs/plans/README.md) is the unnumbered portfolio
  landing page and shared planning guidance. Executable component plans are
  `0001` through `0007`.
- Ignored `docs/evidence/` stores command output and observed results from a
  verification run. It demonstrates that named checks were executed, but is
  not tracked authority.

## Scope And Secrets

- Never commit `.env`, credentials, access tokens, or generated evidence. Do
  not log secrets.
- Keep work within its plan's owned files. Do not add Java, resources,
  dev-stack files, or runtime behavior unless the approved implementation plan
  explicitly owns them.
- Before handing off, run `git diff --check`, relevant `scripts/test.sh` goals,
  `actionlint` for workflow edits, and the applicable `prek` checks.
