# 40 - circuit breaker - Work Plan

```yaml
slug: 40-circuit-breaker
revision: 2
wave: 2
prerequisites: [30-config-and-payload]
parallel_with: []
owns_files:
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/CircuitBreaker.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/CircuitState.java
  - src/main/java/com/wuerthit/keycloak/authenticators/loginsync/Permit.java
  - src/test/java/com/wuerthit/keycloak/authenticators/loginsync/CircuitBreakerTest.java
```

## TL;DR (For humans)

**What you'll get.** A hand-written, thread-safe CLOSED / OPEN / HALF_OPEN circuit breaker whose
permits are **generation-bound and settled exactly once**, with a deterministic concurrent test
suite, so a syncservice outage degrades to "logins proceed unsynced" instead of becoming a
NetEye-wide login outage - and cannot strand itself.

**Why revision 2 changed the API.** Both reviewers independently found the same defect: revision 1
exposed `acquirePermit()` plus global `recordSuccess()`/`recordFailure()`. That design allows a
permit to be acquired and never settled (leaking the sole HALF_OPEN probe slot **forever**), and
allows a late outcome from an old breaker generation to mutate a newer one - a stale CLOSED
request could close a freshly OPEN circuit. The permit is now an object carrying its generation,
settled exactly once via `complete(...)`, with a third neutral outcome for cases that must release
the slot without recording a sample.

**What it will NOT do.** No HTTP, no JSON, no Keycloak API. The breaker never performs I/O and
never queues, replays or persists anything.

**Effort.** 2 implementation todos, plus 2 final verification todos.

**Risk.** Medium. Most concurrency-sensitive class in the project; mitigated structurally by a
single `AtomicReference`, generation-bound permits, and a permit API that makes lock-around-I/O
impossible to express.

---

## Scope

### In scope

- `CircuitState`: state enum plus the immutable state snapshot record.
- `Permit`: a generation-bound, idempotently settleable handle.
- `CircuitBreaker`: the three-state machine, transitions, and logging.
- `CircuitBreakerTest`: full transition coverage plus barrier-synchronised concurrency tests.

### Out of scope (Must-NOT-Have)

- MUST NOT add `resilience4j` or any external circuit-breaker library.
- MUST NOT perform HTTP, file or network I/O.
- MUST NOT accept a callback or `Supplier` that the breaker itself invokes - that would place I/O
  inside the breaker's call stack and invite a lock around it.
- MUST NOT wrap I/O in `synchronized` or hold a lock across a caller's work.
- MUST NOT queue, buffer, replay or persist skipped events.
- MUST NOT use "fail-closed" or "fail-open" in any identifier (prose comments may).
- MUST NOT use `System.currentTimeMillis()` for cooldown timing.
- MUST NOT spread state across more than one atomic.
- MUST NOT log individual skipped syncs at WARN.
- MUST NOT depend on `SyncClient`, `ServiceAccountTokenProvider` or any Keycloak SPI type.

---

## Key decisions

