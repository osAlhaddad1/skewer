package dev.skewer.bank.jpa;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * The same table mapped with {@code @Version}: every update becomes {@code ... WHERE id = ? AND version = ?}
 * and fails if another transaction got there first. Only the rows a transaction writes are checked.
 */
@Entity(name = "VersionedAccount")
@Table(name = "account")
public class VersionedAccountEntity implements BankAccount {

    @Id
    private Long id;

    @Column(name = "customer_id", updatable = false)
    private long customerId;

    @Column(name = "balance_cents")
    private long balanceCents;

    @Column(name = "last_writer")
    private UUID lastWriter;

    @Version
    private long version;

    protected VersionedAccountEntity() {
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

    public long getVersion() {
        return version;
    }
}
