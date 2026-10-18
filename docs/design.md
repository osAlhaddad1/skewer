# Skewer design

How Skewer records what happened, how it decides whether that was serializable, and why each choice was made.
The project plan set the scope; this document records what was built and where the build departed from it.

## Two phases

```
 run phase                                            analysis phase (offline)
 ┌──────────┐  HTTP   ┌──────────────┐  JDBC  ┌──────────────┐     ┌──────────────┐     ┌─────────┐    ┌────────┐
 │ workload │ ──────▶ │ bank-service │ ─────▶ │ Postgres 18  │     │ history file │ ──▶ │ checker │ ─▶ │ report │
 │ 32 virt. │ ◀────── │ 9 strategies │        │ account      │ ──▶ │ (JSON lines) │     │ 2 layers│    │ HTML   │
 │ threads  │ results │ + instrument.│        │ txn_log      │     └──────────────┘     └─────────┘    └────────┘
 └──────────┘         └──────────────┘        │ write_audit  │            ▲
       │ read-only reports, client timings    └──────────────┘            │
       └──────────────────────────────────────────────────────────────────┘
```

The checker only reads history files, so every saved failing run can be re-analysed after a checker fix
(`./skewer check <file>`) without rerunning the workload.

| Module | Role | Depends on |
|---|---|---|
| `checker` | History model, version order, dependency graph, Tarjan, Adya classifier, invariants. Plain Java, no Spring. | Jackson |
| `bank-service` | Spring Boot 4 app: the strategies, instrumentation, admin endpoints for reset and export | checker (model only) |
| `workload` | Seeded generator, HTTP client on virtual threads, history assembly, HdrHistogram metrics | checker |
| `report` | Results model, hypothesis verdicts, static HTML with SVG cycle drawings | checker, workload |
| `experiments` | The `skewer` CLI: demo, run, matrix, controls, check, report. Embeds the bank service. | all |

## Instrumentation

```sql
account     (id, customer_id, joint, balance_cents, version, last_writer UUID)
txn_log     (txn_id, op_id, op, strategy, isolation, outcome, reads JSONB, deltas JSONB, committed_at, db_xid)
write_audit (seq, account_id, prev_writer, new_writer, old_balance, new_balance, db_xid)
AFTER UPDATE trigger on account → write_audit (OLD.last_writer → NEW.last_writer)
```

| Fact | How it is captured | Why it is trustworthy |
|---|---|---|
| Which version a write replaced | Trigger reads `OLD.last_writer` | It is the row Postgres actually overwrote, not what the application believed it read |
| Which version a read saw | Every read also returns `last_writer` | Versions are named by their writer, which is unique per transaction |
| That a transaction committed | `txn_log` row inserted as its last statement | The row exists if and only if the transaction committed; audit rows of aborted transactions roll back too |
| Which isolation level ran | `SHOW transaction_isolation` inside the transaction | The level that ran, not the one the annotation asked for |
| That an operation was atomic | `pg_current_xact_id()` on every audit row and log row | Writes and their commit record must share one database transaction id |
| Read-only reports | Read set returned in the response, recorded by the client | A read-only report leaves no `txn_log` row |
| Indeterminate operations | Client timeout, then look up `op_id` in `txn_log` | Jepsen's rule: a timed-out operation may or may not have committed |

**Departure from the plan: `db_xid`.** The plan's schema had no database transaction id. The first matrix pass
found G0 cycles under the self-invocation gotcha, which the classifier labelled "impossible on Postgres, suspect
Skewer". They were real: with no transaction, every statement auto-commits on its own, so two "transfers" can
interleave their writes. Recording `pg_current_xact_id()` lets the checker prove that (the `atomicity`
invariant) and explain the G0 instead of blaming itself.

**Departure: Postgres 18 `RETURNING old.*`.** The atomic-update strategy has no separate read; the version each
`UPDATE ... SET balance = balance - :amt` replaced comes back from `RETURNING old.last_writer`, so its read set
is exact rather than inferred.

### Design decisions

- **Explicit `last_writer`, not `xmin`.** `xmin` needs no application change, but maps to Postgres transaction
  ids rather than application transactions, and points at subtransaction ids under savepoints. Stretch goal.
- **Version order from `prev_writer`, never from `seq`.** Sequences are not rolled back on abort and are not
  handed out in commit order. `seq` only orders repeated writes by the same transaction and breaks display ties.
- **Same instrumentation for every strategy.** Throughput is also measured with the trigger and `txn_log`
  switched off, as a per-cell baseline.
- **The service is reached over HTTP.** The workload knows nothing of the strategies' code; the
  `@Transactional` boundaries are exactly the ones a real request goes through.
- **Quiesce before export and before reset.** A client that times out moves on while its transaction may still
  commit. The service counts in-flight operations; the workload waits for zero before exporting a history, and
  reset waits too, so no straggler can write into the next run.

## The checker

### Layer 1: invariants

| Invariant | Meaning |
|---|---|
| `atomicity` | Every operation's writes and its commit record belong to one database transaction |
| `conservation` | Final total equals initial total plus every committed transaction's claimed effect |
| `exactly-once` | Each account's final balance equals its initial balance plus committed deltas |
| `account-rule` | No committed version of a single account is below zero |
| `customer-rule` | No joint customer's accounts sum below zero, on final balances and on the running sum of committed effects in commit order |
| `read-consistency` | A report saw each committed transaction entirely or not at all |

