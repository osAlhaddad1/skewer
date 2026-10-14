package dev.skewer.checker.history;

/** The effect a committed transaction claims to have had on one account. */
public record Delta(long account, long cents) {
}
