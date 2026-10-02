/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.examples;

import com.ydxc20091.energycore.api.EnergySide;
import com.ydxc20091.energycore.api.EnergyStorage;
import com.ydxc20091.energycore.api.EnergyTransaction;
import com.ydxc20091.energycore.api.EnergyTransactionContext;
import com.ydxc20091.energycore.api.EnergyTransfers;
import com.ydxc20091.energycore.ce.EnergyStorageController;
import com.ydxc20091.energycore.ce.EnergyStorageBehavior;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.block.entity.tick.BlockEntityTicker;
import net.momirealms.craftengine.core.block.entity.tick.SleepingBlockEntityTicker;
import net.momirealms.craftengine.core.util.Direction;
import net.momirealms.craftengine.core.world.CEWorld;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** Inventory, fuel and progress are part of the inherited committed machine envelope. */
public final class MachineController extends EnergyStorageController {
    private static final int MAGIC = 0x45434D43;
    private final EnergyCoreExamplesPlugin plugin;
    private MachineBehavior behavior;
    private final ItemStack[] items;
    private long fuelRemaining;
    private int progress;
    private boolean processing;
    private boolean failureReported;
    private final SleepingBlockEntityTicker<MachineController> ticker =
            new SleepingBlockEntityTicker<>((world, position, state, controller) -> controller.process());

    MachineController(BlockEntity entity, MachineBehavior behavior, EnergyCoreExamplesPlugin plugin) {
        super(entity, behavior, plugin.bridge());
        this.plugin = plugin;
        this.behavior = behavior;
        this.items = new ItemStack[switch (behavior.kind()) { case GENERATOR -> 1; case FURNACE -> 2; case BANK -> 0; }];
        ticker.sleep();
    }

    public MachineBehavior.Kind kind() { return behavior.kind(); }
    public int slots() { return items.length; }
    public boolean sleeping() { accessContext().checkAccess(); return behavior.kind() == MachineBehavior.Kind.BANK || ticker.isSleeping(); }
    public long fuelRemaining() { accessContext().checkAccess(); return fuelRemaining; }
    public int progress() { accessContext().checkAccess(); return progress; }
    public int recipeTicks() { return behavior.recipeTicks(); }

    public ItemStack item(int slot) {
        accessContext().checkAccess();
        java.util.Objects.checkIndex(slot, items.length);
        return copy(items[slot]);
    }

    public boolean accepts(int slot, ItemStack item) {
        accessContext().checkAccess();
        java.util.Objects.checkIndex(slot, items.length);
        if (empty(item)) return true;
        return switch (behavior.kind()) {
            case GENERATOR -> slot == 0 && (item.getType() == Material.COAL || item.getType() == Material.CHARCOAL);
            case FURNACE -> slot == 0 && item.getType() == Material.RAW_IRON;
            case BANK -> false;
        };
    }

    public void setItem(int slot, ItemStack item, EnergyTransactionContext transaction) {
        accessContext().checkAccess();
        if (hasProtectedData()) throw new IllegalStateException("Machine contains preserved, unreadable data");
        if (!accepts(slot, item) && !empty(item)) throw new IllegalArgumentException("Item is not valid for this input slot");
        enlist(transaction);
        items[slot] = empty(item) ? null : item.clone();
    }

    /** Removes from either an input or output slot without allowing writes into the output. */
    public ItemStack removeItem(int slot, int count, EnergyTransactionContext transaction) {
        accessContext().checkAccess();
        java.util.Objects.checkIndex(slot, items.length);
        if (count < 0) throw new IllegalArgumentException("Negative item amount");
        if (count <= 0 || empty(items[slot])) return null;
        if (hasProtectedData()) throw new IllegalStateException("Machine contains preserved, unreadable data");
        enlist(transaction);
        ItemStack removed = items[slot].clone();
        int amount = Math.min(count, removed.getAmount());
        removed.setAmount(amount);
        items[slot].setAmount(items[slot].getAmount() - amount);
        if (items[slot].getAmount() == 0) items[slot] = null;
        return removed;
    }

    public void wakeUp() {
        if (behavior.kind() == MachineBehavior.Kind.BANK || !active() || !plugin.isEnabled()) return;
        accessContext().checkAccess();
        ticker.wakeUp();
    }

    @Override public void onLoad() {
        super.onLoad();
        plugin.loaded(this);
        if (behavior.kind() != MachineBehavior.Kind.BANK) {
            for (Direction direction : Direction.values()) subscribeChunkLoad(blockEntity.pos().relative(direction), this::wakeUp);
            wakeUp();
        }
    }

    @Override public void onUnload() { plugin.unloaded(this); ticker.sleep(); super.onUnload(); }
    @Override public void onRemove() { plugin.unloaded(this); ticker.sleep(); super.onRemove(); }

