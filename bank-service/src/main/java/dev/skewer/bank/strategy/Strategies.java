package dev.skewer.bank.strategy;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

import dev.skewer.bank.core.AccountSql;
import dev.skewer.bank.core.TxnLogger;
import dev.skewer.bank.tx.TxRunner;

/** All strategies by name; the request picks one. */
@Component
public class Strategies {

    private final Map<String, Strategy> byName = new TreeMap<>();

    public Strategies(List<Strategy> strategies) {
        strategies.forEach(s -> byName.put(s.name(), s));
    }

    public Strategy get(String name) {
        Strategy s = byName.get(name);
        if (s == null) {
            throw new IllegalArgumentException("unknown strategy '" + name + "', expected one of " + byName.keySet());
        }
        return s;
    }

    public List<String> names() {
        return List.copyOf(byName.keySet());
    }

    @Configuration
    static class Variants {

        @Bean
        PessimisticStrategy pessimistic(TxRunner tx, TxnLogger logger, AccountSql sql) {
            return new PessimisticStrategy(tx, logger, sql, true);
        }

        @Bean
        PessimisticStrategy pessimisticUnordered(TxRunner tx, TxnLogger logger, AccountSql sql) {
            return new PessimisticStrategy(tx, logger, sql, false);
        }

        @Bean
        OptimisticStrategy optimistic(TxRunner tx, TxnLogger logger, AccountSql sql) {
            return new OptimisticStrategy(tx, logger, sql, false);
        }

        @Bean
        OptimisticStrategy optimisticForceIncrement(TxRunner tx, TxnLogger logger, AccountSql sql) {
            return new OptimisticStrategy(tx, logger, sql, true);
        }
    }
}
