/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import java.util.*;
/** One shared participant covers the complete player inventory and cursor. */
public final class PlayerInventoryAccess implements EnergyTransactionParticipant {
    private final Player player;
    private final EnergyAccessContext context;
    private State expected;
    private EnergyTransactionContext expectedRoot;
    public PlayerInventoryAccess(Player player) { this(player,BukkitEnergyAccessContext.entity(player)); }
    public PlayerInventoryAccess(Player player,EnergyAccessContext context) { this.player=Objects.requireNonNull(player); this.context=Objects.requireNonNull(context); }
    public EnergyAccessContext context() { return context; }
    public Player player() { context.checkAccess(); return player; }
    public boolean isFor(Player candidate) { return player==candidate; }
    public ItemStack item(int slot) { checkSlot(slot); context.checkAccess(); return copy(player.getInventory().getItem(slot)); }
    public ItemStack cursor() { context.checkAccess(); return copy(player.getItemOnCursor()); }
    public void setItem(int slot,ItemStack value,EnergyTransactionContext transaction) {
        checkSlot(slot); transaction.enlist(this); validate(); player.getInventory().setItem(slot,copy(value)); expected=read();
    }
    public void setCursor(ItemStack value,EnergyTransactionContext transaction) {
        transaction.enlist(this); validate(); player.setItemOnCursor(copy(value)); expected=read();
    }
    public boolean replaceOne(int slot,ItemStack replacement,EnergyTransactionContext transaction) {
        ItemStack current=item(slot); if(empty(current)) return false;
        ItemStack[] proposed=copy(player.getInventory().getContents());
        if(current.getAmount()==1) proposed[slot]=copy(replacement);
        else { proposed[slot].setAmount(current.getAmount()-1); if(!insert(proposed,replacement)) return false; }
        transaction.enlist(this); validate(); player.getInventory().setContents(copy(proposed)); expected=read(); return true;
    }
    public boolean canReplaceOne(int slot,ItemStack replacement) {
        ItemStack current=item(slot); if(empty(current)) return false;
        if(current.getAmount()==1) return true;
        ItemStack[] proposed=copy(player.getInventory().getContents()); proposed[slot].setAmount(current.getAmount()-1);
        return insert(proposed,replacement);
    }
    public boolean insert(ItemStack replacement,EnergyTransactionContext transaction) {
        context.checkAccess(); ItemStack[] proposed=copy(player.getInventory().getContents()); if(!insert(proposed,replacement)) return false;
        transaction.enlist(this); validate(); player.getInventory().setContents(copy(proposed)); expected=read(); return true;
    }
    private boolean insert(ItemStack[] proposed,ItemStack replacement) {
        if(empty(replacement)) return true;
        int remaining=replacement.getAmount(), limit=player.getInventory().getStorageContents().length;
        for(int i=0;i<limit;i++) {
            ItemStack existing=proposed[i]; if(empty(existing)||!existing.isSimilar(replacement)) continue;
            int add=Math.max(0,Math.min(remaining,Math.min(existing.getMaxStackSize(),player.getInventory().getMaxStackSize())-existing.getAmount()));
            existing.setAmount(existing.getAmount()+add); remaining-=add; if(remaining==0) return true;
        }
        for(int i=0;i<limit;i++) if(empty(proposed[i])) {
            int add=Math.min(remaining,Math.min(replacement.getMaxStackSize(),player.getInventory().getMaxStackSize()));
            proposed[i]=copy(replacement); proposed[i].setAmount(add); remaining-=add; if(remaining==0) return true;
        }
        return false;
    }
    @Override public Object snapshot() { context.checkAccess(); State state=read(); expected=state; expectedRoot=EnergyTransactionScope.root(); return new Snapshot(state,context.tick()); }
    @Override public void restore(Object snapshot) {
        context.checkAccess(); State original=((Snapshot)snapshot).state;
        State actual=read(); State written=expected;
        if(written!=null) {
            for(int i=0;i<actual.items.length;i++) if(Objects.equals(actual.items[i],written.items[i])) player.getInventory().setItem(i,copy(original.items[i]));
            if(Objects.equals(actual.cursor,written.cursor)) player.setItemOnCursor(copy(original.cursor));
        }
        expected=read();
    }
    @Override public void validate() {
        context.checkAccess();
        if(expectedRoot==null||!expectedRoot.isOpen()) { expected=null; expectedRoot=null; }
        if(expected!=null&&!same(expected,read())) throw new EnergyAccessException("Player inventory or cursor changed during transaction");
    }
    @Override public void validateSnapshot(Object snapshot) { validate(); if(((Snapshot)snapshot).tick!=context.tick()) throw new EnergyAccessException("Inventory transaction crossed a tick"); }
    @Override public Runnable prepareCommitNotification() { expected=null; expectedRoot=null; return ()->{}; }
    @Override public void afterCommit() { prepareCommitNotification().run(); }
    private State read() { return new State(copy(player.getInventory().getContents()),copy(player.getItemOnCursor())); }
    private static boolean same(State a,State b) { return Arrays.equals(a.items,b.items)&&Objects.equals(a.cursor,b.cursor); }
    private void checkSlot(int slot) { if(slot<0||slot>=player.getInventory().getSize()) throw new IllegalArgumentException("Invalid inventory slot"); }
    private record State(ItemStack[] items,ItemStack cursor) {}
    private record Snapshot(State state,long tick) {}
    public static boolean empty(ItemStack item) { return item==null||item.getAmount()==0||item.getType().isAir(); }
    public static ItemStack copy(ItemStack item) { return item==null?null:item.clone(); }
    private static ItemStack[] copy(ItemStack[] items) { ItemStack[] result=new ItemStack[items.length]; for(int i=0;i<items.length;i++) result[i]=copy(items[i]); return result; }
}
