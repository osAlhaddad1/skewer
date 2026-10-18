# Spring's @Transactional didn't stop my double-spend

*A ten-minute read. Code, data and the full report: this repository; `./skewer demo` reproduces everything below.*

## 1. The bug in 20 lines of code

```java
@Transactional
public void withdraw(long accountId, long amount) {
    Account account = accounts.findById(accountId).orElseThrow();
    if (account.getBalance() < amount) {
        throw new InsufficientFunds();
    }
    account.setBalance(account.getBalance() - amount);
    // dirty checking writes the UPDATE at commit
}
```

This is how most Spring services move money, and it passes every unit test. Run two of these at the same time
against the same account and both can read €100, both subtract, and both write. One withdrawal disappears.
If the two amounts were €60 and €30, the account ends at €40 or €70 instead of €10, and the bank has created
money.

Skewer's demo runs exactly this code (`JpaStrategy`) under plain `@Transactional` with 32 concurrent clients for
ten seconds. Every run so far has created money from nothing: €6,420 in the seed-42 run, a median of
€5,603 per eight-second run across the matrix.

## 2. Isolation levels in two minutes

`@Transactional` gives you atomicity: all of the method's writes commit, or none do. It does not give you
isolation, which is the question of what concurrent transactions can see of each other. `@Transactional` with no
arguments means `Isolation.DEFAULT`, which means whatever the database does, and Postgres does Read Committed.

- **Read Committed** takes a fresh snapshot for every statement. A transaction can read a row, have it changed
  under it, and then overwrite the change: a *lost update*. A blocked `UPDATE` waits for the other writer and
  re-checks its `WHERE` clause against the newest row, which is why `UPDATE ... SET balance = balance - :amt
  WHERE balance >= :amt` is safe even here.
- **Repeatable Read** (snapshot isolation) uses one snapshot per transaction and refuses to overwrite a row
  changed since that snapshot, failing with SQLSTATE 40001. Lost updates are gone. *Write skew* is not: two
  transactions read the same two rows, each writes a different one, and no row-level conflict ever fires.
- **Serializable** (SSI) additionally tracks read/write dependencies and aborts one transaction of any dangerous
  pattern with 40001. Nothing slips through, but the application must retry, and the retry must wrap the whole
  transaction, because after a 40001 the transaction is already dead.

## 3. How Skewer catches it: version chains and cycles

Every account row carries `last_writer`, the id of the transaction that wrote that version. Every read returns
it, so each transaction's read set names the exact versions it saw. An `AFTER UPDATE` trigger records, for every
committed write, which version it replaced, taken from the row Postgres actually overwrote. A `txn_log` row, the
transaction's last statement, proves the commit and records the isolation level that actually ran.

From that, three kinds of edges between committed transactions:

- **ww**: T2 wrote the version right after T1's.
- **wr**: T2 read the version T1 wrote.
- **rw**: T1 read a version and T2 wrote the next one, so T1 *should* have come before T2.

An edge means "must come before". A cycle means every transaction in it must come before itself: no serial order
explains what happened. The lost update above is the smallest possible cycle: T2 read the version before T1's
write (rw, T2 before T1) and then overwrote T1's version (ww, T1 before T2).

Because Skewer controls the database, it observes the version order instead of guessing it, which turns an
NP-complete problem into cycle detection: Tarjan's strongly connected components, then the shortest cycle through
each anti-dependency, classified with Adya's taxonomy. Before trusting any of it, the checker had to stay silent
on 10,000 generated serial histories and catch every planted stale read.

## 4. The matrix: what holds, what breaks, what each fix costs

Six strategies for the same four operations (transfer, withdraw, a joint withdrawal under a two-account rule,
and a read-only total), each at three isolation levels, five seeds of eight seconds, 32 clients on 20 accounts.

