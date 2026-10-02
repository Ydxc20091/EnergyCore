/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import org.bukkit.GameMode;
import org.bukkit.inventory.ItemStack;
import java.util.*;
public final class ItemEnergyTransfers {
    public enum Direction { CHARGE, DISCHARGE }
    public enum Status { SUCCESS, NOT_A_BATTERY, PROTECTED_DATA, STACK_NOT_SUPPORTED, NO_TRANSFER, NO_INVENTORY_SPACE, ATOMIC_TRANSFER_UNSUPPORTED }
    public record Result(Status status,long moved) {}
    private final ItemEnergyRegistry registry;
    public ItemEnergyTransfers(ItemEnergyRegistry registry) { this.registry=registry; }
    public Result transfer(ItemSlotAccess slot,GameMode mode,EnergyStorage storage,long maximum,Direction direction,EnergyAction action) {
        Objects.requireNonNull(slot); Objects.requireNonNull(mode); Objects.requireNonNull(storage); Objects.requireNonNull(direction); Objects.requireNonNull(action);
        if(maximum<0) throw new IllegalArgumentException("Negative transfer maximum");
        storage.context().checkSameContext(slot.context()); ItemStack input=slot.item();
        var resolved=registry.resolve(input,slot.context()); if(resolved.isEmpty()) return new Result(Status.NOT_A_BATTERY,0);
        ItemEnergyHandler battery=resolved.get();
        if(battery.protectedData()) return new Result(Status.PROTECTED_DATA,0);
        if(input.getAmount()>1&&!battery.allowStacked()) return new Result(Status.STACK_NOT_SUPPORTED,0);
        if(!storage.supportsTransactions()||!battery.supportsTransactions()) return new Result(Status.ATOMIC_TRANSFER_UNSUPPORTED,0);
        if(mode==GameMode.SPECTATOR) return new Result(Status.NO_TRANSFER,0);
        try(EnergyTransaction tx=EnergyTransaction.open(storage.context())) {
            if(action==EnergyAction.EXECUTE) {
                tx.enlist(slot.inventory());
                if(!Objects.equals(input,slot.item()))
                    throw new EnergyAccessException("Battery slot changed while resolving its provider");
            }
            long moved=direction==Direction.CHARGE?EnergyTransfers.move(storage,battery,maximum,tx):EnergyTransfers.move(battery,storage,maximum,tx);
            if(action==EnergyAction.EXECUTE) slot.inventory().validate();
            if(moved==0) return new Result(Status.NO_TRANSFER,0);
            ItemStack replacement=battery.item();
            if(action==EnergyAction.EXECUTE) slot.inventory().validate();
            if(!PlayerInventoryAccess.empty(replacement)&&replacement.getAmount()!=1)
                throw new IllegalStateException("Item energy providers must return a count-one replacement");
            if(PlayerInventoryAccess.empty(replacement)&&battery.getEnergyStored()!=0)
                throw new IllegalStateException("Item provider discarded stored energy in its replacement");
            if(action==EnergyAction.SIMULATE) { if(!slot.canReplaceOne(replacement)) return new Result(Status.NO_INVENTORY_SPACE,0); }
            else if(!slot.replaceOne(replacement,tx)) return new Result(Status.NO_INVENTORY_SPACE,0);
            if(action==EnergyAction.EXECUTE) tx.commit();
            return new Result(Status.SUCCESS,moved);
        }
    }
    public ItemEnergyRegistry batteries() { return registry; }
}
