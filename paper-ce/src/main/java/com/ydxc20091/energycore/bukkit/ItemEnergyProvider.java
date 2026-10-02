/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.EnergyAccessContext;
import org.bukkit.inventory.ItemStack;
import java.util.Optional;
@FunctionalInterface
public interface ItemEnergyProvider { Optional<ItemEnergyHandler> resolve(ItemStack countOneItem,EnergyAccessContext context); }
