/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.ce.CraftEngineBridge;
import com.ydxc20091.energycore.config.EnergyCoreSettings;
import com.ydxc20091.energycore.async.SnapshotWorkQueue;
import com.ydxc20091.energycore.ui.DiagnosticsUi;
import io.papermc.paper.command.brigadier.*;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public final class EnergyCorePlugin extends JavaPlugin implements BukkitEnergyService {
    private CraftEngineBridge bridge;
    private volatile EnergyCoreSettings settings;
    private volatile SnapshotWorkQueue queue;
    private DiagnosticsUi ui;
    private final AtomicLong reloadRequest=new AtomicLong();
    public static BukkitEnergyService service() { BukkitEnergyService found=Bukkit.getServicesManager().load(BukkitEnergyService.class); if(found==null) throw new IllegalStateException("EnergyCore is unavailable"); return found; }
    @Override public void onLoad() {
        bridge=new CraftEngineBridge(this); bridge.register();
        if(!getDataFolder().toPath().resolve("config.yml").toFile().exists()) saveResource("config.yml",false);
        try { settings=EnergyCoreSettings.load(getDataFolder().toPath().resolve("config.yml")); } catch(Exception failure) { throw new IllegalStateException("Invalid EnergyCore configuration; file preserved",failure); }
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS,event->event.registrar().register("energycore","EnergyCore diagnostics",List.of("ec"),new AdminCommand()));
    }
    @Override public void onEnable() {
        queue=new SnapshotWorkQueue(settings.workers(),settings.queueCapacity()); ui=new DiagnosticsUi(this); bridge.start();
        getServer().getPluginManager().registerEvents(ui,this);
        getServer().getServicesManager().register(BukkitEnergyService.class,this,this,ServicePriority.Normal);
        getServer().getServicesManager().register(CraftEngineBridge.class,bridge,this,ServicePriority.Normal);
        getLogger().info("EnergyCore "+getPluginMeta().getVersion()+" by ydxc20091 enabled.");
    }
    @Override public void onDisable() { reloadRequest.incrementAndGet(); if(ui!=null) ui.close(); getServer().getServicesManager().unregisterAll(this); if(bridge!=null) bridge.close(); if(queue!=null) queue.close(); }
    @Override public CraftEngineBridge bridge() { return bridge; }
    @Override public SnapshotWorkQueue workQueue() { return queue; }
    @Override public Optional<EnergyStorage> storageAt(Location location,EnergySide side) { return bridge.resolver().resolve(location,side); }
    public EnergyCoreSettings settings() { return settings; }
    private final class AdminCommand implements BasicCommand {
        @Override public String permission() { return "energycore.admin"; }
        @Override public void execute(CommandSourceStack source,String[] args) {
            CommandSender sender=source.getSender(); String sub=args.length==0?"status":args[0].toLowerCase(Locale.ROOT);
            try {
                switch(sub) {
                    case "status" -> sender.sendMessage("EnergyCore "+getPluginMeta().getVersion()+" | ydxc20091 | unit="+settings.displayUnit()+" | queued="+queue.waiting()+" | rejected="+queue.rejected());
                    case "inspect" -> {
                        if(!(sender instanceof Player player)) { sender.sendMessage("Look at an energy store as a player."); return; }
                        var target=player.getTargetBlockExact(5);
                        if(target==null||!Bukkit.isOwnedByCurrentRegion(target.getLocation())) { sender.sendMessage("Target is unavailable on this region."); return; }
                        var storage=storageAt(target.getLocation());
                        if(storage.isEmpty()) { sender.sendMessage("No energy provider at target."); return; }
                        sender.sendMessage(storage.get().getEnergyStored()+" / "+storage.get().getMaxEnergyStored()+" "+settings.displayUnit());
                        bridge.resolver().controller(target.getLocation()).ifPresent(controller->{ if(controller.hasProtectedData()) sender.sendMessage("Unknown or damaged data is protected and preserved."); });
                    }
                    case "ui" -> { if(sender instanceof Player player) ui.open(player); else sender.sendMessage("Only players can open the menu."); }
                    case "reload" -> reloadSettings(sender);
                    default -> sender.sendMessage("/energycore status|inspect|ui|reload");
                }
            } catch(RuntimeException failure) { getLogger().warning("EnergyCore command failed: "+failure); sender.sendMessage("EnergyCore: "+failure.getMessage()); }
        }
        @Override public Collection<String> suggest(CommandSourceStack source,String[] args) { String prefix=args.length==0?"":args[0].toLowerCase(Locale.ROOT); return List.of("status","inspect","ui","reload").stream().filter(value->value.startsWith(prefix)).toList(); }
    }
    private void reloadSettings(CommandSender sender) {
        long request=reloadRequest.incrementAndGet(); SnapshotWorkQueue previous=queue;
        previous.submit(()->EnergyCoreSettings.load(getDataFolder().toPath().resolve("config.yml"))).whenComplete((loaded,failure)->{
            Runnable publish=()->{
                if(!isEnabled()||reloadRequest.get()!=request||queue!=previous) return;
                if(failure!=null) { sender.sendMessage("Reload failed; previous settings retained: "+failure.getMessage()); return; }
                SnapshotWorkQueue replacement=new SnapshotWorkQueue(loaded.workers(),loaded.queueCapacity()); settings=loaded; queue=replacement; previous.close();
                bridge.invalidateConfiguration();
                sender.sendMessage("EnergyCore settings reloaded. Reload CE packs using CraftEngine.");
            };
            if(sender instanceof Player player) player.getScheduler().run(this,task->publish.run(),()->{}); else getServer().getGlobalRegionScheduler().execute(this,publish);
        });
    }
}
