/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.examples;

import com.ydxc20091.energycore.ce.EnergyStorageBehavior;
import net.momirealms.craftengine.core.block.BlockDefinition;
import net.momirealms.craftengine.core.block.ImmutableBlockState;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.block.entity.BlockEntityController;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;

/** Small standalone machines; their public ports use the standard EnergyCore provider. */
public final class MachineBehavior extends EnergyStorageBehavior {
    public enum Kind { GENERATOR, BANK, FURNACE }
    private final EnergyCoreExamplesPlugin plugin;
    private final Kind kind;
    private final long production;
    private final long fuelEnergy;
    private final long consumption;
    private final int recipeTicks;

    MachineBehavior(BlockDefinition block, ConfigSection config, EnergyCoreExamplesPlugin plugin, Kind kind) {
        super(block, config, plugin.bridge());
        this.plugin = plugin;
        this.kind = kind;
        production = positive(config, "production-per-tick", 80);
        fuelEnergy = positive(config, "energy-per-fuel", 12800);
        consumption = positive(config, "consumption-per-tick", 20);
        long duration = positive(config, "recipe-ticks", 200);
        if (duration > Integer.MAX_VALUE) throw new IllegalArgumentException("recipe-ticks is too large");
        recipeTicks = (int) duration;
        if (kind == Kind.FURNACE && consumption > capacity())
            throw new IllegalArgumentException("Furnace capacity must cover consumption-per-tick");
    }

    private static long positive(ConfigSection section, String path, long fallback) {
        Object value = section.get(path);
        long number = value == null ? fallback : Long.parseLong(value.toString());
        if (number <= 0) throw new IllegalArgumentException(path + " must be positive");
        return number;
    }

    Kind kind() { return kind; }
    long production() { return production; }
    long fuelEnergy() { return fuelEnergy; }
    long consumption() { return consumption; }
    int recipeTicks() { return recipeTicks; }

    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) {
        return new MachineController(entity, this, plugin);
    }

    @Override public InteractionResult useOnBlock(UseOnContext context, ImmutableBlockState state) {
        InteractionResult charging = super.useOnBlock(context, state);
        return charging.success() ? charging : open(context);
    }

    @Override public InteractionResult useWithoutItem(UseOnContext context, ImmutableBlockState state) {
        return open(context);
    }

    private InteractionResult open(UseOnContext context) {
        if (context.getPlayer() == null || !(context.getPlayer().platformPlayer() instanceof Player player)) return InteractionResult.PASS;
        var position = context.getClickedPos();
        Location location = new Location((org.bukkit.World) context.getWorld().platformWorld(), position.x(), position.y(), position.z());
        if (!Bukkit.isOwnedByCurrentRegion(player) || !Bukkit.isOwnedByCurrentRegion(location)) return InteractionResult.SUCCESS_AND_CANCEL;
        if (!BukkitCraftEngine.instance().antiGriefProvider().test(player, Flag.OPEN_CONTAINER, location)) return InteractionResult.SUCCESS_AND_CANCEL;
        BlockEntity entity = context.getWorld().storageWorld().getBlockEntityAtIfLoaded(context.getClickedPos());
        if (entity == null) return InteractionResult.PASS;
        entity.controller.let(MachineController.class, controller -> plugin.views().open(player, controller));
        return InteractionResult.SUCCESS_AND_CANCEL;
    }
}
