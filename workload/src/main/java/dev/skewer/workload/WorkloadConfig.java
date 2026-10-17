package dev.skewer.workload;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The workload's knobs. Defaults are tuned so naive-jpa at Read Committed fails within seconds; they then stay
 * fixed for the whole matrix. Tuning (M2) lowered the Zipf exponent from the plan's 1.0 to 0.6: naive-jpa still
 * fails within the first second, and every strategy gains about 40% throughput because the hottest account no
 * longer takes a third of all traffic.
 *
 * @param accounts          contention pool size, two accounts per customer
 * @param jointCustomerShare share of customers whose two accounts follow the customer rule (joint accounts)
 * @param skew              Zipf exponent over accounts; higher means more collisions on hot accounts
 * @param thinkMaxMs        pause between read and write inside the transaction, uniform in [0, thinkMaxMs]
 * @param transferPct       operation mix, in percent; the four must add up to 100
 * @param intraCustomerPct  share of transfers between a customer's own two accounts (what read skew needs)
 * @param toJointPct        share of transfers that top up a joint account (keeps joint customers near zero)
 * @param durationMs        stop condition: wall time
 * @param maxOps            stop condition: operations issued
 * @param timeoutMs         client timeout; a timed-out operation is indeterminate until txn_log decides
 * @param totalMode         null (strategy default), split (one read per account) or single (one statement)
 * @param instrument        false for throughput baselines: no txn_log, no audit trigger, nothing to check
 */
public record WorkloadConfig(
        int concurrency,
        int accounts,
        double jointCustomerShare,
        double skew,
        int thinkMaxMs,
        int transferPct,
        int withdrawPct,
        int jointPct,
        int totalPct,
        int intraCustomerPct,
        int toJointPct,
        long singleInitialCents,
        long jointInitialCents,
        long durationMs,
        int maxOps,
        int timeoutMs,
        String totalMode,
        boolean instrument) {

    public WorkloadConfig {
        if (transferPct + withdrawPct + jointPct + totalPct != 100) {
            throw new IllegalArgumentException("operation mix must add up to 100");
        }
        if (accounts < 4 || accounts % 2 != 0) {
            throw new IllegalArgumentException("accounts must be even and at least 4");
        }
    }

    public static WorkloadConfig defaults() {
        return new WorkloadConfig(32, 20, 0.5, 0.6, 20, 50, 20, 20, 10, 30, 30,
                10_000_00, 50_00, 30_000, 10_000, 2_000, null, true);
    }

    public WorkloadConfig withRunLength(long durationMs, int maxOps) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withThinkMaxMs(int thinkMaxMs) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withMix(int transfer, int withdraw, int joint, int total) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transfer, withdraw,
                joint, total, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withIntraCustomerPct(int intraCustomerPct) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withTotalMode(String totalMode) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withInstrument(boolean instrument) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withConcurrency(int concurrency) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    public WorkloadConfig withSkew(double skew) {
        return new WorkloadConfig(concurrency, accounts, jointCustomerShare, skew, thinkMaxMs, transferPct, withdrawPct,
                jointPct, totalPct, intraCustomerPct, toJointPct, singleInitialCents, jointInitialCents, durationMs,
                maxOps, timeoutMs, totalMode, instrument);
    }

    /** Knobs as strings, for the history's meta record and the report. */
    public Map<String, String> describe() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("concurrency", String.valueOf(concurrency));
        m.put("accounts", String.valueOf(accounts));
        m.put("jointCustomerShare", String.valueOf(jointCustomerShare));
        m.put("skew", String.valueOf(skew));
        m.put("thinkMaxMs", String.valueOf(thinkMaxMs));
        m.put("mix", "transfer " + transferPct + "%, withdraw " + withdrawPct + "%, joint " + jointPct + "%, total " + totalPct + "%");
        m.put("intraCustomerPct", String.valueOf(intraCustomerPct));
        m.put("toJointPct", String.valueOf(toJointPct));
        m.put("singleInitialCents", String.valueOf(singleInitialCents));
        m.put("jointInitialCents", String.valueOf(jointInitialCents));
        m.put("durationMs", String.valueOf(durationMs));
        m.put("maxOps", String.valueOf(maxOps));
        m.put("timeoutMs", String.valueOf(timeoutMs));
        m.put("totalMode", String.valueOf(totalMode));
        m.put("instrument", String.valueOf(instrument));
        return m;
    }
}
