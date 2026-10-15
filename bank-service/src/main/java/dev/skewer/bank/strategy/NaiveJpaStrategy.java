package dev.skewer.bank.strategy;

import org.springframework.stereotype.Component;

import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.jpa.AccountEntity;
import dev.skewer.bank.tx.TxRunner;

/**
 * Load, compute, save, inside {@code @Transactional}. What most Spring services look like, and what most teams
 * assume is safe. At Read Committed, two withdrawals that read the same balance both write; one vanishes.
 */
@Component
public class NaiveJpaStrategy extends JpaStrategy<AccountEntity> {

    public NaiveJpaStrategy(TxRunner tx, TxnLogger logger, AccountSql sql) {
        super(tx, logger, sql, AccountEntity.class);
    }

    @Override
    public String name() {
        return "naive-jpa";
    }
}
