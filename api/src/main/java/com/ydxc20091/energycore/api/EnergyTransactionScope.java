package com.ydxc20091.energycore.api;

import java.util.Objects;

/** Shared coordinator stack for native and optional integration transactions. */
public final class EnergyTransactionScope {
    private static final ThreadLocal<EnergyTransactionContext> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<EnergyTransactionContext> ROOT = new ThreadLocal<>();
    private EnergyTransactionScope() {}
    public static EnergyTransactionContext current() { return CURRENT.get(); }
    /** Stable identity for participants shared by nested operations. */
    public static EnergyTransactionContext root() { return ROOT.get(); }
    public static boolean hasOpenTransaction() { return CURRENT.get() != null; }
    public static void enter(EnergyTransactionContext context, EnergyTransactionContext parent) {
        Objects.requireNonNull(context, "context");
        if (CURRENT.get() != parent) throw new IllegalStateException("Another transaction coordinator is active");
        if (parent == null) ROOT.set(context);
        CURRENT.set(context);
    }
    public static void checkCurrent(EnergyTransactionContext context) {
        if (CURRENT.get() != context) throw new IllegalStateException("Close the nested transaction first");
    }
    public static void leave(EnergyTransactionContext context, EnergyTransactionContext parent) {
        checkCurrent(context);
        if (parent == null) { CURRENT.remove(); ROOT.remove(); } else CURRENT.set(parent);
    }
}
