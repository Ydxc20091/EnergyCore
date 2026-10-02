package com.ydxc20091.energycore.api;

import java.util.Objects;

/** Integer energy. Mutation returns an amount between zero and the requested maximum. */
public interface EnergyStorage {
    long getEnergyStored();
    long getMaxEnergyStored();
    boolean canReceive();
    boolean canExtract();
    long receiveEnergy(long maximum, EnergyTransactionContext transaction);
    long extractEnergy(long maximum, EnergyTransactionContext transaction);
    EnergyAccessContext context();
    /** Stable backing identity, shared by every view of the same storage. */
    default Object identity() { return this; }
    default boolean supportsTransactions() { return true; }

    default long receiveEnergy(long maximum, EnergyAction action) { return operate(maximum, action, true); }
    default long extractEnergy(long maximum, EnergyAction action) { return operate(maximum, action, false); }
    default long receiveEnergy(long maximum, boolean simulate) { return receiveEnergy(maximum, simulate ? EnergyAction.SIMULATE : EnergyAction.EXECUTE); }
    default long extractEnergy(long maximum, boolean simulate) { return extractEnergy(maximum, simulate ? EnergyAction.SIMULATE : EnergyAction.EXECUTE); }
    private long operate(long maximum, EnergyAction action, boolean receiving) {
        if (maximum < 0) throw new IllegalArgumentException("Negative energy amount");
        Objects.requireNonNull(action, "action");
        if (!supportsTransactions()) throw new UnsupportedOperationException("Storage does not support atomic transactions");
        try (EnergyTransaction transaction = EnergyTransaction.open(context())) {
            long moved = receiving ? receiveEnergy(maximum, transaction) : extractEnergy(maximum, transaction);
            if (moved < 0 || moved > maximum) throw new IllegalStateException("Storage violated the energy amount contract");
            if (action == EnergyAction.EXECUTE) transaction.commit();
            return moved;
        }
    }
}
