package com.ydxc20091.energycore.core;

import com.ydxc20091.energycore.api.*;
import java.util.Objects;

/** Private machine storage. Expose EnergyPorts to enforce external transfer budgets. */
public final class EnergyBuffer implements EnergyStorage, EnergyTransactionParticipant {
    private final EnergyAccessContext context;
    private final Runnable committed;
    private long capacity;
    private long energy;
    private long version;
    private boolean dirty;
    EnergyPorts.SharedBudget portsBudget;

    public EnergyBuffer(long capacity, EnergyAccessContext context, Runnable committed) { this(capacity, 0, context, committed); }
    public EnergyBuffer(long capacity, long restoredEnergy, EnergyAccessContext context, Runnable committed) {
        if (capacity < 0 || restoredEnergy < 0) throw new IllegalArgumentException("Negative energy or capacity");
        this.capacity = capacity;
        energy = restoredEnergy;
        this.context = Objects.requireNonNull(context, "context");
        this.committed = Objects.requireNonNull(committed, "committed");
    }
    @Override public long getEnergyStored() { context.checkAccess(); return energy; }
    @Override public long getMaxEnergyStored() { context.checkAccess(); return capacity; }
    public long version() { context.checkAccess(); return version; }
    @Override public boolean canReceive() { context.checkAccess(); return capacity > 0; }
    @Override public boolean canExtract() { context.checkAccess(); return true; }
    @Override public EnergyAccessContext context() { return context; }
    @Override public Object identity() { return this; }

    public void setCapacity(long capacity, EnergyTransactionContext transaction) {
        check(capacity, transaction);
        if (this.capacity == capacity) return;
        transaction.enlist(this);
        this.capacity = capacity;
        changed();
    }
    @Override public long receiveEnergy(long maximum, EnergyTransactionContext transaction) {
        check(maximum, transaction);
        if (maximum == 0 || energy >= capacity) return 0;
        long received = Math.min(maximum, capacity - energy);
        transaction.enlist(this);
        energy = Math.addExact(energy, received);
        changed();
        return received;
    }
    @Override public long extractEnergy(long maximum, EnergyTransactionContext transaction) {
        check(maximum, transaction);
        long extracted = Math.min(maximum, energy);
        if (extracted == 0) return 0;
        transaction.enlist(this);
        energy -= extracted;
        changed();
        return extracted;
    }
    private void check(long maximum, EnergyTransactionContext transaction) {
        if (maximum < 0) throw new IllegalArgumentException("Negative energy amount");
        context.checkAccess();
        Objects.requireNonNull(transaction, "transaction").checkOpen();
    }
    private void changed() { version = Math.addExact(version, 1); dirty = true; }
    @Override public Object snapshot() { validate(); return new Snapshot(capacity, energy, version, dirty, context.tick()); }
    @Override public void restore(Object snapshot) {
        context.checkAccess();
        Snapshot value = (Snapshot) snapshot;
        capacity = value.capacity; energy = value.energy; version = value.version; dirty = value.dirty;
    }
    @Override public void validate() {
        context.checkAccess();
        if (energy < 0 || capacity < 0) throw new IllegalStateException("Invalid energy buffer state");
    }
    @Override public void validateSnapshot(Object snapshot) {
        validate();
        if (((Snapshot) snapshot).tick != context.tick()) throw new EnergyAccessException("Energy participant crossed a tick");
    }
    @Override public Runnable prepareCommitNotification() {
        boolean notify = dirty;
        dirty = false;
        return notify ? committed : () -> {};
    }
    @Override public void afterCommit() { prepareCommitNotification().run(); }
    private record Snapshot(long capacity, long energy, long version, boolean dirty, long tick) {}
}
