package dev.skewer.workload;

import java.util.List;
import java.util.Random;

/**
 * Seeded operation stream for one client. The seed fixes which operations, accounts and amounts each client
 * issues; it cannot fix how threads interleave, so runs are reproducible in workload, not bit for bit.
 */
public final class OpGenerator {

    private final Random rnd;
    private final WorkloadConfig config;
    private final AccountPool pool;
    private final Zipf singleZipf;
    private final Zipf jointZipf;

    public OpGenerator(long seed, int client, WorkloadConfig config, AccountPool pool) {
        this.rnd = new Random(seed * 1_000_003L + client);
        this.config = config;
        this.pool = pool;
        this.singleZipf = new Zipf(pool.single().size(), config.skew());
        this.jointZipf = pool.hasJoint() ? new Zipf(pool.joint().size(), config.skew()) : null;
    }

    public PlannedOp next() {
        int roll = rnd.nextInt(100);
        int think = config.thinkMaxMs() > 0 ? rnd.nextInt(config.thinkMaxMs() + 1) : 0;
        if (roll < config.transferPct()) {
            return transfer(think);
        }
        roll -= config.transferPct();
        if (roll < config.withdrawPct()) {
            long account = pick(pool.single(), singleZipf);
            return new PlannedOp(PlannedOp.WITHDRAW, null, null, account, AccountPool.customerOf(account),
                    euros(1, 20), think);
        }
        roll -= config.withdrawPct();
        if (roll < config.jointPct() && jointZipf != null) {
            long account = pick(pool.joint(), jointZipf);
            return new PlannedOp(PlannedOp.JOINT_WITHDRAW, null, null, account, AccountPool.customerOf(account),
                    euros(20, 80), think);
        }
        // Reports mostly cover single customers, where intra-customer transfers make read skew possible.
        long account = rnd.nextInt(100) < 70 || jointZipf == null ? pick(pool.single(), singleZipf) : pick(pool.joint(), jointZipf);
        return new PlannedOp(PlannedOp.TOTAL_BALANCE, null, null, null, AccountPool.customerOf(account), 0, think);
    }

    private PlannedOp transfer(int think) {
        long from = pick(pool.single(), singleZipf);
        long to;
        int roll = rnd.nextInt(100);
        if (roll < config.intraCustomerPct()) {
            to = AccountPool.siblingOf(from);
        } else if (roll < config.intraCustomerPct() + config.toJointPct() && jointZipf != null) {
            to = pick(pool.joint(), jointZipf);
        } else {
            do {
                to = pick(pool.single(), singleZipf);
            } while (to == from);
        }
        return new PlannedOp(PlannedOp.TRANSFER, from, to, null, null, euros(1, 50), think);
    }

    private long pick(List<Long> ids, Zipf zipf) {
        return ids.get(zipf.sample(rnd));
    }

    private long euros(int min, int max) {
        return (min + rnd.nextInt(max - min + 1)) * 100L;
    }
}
