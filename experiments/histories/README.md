Failing runs save their full history here as JSON lines (one record per line: meta, account, txn, write,
final, client). Re-analyse any of them offline, without rerunning the workload:

    ./skewer check experiments/histories/<run>/<cell>-<seed>.jsonl

Histories are not committed (they are large); run `./skewer matrix` to regenerate them.