| | Read Committed | Repeatable Read | Serializable |
|---|---|---|---|
| naive JPA | lost updates, write skew, read skew (5/5) | write skew (5/5) | correct; 55% of ops fail |
| single `UPDATE … WHERE` | write skew (5/5) | write skew (5/5) | correct; 51% of ops fail |
| `FOR UPDATE`, id order | **correct, 0 failed ops** | correct; 70% of ops fail | correct; 70% of ops fail |
| `FOR UPDATE`, request order | correct; deadlocks | correct; deadlocks | correct; deadlocks |
| `@Version` + retry | write skew (5/5) | write skew (5/5) | correct; 12% fail after retries |
| naive code + retry on 40001 | | | correct; 12% fail after retries |

The cost, at this (deliberately high) contention, for the strategies that were correct:

| Strategy | ops/s | p99 | aborted attempts | ops that failed |
|---|---|---|---|---|
| `FOR UPDATE` in id order, Read Committed | 177 | 1,047 ms | 0% | 0% |
| Serializable + retry | 213 | 618 ms | 73% | 12% |
| `@Version` + force increment, Repeatable Read | 228 | 611 ms | 72% | 12% |
| `FOR UPDATE` in id order, Serializable | 259 | 189 ms | 70% | 70% |

Throughput alone would pick the last row, which fails seven operations in ten. The interesting comparison is
the first two: locking in a fixed order queues instead of aborting, so nothing fails, at the price of a long tail
while transactions wait behind the hottest account.

## 5. Three surprises, taken from the actual results

**`@Version` protects rows, not rules.** Optimistic locking checks the version of every row a transaction
*writes*. The joint withdrawal reads two accounts and writes one, so two withdrawals from the two accounts of the
same customer never touch the same version: both pass, and the customer ends below zero. That happened in all ten
runs at Read Committed and Repeatable Read. The fix the plan predicted held: forcing a version increment on the
account that was only read (`LockModeType.OPTIMISTIC_FORCE_INCREMENT`) turns the read into a conflicting write,
and write skew vanished in all ten runs.

**Raising the isolation level made the locking code worse.** The `SELECT … FOR UPDATE` strategy was correct and
failed nothing at Read Committed. The same code at Repeatable Read failed 70% of its operations: under snapshot
isolation, `FOR UPDATE` on a row that changed after the transaction's snapshot is a serialization failure, not a
wait. "Just use a stricter level" is only advice if the code around it retries.

**Naive code does not just race, it deadlocks.** The plan expected deadlocks only from the strategy that takes
locks in request order. Naive JPA deadlocked in 15% of attempts, because dirty checking flushes updates in the
order entities were loaded, so two opposite transfers update their rows in opposite order. The "safe" single
`UPDATE` statement did the same, in 16% of attempts.

And the two Spring gotchas behaved exactly as feared. A `@Transactional(isolation = SERIALIZABLE)` method called
from inside its own class ran with no transaction at all: Postgres reported Read Committed, every statement
committed on its own (each write carried a different database transaction id from the operation's commit record),
and a median of €28,785 appeared from nowhere per run. A retry loop placed inside the transactional method
recovered nothing: every one of its retries failed with "current transaction is aborted".

## 6. What I'd tell a team tomorrow

1. Treat `@Transactional` as "atomic", never as "isolated". Write the isolation level you rely on next to the code
   that relies on it.
2. Read-then-write on money: either push the check into one `UPDATE … WHERE` (single-row rules only), lock with
   `FOR UPDATE` in a fixed order, or run at Serializable with a retry.
3. Rules that span rows (limits across accounts, totals, uniqueness) defeat row-level fixes. Lock every row the
   rule reads, force a version increment on them, or use Serializable.
4. Retries wrap the whole transaction, outside the proxy, never inside the method.
5. Lock in a deterministic order, including the order your ORM flushes in (`hibernate.order_updates`).
6. Reports that add up several rows read them in one statement, or in one snapshot.
7. Test it: Skewer's checker is a few hundred lines, and a two-transaction cycle is a better bug report than any
   flaky integration test.
