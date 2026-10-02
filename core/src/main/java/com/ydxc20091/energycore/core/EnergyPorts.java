package com.ydxc20091.energycore.core;

import com.ydxc20091.energycore.api.*;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Persistent external ports. Reconstructing these views never resets backing-store budgets. */
public final class EnergyPorts {
    private final EnergyStorage buffer;
    private final SharedBudget budget;
    private final Map<EnergySide, EnergyStorage> ports;
    private final EnergyStorage external;

    public record PortConfig(EnergyPortMode mode, long inputPerTick, long outputPerTick) {
        public PortConfig {
            Objects.requireNonNull(mode, "mode");
            if (inputPerTick < 0 || outputPerTick < 0) throw new IllegalArgumentException("Negative port rate");
        }
        public static PortConfig unlimited(EnergyPortMode mode) { return new PortConfig(mode, Long.MAX_VALUE, Long.MAX_VALUE); }
    }
    public EnergyPorts(EnergyBuffer buffer, long inputPerTick, long outputPerTick) { this(buffer, inputPerTick, outputPerTick, Map.of()); }
    public EnergyPorts(EnergyBuffer buffer, long inputPerTick, long outputPerTick, Map<EnergySide, PortConfig> configuration) {
        this((EnergyStorage) buffer, inputPerTick, outputPerTick, configuration);
    }
    public EnergyPorts(EnergyStorage buffer, long inputPerTick, long outputPerTick) { this(buffer, inputPerTick, outputPerTick, Map.of()); }
    public EnergyPorts(EnergyStorage buffer, long inputPerTick, long outputPerTick, Map<EnergySide, PortConfig> configuration) {
        this.buffer = Objects.requireNonNull(buffer, "buffer");
        buffer.context().checkAccess();
        if (!(buffer.identity() instanceof EnergyBuffer backing)) throw new IllegalArgumentException("Ports require an EnergyBuffer backing identity");
        if (inputPerTick < 0 || outputPerTick < 0) throw new IllegalArgumentException("Negative total rate");
        Objects.requireNonNull(configuration, "configuration");
        EnumMap<EnergySide, PortConfig> normalized = new EnumMap<>(EnergySide.class);
        for (EnergySide side : EnergySide.values()) normalized.put(side, configuration.getOrDefault(side, PortConfig.unlimited(EnergyPortMode.BOTH)));
        if (backing.portsBudget == null) backing.portsBudget = new SharedBudget(buffer.context(), inputPerTick, outputPerTick, normalized);
        budget = backing.portsBudget;
        if (budget.inputRate != inputPerTick || budget.outputRate != outputPerTick || !budget.configuration.equals(normalized))
            throw new IllegalStateException("A buffer already has differently configured ports");
        EnumMap<EnergySide, EnergyStorage> views = new EnumMap<>(EnergySide.class);
        for (EnergySide side : EnergySide.values()) views.put(side, new Port(side));
        ports = Map.copyOf(views);
        external = new Port(null);
    }
    public EnergyStorage port(EnergySide side) { buffer.context().checkAccess(); return ports.get(Objects.requireNonNull(side, "side")); }
    public EnergyStorage external() { buffer.context().checkAccess(); return external; }
    public long inputPerTick() { return budget.inputRate; }
    public long outputPerTick() { return budget.outputRate; }
    public long remainingInput() { return budget.remaining(null, true); }
    public long remainingOutput() { return budget.remaining(null, false); }

