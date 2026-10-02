package com.ydxc20091.energycore.core;

import com.ydxc20091.energycore.api.*;
import java.util.Objects;

/** Permission views preserve the delegate's identity and existing transfer budgets. */
public final class EnergyViews {
    private EnergyViews() {}
    public static EnergyStorage readOnly(EnergyStorage storage) { return access(storage, EnergyPortMode.NONE); }
    public static EnergyStorage input(EnergyStorage storage) { return access(storage, EnergyPortMode.INPUT); }
    public static EnergyStorage output(EnergyStorage storage) { return access(storage, EnergyPortMode.OUTPUT); }
    public static EnergyStorage access(EnergyStorage storage, EnergyPortMode mode) {
        Objects.requireNonNull(storage, "storage"); Objects.requireNonNull(mode, "mode");
        return new EnergyStorage() {
            @Override public long getEnergyStored() { return storage.getEnergyStored(); }
            @Override public long getMaxEnergyStored() { return storage.getMaxEnergyStored(); }
            @Override public boolean canReceive() { context().checkAccess(); return mode.canReceive() && storage.canReceive(); }
            @Override public boolean canExtract() { context().checkAccess(); return mode.canExtract() && storage.canExtract(); }
            @Override public long receiveEnergy(long maximum, EnergyTransactionContext transaction) {
                check(maximum, transaction);
                return mode.canReceive() ? storage.receiveEnergy(maximum, transaction) : 0;
            }
            @Override public long extractEnergy(long maximum, EnergyTransactionContext transaction) {
                check(maximum, transaction);
                return mode.canExtract() ? storage.extractEnergy(maximum, transaction) : 0;
            }
            @Override public EnergyAccessContext context() { return storage.context(); }
            @Override public Object identity() { return storage.identity(); }
            @Override public boolean supportsTransactions() { return storage.supportsTransactions(); }
            private void check(long maximum, EnergyTransactionContext transaction) {
                if (maximum < 0) throw new IllegalArgumentException("Negative energy amount");
                context().checkAccess(); Objects.requireNonNull(transaction, "transaction").checkOpen();
            }
        };
    }
}
