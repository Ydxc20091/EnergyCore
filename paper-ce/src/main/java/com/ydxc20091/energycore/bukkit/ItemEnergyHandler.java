/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.EnergyStorage;
import org.bukkit.inventory.ItemStack;
/** A provider-owned count-one copy. The caller installs its replacement through a real slot. */
public interface ItemEnergyHandler extends EnergyStorage {
    ItemStack item();
    default boolean protectedData() { return false; }
    default boolean allowStacked() { return false; }
}
