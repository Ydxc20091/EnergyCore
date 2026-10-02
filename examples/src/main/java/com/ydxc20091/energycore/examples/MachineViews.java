/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.examples;

import com.ydxc20091.energycore.api.EnergyTransaction;
import com.ydxc20091.energycore.EnergyCorePlugin;
import com.ydxc20091.energycore.bukkit.PlayerInventoryAccess;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.HandlerList;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/** Each GUI is a projection. Only real inventory and logical machine slots join transactions. */
final class MachineViews implements Listener {
    private final EnergyCoreExamplesPlugin plugin;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final Set<Session> projections = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong revision = new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean stopping;

    MachineViews(EnergyCoreExamplesPlugin plugin) { this.plugin = plugin; }

    void open(Player player, MachineController machine) {
        if (!owns(player, machine)) return;
        Session session = new Session(player, machine);
        sessions.put(player.getUniqueId(), session);
        projections.add(session);
        render(session, snapshot(machine));
        if (player.openInventory(session.inventory) == null) {
            sessions.remove(player.getUniqueId(), session);
            projections.remove(session);
        }
    }

    void refresh(MachineController machine) {
        machine.accessContext().checkAccess();
        Projection projection = snapshot(machine);
        for (Session session : sessions.values()) {
            if (session.machine != machine) continue;
            session.player.getScheduler().run(plugin, task -> {
                if (sessions.get(session.player.getUniqueId()) != session) return;
                if (!owns(session)) { close(session); return; }
                render(session, projection);
            }, () -> sessions.remove(session.player.getUniqueId(), session));
        }
    }

    void unload(MachineController machine) {
        for (Session session : projections) if (session.machine == machine) {
            sessions.remove(session.player.getUniqueId(), session);
            scheduleClose(session.player, session.inventory, session);
        }
    }

    void invalidate() {
        sessions.clear();
        for (Session session : Set.copyOf(projections)) {
            if (!session.player.isOnline()) projectionClosed(session);
            else scheduleClose(session.player, session.inventory, session);
        }
    }

    void shutdown() {
        stopping = true;
        sessions.clear();
        for (Session session : Set.copyOf(projections)) {
            if (!session.player.isOnline()) projectionClosed(session);
            else if (Bukkit.isOwnedByCurrentRegion(session.player)) {
                try {
                    if (session.player.getOpenInventory().getTopInventory() == session.inventory) session.player.closeInventory();
                } finally { projectionClosed(session); }
            } else scheduleClose(session.player, session.inventory, session);
        }
        unregisterIfClosed();
    }

    private boolean owns(Player player, MachineController machine) {
        if (!plugin.isEnabled() || !player.isOnline() || !Bukkit.isOwnedByCurrentRegion(player)
                || !Bukkit.isOwnedByCurrentRegion(machine.location())) return false;
        try { machine.accessContext().checkAccess(); return true; }
        catch (RuntimeException rejected) { return false; }
    }

    private boolean owns(Session session) {
        if (!plugin.isEnabled() || !session.player.isOnline() || !Bukkit.isOwnedByCurrentRegion(session.player)
                || !Bukkit.isOwnedByCurrentRegion(session.location)) return false;
        try { session.machine.accessContext().checkAccess(); return true; }
        catch (RuntimeException rejected) { return false; }
    }

    private void close(Session session) {
        sessions.remove(session.player.getUniqueId(), session);
        scheduleClose(session.player, session.inventory, session);
    }

    private void rejectProjection(Player player, Inventory inventory, Session projection) {
        sessions.remove(player.getUniqueId(), projection);
        scheduleClose(player, inventory, projection.player == player ? projection : null);
    }

    private void scheduleClose(Player player, Inventory inventory, Session projection) {
        Runnable retired = () -> { if (projection != null) projectionClosed(projection); };
        player.getScheduler().run(plugin.bridge().plugin(), task -> {
            try { if (player.getOpenInventory().getTopInventory() == inventory) player.closeInventory(); }
            finally { retired.run(); }
        }, retired);
    }

    private void projectionClosed(Session session) {
        projections.remove(session);
        sessions.remove(session.player.getUniqueId(), session);
        unregisterIfClosed();
    }

