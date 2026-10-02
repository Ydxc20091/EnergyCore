/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.fluidcore;

import com.ydxc20091.energycore.api.*;
import com.ydxc20091.fluidcore.api.FluidTransaction;
import com.ydxc20091.fluidcore.api.TransactionParticipant;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;

/** One coordinator for fluid, energy and item participants. Close without commit rolls back. */
public final class FluidEnergyTransaction implements EnergyTransactionContext {
    private final FluidTransaction fluids;
    private final FluidEnergyTransaction parent;
    private final EnergyAccessContext access;
    private final Thread thread;
    private final long tick;
    private final IdentityHashMap<EnergyTransactionParticipant, TransactionParticipant> participants;
    private final IdentityHashMap<EnergyTransactionParticipant, Runnable> notifications;
    private boolean open = true;
    private boolean committed;

    private FluidEnergyTransaction(FluidTransaction fluids, FluidEnergyTransaction parent,
                                  EnergyAccessContext access) {
        this.fluids = fluids;
        this.parent = parent;
        this.access = access;
        this.thread = Thread.currentThread();
        this.tick = access.tick();
        this.participants = parent == null ? new IdentityHashMap<>() : parent.participants;
        this.notifications = parent == null ? new IdentityHashMap<>() : parent.notifications;
        EnergyTransactionScope.enter(this, parent);
        try { fluids.enlist(new TransactionParticipant() {
            @Override public Object snapshot() { return tick; }
            @Override public void restore(Object snapshot) { }
            @Override public void validate() { checkAccess(); }
            @Override public void validateSnapshot(Object snapshot) { checkAccess(); }
            @Override public void afterCommit() {
                if (parent == null) prepareNotifications();
            }
        }); } catch (RuntimeException failure) {
            open = false;
            EnergyTransactionScope.leave(this, parent);
            throw failure;
        }
    }

    public static FluidEnergyTransaction open(EnergyAccessContext access) {
        Objects.requireNonNull(access, "access").checkAccess();
        if (EnergyTransactionScope.hasOpenTransaction())
            throw new IllegalStateException("Use the active transaction's openNested()");
        FluidTransaction fluids = FluidTransaction.open();
        try { return new FluidEnergyTransaction(fluids, null, access); }
        catch (RuntimeException failure) { fluids.close(); throw failure; }
    }

    /** Pass this coordinator to FluidStorage operations; commit through this wrapper. */
    public FluidTransaction fluids() { checkOpen(); return fluids; }

    /** Pass this view to EnergyStorage and EnergyCore item-slot operations. */
    public EnergyTransactionContext energy() { checkOpen(); return this; }

    @Override public void enlist(EnergyTransactionParticipant participant) {
        checkOpen();
        Objects.requireNonNull(participant, "participant");
        TransactionParticipant adapted = participants.computeIfAbsent(participant, key -> new TransactionParticipant() {
            @Override public Object snapshot() { return key.snapshot(); }
            @Override public void restore(Object snapshot) { key.restore(snapshot); }
            @Override public void validate() { key.validate(); }
            @Override public void validateSnapshot(Object snapshot) { key.validateSnapshot(snapshot); }
            @Override public void afterCommit() {
                Runnable notification = notifications.remove(key);
                if (notification != null) notification.run();
            }
        });
        fluids.enlist(adapted);
    }

    private void prepareNotifications() {
        // The guard is enlisted first: publish every energy participant before
        // FluidTransaction dispatches any fluid, energy or inventory notification.
        var committedParticipants = new ArrayList<>(participants.keySet());
        finish(true);
        RuntimeException failure = null;
        for (EnergyTransactionParticipant participant : committedParticipants) {
            try {
                Runnable notification = participant.prepareCommitNotification();
                if (notification != null) notifications.put(participant, notification);
            } catch (RuntimeException exception) {
                if (failure == null) failure = exception;
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }

    private void checkAccess() {
        if (Thread.currentThread() != thread) throw new EnergyAccessException("Transaction belongs to another thread");
        access.checkAccess();
        if (access.tick() != tick) throw new EnergyAccessException("Transaction crossed a server tick");
    }

    @Override public void checkOpen() {
        if (!open) throw new IllegalStateException("Transaction is closed");
        EnergyTransactionScope.checkCurrent(this);
        checkAccess();
        fluids.checkOpen();
    }

    @Override public FluidEnergyTransaction openNested() {
        checkOpen();
        FluidTransaction child = fluids.openNested();
        try { return new FluidEnergyTransaction(child, this, access); }
        catch (RuntimeException failure) { child.close(); throw failure; }
    }

    @Override public void commit() {
        checkOpen();
        try { fluids.commit(); }
        catch (FluidTransaction.CommitNotificationException failure) {
            throw new EnergyTransaction.CommitNotificationException("State committed; notification failed", failure);
        }
        finally {
            if (fluids.isCommitted() && open) finish(true);
        }
    }

    private void finish(boolean wasCommitted) {
        if (!open) return;
        committed = wasCommitted;
        open = false;
        EnergyTransactionScope.leave(this, parent);
        if (parent == null) participants.clear();
    }

    @Override public boolean isOpen() { return open; }
    @Override public boolean isCommitted() { return committed; }

    @Override public void close() {
        if (!open) return;
        if (Thread.currentThread() != thread) throw new EnergyAccessException("Transaction belongs to another thread");
        EnergyTransactionScope.checkCurrent(this);
        fluids.checkOpen();
        try { fluids.close(); }
        finally { finish(false); }
    }
}
