package com.ydxc20091.energycore.api;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;

/** Synchronous snapshot transaction confined to its ownership context, thread and opening tick. */
public final class EnergyTransaction implements EnergyTransactionContext {
    private final EnergyTransaction parent;
    private final EnergyAccessContext context;
    private final Thread owner = Thread.currentThread();
    private final long openedTick;
    private final IdentityHashMap<EnergyTransactionParticipant, Object> snapshots = new IdentityHashMap<>();
    private final List<EnergyTransactionParticipant> order = new ArrayList<>();
    private boolean open = true;
    private boolean committed;

    private EnergyTransaction(EnergyTransaction parent, EnergyAccessContext context) {
        this.parent = parent;
        this.context = context;
        context.checkAccess();
        openedTick = context.tick();
        EnergyTransactionScope.enter(this, parent);
    }

    public static EnergyTransaction open(EnergyAccessContext context) {
        Objects.requireNonNull(context, "context");
        if (EnergyTransactionScope.hasOpenTransaction()) throw new IllegalStateException("Use openNested while a transaction is active");
        return new EnergyTransaction(null, context);
    }
    public static EnergyTransaction current() { return EnergyTransactionScope.current() instanceof EnergyTransaction nativeTransaction ? nativeTransaction : null; }
    public static boolean hasOpenTransaction() { return EnergyTransactionScope.hasOpenTransaction(); }
    @Override public EnergyTransaction openNested() { checkOpen(); return new EnergyTransaction(this, context); }

    @Override public void enlist(EnergyTransactionParticipant participant) {
        checkOpen();
        capture(Objects.requireNonNull(participant, "participant"));
    }
    private void capture(EnergyTransactionParticipant participant) {
        if (snapshots.containsKey(participant)) return;
        if (parent != null) parent.capture(participant);
        participant.validate();
        snapshots.put(participant, participant.snapshot());
        order.add(participant);
    }
    @Override public void checkOpen() {
        checkStructure();
        context.checkAccess();
        if (context.tick() != openedTick) throw new EnergyAccessException("Transaction crossed a tick");
    }
    private void checkStructure() {
        if (Thread.currentThread() != owner) throw new EnergyAccessException("Transaction accessed from another thread");
        if (!open) throw new IllegalStateException("Transaction is closed");
        EnergyTransactionScope.checkCurrent(this);
    }
    @Override public void commit() {
        checkOpen();
        for (EnergyTransactionParticipant participant : order) participant.validateSnapshot(snapshots.get(participant));
        committed = true;
        finish();
        if (parent != null) return;
        CommitNotificationException failure = null;
        List<Runnable> notifications = new ArrayList<>(order.size());
        for (EnergyTransactionParticipant participant : order) {
            try { notifications.add(Objects.requireNonNull(participant.prepareCommitNotification(), "commit notification")); }
            catch (RuntimeException exception) {
                if (failure == null) failure = new CommitNotificationException("State committed; notification preparation failed", exception);
                else failure.addSuppressed(exception);
            }
        }
        for (Runnable notification : notifications) {
            try { notification.run(); }
            catch (RuntimeException exception) {
                if (failure == null) failure = new CommitNotificationException("State committed; notification failed", exception);
                else failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
    @Override public boolean isOpen() { return open; }
    @Override public boolean isCommitted() { return committed; }
    private void finish() {
        open = false;
        snapshots.clear();
        EnergyTransactionScope.leave(this, parent);
    }
    @Override public void close() {
        if (!open) return;
        // Rollback remains possible after a rejected cross-tick commit, provided ownership is retained.
        checkStructure();
        RuntimeException failure = null;
        for (int index = order.size() - 1; index >= 0; index--) {
            EnergyTransactionParticipant participant = order.get(index);
            try { participant.restore(snapshots.get(participant)); }
            catch (RuntimeException exception) {
                if (failure == null) failure = exception; else failure.addSuppressed(exception);
            }
        }
        finish();
        if (failure != null) throw failure;
    }

    /** State is already committed. Retrying the operation would apply it twice. */
    public static final class CommitNotificationException extends RuntimeException {
        public CommitNotificationException(String message, Throwable cause) { super(message, cause); }
    }
}
