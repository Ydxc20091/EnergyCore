package com.ydxc20091.energycore.api;

/** Coordinator contract for native and optional resource integration transactions. */
public interface EnergyTransactionContext extends AutoCloseable {
    void enlist(EnergyTransactionParticipant participant);
    void checkOpen();
    EnergyTransactionContext openNested();
    void commit();
    boolean isOpen();
    boolean isCommitted();
    @Override void close();
}