    @Override public <C extends BlockEntityController> BlockEntityTicker<C> createBlockEntityTicker(CEWorld world, ImmutableBlockState state) {
        return behavior.kind() == MachineBehavior.Kind.BANK ? null : createTickerHelper(ticker);
    }

    private void process() {
        if (!plugin.isEnabled() || !active()) { ticker.sleep(); return; }
        processing = true;
        boolean workNextTick = false;
        try {
            accessContext().checkAccess();
            if (hasProtectedData()) return;
            workNextTick = switch (behavior.kind()) {
                case GENERATOR -> generate();
                case FURNACE -> smelt();
                case BANK -> false;
            };
        } catch (RuntimeException failure) {
            if (!failureReported) {
                failureReported = true;
                plugin.getLogger().warning("Example machine at " + location() + " paused: " + failure.getMessage());
            }
        } finally {
            processing = false;
            if (!workNextTick) ticker.sleep();
        }
    }

    private boolean generate() {
        long storedBefore = privateStorage().getEnergyStored();
        boolean canProduce = storedBefore < privateStorage().getMaxEnergyStored()
                && (fuelRemaining > 0 || !empty(items[0]));
        if (!canProduce && (storedBefore == 0 || !hasReceiverRoom())) return false;
        try (EnergyTransaction transaction = EnergyTransaction.open(accessContext())) {
            enlist(transaction);
            pushAdjacent(transaction);
            EnergyStorage internal = privateStorage();
            long room = Math.max(0, internal.getMaxEnergyStored() - internal.getEnergyStored());
            if (room > 0) {
                if (fuelRemaining == 0 && !empty(items[0])) {
                    if (!accepts(0, items[0])) return false;
                    items[0].setAmount(items[0].getAmount() - 1);
                    if (items[0].getAmount() == 0) items[0] = null;
                    fuelRemaining = behavior.fuelEnergy();
                }
                long generated = internal.receiveEnergy(Math.min(Math.min(room, behavior.production()), fuelRemaining), transaction);
                fuelRemaining -= generated;
                pushAdjacent(transaction);
            }
            transaction.commit();
        }
        return privateStorage().getEnergyStored() < privateStorage().getMaxEnergyStored()
                && (fuelRemaining > 0 || !empty(items[0]))
                || privateStorage().getEnergyStored() > 0 && hasReceiverRoom();
    }

    private boolean smelt() {
        if (empty(items[0]) || items[0].getType() != Material.RAW_IRON || !outputAvailable()) return false;
        boolean completed = false;
        try (EnergyTransaction transaction = EnergyTransaction.open(accessContext())) {
            enlist(transaction);
            long before = privateStorage().getEnergyStored();
            pullAdjacent(transaction);
            if (privateStorage().getEnergyStored() < behavior.consumption()) {
                if (privateStorage().getEnergyStored() != before) transaction.commit();
                return hasAvailableSource();
            }
            if (privateStorage().extractEnergy(behavior.consumption(), transaction) != behavior.consumption()) return true;
            if (progress >= behavior.recipeTicks() - 1) {
                items[0].setAmount(items[0].getAmount() - 1);
                if (items[0].getAmount() == 0) items[0] = null;
                if (empty(items[1])) items[1] = new ItemStack(Material.IRON_INGOT);
                else items[1].setAmount(items[1].getAmount() + 1);
                progress = 0;
                completed = true;
            } else progress++;
            transaction.commit();
        }
        return !completed || !empty(items[0]) && outputAvailable();
    }

    private boolean outputAvailable() {
        return empty(items[1]) || items[1].getType() == Material.IRON_INGOT && items[1].getAmount() < items[1].getMaxStackSize();
    }

    private void pushAdjacent(EnergyTransactionContext transaction) {
        for (EnergySide side : EnergySide.values()) {
            EnergyStorage target = neighbor(side);
            if (target != null) EnergyTransfers.move(storage(side), target, Long.MAX_VALUE, transaction);
        }
    }

    private void pullAdjacent(EnergyTransactionContext transaction) {
        for (EnergySide side : EnergySide.values()) {
            EnergyStorage source = neighbor(side);
            if (source != null) EnergyTransfers.move(source, storage(side), Long.MAX_VALUE, transaction);
        }
    }

    private boolean hasReceiverRoom() {
        for (EnergySide side : EnergySide.values()) {
            if (!storage(side).canExtract()) continue;
            EnergyStorage target = neighbor(side);
            if (target != null && target.canReceive() && target.getEnergyStored() < target.getMaxEnergyStored()) return true;
        }
        return false;
    }

