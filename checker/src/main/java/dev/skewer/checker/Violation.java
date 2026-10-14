package dev.skewer.checker;

import java.util.List;

/**
 * A broken business invariant.
 *
 * @param kind  atomicity, conservation, exactly-once, account-rule, customer-rule or read-consistency
 * @param cents the money involved: created or destroyed, overdrawn, or misreported
 */
public record Violation(String kind, String message, long cents, List<String> details) {

    public Violation {
        details = List.copyOf(details);
    }
}
