/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.examples;

import com.ydxc20091.energycore.EnergyCorePlugin;
import com.ydxc20091.energycore.api.EnergySide;
import com.ydxc20091.energycore.bukkit.EnergyStorageCommitEvent;
import com.ydxc20091.energycore.bukkit.EnergyCoreReloadEvent;
import com.ydxc20091.energycore.ce.CraftEngineBridge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.compatibility.PluginTask;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.plugin.java.JavaPlugin;

/** Optional content pack, registered before CraftEngine's initial configuration parse. */
public final class EnergyCoreExamplesPlugin extends JavaPlugin implements Listener {
    private final ConcurrentHashMap<Position, MachineController> loaded = new ConcurrentHashMap<>();
    private final MachineViews views = new MachineViews(this);
    private CraftEngineBridge bridge;
    private volatile long lastInteractionWarning;
    private volatile boolean packInstalled;
    private volatile IOException packInstallationFailure;

    @Override public void onLoad() {
        bridge = JavaPlugin.getPlugin(EnergyCorePlugin.class).bridge();
        BlockBehaviors.register(Key.of("energycoreexample:generator"),
                (block, section) -> new MachineBehavior(block, section, this, MachineBehavior.Kind.GENERATOR));
        BlockBehaviors.register(Key.of("energycoreexample:bank"),
                (block, section) -> new MachineBehavior(block, section, this, MachineBehavior.Kind.BANK));
        BlockBehaviors.register(Key.of("energycoreexample:furnace"),
                (block, section) -> new MachineBehavior(block, section, this, MachineBehavior.Kind.FURNACE));
        CraftEngine.instance().beforeEnableTaskRegistry().registerTask(PluginTask.create(this::installExamplePack, getName()));
    }

    private void installExamplePack() {
        if (packInstalled || packInstallationFailure != null) return;
        try {
            Path pack = getDataFolder().toPath().getParent().resolve("CraftEngine/resources/energycore-examples");
            install(pack, "pack.yml");
            install(pack, "configuration/examples.yml");
            packInstalled = true;
        } catch (IOException failure) {
            packInstallationFailure = failure;
            getLogger().severe("Cannot install the EnergyCore example pack: " + failure.getMessage());
        }
    }

    private void install(Path pack, String resource) throws IOException {
        Path target = pack.resolve(resource);
        if (Files.exists(target)) return;
        Files.createDirectories(target.getParent());
        try (var input = getResource("pack/" + resource)) {
            if (input == null) throw new IOException("Missing example resource: " + resource);
            Files.copy(input, target);
        }
    }

    @Override public void onEnable() {
        installExamplePack();
        if (packInstallationFailure != null) throw new IllegalStateException("Cannot install the EnergyCore example pack", packInstallationFailure);
        CraftEngineBridge registered = getServer().getServicesManager().load(CraftEngineBridge.class);
        if (registered == null) throw new IllegalStateException("EnergyCore CE bridge is not available");
        bridge = registered;
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(views, bridge.plugin());
        for (var entry : Map.copyOf(loaded).entrySet()) wake(entry.getKey());
        getLogger().info("EnergyCore examples enabled: coal generator, energy bank, electric furnace and battery");
    }

    @Override public void onDisable() {
        views.shutdown();
        for (var entry : Map.copyOf(loaded).entrySet()) {
            MachineController controller = entry.getValue();
            bridge.schedule(entry.getKey().location(), () -> { controller.onUnload(); return null; }).exceptionally(failure -> null);
        }
        loaded.clear();
    }

    CraftEngineBridge bridge() { return bridge; }
    MachineViews views() { return views; }
    void loaded(MachineController controller) { loaded.put(Position.of(controller.location()), controller); }
    void unloaded(MachineController controller) {
        loaded.remove(Position.of(controller.location()), controller);
        views.unload(controller);
    }

    private void wake(Position position) {
        MachineController controller = loaded.get(position);
        if (controller == null || !isEnabled()) return;
        Location location = position.location();
        if (location.getWorld() == null) return;
        bridge.schedule(location, () -> {
            if (isEnabled() && loaded.get(position) == controller) controller.wakeUp();
            return null;
        }).exceptionally(failure -> null);
    }

    private void wakeAround(Location location) {
        wake(Position.of(location));
        for (EnergySide side : EnergySide.values()) wake(Position.of(MachineController.offset(location, side)));
    }

    @EventHandler public void energyCommitted(EnergyStorageCommitEvent event) { wakeAround(event.location()); }
    @EventHandler(ignoreCancelled = true) public void neighborChanged(BlockPhysicsEvent event) { wakeAround(event.getBlock().getLocation()); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void configurationChanged(EnergyCoreReloadEvent event) { views.invalidate(); }

    @EventHandler(priority = EventPriority.MONITOR)
    public void reloaded(CraftEngineReloadEvent event) {
        for (Position position : loaded.keySet()) wake(position);
    }

    void reportInteractionFailure(RuntimeException failure) {
        long now = System.nanoTime();
        if (now - lastInteractionWarning > 30_000_000_000L) {
            lastInteractionWarning = now;
            getLogger().warning("Rejected example inventory operation: " + failure.getMessage());
        }
    }

    private record Position(UUID world, int x, int y, int z) {
        static Position of(Location location) { return new Position(location.getWorld().getUID(), location.getBlockX(), location.getBlockY(), location.getBlockZ()); }
        Location location() { return new Location(org.bukkit.Bukkit.getWorld(world), x, y, z); }
    }
}
