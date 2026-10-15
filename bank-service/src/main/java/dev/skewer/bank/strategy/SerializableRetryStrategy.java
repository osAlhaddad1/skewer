package dev.skewer.bank.strategy;

import org.springframework.stereotype.Component;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;
import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.jpa.AccountEntity;
import dev.skewer.bank.tx.Retry;
import dev.skewer.bank.tx.TxRunner;

/**
 * The naive-jpa code, unchanged, run at Serializable with a retry wrapped around the whole transaction. Postgres
 * aborts whichever transaction would complete a dangerous pattern; the cost is the abort rate.
 */
@Component
public class SerializableRetryStrategy extends JpaStrategy<AccountEntity> {

    public SerializableRetryStrategy(TxRunner tx, TxnLogger logger, AccountSql sql) {
        super(tx, logger, sql, AccountEntity.class);
    }

    @Override
    public String name() {
        return "serializable-retry";
    }

    @Override
    public OpResult execute(OpRequest request) {
        return Retry.transientFailures(() -> attempt(request));
    }
}
