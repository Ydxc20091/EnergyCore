/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.core.EnergyData;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
public final class ItemEnergyData {
    public static final NamespacedKey DATA_KEY=new NamespacedKey("energycore","battery_data");
    public static final NamespacedKey BLOCK_DATA_KEY=new NamespacedKey("energycore","storage_data");
    public static final NamespacedKey INITIALIZED_KEY=new NamespacedKey("energycore","initialized");
    public static final NamespacedKey BUDGET_KEY=new NamespacedKey("energycore","transfer_budget");
    public record ReadResult(long energy,boolean protectedData,String message) {}
    public ReadResult read(ItemStack item,BatteryDefinition definition) {
        if(!item.hasItemMeta()) return new ReadResult(definition.initialEnergy(),false,"");
        var pdc=item.getItemMeta().getPersistentDataContainer();
        if(!pdc.has(DATA_KEY)) {
            if(pdc.has(INITIALIZED_KEY)) return new ReadResult(0,true,"Initialized battery is missing its data");
            return new ReadResult(definition.initialEnergy(),false,"");
        }
        if(!pdc.has(DATA_KEY,PersistentDataType.BYTE_ARRAY)) return new ReadResult(0,true,"Battery data has the wrong type");
        var decoded=EnergyData.decode(pdc.get(DATA_KEY,PersistentDataType.BYTE_ARRAY));
        boolean protectedData=decoded.status()!=EnergyData.Status.PRESENT;
        return new ReadResult(decoded.energy(),protectedData,decoded.message());
    }
    public void write(ItemStack item,long energy) {
        item.editMeta(meta->{ meta.getPersistentDataContainer().set(DATA_KEY,PersistentDataType.BYTE_ARRAY,EnergyData.encode(energy)); meta.getPersistentDataContainer().set(INITIALIZED_KEY,PersistentDataType.BYTE,(byte)1); });
    }
    public static void writeRaw(ItemStack item,NamespacedKey key,byte[] raw) {
        item.editMeta(meta->meta.getPersistentDataContainer().set(key,PersistentDataType.BYTE_ARRAY,raw.clone()));
    }
}