| id      | decision                                                                                                                                            | rationale                                                                                                                                                                                                        |
| ------- | --------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| B1      | All state in ONE `AtomicReference` to an immutable record, mutated with `updateAndGet`                                                              | separate atomics can be read mid-update and yield an impossible combination                                                                                                                                      |
| B2      | `System.nanoTime()` via an injected time source                                                                                                     | monotonic; `currentTimeMillis()` jumps under NTP correction and can strand the breaker OPEN or release it early                                                                                                  |
| B3      | Count-based window of the last `windowSize` outcomes                                                                                                | matches LLD 3.2's "sliding window: 20 requests"                                                                                                                                                                  |
| B4      | Error-rate trigger evaluated only on a **full** window                                                                                              | without it the first failure is 1/1 = 100% and trips instantly                                                                                                                                                   |
| B5      | Exactly one settlement per **logical sync**                                                                                                         | there is no retry, so this is trivially true today; stating it prevents a future change double-counting                                                                                                          |
| B6      | HALF_OPEN admits exactly one trial via CAS; losers take the OPEN path                                                                               | a burst at cooldown expiry must not all hammer a still-broken receiver                                                                                                                                           |
| B7      | WARN only on the transition into OPEN with marker `LOGIN_SYNC_CIRCUIT_OPEN`; skips at DEBUG                                                         | user instruction deviating from LLD 3.2 - a per-skip WARN floods the log during the outage it describes                                                                                                          |
| **B8**  | **`Permit` carries a generation and is settled exactly once via `complete(Outcome)`**                                                               | **review: global record methods let a stale outcome mutate a newer generation, and an unsettled permit leaks the HALF_OPEN slot forever**                                                                        |
| **B9**  | **Settlement outcomes are `SUCCESS`, `FAILURE` and `ABANDONED`**                                                                                    | **`ABANDONED` releases the slot without recording a sample - required for bulkhead saturation, which must never count as a receiver failure (it would open the breaker on a healthy receiver under local load)** |
| **B10** | **Settlement is idempotent; a second `complete()` on the same permit is a no-op, and a settlement whose generation no longer matches is discarded** | **prevents both double-counting and late/stale mutation of a newer breaker generation**                                                                                                                          |

---

## Verification strategy

**TDD** - tests before implementation. The invariants are the specification, and writing them
first is the only way to know the CAS, the generation guard and the settlement path hold them.

Concurrency tests use a `CyclicBarrier` or `CountDownLatch` so all threads race at the exact
transition point; review found revision 1's test could pass without ever creating a real race.
Anti-vacuity is proven by a **deterministic faulty fixture** - a deliberately broken subclass or
strategy compiled into the test sources - rather than by manually editing production source, so
the check is repeatable and leaves the tree clean.

Per portfolio invariant P1 every check names a command and an expected result; per P3, record
`BASE_SHA` before the first commit.

---

## Execution strategy

| wave | todos  | depends on |
| ---- | ------ | ---------- |
| 1    | 1      | -          |
| 2    | 2      | 1          |
| F    | F1, F2 | 1, 2       |

Todo 1 writes the failing specification; todo 2 makes it pass. Deliberately separate commits so a
reviewer sees the specification before the implementation.

---

## Todos

