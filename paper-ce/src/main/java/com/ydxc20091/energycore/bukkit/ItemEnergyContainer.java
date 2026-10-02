/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.core.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.nio.ByteBuffer;
/** An isolated count-one battery. Install item() once through a real slot transaction. */
public final class ItemEnergyContainer implements ItemEnergyHandler,EnergyTransactionParticipant {
    private final ItemStack original;
    private final BatteryDefinition definition;
    private final ItemEnergyData data;
    private final EnergyAccessContext context;
    private final EnergyBuffer buffer;
    private final boolean protectedData;
    private long budgetTick=Long.MIN_VALUE,received,extracted;
    public ItemEnergyContainer(ItemStack item,BatteryDefinition definition,EnergyAccessContext context) {
        this.original=item.clone(); original.setAmount(1); this.definition=definition; this.context=context; data=new ItemEnergyData();
        var decoded=data.read(original,definition); boolean bad=decoded.protectedData();
        if(original.hasItemMeta()) {
            var pdc=original.getItemMeta().getPersistentDataContainer();
            if(pdc.has(ItemEnergyData.BUDGET_KEY)) {
                byte[] raw=pdc.has(ItemEnergyData.BUDGET_KEY,PersistentDataType.BYTE_ARRAY)?pdc.get(ItemEnergyData.BUDGET_KEY,PersistentDataType.BYTE_ARRAY):null;
                if(raw==null||raw.length!=24) bad=true;
                else { ByteBuffer bytes=ByteBuffer.wrap(raw); budgetTick=bytes.getLong(); received=bytes.getLong(); extracted=bytes.getLong(); if(received<0||extracted<0) bad=true; }
            }
        }
        protectedData=bad; buffer=new EnergyBuffer(definition.capacity(),decoded.energy(),context,()->{});
    }
    public boolean protectedData() { context.checkAccess(); return protectedData; }
    public BatteryDefinition definition() { return definition; }
    @Override public boolean allowStacked() { return definition.allowStacked(); }
    public ItemStack item() {
        context.checkAccess(); ItemStack replacement=original.clone(); if(protectedData) return replacement;
        data.write(replacement,buffer.getEnergyStored());
        ItemEnergyData.writeRaw(replacement,ItemEnergyData.BUDGET_KEY,ByteBuffer.allocate(24).putLong(budgetTick).putLong(received).putLong(extracted).array());
        return replacement;
    }
    @Override public EnergyAccessContext context() { return context; }
    @Override public Object identity() { return buffer.identity(); }
    @Override public long getEnergyStored() { return buffer.getEnergyStored(); }
    @Override public long getMaxEnergyStored() { return buffer.getMaxEnergyStored(); }
    @Override public boolean canReceive() { context.checkAccess(); return !protectedData&&definition.inputPerTick()>0; }
    @Override public boolean canExtract() { context.checkAccess(); return !protectedData&&definition.outputPerTick()>0; }
    @Override public long receiveEnergy(long amount,EnergyTransactionContext transaction) {
        if(amount<0) throw new IllegalArgumentException("Negative amount"); validate(); transaction.enlist(this); refresh();
        long moved=buffer.receiveEnergy(Math.min(amount,Math.max(0,definition.inputPerTick()-received)),transaction); received+=moved; return moved;
    }
    @Override public long extractEnergy(long amount,EnergyTransactionContext transaction) {
        if(amount<0) throw new IllegalArgumentException("Negative amount"); validate(); transaction.enlist(this); refresh();
        long moved=buffer.extractEnergy(Math.min(amount,Math.max(0,definition.outputPerTick()-extracted)),transaction); extracted+=moved; return moved;
    }
    private void refresh() { long tick=context.tick(); if(tick!=budgetTick) { budgetTick=tick; received=extracted=0; } }
    @Override public Object snapshot() { validate(); return new Budget(budgetTick,received,extracted,context.tick()); }
    @Override public void restore(Object snapshot) { context.checkAccess(); Budget state=(Budget)snapshot; budgetTick=state.tick; received=state.received; extracted=state.extracted; }
    @Override public void validate() { context.checkAccess(); if(protectedData) throw new EnergyAccessException("Battery contains protected unknown or damaged data"); }
    @Override public void validateSnapshot(Object snapshot) { validate(); if(((Budget)snapshot).ownerTick!=context.tick()) throw new EnergyAccessException("Battery transaction crossed a tick"); }
    private record Budget(long tick,long received,long extracted,long ownerTick) {}
}
