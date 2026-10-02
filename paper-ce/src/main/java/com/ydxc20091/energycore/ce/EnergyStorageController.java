/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.core.*;
import com.ydxc20091.energycore.bukkit.*;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.libraries.nbt.*;
import net.momirealms.craftengine.libraries.nbt.Tag;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import java.io.*;
import java.util.zip.CRC32;
/** Passive by default. All machine fields are published in one immutable committed envelope. */
public class EnergyStorageController extends BlockEntityController implements EnergyTransactionParticipant {
    public static final String DATA_KEY="energycore:storage";
    private static final int MAX_ENVELOPE_BYTES=4*1024*1024;
    private static final int ENVELOPE_OVERHEAD=25;
    protected final CraftEngineBridge bridge;
    protected EnergyStorageBehavior behavior;
    private final EnergyAccessContext context;
    private EnergyBuffer buffer;
    private EnergyStorage privateStorage;
    private EnergyPorts ports;
    private volatile byte[] savedData=encodeEnvelope(0,new byte[0]);
    private volatile Tag malformedTag;
    private byte[] pendingExtra=new byte[0];
    private byte[] preparedCommit;
    private long pendingEnergy,version;
    private long lifecycle;
    private long configurationGeneration=-1;
    private boolean active,protectedData;
    public EnergyStorageController(BlockEntity entity,EnergyStorageBehavior behavior,CraftEngineBridge bridge) {
        super(entity); this.bridge=bridge; this.behavior=behavior;
        context=new BukkitEnergyAccessContext(()->blockEntity.world!=null&&Bukkit.isOwnedByCurrentRegion(location()),()->bridge.running()&&active&&blockEntity.isValid()&&blockEntity.isValidForTick());
    }
    public Location location() {
        if(blockEntity.world==null) throw new EnergyAccessException("CE block entity has no world");
        return new Location((World)blockEntity.world.world().platformWorld(),blockEntity.pos.x(),blockEntity.pos.y(),blockEntity.pos.z());
    }
    public EnergyAccessContext accessContext() { return context; }
    public boolean active() { return active&&bridge.running(); }
    public long version() { context.checkAccess(); return version; }
    public boolean hasProtectedData() { context.checkAccess(); return protectedData; }
    public byte[] savedData() { return savedData.clone(); }
    private void ensureBuffer() {
        context.checkAccess();
        if(configurationGeneration!=bridge.generation()) refreshConfiguration();
        if(buffer!=null) return;
        long epoch=lifecycle;
        EnergyAccessContext bufferContext=new EnergyAccessContext() {
            public void checkAccess() { context.checkAccess(); if(epoch!=lifecycle) throw new EnergyAccessException("Storage buffer belongs to an expired lifecycle"); }
            public long tick() { checkAccess(); return context.tick(); }
        };
        buffer=new EnergyBuffer(behavior.capacity(),pendingEnergy,bufferContext,()->{});
        EnergyBuffer backing=buffer;
        privateStorage=new EnergyStorage() {
            @Override public long getEnergyStored() { return backing.getEnergyStored(); }
            @Override public long getMaxEnergyStored() { return backing.getMaxEnergyStored(); }
            @Override public boolean canReceive() { bufferContext.checkAccess(); return !protectedData; }
            @Override public boolean canExtract() { bufferContext.checkAccess(); return !protectedData; }
            @Override public EnergyAccessContext context() { return bufferContext; }
            @Override public Object identity() { bufferContext.checkAccess(); return backing.identity(); }
            @Override public long receiveEnergy(long amount,EnergyTransactionContext transaction) { bufferContext.checkAccess(); enlist(transaction); return backing.receiveEnergy(amount,transaction); }
            @Override public long extractEnergy(long amount,EnergyTransactionContext transaction) { bufferContext.checkAccess(); enlist(transaction); return backing.extractEnergy(amount,transaction); }
        };
        ports=new EnergyPorts(privateStorage,behavior.inputRate(),behavior.outputRate(),behavior.ports());
    }
    public EnergyStorage privateStorage() { ensureBuffer(); return guard(privateStorage); }
    public EnergyStorage storage(EnergySide side) { ensureBuffer(); return guard(ports.port(side)); }
    public EnergyStorage storageExternal() { ensureBuffer(); return guard(ports.external()); }
    private EnergyStorage guard(EnergyStorage delegate) {
        long generation=bridge.generation(),epoch=lifecycle;
        return new EnergyStorage() {
            private void check() { context.checkAccess(); if(generation!=bridge.generation()||epoch!=lifecycle) throw new EnergyAccessException("Reload or unload invalidated the storage handle; resolve it again"); }
            @Override public long getEnergyStored() { check(); return delegate.getEnergyStored(); }
            @Override public long getMaxEnergyStored() { check(); return delegate.getMaxEnergyStored(); }
            @Override public boolean canReceive() { check(); return !protectedData&&delegate.canReceive(); }
            @Override public boolean canExtract() { check(); return !protectedData&&delegate.canExtract(); }
            @Override public Object identity() { check(); return delegate.identity(); }
            @Override public EnergyAccessContext context() { return new EnergyAccessContext() { public void checkAccess() { check(); } public long tick() { check(); return context.tick(); } }; }
            @Override public long receiveEnergy(long amount,EnergyTransactionContext tx) { check(); enlist(tx); return delegate.receiveEnergy(amount,tx); }
            @Override public long extractEnergy(long amount,EnergyTransactionContext tx) { check(); enlist(tx); return delegate.extractEnergy(amount,tx); }
        };
    }
    public void enlist(EnergyTransactionContext tx) {
        context.checkAccess(); if(protectedData) throw new EnergyAccessException("Machine contains protected unknown or damaged data");
        tx.enlist(this);
    }
    protected byte[] captureExtraData() { return new byte[0]; }
    protected void restoreExtraData(byte[] bytes) { if(bytes.length!=0) throw new IllegalArgumentException("Unexpected machine data"); }
    protected Object snapshotExtraState() { return null; }
    protected void restoreExtraSnapshot(Object snapshot) {}
    protected void validateExtraState() {}
    protected void afterStateCommit() {}
    protected void onBehaviorReload(EnergyStorageBehavior replacement) {}
    private void refreshConfiguration() {
        EnergyStorageBehavior replacement=blockEntity.blockState.behavior().getFirst(EnergyStorageBehavior.class);
        if(replacement==null) throw new EnergyAccessException("The block no longer has an EnergyCore behavior");
        if(replacement!=behavior) {
            if(EnergyTransaction.hasOpenTransaction()) throw new EnergyAccessException("Resolve reloaded machine configuration before opening a transaction");
            onBehaviorReload(replacement);
            behavior=replacement; buffer=null; privateStorage=null; ports=null; lifecycle++;
        }
        configurationGeneration=bridge.generation();
    }
    @Override public Object snapshot() { validate(); return new ControllerSnapshot(snapshotExtraState(),context.tick(),bridge.generation(),lifecycle); }
    @Override public void restore(Object snapshot) { context.checkAccess(); restoreExtraSnapshot(((ControllerSnapshot)snapshot).extra); preparedCommit=null; }
    @Override public void validate() { context.checkAccess(); if(protectedData) throw new EnergyAccessException("Protected machine data"); validateExtraState(); }
    @Override public void validateSnapshot(Object snapshot) {
        validate(); ControllerSnapshot state=(ControllerSnapshot)snapshot;
        if(state.tick!=context.tick()||state.generation!=bridge.generation()||state.lifecycle!=lifecycle) throw new EnergyAccessException("Machine transaction crossed a tick, reload or unload");
        ensureBuffer(); preparedCommit=encodeEnvelope(buffer.getEnergyStored(),captureExtraData());
    }
    @Override public Runnable prepareCommitNotification() {
        ensureBuffer();
        if(preparedCommit==null) throw new IllegalStateException("Missing validated machine commit snapshot");
        savedData=preparedCommit; preparedCommit=null; pendingEnergy=buffer.getEnergyStored(); pendingExtra=java.util.Arrays.copyOfRange(savedData,17,savedData.length-8); version++;
        blockEntity.world.blockEntityChanged(blockEntity.pos);
        Location committedLocation=location();
        long committedVersion=version;
        return ()->{
            Throwable failure=null;
            try { Bukkit.getPluginManager().callEvent(new EnergyStorageCommitEvent(committedLocation,committedVersion)); } catch(Throwable thrown) { failure=thrown; }
            try { afterStateCommit(); } catch(Throwable thrown) { if(failure==null) failure=thrown; else failure.addSuppressed(thrown); }
            if(failure!=null) throw new IllegalStateException("Machine committed, notification failed",failure);
        };
    }
    @Override public void afterCommit() { prepareCommitNotification().run(); }
    private record ControllerSnapshot(Object extra,long tick,long generation,long lifecycle) {}
    @Override public void onLoad() {
        active=true;
        if(!protectedData) try {
            refreshConfiguration(); restoreExtraData(pendingExtra.clone());
            if(pendingExtra.length==0) {
                pendingExtra=captureExtraData().clone();
                savedData=encodeEnvelope(pendingEnergy,pendingExtra);
            }
        }
        catch(RuntimeException failure) { protectedData=true; bridge.plugin().getLogger().warning("Preserving invalid EnergyCore machine data at "+location()+": "+failure.getMessage()); }
    }
    @Override public void onUnload() { discardLiveState(); }
    @Override public void onRemove() {
        try { if(blockEntity.world!=null) bridge.preserveRemoval(location(),savedData); }
        finally { discardLiveState(); }
    }
    private void discardLiveState() {
        active=false; lifecycle++;
        buffer=null; privateStorage=null; ports=null; preparedCommit=null;
        configurationGeneration=-1;
    }
    @Override public void saveCustomData(CompoundTag tag) { Tag malformed=malformedTag; if(malformed!=null) tag.put(DATA_KEY,malformed.deepClone()); else tag.putByteArray(DATA_KEY,savedData.clone()); }
    @Override public void loadCustomData(CompoundTag tag) {
        byte[] raw=tag.getByteArray(DATA_KEY);
        if(raw!=null) read(raw);
        else if(tag.containsKey(DATA_KEY)) { malformedTag=tag.get(DATA_KEY).deepClone(); protectedData=true; savedData=archive(malformedTag); }
    }
    @Override public void loadCustomDataFromItem(Item item) {
        if(!(item.platformItem() instanceof ItemStack stack)||!stack.hasItemMeta()) return;
        var pdc=stack.getItemMeta().getPersistentDataContainer();
        if(pdc.has(ItemEnergyData.BLOCK_DATA_KEY,PersistentDataType.BYTE_ARRAY)) read(pdc.get(ItemEnergyData.BLOCK_DATA_KEY,PersistentDataType.BYTE_ARRAY));
        else if(pdc.has(ItemEnergyData.BLOCK_DATA_KEY)) { protectedData=true; try { savedData=pdc.serializeToBytes(); } catch(IOException failure) { throw new UncheckedIOException(failure); } }
    }
    private void read(byte[] raw) {
        if(active&&bridge.running()) {
            context.checkAccess();
            if(EnergyTransaction.hasOpenTransaction()) throw new EnergyAccessException("Cannot replace machine data during a transaction");
        }
        savedData=raw.clone(); malformedTag=null;
        try {
            if(raw.length<ENVELOPE_OVERHEAD||raw.length>MAX_ENVELOPE_BYTES) throw new IOException("Invalid envelope length");
            DataInputStream in=new DataInputStream(new ByteArrayInputStream(raw));
            if(in.readInt()!=0x45434D31||in.readUnsignedByte()!=1) throw new IOException("Unknown envelope version");
            long energy=in.readLong(); int length=in.readInt();
            if(energy<0||length<0||length!=raw.length-ENVELOPE_OVERHEAD) throw new IOException("Invalid envelope fields");
            byte[] extra=in.readNBytes(length); long checksum=in.readLong(); CRC32 crc=new CRC32(); crc.update(raw,0,raw.length-8);
            if(checksum!=crc.getValue()) throw new IOException("Envelope checksum mismatch");
            pendingEnergy=energy; pendingExtra=extra; protectedData=false;
            if(buffer!=null) { buffer=null; privateStorage=null; ports=null; lifecycle++; }
            if(active&&bridge.running()) {
                context.checkAccess(); restoreExtraData(pendingExtra.clone());
            }
        } catch(IOException|RuntimeException failure) { protectedData=true; }
    }
    private static byte[] encodeEnvelope(long energy,byte[] extra) {
        if(energy<0) throw new IllegalArgumentException("Negative stored energy");
        if(extra.length>MAX_ENVELOPE_BYTES-ENVELOPE_OVERHEAD)
            throw new IllegalArgumentException("Machine snapshot exceeds the 4 MiB envelope limit");
        try {
            ByteArrayOutputStream output=new ByteArrayOutputStream(); DataOutputStream data=new DataOutputStream(output);
            data.writeInt(0x45434D31); data.writeByte(1); data.writeLong(energy); data.writeInt(extra.length); data.write(extra); data.flush();
            CRC32 crc=new CRC32(); crc.update(output.toByteArray()); data.writeLong(crc.getValue()); data.flush(); return output.toByteArray();
        } catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
    private static byte[] archive(Tag tag) {
        try { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); DataOutputStream output=new DataOutputStream(bytes); output.writeInt(0x45434152); output.writeByte(tag.getId()); tag.write(output); output.flush(); return bytes.toByteArray(); }
        catch(IOException failure) { throw new UncheckedIOException(failure); }
    }
}
