/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.bukkit.BatteryDefinition;
import net.momirealms.craftengine.core.item.setting.CustomItemSettingType;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
final class BatterySettings {
    static final CustomItemSettingType<BatteryDefinition> TYPE=CustomItemSettingType.simple();
    static BatteryDefinition parse(ConfigSection section) {
        long capacity=EnergyConfigurationValues.longValue(section,"capacity",100000);
        return new BatteryDefinition(capacity,EnergyConfigurationValues.longValue(section,"initial-energy",0),
            EnergyConfigurationValues.longValue(section,"input-per-tick",capacity),
            EnergyConfigurationValues.longValue(section,"output-per-tick",capacity),section.getBoolean("allow-stacked",false));
    }
}
