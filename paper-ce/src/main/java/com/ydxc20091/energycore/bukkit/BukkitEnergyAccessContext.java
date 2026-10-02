/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;

import com.ydxc20091.energycore.api.EnergyAccessException;
import com.ydxc20091.energycore.api.EnergyAccessContext;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Checks current region ownership on every access, including after a Folia region merge. */
public final class BukkitEnergyAccessContext implements EnergyAccessContext {
    private final BooleanSupplier owner;
    private final BooleanSupplier valid;

    public BukkitEnergyAccessContext(BooleanSupplier owner, BooleanSupplier valid) {
        this.owner = Objects.requireNonNull(owner);
        this.valid = Objects.requireNonNull(valid);
    }

    public static BukkitEnergyAccessContext block(Location location, BooleanSupplier valid) {
        Location position = location.clone();
        Objects.requireNonNull(position.getWorld(), "world");
        return new BukkitEnergyAccessContext(() -> Bukkit.isOwnedByCurrentRegion(position),
                () -> position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4) && valid.getAsBoolean());
    }

    public static BukkitEnergyAccessContext entity(Entity entity) {
        return new BukkitEnergyAccessContext(() -> Bukkit.isOwnedByCurrentRegion(entity), entity::isValid);
    }

    @Override public void checkAccess() {
        if (!owner.getAsBoolean()) throw new EnergyAccessException("Storage belongs to another region or entity thread");
        if (!valid.getAsBoolean()) throw new EnergyAccessException("Storage is unloaded, removed or disabled");
    }

    @Override public long tick() { checkAccess(); return Bukkit.getCurrentTick(); }

    public static <T> CompletableFuture<T> schedule(Plugin plugin, Location location, Supplier<T> operation) {
        CompletableFuture<T> future = new CompletableFuture<>();
        if (!plugin.isEnabled()) return CompletableFuture.failedFuture(new EnergyAccessException("Plugin is disabled"));
        try {
            Bukkit.getRegionScheduler().run(plugin, location.clone(), task -> {
                if (future.isCancelled()) return;
                try {
                    if (!plugin.isEnabled()) throw new EnergyAccessException("Plugin stopped before the operation");
                    future.complete(operation.get());
                } catch (Throwable failure) { future.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { future.completeExceptionally(failure); }
        return future;
    }
}
