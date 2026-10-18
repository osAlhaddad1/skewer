# Skewer

**Proving, with a reproducible counterexample, that `@Transactional` Spring code on PostgreSQL can lose money
under concurrency, and measuring which fix actually holds.**

Skewer hammers a small Spring Boot banking service with concurrent transfers and withdrawals, records exactly
which version of each row every transaction read and wrote, and finds the dependency cycles that no
one-at-a-time execution could produce. Each failure comes out as a minimal counterexample: the transactions,
the versions they saw, the edges between them, and a drawing.

```
$ ./skewer demo --seed 42
run demo-42: naive-jpa @ default
  1018 committed transactions, edges {ww=1236, wr=1792, rw=1400}, isolation actually run {read committed=1018}
  INVARIANT conservation: €6,420.00 created from nothing
  INVARIANT customer-rule: 77 time(s) a joint customer's accounts summed below €0.00
  INVARIANT read-consistency: 2 report(s) mixed two database states
  CYCLES {G0=0, G1c=0, G-single=830, G2-item=496} {lost update=828, write skew=8, read skew=2}

  G-single (lost update)
    T2 read account 8 at €10,000.00. T1 then wrote €9,952.00, and T2 overwrote it with €10,044.00, computed
    from its stale €10,000.00. T1's update was lost: no serial order of T2 and T1 ends this way.
    T2 --rw--> T1   T2 read account 8 at the version by init; T1 wrote the next version, which T2 never saw
    T1 --ww--> T2   T2 overwrote T1's version of account 8
```

## Results

23 cells × 5 seeds, 8 s each, 32 clients on 20 accounts. Full report with every counterexample drawn:
[`docs/report/index.html`](docs/report/index.html) (GitHub Pages: serve `docs/`).

| Strategy | Read Committed | Repeatable Read | Serializable |
|---|---|---|---|
| `naive-jpa` (load, compute, save) | **breaks 5/5**: lost updates, write skew, read skew; median €5,603 created per run | **breaks 5/5**: write skew; 53% of ops fail (40001) | holds; 55% of ops fail (40001) |
| `atomic-update` (`UPDATE … WHERE balance ≥ :amt`) | **breaks 5/5**: write skew; deadlocks in 16% of attempts | **breaks 5/5**: write skew | holds; 51% of ops fail |
| `pessimistic` (`FOR UPDATE`, id order) | holds; **0 failed ops**, 177 ops/s | holds; 70% of ops fail (40001) | holds; 70% of ops fail |
| `pessimistic-unordered` | holds; deadlocks in 27% of attempts | holds; deadlocks + 40001 | holds; deadlocks + 40001 |
| `optimistic` (`@Version` + retry) | **breaks 5/5**: write skew | **breaks 5/5**: write skew | holds; 12% of ops fail after 10 attempts |
| `serializable-retry` | — | — | holds; 213 ops/s, 73% of attempts abort, 12% of ops fail after 10 attempts |

| Gotcha / follow-up | Result |
|---|---|
| `@Transactional(SERIALIZABLE)` reached by self-invocation | No transaction at all: `SHOW transaction_isolation` reports read committed, writes and commit record carry different database transaction ids, median **€28,785 created per run** |
| Retry loop inside the `@Transactional` method | Every retry fails with 25P02 "current transaction is aborted"; 55% of operations fail, none recovered |
| `OPTIMISTIC_FORCE_INCREMENT` on the row only read | Write skew closed: 0/10 runs at Read Committed and Repeatable Read |
| Report as two `findById` calls vs one statement | Read skew in 5/5 runs at Read Committed; 0/5 with one statement; 0/5 at Repeatable Read |

All 23 predictions from the project plan held; 14 held with something the prediction left out. Controls:
**30/30** negative-control runs (every strategy at Serializable) clean, **20/20** positive-control runs caught,
median first complete counterexample after **0.05 s**.

### Three findings that sharpen common assumptions

1. **`@Version` does not make an invariant safe.** Optimistic locking checks only the rows a transaction writes.
   A rule that spans two rows (two joint accounts summed) broke in 10/10 runs at Read Committed and Repeatable
   Read. Forcing a version increment on the row that was only read closed it in 10/10.
2. **Raising the isolation level can make locking code worse.** The same `SELECT … FOR UPDATE` code that had
   zero failed operations at Read Committed failed 70% of operations at Repeatable Read and Serializable: a
   `FOR UPDATE` on a row changed after the transaction's snapshot is a serialization failure, not a wait.
3. **"Serializable plus retry" is correct, not free.** At this contention 73% of attempts aborted and 12% of
   operations still failed after ten attempts. Ordered pessimistic locking at Read Committed was also correct,
   with no failed operations, at 177 ops/s against 213.

And two that surprised the plan itself: naive JPA code deadlocks too (15% of attempts: dirty checking flushes
updates in load order, so opposite-direction transfers lock rows in opposite order), and so does the
"safe" single-statement `UPDATE` (16%).


