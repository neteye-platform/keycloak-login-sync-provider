# 20 - scaffold reconciliation - Work Plan

```yaml
slug: 20-scaffold-reconciliation
revision: 2
wave: 0
prerequisites: []
parallel_with: [10-contract-reconciliation]
owns_files:
  - .github/workflows/release.yaml   # release-guard only, todo 1
  - pom.xml
  - .gitignore
  - src/main/resources/.gitkeep
  - README.md   # one stale line only; doc sections belong to plan 80
```

## TL;DR (For humans)

**What you'll get.** The already-merged scaffold brought into line with the LLD, without
rebuilding it: an enforceable guard on the live release workflow **before** anything touches
`pom.xml`, `provided` Jackson repinned to the version Keycloak 26.7.0 actually ships,
`src/main/resources/` created for plan 60, one stale README sentence removed, and `.omo/` plus
`.env` ignored.

**Why the release guard comes first.** `release.yaml` is already on the repo and fires on any push
to `main` whose changes touch `pom.xml`. Since `v0.1.0` has no tag yet, the very next such merge
would publish a release of a provider that contains **zero production Java**. Revision 1 tried to
manage this with a warning in a commit body; review correctly rejected that as unenforceable. The
workflow is now guarded by code, in its own commit, before the pom is touched.

**What it will NOT do.** No production Java. No new workflow. No change to the `repo-commons` pin,
action SHAs, licences, `SECURITY.md`, `scripts/test.sh` or any hygiene file. No version bump.

**Effort.** 3 implementation todos, plus 2 final verification todos.

**Risk.** Medium, concentrated entirely in todo 1. After the guard lands, the pom edit is routine.

---

## Scope

### In scope

- Guard `release.yaml` so it releases only when the pom **version itself** changes.
- Repin `provided` `jackson-databind` to `2.21.2`.
- Create `src/main/resources/.gitkeep`.
- Remove the false README sentence about `release.yaml` not existing.
- Add `.omo/` and `.env` to `.gitignore`.

### Out of scope (Must-NOT-Have)

- MUST NOT modify any workflow other than `release.yaml`, and in `release.yaml` MUST change only
  the release-condition guard - not the triggers, the action SHAs, the GitHub App token step, or
  the artifact glob.
- MUST NOT change the `repo-commons` pin `740d307f524f37043030b6786c4d6a08897bd615`.
- MUST NOT unpin any third-party GitHub Action.
- MUST NOT change `<version>` away from `0.1.0`.
- MUST NOT touch `.editorconfig`, `.semgrepignore`, `renovate.json`, `.pre-commit-config.yaml`,
  `LICENSE-APACHE`, `LICENSE-MIT`, `SECURITY.md`, `scripts/test.sh`.
- MUST NOT write any `.java`, `META-INF/services` content, or theme resource.
- MUST NOT add README Configuration or Limitations sections - plan 80 owns those.
- MUST NOT add `resilience4j`, an external HTTP client, an external JSON library, or
  `keycloak-admin-client`.
- MUST NOT remove Mockito.

---

## Key decisions

| id | decision | rationale |
|----|----------|-----------|
| S0 | **Guard `release.yaml` before touching `pom.xml`** | a commit-body warning cannot stop a workflow; only code can. Publishing `v0.1.0` from an empty provider is irreversible in practice |
| R3 | `provided` Jackson pinned to `2.21.2` | Keycloak 26.7.0 ships 2.21.2 via Quarkus 3.33.2.1; compiling against 2.22.1 can pass CI and fail at runtime with `NoSuchMethodError` |
| S1 | Keep Mockito `5.12.0`, test scope | already resolves against JUnit Jupiter 6.1.2; plans 60/70 need it |
| S2 | Keep explicit `${keycloak.version}` on all three Keycloak artifacts | `keycloak-spi-bom` manages only `keycloak-core` and `keycloak-server-spi` |
| S3 | `.gitignore` gains `.omo/` and `.env`, and is owned **solely** by this plan | plan 80 needed `.env` ignored; centralising ownership prevents a two-plan conflict |
| S4 | The Jackson rationale is inlined here, not cross-referenced to plan 10 | plan 10 runs in parallel, so its output cannot be a prerequisite of this plan |

---

## Verification strategy

1. **Release-guard gate:** `actionlint` passes and the guard's condition is asserted by grep plus
   a dry-run reasoning check on both the version-changed and version-unchanged cases.
2. **Build gate:** `scripts/test.sh clean verify` exits 0 (`mvn` is not on PATH; host JDK is 25).
3. **Dependency gate:** `dependency:tree` shows Jackson 2.21.2 and no `compile`-scope third-party.
4. **Non-regression gate:** `git diff --name-only $BASE_SHA..HEAD` lists only owned files.
5. **Lint gate:** `prek run --all-files` exits 0.

Record `BASE_SHA=$(git rev-parse HEAD)` before the first commit (portfolio invariant P3). Evidence
lives under the gitignored `.omo/evidence/` and is excluded from tracked-file audits (P2).

---

## Execution strategy

| wave | todos | depends on |
|------|-------|-----------|
| 1 | 1 | - |
| 2 | 2, 3 | 1 |
| F | F1, F2 | 1, 2, 3 |

