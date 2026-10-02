/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
/** A successful configuration publication invalidated older provider handles and views. */
public final class EnergyCoreReloadEvent extends Event {
    private static final HandlerList HANDLERS=new HandlerList();
    private final long generation;
    public EnergyCoreReloadEvent(long generation,boolean asynchronous) { super(asynchronous); this.generation=generation; }
    public long generation() { return generation; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
