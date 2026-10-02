package com.ydxc20091.energycore.api;
import java.util.Objects;

/** Transfers always operate both ends through one coordinator and roll back partial failures. */
public final class EnergyTransfers {
    private EnergyTransfers() {}
    public static long move(EnergyStorage source, EnergyStorage target, long maximum, EnergyAction action) {
        check(source, target, maximum);
        Objects.requireNonNull(action, "action");
        try (EnergyTransaction transaction = EnergyTransaction.open(source.context())) {
            long moved = move(source, target, maximum, transaction);
            if (action == EnergyAction.EXECUTE) transaction.commit();
            return moved;
        }
    }
    public static long moveExact(EnergyStorage source, EnergyStorage target, long amount, EnergyAction action) {
        check(source, target, amount);
        Objects.requireNonNull(action, "action");
        try (EnergyTransaction transaction = EnergyTransaction.open(source.context())) {
            long moved = moveExact(source, target, amount, transaction);
            if (action == EnergyAction.EXECUTE) transaction.commit();
            return moved;
        }
    }
    public static long move(EnergyStorage source, EnergyStorage target, long maximum, EnergyTransactionContext transaction) {
        return transfer(source, target, maximum, false, transaction);
    }
    public static long moveExact(EnergyStorage source, EnergyStorage target, long amount, EnergyTransactionContext transaction) {
        return transfer(source, target, amount, true, transaction);
    }
    private static long transfer(EnergyStorage source, EnergyStorage target, long maximum, boolean exact, EnergyTransactionContext transaction) {
        check(source, target, maximum);
        Objects.requireNonNull(transaction, "transaction").checkOpen();
        if (maximum == 0) return 0;
        // Probing in a child supports output-only sources without compensating writes.
        long available;
        long accepted;
        try (EnergyTransactionContext probe = transaction.openNested()) {
            available = source.extractEnergy(maximum, probe);
            checkAmount(available, maximum);
            accepted = target.receiveEnergy(available, probe);
            checkAmount(accepted, available);
        }
        if (accepted == 0 || (exact && accepted != maximum)) return 0;
        // A second child protects unrelated caller state if either handler changes its answer.
        try (EnergyTransactionContext execution = transaction.openNested()) {
            long removed = source.extractEnergy(accepted, execution);
            checkAmount(removed, accepted);
            long inserted = target.receiveEnergy(removed, execution);
            checkAmount(inserted, removed);
            if (removed != accepted || inserted != accepted) throw new IllegalStateException("Storage changed its acceptance during transfer");
            execution.commit();
            return accepted;
        }
    }
    private static void check(EnergyStorage source, EnergyStorage target, long maximum) {
        Objects.requireNonNull(source, "source"); Objects.requireNonNull(target, "target");
        if (maximum < 0) throw new IllegalArgumentException("Negative transfer amount");
        if (source.identity() == target.identity()) throw new IllegalArgumentException("Cannot transfer between views of the same storage");
        if (!source.supportsTransactions() || !target.supportsTransactions()) throw new UnsupportedOperationException("Both storage handlers must support atomic transactions");
        source.context().checkSameContext(target.context());
    }
    private static void checkAmount(long actual, long maximum) {
        if (actual < 0 || actual > maximum) throw new IllegalStateException("Storage violated the energy amount contract");
    }
}
