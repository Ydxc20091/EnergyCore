/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ui;
import com.ydxc20091.energycore.EnergyCorePlugin;
import com.ydxc20091.energycore.bukkit.EnergyCoreReloadEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.momirealms.sparrow.ui.SparrowUI;
import net.momirealms.sparrow.ui.item.StaticItem;
import net.momirealms.sparrow.ui.pane.*;
import net.momirealms.sparrow.ui.window.NormalWindow;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/** Open-time immutable diagnostics; no idle polling. */
public final class DiagnosticsUi implements Listener,AutoCloseable {
    private final EnergyCorePlugin plugin;
    private volatile boolean initialized;
    private volatile boolean closed;
    private final ConcurrentHashMap<UUID,TrackedWindow> windows=new ConcurrentHashMap<>();
    public DiagnosticsUi(EnergyCorePlugin plugin) { this.plugin=plugin; }
    public synchronized void initialize() {
        if(initialized||closed) return;
        String version=Bukkit.getMinecraftVersion();
        String[] parts=version.split("\\.");
        boolean supported=!version.startsWith("1.21")||(parts.length>2&&Integer.parseInt(parts[2])>=4);
        if(!supported) return;
        try { SparrowUI.getInstance().setUp(plugin); initialized=true; }
        catch(RuntimeException|LinkageError failure) {
            plugin.getLogger().warning("Sparrow UI diagnostics are unavailable on "+version+"; use /energycore inspect: "+failure);
        }
    }
    public void open(Player player) {
        if(closed||!plugin.isEnabled()||!Bukkit.isOwnedByCurrentRegion(player)) return;
        long generation=plugin.bridge().generation();
        if(!plugin.settings().diagnosticUi()||!initialized) { player.sendMessage("Use /energycore inspect on this configuration."); return; }
        var pane=NormalPane.empty(PaneSize.of(9,3));
        pane.setItem(10,display(Material.REDSTONE,"EnergyCore",List.of("Author: ydxc20091","Unit: "+plugin.settings().displayUnit(),"Reload generation: "+plugin.bridge().generation())));
        pane.setItem(12,display(Material.CLOCK,"Background work",List.of("Active: "+plugin.workQueue().active(),"Waiting: "+plugin.workQueue().waiting(),"Rejected: "+plugin.workQueue().rejected())));
        List<String> contents=new ArrayList<>(); var target=player.getTargetBlockExact(5);
        if(target!=null&&Bukkit.isOwnedByCurrentRegion(target.getLocation())) {
            plugin.storageAt(target.getLocation()).ifPresent(storage->contents.add(storage.getEnergyStored()+" / "+storage.getMaxEnergyStored()+" "+plugin.settings().displayUnit()));
            plugin.bridge().resolver().controller(target.getLocation()).ifPresent(controller->{ if(controller.hasProtectedData()) contents.add("Protected data preserved"); });
        }
        if(contents.isEmpty()) contents.add("Look at a nearby energy store and reopen");
        pane.setItem(14,display(Material.REDSTONE_BLOCK,"Target storage",contents));
        pane.setItem(16,display(Material.BOOK,"Transactions",List.of("Same thread, tick and ownership","Cross-region atomic moves rejected","Snapshot captured when opened")));
        NormalWindow window=NormalWindow.builder().setViewer(player).setTitle("EnergyCore").setUpperPane(pane).build();
        TrackedWindow tracked=new TrackedWindow(window,generation);
        UUID id=player.getUniqueId();
        TrackedWindow previous=windows.put(id,tracked);
        if(previous!=null) closeWindow(previous);
        window.addCloseHandler(reason->windows.remove(id,tracked));
        window.addOpenHandler(()->{
            if(closed||generation!=plugin.bridge().generation()||windows.get(id)!=tracked) closeWindow(tracked);
        });
        window.open().whenComplete((result,failure)->{
            if(failure!=null) { windows.remove(id,tracked); plugin.getLogger().warning("Cannot open diagnostics: "+failure.getMessage()); }
            else if(result==net.momirealms.sparrow.ui.window.Window.OpenResult.VIEWER_UNAVAILABLE) windows.remove(id,tracked);
            else if(closed||generation!=plugin.bridge().generation()||windows.get(id)!=tracked) closeWindow(tracked);
        });
    }
    @EventHandler public void reloaded(EnergyCoreReloadEvent event) {
        for(var entry:Map.copyOf(windows).entrySet())
            if(entry.getValue().generation<event.generation()&&windows.remove(entry.getKey(),entry.getValue())) closeWindow(entry.getValue());
    }
    @Override public void close() {
        closed=true;
        for(var entry:Map.copyOf(windows).entrySet()) if(windows.remove(entry.getKey(),entry.getValue())) closeWindow(entry.getValue());
    }
    private void closeWindow(TrackedWindow tracked) {
        try { tracked.window.close().exceptionally(failure->{ plugin.getLogger().fine("Diagnostic close skipped: "+failure.getMessage()); return null; }); }
        catch(RuntimeException failure) { plugin.getLogger().fine("Diagnostic close skipped: "+failure.getMessage()); }
    }
    private record TrackedWindow(NormalWindow window,long generation) {}
    private StaticItem display(Material material,String title,List<String> lines) {
        ItemStack item=new ItemStack(material); item.editMeta(meta->{ meta.displayName(Component.text(title,NamedTextColor.AQUA)); meta.lore(lines.stream().map(line->Component.text(line,NamedTextColor.GRAY)).toList()); }); return new StaticItem(item);
    }
}
