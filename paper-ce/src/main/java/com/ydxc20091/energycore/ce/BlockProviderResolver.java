/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.bukkit.*;
import net.momirealms.craftengine.bukkit.api.BukkitAdaptor;
import net.momirealms.craftengine.core.block.entity.BlockEntity;
import net.momirealms.craftengine.core.world.BlockPos;
import org.bukkit.*;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
/** Resolves live loaded controllers; never retains chunk or block-entity references. */
public final class BlockProviderResolver {
    private final CraftEngineBridge bridge;
    private final CopyOnWriteArrayList<Registration> providers=new CopyOnWriteArrayList<>();
    BlockProviderResolver(CraftEngineBridge bridge) { this.bridge=bridge; }
    public Optional<EnergyStorage> resolve(Location location,EnergySide side) {
        var builtin=controller(location).map(value->side==null?value.storageExternal():value.storage(side));
        if(builtin.isPresent()||!bridge.running()) return builtin;
        for(Registration registration:providers) {
            if(!registration.owner.isEnabled()) { providers.remove(registration); continue; }
            Optional<EnergyStorage> result=Objects.requireNonNull(registration.provider.resolve(location.clone(),side),"Provider returned null");
            if(result.isPresent()) return Optional.of(guard(location,registration,result.get()));
        }
        return Optional.empty();
    }
    public AutoCloseable register(Plugin owner,BlockEnergyProvider provider) {
        if(!bridge.running()||!owner.isEnabled()) throw new IllegalStateException("Provider owner and EnergyCore must be enabled");
        Registration registration=new Registration(owner,Objects.requireNonNull(provider)); providers.add(registration); return ()->providers.remove(registration);
    }
    public <T> CompletableFuture<Optional<T>> executeScheduled(Location location,EnergySide side,Function<EnergyStorage,T> operation) {
        return bridge.schedule(location,()->resolve(location,side).map(operation));
    }
    void unregisterOwner(Plugin owner) { providers.removeIf(value->value.owner==owner); }
    void close() { providers.clear(); }
    private EnergyStorage guard(Location location,Registration registration,EnergyStorage delegate) {
        long generation=bridge.generation();
        EnergyAccessContext owner=BukkitEnergyAccessContext.block(location,()->bridge.running()&&registration.owner.isEnabled()&&providers.contains(registration)&&generation==bridge.generation());
        owner.checkSameContext(delegate.context());
        EnergyAccessContext context=new EnergyAccessContext() { public void checkAccess() { owner.checkSameContext(delegate.context()); } public long tick() { checkAccess(); return owner.tick(); } };
        EnergyTransactionParticipant guard=new EnergyTransactionParticipant() {
            public Object snapshot() { validate(); return context.tick(); }
            public void restore(Object state) {}
            public void validate() { context.checkAccess(); }
            public void validateSnapshot(Object state) { validate(); if((long)state!=context.tick()) throw new EnergyAccessException("Provider transaction crossed a tick"); }
        };
        return new EnergyStorage() {
            @Override public EnergyAccessContext context() { return context; }
            @Override public Object identity() { context.checkAccess(); return delegate.identity(); }
            @Override public boolean supportsTransactions() { context.checkAccess(); return delegate.supportsTransactions(); }
            @Override public long getEnergyStored() { context.checkAccess(); return delegate.getEnergyStored(); }
            @Override public long getMaxEnergyStored() { context.checkAccess(); return delegate.getMaxEnergyStored(); }
            @Override public boolean canReceive() { context.checkAccess(); return delegate.canReceive(); }
            @Override public boolean canExtract() { context.checkAccess(); return delegate.canExtract(); }
            @Override public long receiveEnergy(long amount,EnergyTransactionContext tx) { context.checkAccess(); tx.enlist(guard); return delegate.receiveEnergy(amount,tx); }
            @Override public long extractEnergy(long amount,EnergyTransactionContext tx) { context.checkAccess(); tx.enlist(guard); return delegate.extractEnergy(amount,tx); }
        };
    }
    public Optional<EnergyStorageController> controller(Location location) {
        if(!Bukkit.isOwnedByCurrentRegion(location)) throw new EnergyAccessException("Resolve block providers on the owning region thread");
        if(!bridge.running()||location.getWorld()==null||!location.getWorld().isChunkLoaded(location.getBlockX()>>4,location.getBlockZ()>>4)) return Optional.empty();
        BlockEntity entity=BukkitAdaptor.adapt(location.getWorld()).storageWorld().getBlockEntityAtIfLoaded(new BlockPos(location.getBlockX(),location.getBlockY(),location.getBlockZ()));
        if(entity==null||!entity.isValid()) return Optional.empty();
        EnergyStorageController[] found=new EnergyStorageController[1];
        entity.controller.let(EnergyStorageController.class,value->{ if(found[0]!=null) throw new EnergyAccessException("Only one EnergyCore controller is allowed per block"); found[0]=value; });
        return Optional.ofNullable(found[0]);
    }
    private record Registration(Plugin owner,BlockEnergyProvider provider) {}
}
