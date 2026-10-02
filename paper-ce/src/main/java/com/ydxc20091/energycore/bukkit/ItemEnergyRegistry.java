/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.bukkit;
import com.ydxc20091.energycore.api.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.LongSupplier;
public final class ItemEnergyRegistry implements AutoCloseable {
    private final Function<ItemStack,BatteryDefinition> definition;
    private final LongSupplier generation;
    private final CopyOnWriteArrayList<Registration> providers=new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    public ItemEnergyRegistry(Function<ItemStack,BatteryDefinition> definition,LongSupplier generation) { this.definition=definition; this.generation=generation; }
    public Optional<ItemEnergyHandler> resolve(ItemStack item,EnergyAccessContext context) {
        if(closed) throw new EnergyAccessException("Item registry is closed");
        context.checkAccess(); if(PlayerInventoryAccess.empty(item)) return Optional.empty();
        ItemStack countOne=item.clone(); countOne.setAmount(1); long current=generation.getAsLong();
        EnergyAccessContext guarded=new EnergyAccessContext() { public void checkAccess() { context.checkAccess(); if(closed||current!=generation.getAsLong()) throw new EnergyAccessException("Reload or shutdown invalidated battery handle"); } public long tick() { checkAccess(); return context.tick(); } };
        BatteryDefinition builtIn=definition.apply(item);
        if(builtIn!=null) return Optional.of(new ItemEnergyContainer(countOne,builtIn,guarded));
        for(Registration registration:providers) {
            if(!registration.owner.isEnabled()) { providers.remove(registration); continue; }
            EnergyAccessContext providerContext=new EnergyAccessContext() {
                public void checkAccess() { guarded.checkAccess(); if(!registration.owner.isEnabled()||!providers.contains(registration)) throw new EnergyAccessException("Item provider was unregistered"); }
                public long tick() { checkAccess(); return guarded.tick(); }
            };
            Optional<ItemEnergyHandler> result=Objects.requireNonNull(registration.provider.resolve(countOne.clone(),providerContext));
            if(result.isPresent()) return Optional.of(guard(result.get(),providerContext));
        }
        return Optional.empty();
    }
    public AutoCloseable register(Plugin owner,ItemEnergyProvider provider) {
        if(closed||!owner.isEnabled()) throw new IllegalArgumentException("Registry and provider owner must be enabled");
        Registration registration=new Registration(owner,provider); providers.add(registration); return ()->providers.remove(registration);
    }
    public void unregisterOwner(Plugin owner) { providers.removeIf(value->value.owner==owner); }
    @Override public void close() { closed=true; providers.clear(); }
    private ItemEnergyHandler guard(ItemEnergyHandler delegate,EnergyAccessContext owner) {
        EnergyAccessContext context=new EnergyAccessContext() {
            public void checkAccess() { owner.checkSameContext(delegate.context()); }
            public long tick() { checkAccess(); return owner.tick(); }
        };
        EnergyTransactionParticipant participant=new EnergyTransactionParticipant() {
            public Object snapshot() { validate(); return context.tick(); }
            public void restore(Object state) {}
            public void validate() { context.checkAccess(); }
            public void validateSnapshot(Object state) { validate(); if((long)state!=context.tick()) throw new EnergyAccessException("Provider transaction crossed a tick"); }
        };
        return new ItemEnergyHandler() {
            public EnergyAccessContext context() { return context; }
            public Object identity() { context.checkAccess(); return delegate.identity(); }
            public long getEnergyStored() { context.checkAccess(); return delegate.getEnergyStored(); }
            public long getMaxEnergyStored() { context.checkAccess(); return delegate.getMaxEnergyStored(); }
            public boolean canReceive() { context.checkAccess(); return delegate.canReceive(); }
            public boolean canExtract() { context.checkAccess(); return delegate.canExtract(); }
            public boolean supportsTransactions() { context.checkAccess(); return delegate.supportsTransactions(); }
            public ItemStack item() { context.checkAccess(); return delegate.item().clone(); }
            public boolean protectedData() { context.checkAccess(); return delegate.protectedData(); }
            public boolean allowStacked() { context.checkAccess(); return delegate.allowStacked(); }
            public long receiveEnergy(long amount,EnergyTransactionContext tx) { context.checkAccess(); tx.enlist(participant); return delegate.receiveEnergy(amount,tx); }
            public long extractEnergy(long amount,EnergyTransactionContext tx) { context.checkAccess(); tx.enlist(participant); return delegate.extractEnergy(amount,tx); }
        };
    }
    private record Registration(Plugin owner,ItemEnergyProvider provider) {}
}
