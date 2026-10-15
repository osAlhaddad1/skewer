package dev.skewer.bank.strategy;

import dev.skewer.bank.api.OpRequest;
import dev.skewer.bank.api.OpResult;

/** One way of implementing the bank's operations. Never throws: database failures come back as failed results. */
public interface Strategy {

    String name();

    OpResult execute(OpRequest request);
}