## Quick start

Needs Docker and Java 25.

```bash
./skewer demo
```

That starts Postgres 18 (`docker compose up -d`), builds once, runs naive JPA code under plain `@Transactional`
for ten seconds and prints the double-spend; `experiments/results/demo.html` draws it. On Windows use
`skewer.cmd demo`.

```bash
./skewer matrix
```

Runs every experiment (23 cells × 5 seeds, throughput baselines, then the controls; about 25 minutes on a
laptop) and writes [`docs/report/index.html`](docs/report/index.html). `--quick` does one short seed per cell.

| Command | Does |
|---|---|
| `./skewer demo [--seed N]` | The headline: naive-jpa at Postgres's default isolation |
| `./skewer run --strategy S --isolation I [--seed N] [--think-ms T] [--total-mode split\|single]` | One run, checked, history saved |
| `./skewer matrix [--quick] [--seeds N]` | All cells, then the HTML report |
| `./skewer controls [--negative-runs N] [--positive-runs N]` | End-to-end negative and positive controls |
| `./skewer check <history.jsonl>...` | Re-analyse saved histories offline |
| `./skewer report` | Rebuild the report from `experiments/results/matrix.json` |

Strategies: `naive-jpa`, `atomic-update`, `pessimistic`, `pessimistic-unordered`, `optimistic`,
`optimistic-force-increment`, `serializable-retry`, `gotcha-self-invocation`, `gotcha-retry-inside`.
Isolation: `default`, `read_committed`, `repeatable_read`, `serializable`.

## Why

`@Transactional` makes a method atomic, not isolated. Isolation, meaning what concurrent transactions can see of
each other, defaults to whatever the database does, and Postgres defaults to Read Committed. Code that loads an
entity, checks a balance and saves passes every unit test and loses updates under load.

| Level | What still slips through | How Postgres reacts |
|---|---|---|
| Read Committed (default) | Lost updates in read-then-write code, read skew, write skew | A blocked `UPDATE` waits, then re-checks its `WHERE` on the newest version |
| Repeatable Read | Write skew | Updating a row changed since the snapshot fails with 40001 |
| Serializable | Nothing | Aborts one transaction of a dangerous pattern with 40001; retry the whole transaction |

## How it works

1. **Six strategies, one interface.** The same four operations (transfer, withdraw, joint withdraw, total
   balance) written six ways, plus two Spring gotchas. Strategy and isolation are chosen per request; every
   strategy runs inside a real `@Transactional` proxy.
2. **Exact version tracking.** Every write sets `last_writer`; an `AFTER UPDATE` trigger records which version it
   replaced, from the row Postgres actually overwrote. Every read returns `last_writer`. A `txn_log` row inserted
   as the last statement exists if and only if the transaction committed, and records the isolation that
   actually ran (`SHOW transaction_isolation`) and the database transaction id.
3. **Two-layer checker.** Business invariants say money went wrong. A direct serialization graph (ww, wr, rw
   edges) says which transactions did it: Tarjan's algorithm, shortest cycles by breadth-first search, Adya's
   classes (G0, G1c, G-single, G2-item), banking labels (lost update, read skew, write skew).
4. **Validated before trusted.** Hand-built histories per class, 10,000 generated serial histories with zero
   false positives, stale-read mutations always caught, a JGraphT cross-check, integration tests per strategy,
   and end-to-end negative and positive controls.

Details: [docs/design.md](docs/design.md). Write-up: [docs/writeup.md](docs/writeup.md).

## Repository

```
skewer/
├── checker/       history model, version order, dependency graph, Tarjan, Adya classifier, invariants (no Spring)
├── bank-service/  Spring Boot 4 app: strategies, instrumentation, Flyway schema and trigger
├── workload/      seeded generator, virtual-thread HTTP clients, history recorder, HdrHistogram metrics
├── report/        hypothesis verdicts, static HTML report with SVG cycle drawings
├── experiments/   the skewer CLI, matrix definition and settings, saved failing histories
├── docs/          design, write-up, demo script, generated report
└── docker-compose.yml
```

Stack: Java 25 (virtual threads), Spring Boot 4.1, Hibernate 7, PostgreSQL 18, JUnit 5, jqwik, JGraphT (tests
only), Testcontainers, HdrHistogram.

```bash
./mvnw verify            # unit tests, the checker validation suite, Testcontainers integration tests
./mvnw verify -DskipITs  # without Docker
```

## Prior work

Skewer applies the method of [Elle](https://github.com/jepsen-io/elle) (Kingsbury and Alvaro) and the anomaly
catalogue of [Hermitage](https://github.com/ept/hermitage) (Kleppmann) inside an ordinary Spring application,
using Adya, Liskov and O'Neil's *Generalized Isolation Level Definitions* (ICDE 2000). Its own contribution is
the Spring-level instrumentation and measuring what each fix costs. It is not a general checker: item reads
only, one database, and a clean run is evidence, not proof.