Todo 1 is a hard gate: **no commit touching `pom.xml` may be created or merged before it lands.**

---

## Todos

- [ ] 1. `.github/workflows/release.yaml`: guard the release so it fires only on an actual version change - expect a pom-touching commit with an unchanged version to release nothing

  **References:** the existing `.github/workflows/release.yaml` (89 lines), which triggers on
  `push` to `main` with `paths: [pom.xml]` plus `workflow_dispatch`, reads the version via
  `mvn -q -B help:evaluate -Dexpression=project.version -DforceStdout`, computes `tag=v${version}`,
  and skips only when `git ls-remote --exit-code --tags` already finds that tag. Decision S0.
  **The hazard:** `v0.1.0` has no tag, so the next merge to `main` touching `pom.xml` for **any**
  reason publishes a release of a provider containing zero production Java.
  **Details:** Add a guard step that determines whether **the `<version>` element itself changed**
  in the pushed commit range, and make the build/tag/release steps conditional on it. Implement by
  comparing the project version at `HEAD` against the version at the previous commit - for example
  `git show HEAD~1:pom.xml` into a temporary file, evaluate both, and set an output
  `version_changed=true|false`; when the previous pom is unavailable (initial commit) treat it as
  unchanged and skip. Keep the existing tag-exists skip as a second, independent guard - defence in
  depth, since either alone is insufficient. Change **nothing else**: same triggers, same pinned
  action SHAs, same `create-github-app-token` step, same
  `files: target/keycloak-login-sync-provider-*.jar`.
  **Acceptance criteria:** `actionlint .github/workflows/*.yaml` exits 0.
  `grep -c 'version_changed' .github/workflows/release.yaml` returns at least 2 (the output and at
  least one `if:` consumer). `grep -c 'create-github-app-token' .github/workflows/release.yaml`
  still returns 1. `grep -c 'keycloak-login-sync-provider-\*.jar' .github/workflows/release.yaml`
  still returns 1. `git diff --name-only $BASE_SHA..HEAD` lists only
  `.github/workflows/release.yaml`.
  **QA happy:** `actionlint .github/workflows/*.yaml` exits 0 and all four greps return their
  expected counts. Evidence: `.omo/evidence/20-release-guard-actionlint.log`.
  **QA failure:** in a scratch clone under `/tmp`, create two commits - one that edits a pom
  comment without changing `<version>`, and one that changes `<version>` to `0.1.1` - and run the
  guard's version-comparison shell logic against each. Assert it prints `version_changed=false`
  for the first and `version_changed=true` for the second. This proves the guard discriminates
  rather than always allowing. Discard the scratch clone and confirm the real repo's
  `git status --porcelain` is clean. Evidence: `.omo/evidence/20-release-guard-discriminates.log`.
  **Commit:** `ci: release only when the project version actually changes`

- [ ] 2. `pom.xml` + `src/main/resources/.gitkeep`: repin provided Jackson to 2.21.2 - expect a green build with Jackson resolving to 2.21.2

  **References:** current `pom.xml` property `<jackson.version>2.22.1</jackson.version>`. Keycloak
  26.7.0 depends on Quarkus 3.33.2.1, whose BOM pins `jackson-databind` and `jackson-core` at
  `2.21.2` and `jackson-annotations` at `2.21`. Decisions R3 and S4 - the rationale is stated here
  in full precisely so this todo has no cross-plan reference.
  **Prerequisite:** todo 1 MUST have landed. Do not create this commit otherwise.
  **Details:** Change **only** the `<jackson.version>` property value from `2.22.1` to `2.21.2`.
  Keep the `provided` scope, add no further Jackson artifacts, and change no other property -
  `keycloak.version` stays `26.7.0`, `java.version` `21`, `junit.version` `6.1.2`,
  `testcontainers.version` `1.21.4`, `mockito.version` `5.12.0`, `spotless.version` `3.9.0`. Leave
  `<version>0.1.0</version>` untouched. Keep the explicit `${keycloak.version}` on
  `keycloak-server-spi`, `keycloak-server-spi-private` and `keycloak-services`. Create
  `src/main/resources/` with a zero-byte `.gitkeep`.
  **Commit body MUST state** that todo 1's guard is in place and that this commit does not change
  `<version>`, so no release fires.
  **Acceptance criteria:** `scripts/test.sh clean verify` exits 0.
  `scripts/test.sh -q dependency:tree | grep -c 'jackson-databind:jar:2.21.2'` returns at least 1.
  `scripts/test.sh -q dependency:tree | grep -cE ':compile$' | grep -qv keycloak` - assert no
  `compile`-scope third-party dependency appears.
  `scripts/test.sh -q help:evaluate -Dexpression=project.version -DforceStdout` prints `0.1.0`.
  `grep -c '2.22.1' pom.xml` returns 0. `test -f src/main/resources/.gitkeep` exits 0.
  **QA happy:** `scripts/test.sh clean verify` exits 0 and the dependency-tree grep confirms
  2.21.2. Evidence: `.omo/evidence/20-pom-verify.log`.
  **QA failure:** set `<jackson.version>` to `9.9.9`; `scripts/test.sh -q dependency:tree` must
  fail to resolve the artifact; restore `2.21.2`, re-run, confirm success and a clean
  `git status --porcelain pom.xml`. This proves the property genuinely drives resolution.
  Evidence: `.omo/evidence/20-pom-badversion.log`.
  **Commit:** `chore: pin provided Jackson to the version Keycloak 26.7.0 ships`