The customer rule's running check uses commit order, which is only safe because of how the workload is built: a
credit a debit relied on (a `wr` dependency) was always committed before the debit read it, so commit order can
move credits earlier than a serial order would, never later. A correct history can only look better.

### Layer 2: the dependency graph

One node per committed transaction plus `init`, the writer of every account's first version.

| Edge | From | Meaning |
|---|---|---|
| `ww` | `write_audit` | T2 wrote the version right after T1's version of the same row |
| `wr` | read sets | T2 read the version T1 wrote |
| `rw` | read sets + version order | T1 read a version and T2 wrote the next one; T1 never saw T2's write |

Classification follows Elle's layering. Tarjan's algorithm (iterative, so 100,000 transactions cannot overflow
the stack) finds strongly connected components three times: over `ww` only (any cycle is **G0**), over `ww`+`wr`
(a cycle with a read dependency is **G1c**), and over all edges. In the last pass, every `rw` edge inside a
component lies on a cycle: a breadth-first search for the way back using only `ww`/`wr` edges gives a
**G-single** cycle; if there is none, the shortest way back with any edges gives **G2-item**. Cycles are
de-duplicated by their transaction set.

Banking labels on top:

- **lost update**: G-single whose `rw` reader also wrote that row (Hermitage's P4).
- **read skew**: G-single whose reader wrote nothing.
- **write skew**: G2-item of two transactions, two `rw` edges on different rows.

Postgres prevents G0 and G1c at every level, so finding one means Skewer is wrong, unless the atomicity
invariant shows the operation never ran as one transaction.

### Validation (the M3 gate)

| Test | What it proves |
|---|---|
| Hand-built histories | Exact class and exact cycle for G0, G1c, G-single (lost update, read skew), G2-item (write skew), clean histories, version forks, unknown versions, unlogged writers, broken atomicity |
| 10,000 generated serial histories (jqwik) | No false positives: one-at-a-time execution never produces a report |
| Stale-read mutation (2,000 histories) | Change one read-modify-write's read to the previous version: always flagged as a lost update |
| JGraphT cross-check (1,000 random graphs each) | Tarjan's components equal Kosaraju's; every reported cycle is a real cycle of the right class; a cycle exists iff something is reported |
| Integration tests (Testcontainers) | Each strategy's behaviour against real Postgres 18, races forced deterministically with think time |
| Negative control | Every strategy at Serializable: zero anomalies |
| Positive control | naive-jpa at Read Committed: caught in every run, first counterexample within a fraction of a second |

## Workload

Customer `c` owns accounts `2c-1` and `2c`. Half the customers hold single accounts (account rule: never below
zero), half hold joint accounts (customer rule: the two summed never below zero, each may go negative). Money only
enters joint accounts by transfer, never leaves them by transfer: a transfer out of a joint account checks only
that account, and could break the customer rule even when run alone. Joint customers start at €100 total and are
topped up by transfers, so they hover near zero, where write skew bites.

| Knob | Value | Note |
|---|---|---|
| Concurrency | 32 virtual-thread clients | |
| Accounts | 20 (10 customers) | |
| Zipf exponent | 0.6 | Plan said 1.0; tuned down in M2 (see below) |
| Think time | uniform 0–20 ms | Between read and write, and between a report's reads |
| Mix | 50% transfer, 20% withdraw, 20% joint withdraw, 10% report | |
| Intra-customer transfers | 30% of transfers (60% in the read-skew follow-up) | What read skew needs |
| Run length | 8 s or 6,000 operations per seed | Plan said 30 s; 8 s already yields hundreds of cycles |
| Client timeout | 2 s | Produces indeterminate operations |

**Tuning (M2).** At Zipf 1.0 the hottest account takes a third of all traffic and every strategy queues behind
it (about 60–80 ops/s). At 0.6, naive-jpa still fails within the first second and throughput rises by roughly
40% for every strategy. Postgres's `deadlock_timeout` is lowered from 1 s to 100 ms in `docker-compose.yml`:
several strategies deadlock, and a one-second wait per deadlock stalled whole runs.

The seed fixes the operation stream per client, not the thread interleaving, so runs are reproducible in
workload, not bit for bit. Every failing history is saved; the report states failure rates over seeds.

## Measuring cost honestly

Anomaly verdicts do not depend on timing; throughput and latency do. Three things learned the hard way:

- **Warm the JVM first.** A rerun of one cell in a fresh JVM climbed from 184 to 419 ops/s over its five seeds.
  `skewer matrix` now runs four discarded warm-up runs (JPA and JDBC paths) before the first cell.
- **Keep the machine idle.** The first full matrix overlapped with builds and browser screenshots on the same
  laptop and under-reported several cells by a factor of two or more. It was discarded and rerun untouched.
- **Timeouts snowball.** If the database stalls for a moment, clients time out after 2 s and send their next
  operation while the old one is still running, so the service briefly runs more transactions than there are
  clients. One stall in the discarded run turned a whole seed into timeouts. The report shows timeouts per seed
  (and how many of them committed anyway), so a stall is visible rather than hidden in a median.

## Limits

- Throughput numbers come from one laptop with Postgres in Docker Desktop (about 0.7 ms per JDBC round trip).
  Compare strategies with each other, not with production, and read medians over seeds rather than single runs.
- Item reads only: predicate reads and phantoms are out of scope.
- Skewer finds counterexamples; a clean run is evidence, not proof.
- A rejected atomic-update records no read (the `UPDATE` matched no row), which can only hide edges, never
  invent them.
