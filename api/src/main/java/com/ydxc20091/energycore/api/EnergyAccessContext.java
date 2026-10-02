package com.ydxc20091.energycore.api;

import java.util.Objects;
import java.util.function.LongSupplier;

/** Dynamic execution ownership. World implementations must check current ownership on every call. */
public interface EnergyAccessContext {
    void checkAccess();
    long tick();

    default void checkSameContext(EnergyAccessContext other) {
        checkAccess();
        Objects.requireNonNull(other, "other").checkAccess();
        if (tick() != other.tick()) throw new EnergyAccessException("Storage contexts have different ticks");
    }

    /** Standalone storage only; world-backed storage requires a live ownership implementation. */
    static EnergyAccessContext confinedToCurrentThread() { return confinedToCurrentThread(() -> 0); }

    static EnergyAccessContext confinedToCurrentThread(LongSupplier clock) {
        Objects.requireNonNull(clock, "clock");
        Thread owner = Thread.currentThread();
        return new EnergyAccessContext() {
            @Override public void checkAccess() {
                if (Thread.currentThread() != owner) throw new EnergyAccessException("Wrong storage thread");
            }
            @Override public long tick() { checkAccess(); return clock.getAsLong(); }
        };
    }
}