- [ ] 3. `README.md` + `.gitignore`: remove the stale release note and ignore `.omo/` and `.env` - expect markdownlint clean and `.omo/` untracked

  **References:** `README.md` section `## Releasing` ends with a sentence stating the release
  workflow "is not in the repo yet. It is added in Task 5 of the bootstrap plan." That is false:
  `release.yaml` was committed as `f7dfb9d`. Current `.gitignore` holds only `target/`, `bin/`,
  `.project`, `.classpath`, `.settings/`. Decision S3.
  **Details:** In `README.md`, remove **only** that stale sentence and its orphaned paragraph
  break. Do not add a Configuration or Limitations section, do not touch `## Build and test`,
  `## Layout` or `## License`, and leave the "Configuration and limitations are documented once
  the token-strategy spike concludes" line for plan 80 to replace. Wrap prose at 80 columns
  (markdownlint defaults, MD013).
  In `.gitignore`, append `.omo/` and `.env`. Do **not** ignore `N4-LLD-2.pdf` - whether the LLD
  belongs in the repo is the owner's decision, and silently hiding it would make that choice by
  default. Do not reorder or reformat existing entries.
  **Acceptance criteria:** `grep -c 'not in the repo yet' README.md` returns 0.
  `grep -c 'KC_SPI_' README.md` still returns 0. `grep -c '^## ' README.md` returns 4.
  `markdownlint-cli2 README.md` exits 0. `git check-ignore -q .omo/plans/00-login-sync-index.md`
  exits 0. `git check-ignore -q .env` exits 0.
  `git status --porcelain | grep -c '^?? \.omo'` returns 0.
  `git diff $BASE_SHA..HEAD -- .gitignore | grep -c '^-[^-]'` returns 0, proving append-only.
  **QA happy:** `git add README.md .gitignore && prek run --all-files` exits 0 and every grep
  above returns its expected value. Evidence: `.omo/evidence/20-readme-gitignore.log`.
  **QA failure:** append a 120-character unwrapped line to `README.md`; `markdownlint-cli2 README.md`
  must fail on MD013; revert, re-run, confirm exit 0 and a clean `git status --porcelain README.md`.
  Evidence: `.omo/evidence/20-readme-lint-fail.log`.
  **Commit:** `chore: drop stale release note and ignore planning and env files`

## Final verification wave

- [ ] F1. Reconciliation audit (executable). Run and record exit statuses for: `scripts/test.sh clean verify` (expect 0); `prek run --all-files` (expect 0); `actionlint .github/workflows/*.yaml` (expect 0); `scripts/test.sh -q dependency:tree | grep -c 'jackson-databind:jar:2.21.2'` (expect >=1); `scripts/test.sh -q help:evaluate -Dexpression=project.version -DforceStdout` (expect `0.1.0`); `test -f src/main/resources/.gitkeep` (expect 0); `grep -c 'version_changed' .github/workflows/release.yaml` (expect >=2). Evidence: `.omo/evidence/20-F1-reconciliation.md`.

- [ ] F2. Non-regression audit (executable). With `BASE_SHA` recorded per P3, run `git diff --name-only $BASE_SHA..HEAD` and assert the output set is exactly `{.github/workflows/release.yaml, pom.xml, .gitignore, src/main/resources/.gitkeep, README.md}`. Run `git diff --name-only $BASE_SHA..HEAD -- .github/workflows/build.yaml .github/workflows/common-cron-checks.yaml .github/workflows/common-manual-checks.yaml .github/workflows/common-pull-request-checks.yaml scripts/ SECURITY.md LICENSE-APACHE LICENSE-MIT .editorconfig .semgrepignore renovate.json .pre-commit-config.yaml` expecting empty output. Run `grep -c '740d307f524f37043030b6786c4d6a08897bd615' .github/workflows/common-pull-request-checks.yaml` expecting 1. Run `find src/main/java src/test/java -name '*.java' | wc -l` expecting 0. Evidence: `.omo/evidence/20-F2-nonregression.md`.

## Commit strategy

Three commits, in strict order. Todo 1's release guard MUST land before todo 2's `pom.xml` change
is created or merged; do not squash them. Each commit is independently revertable.

## Success criteria

1. A merge to `main` touching `pom.xml` without a version change publishes **no** release, proven
   by the discrimination test.
2. `scripts/test.sh clean verify`, `prek run --all-files` and `actionlint` all exit 0.
3. Jackson resolves to 2.21.2 at `provided` scope; no `compile`-scope third-party dependency.
4. Version is still `0.1.0`; only `release.yaml` changed among workflows.
5. `.omo/` and `.env` are ignored; `N4-LLD-2.pdf` is left visibly untracked as an owner decision.
