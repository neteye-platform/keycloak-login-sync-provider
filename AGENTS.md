# Repository Guidance

## Purpose And Current State

This repository will provide the Keycloak `login-sync` Authenticator. It is
currently a Maven skeleton: no Java implementation, runtime resources, local
dev stack, or root `README.md` exists.

## Build And Test

- Run Maven only through `scripts/test.sh`; it uses local Maven when
  available and otherwise a Java 21 Maven container.
- `scripts/test.sh clean verify` is the full unit and integration-test gate.
- Java 21 is required. Spotless AOSP runs during Maven `validate`; Surefire runs
  `*Test` and Failsafe runs `*IT` during `verify`.

## Releases

- The release workflow's `push` trigger remains commented until plan 0007
  restores it; `workflow_dispatch` is active.
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
