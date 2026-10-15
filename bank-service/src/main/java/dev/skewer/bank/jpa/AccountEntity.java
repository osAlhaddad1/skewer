package dev.skewer.bank.jpa;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The account as most Spring code maps it: no version column, so dirty checking writes blindly. */
@Entity(name = "Account")
@Table(name = "account")
public class AccountEntity implements BankAccount {

    @Id
    private Long id;

    @Column(name = "customer_id", updatable = false)
    private long customerId;

    @Column(name = "balance_cents")
    private long balanceCents;

    @Column(name = "last_writer")
    private UUID lastWriter;

    protected AccountEntity() {
    }

    @Override
    public Long getId() {
        return id;
    }

    public long getCustomerId() {
        return customerId;
    }

    @Override
    public long getBalanceCents() {
        return balanceCents;
    }

    @Override
    public void setBalanceCents(long balanceCents) {
        this.balanceCents = balanceCents;
    }

    @Override
    public UUID getLastWriter() {
        return lastWriter;
    }

    @Override
    public void setLastWriter(UUID lastWriter) {
        this.lastWriter = lastWriter;
    }
}
