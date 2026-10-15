package dev.skewer.bank.core;

import java.util.UUID;

/** An account row version as read by plain SQL. */
public record AccountRow(long id, long customerId, long balanceCents, UUID lastWriter) {
}
