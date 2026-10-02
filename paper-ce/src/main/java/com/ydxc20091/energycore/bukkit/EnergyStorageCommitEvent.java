/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import org.bukkit.Location;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
/** Delivered after the complete machine snapshot has been committed. */
public final class EnergyStorageCommitEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final Location location;
    private final long version;
    public EnergyStorageCommitEvent(Location location, long version) { this.location=location.clone(); this.version=version; }
    public Location location() { return location.clone(); }
    public long version() { return version; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
