# Demo video script (90 seconds)

Record at 1080p with a terminal on the left and the browser on the right. Run `./skewer demo` once before
recording so Postgres and the build are warm; then record a fresh run with a fixed seed so the numbers match the
narration (`./skewer demo --seed 42`).

| Time | On screen | Say |
|---|---|---|
| 0:00–0:15 | `JpaStrategy.withdraw` in the editor: `load`, `if (balance < amount)`, `setBalance`, and the `@Transactional` on `IsolatedTx.atDefault` | "This is how most Spring services move money. Load the account, check the balance, save. It's `@Transactional`. Is it safe?" |
| 0:15–0:35 | Terminal: `./skewer demo --seed 42`. The run finishes; scroll to `INVARIANT conservation: €… created from nothing` | "Thirty-two clients, ten seconds. The books no longer balance: this much money appeared from nowhere, and the checker can say exactly which transactions did it." |
| 0:35–1:00 | Browser: `experiments/results/demo.html`, the lost-update cycle drawing and the transaction table | "T2 read account 2 at this balance. T1 withdrew. T2 then wrote a balance computed from what it read before, wiping out T1's withdrawal. Each must have run before the other: a cycle no one-at-a-time execution can produce. The red dashed edge is the read that missed a write." |
| 1:00–1:20 | Browser: `docs/report/index.html`, the matrix. Hover naive-jpa (red) then pessimistic and serializable-retry (green) | "Same operations, six implementations, three isolation levels. Red cells broke an invariant. Locking in id order holds; so does Serializable with a retry around the whole transaction. Optimistic locking still lets write skew through." |
| 1:20–1:30 | The "What correctness costs" table, then the repo URL | "And correctness has a price: here it is, measured. Throughput, p99 and abort rate for every fix. Repo link below." |

Keep the cursor still while talking; let the drawings do the work.
