package com.ydxc20091.energycore.api;

/** Participants must snapshot before mutation and restore without external side effects. */
public interface EnergyTransactionParticipant {
    Object snapshot();
    void restore(Object snapshot);
    void validate();
    default void validateSnapshot(Object snapshot) { validate(); }
    /**
     * Called once after the root commits and before any participant notification runs.
     * Publish validated immutable snapshots here and return a notification that captures this commit's state.
     * Preparation must not invoke listeners or start another transaction.
     */
    default Runnable prepareCommitNotification() { return this::afterCommit; }
    /** Called after all state is committed; failures must never imply that state was rolled back. */
    default void afterCommit() {}
}
