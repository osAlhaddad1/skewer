package dev.skewer.workload;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;

class OpGeneratorTest {

    private final WorkloadConfig config = WorkloadConfig.defaults();
    private final AccountPool pool = new AccountPool(config);

    @Test
    void theSeedFixesTheOperationStream() {
        assertThat(ops(42, 3, 500)).isEqualTo(ops(42, 3, 500));
        assertThat(ops(42, 3, 500)).isNotEqualTo(ops(43, 3, 500));
        assertThat(ops(42, 3, 500)).isNotEqualTo(ops(42, 4, 500));
    }

    @Test
    void moneyNeverLeavesJointAccountsByTransfer() {
        for (PlannedOp op : ops(7, 0, 5_000)) {
            if (op.op().equals(PlannedOp.TRANSFER)) {
                assertThat(pool.single()).contains(op.from());
                assertThat(op.to()).isNotEqualTo(op.from());
            }
            if (op.op().equals(PlannedOp.WITHDRAW)) {
                assertThat(pool.single()).contains(op.account());
            }
            if (op.op().equals(PlannedOp.JOINT_WITHDRAW)) {
                assertThat(pool.joint()).contains(op.account());
                assertThat(op.customer()).isEqualTo(AccountPool.customerOf(op.account()));
            }
        }
    }

    @Test
    void operationMixFollowsTheKnobs() {
        List<PlannedOp> ops = ops(1, 0, 20_000);
        long transfers = ops.stream().filter(o -> o.op().equals(PlannedOp.TRANSFER)).count();
        assertThat(transfers / 20_000.0).isBetween(0.47, 0.53);
    }

    @Test
    void poolSplitsCustomersIntoSingleAndJoint() {
        assertThat(pool.accounts()).hasSize(20);
        assertThat(pool.single()).hasSize(10);
        assertThat(pool.joint()).hasSize(10);
        assertThat(AccountPool.siblingOf(1)).isEqualTo(2);
        assertThat(AccountPool.siblingOf(2)).isEqualTo(1);
    }

    @Test
    void zipfFavoursLowRanks() {
        Zipf zipf = new Zipf(10, 1.0);
        Random rnd = new Random(1);
        int[] counts = new int[10];
        for (int i = 0; i < 100_000; i++) {
            counts[zipf.sample(rnd)]++;
        }
        assertThat(counts[0]).isGreaterThan(counts[1]).isGreaterThan(counts[9] * 5);
        Zipf uniform = new Zipf(10, 0);
        int[] flat = new int[10];
        for (int i = 0; i < 100_000; i++) {
            flat[uniform.sample(rnd)]++;
        }
        assertThat(flat[0]).isBetween(9_000, 11_000);
    }

    private List<PlannedOp> ops(long seed, int client, int n) {
        OpGenerator gen = new OpGenerator(seed, client, config, pool);
        List<PlannedOp> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(gen.next());
        }
        return out;
    }
}
