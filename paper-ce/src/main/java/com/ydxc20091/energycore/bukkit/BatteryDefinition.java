/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
public record BatteryDefinition(long capacity, long initialEnergy, long inputPerTick, long outputPerTick, boolean allowStacked) {
    public BatteryDefinition {
        if(capacity<=0 || initialEnergy<0 || initialEnergy>capacity || inputPerTick<0 || outputPerTick<0)
            throw new IllegalArgumentException("Invalid battery capacity, initial energy or rate");
    }
}
