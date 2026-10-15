package dev.skewer.bank.strategy;

import java.util.List;

import jakarta.persistence.LockModeType;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxContext;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.jpa.VersionedAccountEntity;
import dev.skewer.bank.tx.Retry;
import dev.skewer.bank.tx.TxRunner;

/**
 * JPA {@code @Version} plus a retry wrapper. The version check covers only rows the transaction writes, so a
 * joint withdrawal that read its sibling account without writing it is still open to write skew.
 *
 * <p>With {@code forceIncrement}, the joint withdrawal also bumps the sibling's version
 * ({@code OPTIMISTIC_FORCE_INCREMENT}): the read becomes a conflicting write, closing the gap.
 */
public class OptimisticStrategy extends JpaStrategy<VersionedAccountEntity> {

    private final boolean forceIncrement;

    public OptimisticStrategy(TxRunner tx, TxnLogger logger, AccountSql sql, boolean forceIncrement) {
        super(tx, logger, sql, VersionedAccountEntity.class);
        this.forceIncrement = forceIncrement;
    }

    @Override
    public String name() {
        return forceIncrement ? "optimistic-force-increment" : "optimistic";
    }

    @Override
    public OpResult execute(OpRequest request) {
        return Retry.transientFailures(() -> attempt(request));
    }

    @Override
    protected void onJointDebit(TxContext ctx, List<VersionedAccountEntity> all, VersionedAccountEntity target) {
        if (!forceIncrement) {
            return;
        }
        for (VersionedAccountEntity sibling : all) {
            if (sibling != target) {
                em().lock(sibling, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
                // The version bump is a write of the sibling row; attribute the new version to this transaction.
                sibling.setLastWriter(ctx.txnId());
            }
        }
    }
}