    private final class Port implements EnergyStorage {
        private final EnergySide side;
        private final PortConfig config;
        private Port(EnergySide side) {
            this.side = side;
            config = side == null ? PortConfig.unlimited(EnergyPortMode.BOTH) : budget.configuration.get(side);
        }
        @Override public long getEnergyStored() { return buffer.getEnergyStored(); }
        @Override public long getMaxEnergyStored() { return buffer.getMaxEnergyStored(); }
        @Override public boolean canReceive() { context().checkAccess(); return budget.permitted(side, true) && buffer.canReceive(); }
        @Override public boolean canExtract() { context().checkAccess(); return budget.permitted(side, false) && buffer.canExtract(); }
        @Override public EnergyAccessContext context() { return buffer.context(); }
        @Override public Object identity() { return buffer.identity(); }
        @Override public long receiveEnergy(long maximum, EnergyTransactionContext transaction) { return move(maximum, transaction, true); }
        @Override public long extractEnergy(long maximum, EnergyTransactionContext transaction) { return move(maximum, transaction, false); }
        private long move(long maximum, EnergyTransactionContext transaction, boolean receive) {
            if (maximum < 0) throw new IllegalArgumentException("Negative energy amount");
            context().checkAccess(); Objects.requireNonNull(transaction, "transaction").checkOpen();
            if (maximum == 0 || (receive ? !canReceive() : !canExtract())) return 0;
            long permitted = Math.min(maximum, budget.remaining(side, receive));
            if (permitted == 0) return 0;
            transaction.enlist(budget);
            budget.refresh();
            long moved = receive ? buffer.receiveEnergy(permitted, transaction) : buffer.extractEnergy(permitted, transaction);
            budget.consume(side, moved, receive);
            return moved;
        }
    }
    static final class SharedBudget implements EnergyTransactionParticipant {
        final EnergyAccessContext context;
        final long inputRate;
        final long outputRate;
        final Map<EnergySide, PortConfig> configuration;
        private long budgetTick = Long.MIN_VALUE;
        private long received;
        private long extracted;
        private long[] sideReceived = new long[EnergySide.values().length];
        private long[] sideExtracted = new long[EnergySide.values().length];
        SharedBudget(EnergyAccessContext context, long input, long output, Map<EnergySide, PortConfig> configuration) {
            this.context = context; inputRate = input; outputRate = output; this.configuration = Map.copyOf(configuration);
        }
        long remaining(EnergySide side, boolean receive) {
            context.checkAccess();
            boolean current = context.tick() == budgetTick;
            long total = (receive ? inputRate : outputRate) - (current ? receive ? received : extracted : 0);
            if (side == null) {
                long combined = 0;
                for (EnergySide candidate : EnergySide.values()) {
                    long local = sideRemaining(candidate, receive, current);
                    combined = local >= Long.MAX_VALUE - combined ? Long.MAX_VALUE : combined + local;
                }
                return Math.min(total, combined);
            }
            return Math.min(total, sideRemaining(side, receive, current));
        }
        boolean permitted(EnergySide side, boolean receive) {
            context.checkAccess();
            if ((receive ? inputRate : outputRate) == 0) return false;
            if (side == null) {
                for (EnergySide candidate : EnergySide.values()) if (permitted(candidate, receive)) return true;
                return false;
            }
            PortConfig config = configuration.get(side);
            return (receive ? config.mode.canReceive() : config.mode.canExtract()) && (receive ? config.inputPerTick : config.outputPerTick) > 0;
        }
        private long sideRemaining(EnergySide side, boolean receive, boolean current) {
            if (!permitted(side, receive)) return 0;
            PortConfig config = configuration.get(side);
            return (receive ? config.inputPerTick : config.outputPerTick) - (current ? receive ? sideReceived[side.ordinal()] : sideExtracted[side.ordinal()] : 0);
        }
        void refresh() {
            long now = context.tick();
            if (now == budgetTick) return;
            budgetTick = now; received = 0; extracted = 0;
            java.util.Arrays.fill(sideReceived, 0); java.util.Arrays.fill(sideExtracted, 0);
        }
        void consume(EnergySide side, long amount, boolean receive) {
            if (receive) received = Math.addExact(received, amount); else extracted = Math.addExact(extracted, amount);
            if (side == null) {
                long unassigned = amount;
                for (EnergySide candidate : EnergySide.values()) {
                    long assigned = Math.min(unassigned, sideRemaining(candidate, receive, true));
                    consumeSide(candidate, assigned, receive);
                    unassigned -= assigned;
                    if (unassigned == 0) break;
                }
                if (unassigned != 0) throw new IllegalStateException("Unassigned energy port budget");
            } else {
                consumeSide(side, amount, receive);
            }
        }
        private void consumeSide(EnergySide side, long amount, boolean receive) {
            int index = side.ordinal();
            if (receive) sideReceived[index] = Math.addExact(sideReceived[index], amount);
            else sideExtracted[index] = Math.addExact(sideExtracted[index], amount);
        }
        @Override public Object snapshot() { return new Snapshot(budgetTick, received, extracted, sideReceived.clone(), sideExtracted.clone(), context.tick()); }
        @Override public void restore(Object snapshot) {
            context.checkAccess(); Snapshot value = (Snapshot) snapshot;
            budgetTick = value.budgetTick; received = value.received; extracted = value.extracted;
            sideReceived = value.sideReceived.clone(); sideExtracted = value.sideExtracted.clone();
        }
        @Override public void validate() { context.checkAccess(); }
        @Override public void validateSnapshot(Object snapshot) {
            validate();
            if (((Snapshot) snapshot).tick != context.tick()) throw new EnergyAccessException("Port budget crossed a tick");
        }
        private record Snapshot(long budgetTick, long received, long extracted, long[] sideReceived, long[] sideExtracted, long tick) {}
    }
}
