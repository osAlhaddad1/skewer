package dev.skewer.checker.history;

/**
 * An account in the contention pool. Single accounts follow the account rule (balance never below zero); joint
 * accounts follow the customer rule (the customer's accounts summed never below zero, each may go negative).
 */
public record Account(long id, long customerId, boolean joint, long initialBalance) {
}
