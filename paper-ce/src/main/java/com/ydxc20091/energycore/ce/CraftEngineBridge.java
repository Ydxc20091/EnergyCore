/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.bukkit.*;
import net.momirealms.craftengine.bukkit.api.*;
import net.momirealms.craftengine.bukkit.api.event.CraftEngineReloadEvent;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.block.behavior.BlockBehaviors;
import net.momirealms.craftengine.core.entity.player.*;
import net.momirealms.craftengine.core.item.behavior.ItemBehaviors;
import net.momirealms.craftengine.core.item.setting.ItemSettingsModifiers;
import net.momirealms.craftengine.core.loot.function.LootFunctions;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.util.Key;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import net.momirealms.craftengine.libraries.antigrieflib.Flag;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
/** All CraftEngine-specific registrations and lifecycle handling stay in the bridge. */
public final class CraftEngineBridge implements AutoCloseable,Listener {
    private final JavaPlugin plugin;
    private final BlockProviderResolver resolver;
    private final ItemEnergyRegistry batteries;
    private final ItemEnergyTransfers transfers;
    private final AtomicLong generation=new AtomicLong();
    private final ConcurrentHashMap<UUID,PlayerInventoryAccess> inventories=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<BlockAddress,byte[]> removedData=new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> scheduled=ConcurrentHashMap.newKeySet();
    private volatile boolean running;
    private boolean registered;
    public CraftEngineBridge(JavaPlugin plugin) {
        this.plugin=Objects.requireNonNull(plugin); resolver=new BlockProviderResolver(this);
        batteries=new ItemEnergyRegistry(this::definition,this::generation); transfers=new ItemEnergyTransfers(batteries);
    }
    public void register() {
        if(registered) throw new IllegalStateException("Bridge already registered");
        CraftEngine engine=CraftEngine.instance();
        if(engine==null||engine.isFullyLoaded()) throw new IllegalStateException("EnergyCore must register after CE onLoad and before its first pack parse");
        ItemSettingsModifiers.register(Key.of("energycore:battery"),value->{ BatteryDefinition definition=BatterySettings.parse(value.getAsSection()); return settings->settings.addCustomData(BatterySettings.TYPE,definition); });
        ItemBehaviors.register(Key.of("energycore:battery"),(pack,path,id,section)->new BatteryBehavior(this));
        BlockBehaviors.register(Key.of("energycore:storage"),(block,section)->new EnergyStorageBehavior(block,section,this));
        LootFunctions.register(Key.of("energycore:preserve_storage"),section->new PreserveStorageLootFunction(this));
        registered=true;
    }
    public void start() { if(!registered) throw new IllegalStateException("Register bridge first"); running=true; Bukkit.getPluginManager().registerEvents(this,plugin); }
    public JavaPlugin plugin() { return plugin; }
    public BlockProviderResolver resolver() { return resolver; }
    public ItemEnergyRegistry batteries() { return batteries; }
    public ItemEnergyTransfers transfers() { return transfers; }
    public long generation() { return generation.get(); }
    public boolean running() { return running; }
    public PlayerInventoryAccess inventory(Player player) {
        BukkitEnergyAccessContext.entity(player).checkAccess(); if(!running) throw new EnergyAccessException("EnergyCore is stopped");
        return inventories.compute(player.getUniqueId(),(id,current)->current==null||!current.isFor(player)?new PlayerInventoryAccess(player):current);
    }
    private BatteryDefinition definition(ItemStack item) { var found=CraftEngineItems.byItemStack(item); return found==null?null:found.settings().getCustomData(BatterySettings.TYPE); }
    public long invalidateConfiguration() {
        if(!running) throw new EnergyAccessException("EnergyCore is stopped");
        long current=generation.incrementAndGet();
        Bukkit.getPluginManager().callEvent(new EnergyCoreReloadEvent(current,!Bukkit.isPrimaryThread()));
        return current;
    }
    @EventHandler public void onReload(CraftEngineReloadEvent event) { if(running) invalidateConfiguration(); }
    @EventHandler public void onDisable(PluginDisableEvent event) { resolver.unregisterOwner(event.getPlugin()); batteries.unregisterOwner(event.getPlugin()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { inventories.remove(event.getPlayer().getUniqueId()); }
    public InteractionResult interact(UseOnContext context) {
        if(!running||context.getPlayer()==null) return InteractionResult.PASS;
        Player player=(Player)context.getPlayer().platformPlayer(); var position=context.getClickedPos();
        Location location=new Location((org.bukkit.World)context.getLevel().platformWorld(),position.x(),position.y(),position.z());
        if(!Bukkit.isOwnedByCurrentRegion(player)||!Bukkit.isOwnedByCurrentRegion(location)) return InteractionResult.SUCCESS_AND_CANCEL;
        var storage=resolver.resolve(location,null); if(storage.isEmpty()) return InteractionResult.PASS;
        if(!BukkitCraftEngine.instance().antiGriefProvider().test(player,Flag.OPEN_CONTAINER,location)) return InteractionResult.SUCCESS_AND_CANCEL;
        int slot=context.getHand()==InteractionHand.OFF_HAND?40:player.getInventory().getHeldItemSlot();
        try {
            var result=transfers.transfer(new ItemSlotAccess(inventory(player),slot),player.getGameMode(),storage.get(),Long.MAX_VALUE,
                player.isSneaking()?ItemEnergyTransfers.Direction.DISCHARGE:ItemEnergyTransfers.Direction.CHARGE,EnergyAction.EXECUTE);
            if(result.status()==ItemEnergyTransfers.Status.NOT_A_BATTERY) return InteractionResult.PASS;
            if(result.status()!=ItemEnergyTransfers.Status.SUCCESS) player.sendMessage("EnergyCore: "+result.status().name());
            return InteractionResult.SUCCESS_AND_CANCEL;
        } catch(EnergyTransaction.CommitNotificationException committed) { plugin.getLogger().warning("Energy transfer committed; notification failed: "+committed.getMessage()); return InteractionResult.SUCCESS_AND_CANCEL; }
        catch(EnergyAccessException rejected) { player.sendMessage("EnergyCore: "+rejected.getMessage()); return InteractionResult.SUCCESS_AND_CANCEL; }
    }
    void preserveRemoval(Location location,byte[] data) {
        if(!running) return; BlockAddress address=BlockAddress.of(location); byte[] snapshot=data.clone(); removedData.put(address,snapshot);
        Bukkit.getRegionScheduler().runDelayed(plugin,location,task->removedData.remove(address,snapshot),2);
    }
    Optional<byte[]> dataForDrop(Location location) {
        var current=resolver.controller(location); if(current.isPresent()) return Optional.of(current.get().savedData());
        byte[] removed=removedData.remove(BlockAddress.of(location)); return removed==null?Optional.empty():Optional.of(removed.clone());
    }
    public <T> CompletableFuture<T> schedule(Location location,Supplier<T> operation) {
        if(!running) return CompletableFuture.failedFuture(new EnergyAccessException("EnergyCore is stopped"));
        CompletableFuture<T> future=BukkitEnergyAccessContext.schedule(plugin,location,()->{ if(!running) throw new EnergyAccessException("EnergyCore stopped before operation"); return operation.get(); });
        scheduled.add(future); future.whenComplete((value,failure)->scheduled.remove(future)); if(!running) future.cancel(false); return future;
    }
    @Override public void close() { running=false; generation.incrementAndGet(); for(var future:scheduled) future.cancel(false); scheduled.clear(); removedData.clear(); inventories.clear(); resolver.close(); batteries.close(); HandlerList.unregisterAll(this); }
    private record BlockAddress(UUID world,int x,int y,int z) { static BlockAddress of(Location location) { return new BlockAddress(location.getWorld().getUID(),location.getBlockX(),location.getBlockY(),location.getBlockZ()); } }
}