    private boolean hasAvailableSource() {
        for (EnergySide side : EnergySide.values()) {
            if (!storage(side).canReceive()) continue;
            EnergyStorage source = neighbor(side);
            if (source != null && source.canExtract() && source.getEnergyStored() > 0) return true;
        }
        return false;
    }

    private EnergyStorage neighbor(EnergySide side) {
        Location target = offset(location(), side);
        if (!target.getWorld().isChunkLoaded(target.getBlockX() >> 4, target.getBlockZ() >> 4)
                || !Bukkit.isOwnedByCurrentRegion(target)) return null;
        return plugin.bridge().resolver().resolve(target, opposite(side)).orElse(null);
    }

    static Location offset(Location location, EnergySide side) {
        return switch (side) {
            case DOWN -> location.clone().add(0, -1, 0);
            case UP -> location.clone().add(0, 1, 0);
            case NORTH -> location.clone().add(0, 0, -1);
            case SOUTH -> location.clone().add(0, 0, 1);
            case WEST -> location.clone().add(-1, 0, 0);
            case EAST -> location.clone().add(1, 0, 0);
        };
    }

    private static EnergySide opposite(EnergySide side) {
        return switch (side) {
            case DOWN -> EnergySide.UP; case UP -> EnergySide.DOWN;
            case NORTH -> EnergySide.SOUTH; case SOUTH -> EnergySide.NORTH;
            case WEST -> EnergySide.EAST; case EAST -> EnergySide.WEST;
        };
    }

    @Override protected Object snapshotExtraState() { return new State(copy(items), fuelRemaining, progress); }
    @Override protected void restoreExtraSnapshot(Object snapshot) {
        State saved = (State) snapshot;
        for (int i = 0; i < items.length; i++) items[i] = copy(saved.items[i]);
        fuelRemaining = saved.fuel;
        progress = saved.progress;
    }

    @Override protected void validateExtraState() {
        if (fuelRemaining < 0 || progress < 0) throw new IllegalStateException("Invalid machine progress");
        for (ItemStack item : items) if (!empty(item) && (item.getAmount() <= 0 || item.getAmount() > item.getMaxStackSize()))
            throw new IllegalStateException("Invalid logical item stack");
    }

    @Override protected void afterStateCommit() {
        plugin.views().refresh(this);
        if (!processing) wakeUp();
    }

    @Override protected void onBehaviorReload(EnergyStorageBehavior replacement) {
        if (!(replacement instanceof MachineBehavior machine) || machine.kind() != behavior.kind())
            throw new IllegalArgumentException("Changing a machine kind requires replacing its block");
        behavior = machine;
    }

    @Override protected byte[] captureExtraData() {
        if (items == null) return new byte[0];
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(1);
            output.writeByte(behavior.kind().ordinal());
            output.writeLong(fuelRemaining);
            output.writeInt(progress);
            output.writeByte(items.length);
            for (ItemStack item : items) {
                byte[] encoded = empty(item) ? new byte[0] : item.serializeAsBytes();
                output.writeInt(encoded.length);
                output.write(encoded);
            }
            output.flush();
            return bytes.toByteArray();
        } catch (IOException failure) { throw new UncheckedIOException(failure); }
    }

    @Override protected void restoreExtraData(byte[] bytes) {
        if (bytes.length == 0) return;
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != MAGIC || input.readUnsignedByte() != 1 || input.readUnsignedByte() != behavior.kind().ordinal())
                throw new IOException("Unsupported machine data");
            long fuel = input.readLong();
            int savedProgress = input.readInt();
            if (fuel < 0 || savedProgress < 0 || input.readUnsignedByte() != items.length) throw new IOException("Invalid machine state");
            ItemStack[] restored = new ItemStack[items.length];
            for (int i = 0; i < restored.length; i++) {
                int length = input.readInt();
                if (length < 0 || length > 1024 * 1024 || length > input.available()) throw new IOException("Invalid item data length");
                if (length > 0) restored[i] = ItemStack.deserializeBytes(input.readNBytes(length));
            }
            if (input.available() != 0) throw new IOException("Trailing machine data");
            for (int i = 0; i < items.length; i++) items[i] = restored[i];
            fuelRemaining = fuel;
            progress = savedProgress;
            validateExtraState();
        } catch (IOException | RuntimeException failure) { throw new IllegalArgumentException("Cannot read preserved machine state", failure); }
    }

    private record State(ItemStack[] items, long fuel, int progress) {}
    static boolean empty(ItemStack item) { return item == null || item.getType().isAir() || item.getAmount() <= 0; }
    static ItemStack copy(ItemStack item) { return empty(item) ? null : item.clone(); }
    static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = copy(source[i]);
        return result;
    }
}
