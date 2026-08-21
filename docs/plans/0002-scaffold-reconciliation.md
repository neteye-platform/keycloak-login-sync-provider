# 0002 - scaffold reconciliation - Completed Baseline Record

```yaml
slug: 0002-scaffold-reconciliation
revision: 7
status: completed-baseline
wave: 0
prerequisites: []
parallel_with: [0001-contract-reconciliation]
owns_files:
  - .github/workflows/release.yaml # completed immutable POM-version release guard
  - pom.xml # completed provided Jackson pin
  - .gitignore # completed append-only ignore rules
```

## TL;DR (For Humans)

**What you have.** A completed Maven-skeleton reconciliation: an immutable POM-version release
guard, provided Jackson aligned with Keycloak 26.7.0, and generated/local state ignored without
creating runtime content.

**Why it remains here.** Subsequent plans depend on these baseline constraints. This record
preserves them and supplies repeatable re-audits; it does not schedule their implementation again.

**What it will NOT do.** No Java, resources, dev stack, runtime behavior, root `README.md`, or
baseline-file edit. `docs/plans/README.md` remains the shared, unnumbered portfolio guide.

**Effort.** Two re-audit todos, plus two final verification todos.

---

## Scope

### In Scope

- Re-audit the existing `release.yaml` immutable POM-version release behavior.
- Re-audit the provided `jackson-databind` 2.21.2 pin.
- Re-audit the append-only ignores for `docs/evidence/` and `.env`.

### Out Of Scope (Must-NOT-Have)

- MUST NOT modify the release workflow, `pom.xml`, `.gitignore`, or another baseline file.
- MUST NOT modify workflow triggers, pinned action revisions, artifact naming, or token
  permissions.
- MUST NOT create `src/main/resources`, Java, runtime resources, a dev stack, or a root README.
- MUST NOT modify `docs/plans/README.md`, source `.gitkeep` files, `scripts/test.sh`, or unrelated
  hygiene configuration.

---

## Binding Baseline Facts

| id  | fact                                                                                                                                                                           | rationale                                                                           |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------- |
| S0  | Every workflow run resolves the checked-out POM version's `v<version>` Release; an existing Release is a no-op, while an absent Release builds the jar and creates the Release | the user-approved release rule is that every POM version needs a Release            |
| S1  | Only when the Release is absent, the workflow retains an existing tag and creates the tag only when it is absent                                                               | creating a missing Release must not replace an existing tag                         |
| R3  | Provided Jackson is pinned to 2.21.2                                                                                                                                           | Keycloak 26.7.0 supplies that version; a newer compile-time API can fail at runtime |
| S3  | `docs/evidence/` and `.env` are ignored append-only                                                                                                                            | generated evidence and local configuration must not enter tracked change sets       |

---

## Verification Strategy

1. **Immutable Release guard:** `actionlint .github/workflows/*.yaml` exits 0. The workflow
   resolves the checked-out POM version with Maven and reads the corresponding Release. An existing
   Release is a no-op. When it is absent, the workflow builds the expected jar, retains an existing
   tag or creates an absent one, and creates the Release.
2. **Build gate:** `scripts/test.sh clean verify` exits 0.
3. **Dependency and ignore gate:** Maven resolves provided Jackson 2.21.2, and both local paths
   are ignored.
4. **Boundary gate:** the root README, Java implementation, resources, and dev stack remain absent.

Write audit output under ignored `docs/evidence/`; do not create a baseline implementation commit.

---

## Execution Strategy

| wave | todos  | depends on         |
| ---- | ------ | ------------------ |
| A    | 1, 2   | completed baseline |
| F    | F1, F2 | 1, 2               |

Both todos inspect the existing baseline only and may run independently.

---

## Todos

- [ ] 1. `.github/workflows/release.yaml`: re-audit immutable POM-version release behavior - expect an existing Release to be a no-op and an absent Release to be created

  **References:** the commented `pom.xml` push trigger, `workflow_dispatch`, Maven
  `help:evaluate`, remote tag lookup, and the Release action.
  **Details:** Verify that the workflow resolves the checked-out project version with Maven into a
  `v<version>` tag, then uses `gh release view` with `github.token` to record only whether that
  Release exists. When it exists, Build, App-token generation, tag handling, and the Release action
  are skipped. When absent, the workflow runs `mvn -B clean package`, asserts the expected jar,
  generates the App token, and checks the tag only before pushing it when absent. An existing tag
  remains untouched, then the Release action creates the Release.
  **Acceptance criteria:** `actionlint .github/workflows/*.yaml` exits 0.
  `rg -q 'help:evaluate' .github/workflows/release.yaml` and
  `rg -q 'mvn -B clean package' .github/workflows/release.yaml` each exit 0.
  `rg -q 'gh release view "\$tag" --repo "\$GITHUB_REPOSITORY"' .github/workflows/release.yaml`
  exits 0. `test "$(rg -c 'gh release view' .github/workflows/release.yaml)" -eq 1` exits 0.
  `rg -q "steps.release.outputs.release_exists == 'false'" .github/workflows/release.yaml` exits 0.
  `! rg -q 'github.event.before|gh api|overwrite_files|needs_|eligible|complete' \
.github/workflows/release.yaml` exits 0.
  **QA happy:** record the command results without editing the workflow. Evidence:
  `docs/evidence/0002-release-upsert-audit.log`.

- [ ] 2. `pom.xml` + `.gitignore`: re-audit the dependency and ignore baselines - expect the current skeleton to remain clean

  **References:** Keycloak 26.7.0's managed dependency set and binding facts R3 and S3.
  **Details:** Verify `<jackson.version>2.21.2</jackson.version>` remains the sole Jackson version
  and `jackson-databind` remains `provided`. Verify `docs/evidence/` and `.env` remain appended
  ignores. Do not change the pom, ignores, or the absent root README.
  **Acceptance criteria:** `scripts/test.sh clean verify` exits 0.
  `scripts/test.sh -q dependency:tree | grep -c 'jackson-databind:jar:2.21.2'` returns at least 1.
  `grep -c '<jackson.version>2.21.2</jackson.version>' pom.xml` returns 1.
  `git check-ignore -q docs/evidence/probe .env` exits 0.
  **QA happy:** record the command results without editing the baseline. Evidence:
  `docs/evidence/0002-dependency-and-ignore-audit.log`.

## Final Verification Wave

- [ ] F1. Baseline re-audit (executable). Run the release, build, dependency, and ignore acceptance
      commands from todos 1 and 2, each expecting its stated result. This verifies the POM-version
      no-op behavior for an existing Release, and conditional build, tag creation, and Release creation
      when absent. Evidence:
      `docs/evidence/0002-F1-baseline-audit.md`.

- [ ] F2. Scope audit (executable). Run `test ! -e README.md` and
      `test ! -d src/main/resources`, each expecting exit 0. Run
      `find src -name '*.java' -print` expecting no output. Evidence:
      `docs/evidence/0002-F2-scope.md`.

## Commit Strategy

No implementation commit. The reconciliation is already complete; audits write only ignored
evidence and must not modify the three baseline files.

## Success Criteria

1. Every release workflow run makes an existing `v<version>` Release a no-op; when absent, it builds
   the POM version's jar, retains an existing tag or creates an absent one, then creates the Release.
2. Provided Jackson remains at 2.21.2 and the Java 21 Maven gate remains green.
3. `docs/evidence/` and `.env` remain append-only ignores.
4. The baseline remains a Maven skeleton with no root README, Java, resource, dev-stack, or runtime
   file.
