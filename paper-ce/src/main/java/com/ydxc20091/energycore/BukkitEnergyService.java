/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.bukkit.*;
import com.ydxc20091.energycore.ce.CraftEngineBridge;
import com.ydxc20091.energycore.async.SnapshotWorkQueue;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import java.util.Optional;
public interface BukkitEnergyService {
    CraftEngineBridge bridge();
    SnapshotWorkQueue workQueue();
    Optional<EnergyStorage> storageAt(Location location,EnergySide side);
    default Optional<EnergyStorage> storageAt(Location location) { return storageAt(location,null); }
    default AutoCloseable registerBlockProvider(Plugin owner,BlockEnergyProvider provider) { return bridge().resolver().register(owner,provider); }
    default AutoCloseable registerItemProvider(Plugin owner,ItemEnergyProvider provider) { return bridge().batteries().register(owner,provider); }
    default PlayerInventoryAccess inventory(Player player) { return bridge().inventory(player); }
    default ItemEnergyRegistry batteries() { return bridge().batteries(); }
    default ItemEnergyTransfers itemTransfers() { return bridge().transfers(); }
}
