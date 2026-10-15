package dev.skewer.bank.tx;

import java.sql.SQLException;
import java.util.Set;

import org.springframework.dao.OptimisticLockingFailureException;

/** Classifies failures by SQLSTATE, the one thing every layer (JDBC, Hibernate, Spring) preserves. */
public final class SqlStates {

    public static final String SERIALIZATION_FAILURE = "40001";
    public static final String DEADLOCK = "40P01";
    public static final String IN_FAILED_TRANSACTION = "25P02";
    /** Not a SQLSTATE: Hibernate's version check found zero rows, which Postgres does not consider an error. */
    public static final String OPTIMISTIC_LOCK = "optimistic-lock";

    private static final Set<String> TRANSIENT = Set.of(SERIALIZATION_FAILURE, DEADLOCK, OPTIMISTIC_LOCK);

    private SqlStates() {
    }

    public static String of(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof OptimisticLockingFailureException
                    || t.getClass().getName().endsWith("StaleObjectStateException")
                    || t.getClass().getName().endsWith("OptimisticLockException")
                    || t.getClass().getName().endsWith("StaleStateException")) {
                return OPTIMISTIC_LOCK;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "unknown";
    }

    /** Failures that a retry of the whole transaction can fix. */
    public static boolean isTransient(String state) {
        return TRANSIENT.contains(state);
    }
}