    private void unregisterIfClosed() {
        if (stopping && projections.isEmpty()) HandlerList.unregisterAll(this);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void click(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Session projection)) return;
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (stopping || session != projection || projection.player != player || projection.inventory != top) {
            rejectProjection(player, top, projection); return;
        }
        if (!owns(session)) { close(session); return; }
        if (previouslyCancelled) { render(session, snapshot(session.machine)); player.updateInventory(); return; }
        if (session.machine.hasProtectedData()) { render(session, snapshot(session.machine)); player.updateInventory(); return; }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            render(session, snapshot(session.machine)); player.updateInventory(); return;
        }
        try (EnergyTransaction transaction = EnergyTransaction.open(session.machine.accessContext())) {
            PlayerInventoryAccess access = plugin.bridge().inventory(player);
            int raw = event.getRawSlot();
            int logical = logicalSlot(session.machine, raw);
            if (raw >= 0 && raw < session.inventory.getSize()) {
                if (logical < 0) return;
                if (click.isShiftClick()) shiftFromMachine(session.machine, logical, access, transaction);
                else clickMachine(session.machine, logical, access, click == ClickType.RIGHT, transaction);
            } else if (event.getClickedInventory() == player.getInventory() && event.getSlot() >= 0 && event.getSlot() < 36) {
                if (click.isShiftClick()) shiftToMachine(session.machine, access, event.getSlot(), transaction);
                else clickPlayer(access, event.getSlot(), click == ClickType.RIGHT, transaction);
            } else return;
            transaction.commit();
        } catch (RuntimeException rejected) {
            plugin.reportInteractionFailure(rejected);
        } finally {
            if (owns(session)) render(session, snapshot(session.machine));
            player.updateInventory();
        }
    }

    private static void clickMachine(MachineController machine, int slot, PlayerInventoryAccess access, boolean right, EnergyTransaction transaction) {
        ItemStack stored = machine.item(slot);
        ItemStack cursor = access.cursor();
        if (MachineController.empty(cursor)) {
            ItemStack taken = machine.removeItem(slot, right && !MachineController.empty(stored) ? (stored.getAmount() + 1) / 2 : Integer.MAX_VALUE, transaction);
            if (!MachineController.empty(taken)) access.setCursor(taken, transaction);
            return;
        }
        if (!machine.accepts(slot, cursor)) {
            if (!MachineController.empty(stored) && stored.isSimilar(cursor)) {
                int moved = Math.min(stored.getAmount(), cursor.getMaxStackSize() - cursor.getAmount());
                if (moved > 0) {
                    machine.removeItem(slot, moved, transaction);
                    cursor.setAmount(cursor.getAmount() + moved);
                    access.setCursor(cursor, transaction);
                }
            }
            return;
        }
        if (MachineController.empty(stored) || stored.isSimilar(cursor)) {
            int occupied = MachineController.empty(stored) ? 0 : stored.getAmount();
            int moved = Math.min(right ? 1 : cursor.getAmount(), cursor.getMaxStackSize() - occupied);
            if (moved <= 0) return;
            ItemStack result = cursor.clone(); result.setAmount(occupied + moved);
            machine.setItem(slot, result, transaction);
            cursor.setAmount(cursor.getAmount() - moved);
            access.setCursor(cursor, transaction);
        } else if (!right) {
            machine.setItem(slot, cursor, transaction);
            access.setCursor(stored, transaction);
        }
    }

    private static void clickPlayer(PlayerInventoryAccess access, int slot, boolean right, EnergyTransaction transaction) {
        ItemStack stored = access.item(slot);
        ItemStack cursor = access.cursor();
        if (MachineController.empty(cursor)) {
            if (MachineController.empty(stored)) return;
            int moved = right ? (stored.getAmount() + 1) / 2 : stored.getAmount();
            ItemStack picked = stored.clone(); picked.setAmount(moved);
            stored.setAmount(stored.getAmount() - moved);
            access.setItem(slot, stored, transaction); access.setCursor(picked, transaction);
        } else if (MachineController.empty(stored) || stored.isSimilar(cursor)) {
            int occupied = MachineController.empty(stored) ? 0 : stored.getAmount();
            int moved = Math.min(right ? 1 : cursor.getAmount(), cursor.getMaxStackSize() - occupied);
            if (moved <= 0) return;
            ItemStack placed = cursor.clone(); placed.setAmount(occupied + moved);
            cursor.setAmount(cursor.getAmount() - moved);
            access.setItem(slot, placed, transaction); access.setCursor(cursor, transaction);
        } else if (!right) {
            access.setItem(slot, cursor, transaction); access.setCursor(stored, transaction);
        }
    }

    private static void shiftFromMachine(MachineController machine, int slot, PlayerInventoryAccess access, EnergyTransaction transaction) {
        ItemStack original = machine.item(slot);
        if (MachineController.empty(original)) return;
        int remaining = original.getAmount();
        for (int pass = 0; pass < 2 && remaining > 0; pass++) for (int i = 0; i < 36 && remaining > 0; i++) {
            ItemStack target = access.item(i);
            boolean vacant = MachineController.empty(target);
            if (pass == 0 && vacant || pass == 1 && !vacant || !vacant && !target.isSimilar(original)) continue;
            int occupied = vacant ? 0 : target.getAmount();
            int moved = Math.min(remaining, original.getMaxStackSize() - occupied);
            if (moved <= 0) continue;
            ItemStack result = original.clone(); result.setAmount(occupied + moved);
            access.setItem(i, result, transaction);
            remaining -= moved;
        }
        machine.removeItem(slot, original.getAmount() - remaining, transaction);
    }

    private static void shiftToMachine(MachineController machine, PlayerInventoryAccess access, int slot, EnergyTransaction transaction) {
        ItemStack source = access.item(slot);
        if (MachineController.empty(source)) return;
        int remaining = source.getAmount();
        for (int i = 0; i < machine.slots() && remaining > 0; i++) {
            if (!machine.accepts(i, source)) continue;
            ItemStack stored = machine.item(i);
            if (!MachineController.empty(stored) && !stored.isSimilar(source)) continue;
            int occupied = MachineController.empty(stored) ? 0 : stored.getAmount();
            int moved = Math.min(remaining, source.getMaxStackSize() - occupied);
            if (moved <= 0) continue;
            ItemStack result = source.clone(); result.setAmount(occupied + moved);
            machine.setItem(i, result, transaction);
            remaining -= moved;
        }
        if (remaining != source.getAmount()) {
            source.setAmount(remaining);
            access.setItem(slot, source, transaction);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void drag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof Session projection)) return;
        boolean previouslyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        Session session = sessions.get(player.getUniqueId());
        if (stopping || session != projection || projection.player != player || projection.inventory != top) {
            rejectProjection(player, top, projection); return;
        }
        if (!owns(session)) { close(session); return; }
        if (previouslyCancelled) { render(session, snapshot(session.machine)); player.updateInventory(); return; }
        if (session.machine.hasProtectedData()) { player.updateInventory(); return; }
        try (EnergyTransaction transaction = EnergyTransaction.open(session.machine.accessContext())) {
            PlayerInventoryAccess access = plugin.bridge().inventory(player);
            ItemStack cursor = access.cursor();
            if (MachineController.empty(cursor) || !cursor.equals(event.getOldCursor())) return;
            int totalAdded = 0;
            for (var entry : event.getNewItems().entrySet()) {
                int raw = entry.getKey();
                ItemStack proposed = entry.getValue();
                if (MachineController.empty(proposed) || !proposed.isSimilar(cursor) || proposed.getAmount() > proposed.getMaxStackSize()) return;
                ItemStack old;
                if (raw < session.inventory.getSize()) {
                    int logical = logicalSlot(session.machine, raw);
                    if (logical < 0 || !session.machine.accepts(logical, proposed)) return;
                    old = session.machine.item(logical);
                } else {
                    int slot = event.getView().convertSlot(raw);
                    if (slot < 0 || slot >= 36) return;
                    old = access.item(slot);
                }
                if (!MachineController.empty(old) && !old.isSimilar(cursor)) return;
                int added = proposed.getAmount() - (MachineController.empty(old) ? 0 : old.getAmount());
                if (added <= 0) return;
                totalAdded += added;
            }
            ItemStack newCursor = event.getCursor();
            int remaining = MachineController.empty(newCursor) ? 0 : newCursor.getAmount();
            if (!MachineController.empty(newCursor) && !newCursor.isSimilar(cursor) || totalAdded + remaining != cursor.getAmount()) return;
            for (var entry : event.getNewItems().entrySet()) {
                int raw = entry.getKey();
                if (raw < session.inventory.getSize()) session.machine.setItem(logicalSlot(session.machine, raw), entry.getValue(), transaction);
                else access.setItem(event.getView().convertSlot(raw), entry.getValue(), transaction);
            }
            access.setCursor(newCursor, transaction);
            transaction.commit();
        } catch (RuntimeException rejected) { plugin.reportInteractionFailure(rejected); }
        finally {
            if (owns(session)) render(session, snapshot(session.machine));
            player.updateInventory();
        }
    }

    @EventHandler public void closed(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof Session projection && projection.player == event.getPlayer())
            projectionClosed(projection);
    }
    @EventHandler public void quit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
        for (Session session : projections) if (session.player == event.getPlayer()) projectionClosed(session);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) close(session);
    }

    private static int logicalSlot(MachineController machine, int raw) {
        if (machine.slots() > 0 && raw == 11) return 0;
        if (machine.slots() > 1 && raw == 15) return 1;
        return -1;
    }

    private Projection snapshot(MachineController machine) {
        ItemStack[] slots = new ItemStack[machine.slots()];
        for (int i = 0; i < slots.length; i++) slots[i] = machine.item(i);
        return new Projection(revision.incrementAndGet(), slots, machine.privateStorage().getEnergyStored(), machine.privateStorage().getMaxEnergyStored(),
                machine.fuelRemaining(), machine.progress(), machine.recipeTicks(), machine.hasProtectedData(),
                org.bukkit.plugin.java.JavaPlugin.getPlugin(EnergyCorePlugin.class).settings().displayUnit());
    }

    private static void render(Session session, Projection projection) {
        if (projection.revision < session.renderedRevision) return;
        session.renderedRevision = projection.revision;
        ItemStack decoration = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        var blank = decoration.getItemMeta(); blank.displayName(Component.empty()); decoration.setItemMeta(blank);
        for (int i = 0; i < session.inventory.getSize(); i++) session.inventory.setItem(i, decoration.clone());
        if (projection.items.length > 0) session.inventory.setItem(11, MachineController.copy(projection.items[0]));
        if (projection.items.length > 1) session.inventory.setItem(15, MachineController.copy(projection.items[1]));
        ItemStack status = new ItemStack(projection.protectedData ? Material.BARRIER : Material.REDSTONE);
        var meta = status.getItemMeta();
        meta.displayName(Component.text(projection.energy + " / " + projection.capacity + " " + projection.unit));
        meta.lore(java.util.List.of(Component.text("Fuel reserve: " + projection.fuel + " " + projection.unit),
                Component.text("Progress: " + projection.progress + " / " + projection.duration),
                Component.text(projection.protectedData ? "Preserved data is locked" : "Charge batteries by using them on the block")));
        status.setItemMeta(meta);
        session.inventory.setItem(22, status);
    }

    private record Projection(long revision, ItemStack[] items, long energy, long capacity, long fuel, int progress, int duration, boolean protectedData, String unit) {}
    private static final class Session implements InventoryHolder {
        final Player player;
        final MachineController machine;
        final Location location;
        final Inventory inventory;
        long renderedRevision;
        Session(Player player, MachineController machine) {
            this.player = player; this.machine = machine;
            location = machine.location().clone();
            inventory = Bukkit.createInventory(this, 27, Component.text(switch (machine.kind()) {
                case GENERATOR -> "Coal Generator"; case BANK -> "Energy Bank"; case FURNACE -> "Electric Furnace";
            }));
        }
        @Override public Inventory getInventory() { return inventory; }
    }
}
