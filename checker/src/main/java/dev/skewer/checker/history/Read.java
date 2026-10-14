package dev.skewer.checker.history;

import java.util.UUID;

/** One account read: which version (identified by its writer) the transaction saw, and the balance in it. */
public record Read(long account, UUID saw, long balance) {
}