- [ ] 1. `CircuitState.java` + `Permit.java` + `CircuitBreakerTest.java`: write the failing specification first - expect a red suite failing on assertions, not compilation

  **References:** `N4-LLD-2.pdf` section 3.2; `LoginSyncConstants` from plan 30 for defaults and
  `CIRCUIT_OPEN_LOG_MARKER`; decisions B1-B10.
  **Details:** Create `CircuitState` (enum `CLOSED`, `OPEN`, `HALF_OPEN`) plus the immutable state
  snapshot record: state, consecutive-failure count, count-based ring buffer of the last
  `windowSize` outcomes, `openedAtNanos`, half-open permit flag, and a monotonically increasing
  **generation** counter incremented on every state transition (B8).
  Create `Permit` exposing `boolean isTrial()`, `boolean allowsSync()`, and
  `void complete(Outcome)` where `Outcome` is `SUCCESS`, `FAILURE` or `ABANDONED` (B9). `Permit`
  holds the generation it was issued under and settles idempotently (B10).
  Then write `CircuitBreakerTest` against that API so the shape is fixed before implementation.
  Required cases:
  - CLOSED to OPEN at exactly `failureThreshold` consecutive failures (asserted at the threshold,
    not one over).
  - CLOSED to OPEN when the windowed error rate reaches `errorRatePct` **with a full window**.
  - **No** trip on a single failure with `windowSize = 20` (the B4 guard).
  - A success resets the consecutive counter while the window keeps sliding.
  - OPEN issues a non-sync permit; the login is permitted.
  - OPEN to HALF_OPEN after `cooldownSeconds`, driven by an **injected** time source - no sleeping.
  - HALF_OPEN to CLOSED on trial `SUCCESS`, counters reset.
  - HALF_OPEN to OPEN on trial `FAILURE`, cooldown restarted.
  - **`ABANDONED` on a trial permit releases the half-open slot and records no sample**, so a
    subsequent caller can obtain a new trial permit. Assert the window and consecutive counter are
    unchanged.
  - **`ABANDONED` in CLOSED records no sample** - assert `windowSize` failures' worth of
    `ABANDONED` settlements never open the breaker.
  - **Idempotent settlement:** calling `complete()` twice records one outcome.
  - **Stale-generation settlement is discarded:** acquire a permit while CLOSED, force the breaker
    to OPEN via other failures, then settle the stale permit with `SUCCESS`; assert the breaker
    remains OPEN.
  - **Barrier-synchronised 64-thread invariant:** with the breaker in HALF_OPEN, 64 threads block
    on a `CyclicBarrier` and are released simultaneously to call `acquirePermit()`; assert
    **exactly one** trial permit is issued, the other 63 receive non-sync permits, and no thread
    observes an impossible state.
  - **Permit-leak regression:** acquire the sole trial permit, have the caller throw without
    settling, and assert that a `finally`-based settlement in the test harness still releases the
    slot - documenting that consumers must settle in `finally`.
    **Acceptance criteria:** the test class compiles against `CircuitState`, `Permit` and a minimal
    `CircuitBreaker` stub, and `scripts/test.sh test -Dtest=CircuitBreakerTest` **fails** with
    assertion failures. Assert the failure output contains `AssertionError` or
    `AssertionFailedError` and contains neither `NullPointerException` nor a compilation error - a
    red suite must be red for the intended reason.
    **QA happy:** the run fails with assertion errors on the transition tests. Evidence: `.omo/evidence/40-breaker-red.log`.
    **QA failure:** `grep -cE 'NullPointerException|COMPILATION ERROR' .omo/evidence/40-breaker-red.log`
    returns 0, proving the red is genuine. Evidence: `.omo/evidence/40-breaker-red-reason.log`.
    **Commit:** `test: specify circuit breaker state machine and concurrency invariants`

- [ ] 2. `CircuitBreaker.java`: implement until the specification passes - expect zero flakes over 20 runs and a faulty fixture that provably fails

  **References:** todo 1's tests; decisions B1-B10; `LoginSyncConstants.CIRCUIT_OPEN_LOG_MARKER`.
  **Details:** Implement exactly the specification:
  - One `AtomicReference` to the immutable state record, mutated only via `updateAndGet` (B1),
    with the generation incremented on every transition.
  - `System.nanoTime()` via the injected time source for all cooldown arithmetic (B2).
  - Count-based window (B3); error rate evaluated only on a full window (B4).
  - CLOSED to OPEN when consecutive failures reach `failureThreshold` **or** the windowed error
    rate reaches `errorRatePct` on a full window.
  - HALF_OPEN admits exactly one trial by CAS on the permit flag; losers get a non-sync permit
    whose login is permitted (B6). The trial is a probe: whatever its outcome, that user's login
    is permitted.
  - **`acquirePermit()` returns a `Permit`; the caller performs I/O and MUST call
    `permit.complete(outcome)` in a `finally`.** Settlement is idempotent (B10); a settlement whose
    generation no longer matches the current generation is discarded without effect.
  - **`ABANDONED` releases the permit - including the half-open slot - without recording a sample
    or touching the window or the consecutive counter** (B9).
  - The class MUST NOT accept a callback or `Supplier` it invokes itself.
  - Logging (B7): a single WARN carrying `LOGIN_SYNC_CIRCUIT_OPEN` on the transition **into** OPEN
    and nowhere else; DEBUG for skipped syncs and for the recovery transition. Never log a
    payload, token, email or group path.
  - Class Javadoc MUST state: this is a per-JVM instance, therefore **per-node in a cluster and
    shared across all realms**, so a receiver outage is detected independently on every node and
    skipped-event loss is per-node; and **events skipped while OPEN are lost and never replayed** -
    permitting logins during an outage is an availability trade-off, not a security control.
  - Also add to the test sources a deterministic **faulty fixture**: a variant whose half-open CAS
    is replaced by an unconditional grant. It exists solely so the anti-vacuity assertion is
    repeatable and leaves the tree clean.
    **Acceptance criteria:** `scripts/test.sh test -Dtest=CircuitBreakerTest` exits 0 with every
    todo-1 case passing. `grep -rniE 'fail.?closed|fail.?open' src/main/java` yields matches only
    inside comments, never identifiers. `grep -c 'currentTimeMillis' CircuitBreaker.java` returns 0.
    `grep -c 'synchronized' CircuitBreaker.java` returns 0. Spotless AOSP passes.
    **QA happy:** `scripts/test.sh test -Dtest=CircuitBreakerTest` exits 0. Evidence: `.omo/evidence/40-breaker-green.log`.
    **QA failure:** (a) run the barrier-synchronised 64-thread test 20 consecutive times in a loop;
    all 20 must pass with zero flakes. (b) Point the invariant test at the faulty fixture and assert
    it **fails**, proving the assertion detects a broken CAS; the fixture is committed test code, so
    no production source is edited and `git status --porcelain` stays clean. Evidence: `.omo/evidence/40-breaker-concurrency.log`.
    **Commit:** `feat: add thread-safe circuit breaker with generation-bound permits`

