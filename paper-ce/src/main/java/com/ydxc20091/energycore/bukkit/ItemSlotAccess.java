/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import org.bukkit.inventory.ItemStack;
/** Slot view delegates snapshots to its canonical whole-inventory participant. */
public final class ItemSlotAccess {
    private final PlayerInventoryAccess inventory;
    private final int slot;
    public ItemSlotAccess(PlayerInventoryAccess inventory,int slot) { this.inventory=inventory; this.slot=slot; }
    public EnergyAccessContext context() { return inventory.context(); }
    public ItemStack item() { return inventory.item(slot); }
    public boolean replaceOne(ItemStack replacement,EnergyTransactionContext tx) { return inventory.replaceOne(slot,replacement,tx); }
    public boolean canReplaceOne(ItemStack replacement) { return inventory.canReplaceOne(slot,replacement); }
    public PlayerInventoryAccess inventory() { return inventory; }
    public int slot() { return slot; }
}
