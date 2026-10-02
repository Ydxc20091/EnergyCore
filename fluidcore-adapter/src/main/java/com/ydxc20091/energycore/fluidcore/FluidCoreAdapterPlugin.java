/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.fluidcore;

import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class FluidCoreAdapterPlugin extends JavaPlugin {
    @Override public void onEnable() {
        getServer().getServicesManager().register(FluidEnergyService.class, FluidEnergyTransaction::open,
                this, ServicePriority.Normal);
        getLogger().info("Fluid and energy transaction service registered.");
    }

    @Override public void onDisable() { getServer().getServicesManager().unregisterAll(this); }
}
