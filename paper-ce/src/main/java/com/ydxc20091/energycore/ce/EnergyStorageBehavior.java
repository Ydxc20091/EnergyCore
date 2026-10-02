/* Copyright 2026 ydxc20091. SPDX-License-Identifier: GPL-3.0-only */
package com.ydxc20091.energycore.ce;
import com.ydxc20091.energycore.api.*;
import com.ydxc20091.energycore.bukkit.ItemEnergyData;
import net.momirealms.craftengine.bukkit.block.behavior.BukkitBlockBehavior;
import net.momirealms.craftengine.core.block.*;
import net.momirealms.craftengine.core.block.behavior.EntityBlock;
import net.momirealms.craftengine.core.block.entity.*;
import net.momirealms.craftengine.core.entity.player.InteractionResult;
import net.momirealms.craftengine.core.item.Item;
import net.momirealms.craftengine.core.plugin.config.ConfigSection;
import net.momirealms.craftengine.core.world.BlockPos;
import net.momirealms.craftengine.core.world.World;
import net.momirealms.craftengine.core.world.context.UseOnContext;
import org.bukkit.inventory.ItemStack;
import java.util.*;
import com.ydxc20091.energycore.core.EnergyPorts;
/** Extensible CE behavior for passive stores and independent machine controllers. */
public class EnergyStorageBehavior extends BukkitBlockBehavior implements EntityBlock {
    protected final CraftEngineBridge bridge;
    private final long capacity,inputRate,outputRate;
    private final Map<EnergySide,EnergyPorts.PortConfig> ports;
    private int controllerId;
    public EnergyStorageBehavior(BlockDefinition block,ConfigSection section,CraftEngineBridge bridge) {
        super(block); this.bridge=bridge;
        capacity=EnergyConfigurationValues.longValue(section,"capacity",100000);
        inputRate=EnergyConfigurationValues.longValue(section,"input-per-tick",1000);
        outputRate=EnergyConfigurationValues.longValue(section,"output-per-tick",1000);
        if(capacity<=0||inputRate<0||outputRate<0) throw new IllegalArgumentException("Invalid storage capacity or rate");
        EnumMap<EnergySide,EnergyPorts.PortConfig> configured=new EnumMap<>(EnergySide.class);
        if(section.containsKey("sides")) {
            ConfigSection sides=section.getSection("sides");
            for(EnergySide side:EnergySide.values()) {
                String key=side.name().toLowerCase(Locale.ROOT);
                if(!sides.containsKey(key)) continue;
                Object raw=sides.get(key);
                if(raw instanceof Map<?,?>) {
                    ConfigSection port=sides.getSection(key);
                    String mode=EnergyConfigurationValues.string(port,"mode");
                    long in=EnergyConfigurationValues.longValue(port,"input-per-tick",inputRate),out=EnergyConfigurationValues.longValue(port,"output-per-tick",outputRate);
                    configured.put(side,new EnergyPorts.PortConfig(mode==null?EnergyPortMode.BOTH:EnergyPortMode.valueOf(mode.toUpperCase(Locale.ROOT)),in,out));
                } else {
                    EnergyPortMode mode=EnergyPortMode.valueOf(raw.toString().toUpperCase(Locale.ROOT));
                    configured.put(side,new EnergyPorts.PortConfig(mode,inputRate,outputRate));
                }
            }
        }
        ports=Map.copyOf(configured);
    }
    public long capacity() { return capacity; }
    public long inputRate() { return inputRate; }
    public long outputRate() { return outputRate; }
    public Map<EnergySide,EnergyPorts.PortConfig> ports() { return ports; }
    @Override public void initControllerId(int id) { controllerId=id; }
    @Override public BlockEntityController createBlockEntityController(BlockEntity entity) { return new EnergyStorageController(entity,this,bridge); }
    @Override public InteractionResult useOnBlock(UseOnContext context,ImmutableBlockState state) { return bridge.interact(context); }
    @Override public InteractionResult useWithoutItem(UseOnContext context,ImmutableBlockState state) { return bridge.interact(context); }
    @Override public Item itemToPickup(World world,BlockPos position,ImmutableBlockState state,net.momirealms.craftengine.core.entity.player.Player player) {
        // Picking a block creates its default item; committed state moves through its unique loot entry.
        return Item.byId(block().id());
    }
}
