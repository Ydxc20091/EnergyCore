/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.bukkit.ItemEnergyData;
import net.momirealms.craftengine.bukkit.item.BukkitItemManager;
import net.momirealms.craftengine.bukkit.util.LocationUtils;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.loot.LootContext;
import net.momirealms.craftengine.core.loot.function.LootFunction;
import net.momirealms.craftengine.core.plugin.context.parameter.DirectContextParameters;
import org.bukkit.inventory.ItemStack;
final class PreserveStorageLootFunction implements LootFunction {
    private final CraftEngineBridge bridge;
    PreserveStorageLootFunction(CraftEngineBridge bridge) { this.bridge=bridge; }
    @Override public Item apply(Item item,LootContext context) {
        if(item.isEmpty()) return item;
        var position=context.getOptionalParameter(DirectContextParameters.POSITION); if(position.isEmpty()) return item;
        if(Boolean.TRUE.equals(context.getVariable("energycore:storage_preserved"))) throw new IllegalArgumentException("Machine data may be preserved only once in a loot table");
        var data=bridge.dataForDrop(LocationUtils.toLocation(position.get())); if(data.isEmpty()) return item;
        if(item.count()!=1) throw new IllegalArgumentException("Machine data requires a count-one self-drop");
        context.setVariable("energycore:storage_preserved",true);
        Item result=item.copy();
        if(result.platformItem() instanceof ItemStack stack) { ItemEnergyData.writeRaw(stack,ItemEnergyData.BLOCK_DATA_KEY,data.get()); return BukkitItemManager.instance().wrap(stack); }
        throw new IllegalStateException("CE did not return a Bukkit machine item");
    }
}