## Final verification wave

- [ ] F1. Concurrency and settlement audit (executable). Run `scripts/test.sh test -Dtest=CircuitBreakerTest` expecting exit 0. Run the 20-iteration loop of the barrier test expecting 20 passes. Run the faulty-fixture invariant test expecting failure. Assert by inspection plus `grep` that: exactly one field of `CircuitBreaker` is an `AtomicReference` and no other mutable field participates in a transition (`grep -cE 'private (volatile |static )?[A-Za-z<>]+ ' CircuitBreaker.java` reviewed against the state record); `grep -c 'nanoTime' CircuitBreaker.java` >= 1 and `currentTimeMillis` == 0; the `ABANDONED` no-sample tests and the stale-generation-discard test are present by name (`grep -c 'abandoned\|staleGeneration' CircuitBreakerTest.java` >= 3). Evidence: `.omo/evidence/40-F1-concurrency.md`.

- [ ] F2. Logging and scope audit (executable). Run `grep -c 'WARN\|warn(' CircuitBreaker.java` and assert exactly one WARN call site, and that `grep -B2 -A2 'warn(' CircuitBreaker.java` shows it on the transition into OPEN carrying `CIRCUIT_OPEN_LOG_MARKER`. Run `grep -c 'never replayed' CircuitBreaker.java` >= 1 and `grep -c 'per-node' CircuitBreaker.java` >= 1, proving the Javadoc statements exist. Run `grep -rc 'resilience4j' src/ pom.xml` expecting 0. Run `grep -cE 'HttpClient|Socket|Files\.' CircuitBreaker.java` expecting 0, proving no I/O. With `BASE_SHA` per P3, `git diff --name-only $BASE_SHA..HEAD` equals this plan's four owned files. Evidence: `.omo/evidence/40-F2-logging.md`.

## Commit strategy

Two commits: `test:` for the specification, then `feat:` for the implementation. Keep them
separate - the red-then-green pair is the evidence the tests are real. `pom.xml` untouched.

## Success criteria

1. Every LLD 3.2 transition is covered by a passing named test.
2. A permit is settled exactly once, on every path, and `ABANDONED` releases the half-open slot
   without recording a sample.
3. A late settlement from a superseded generation cannot mutate the current state.
4. The barrier-synchronised single-probe invariant holds over 20 consecutive runs, and provably
   fails against the faulty fixture.
5. No external resilience library, no I/O, no lock, no fail-closed/fail-open identifier.
6. WARN fires only on the transition into OPEN; per-node scope and permanent event loss are
   documented in the class.
