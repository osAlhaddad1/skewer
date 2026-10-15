package dev.skewer.bank.jpa;

import java.util.UUID;

/** The two JPA mappings of the account table, so the JPA strategies can share their logic. */
public interface BankAccount {

    Long getId();

    long getBalanceCents();

    void setBalanceCents(long balanceCents);

    UUID getLastWriter();

    void setLastWriter(UUID lastWriter);
}
