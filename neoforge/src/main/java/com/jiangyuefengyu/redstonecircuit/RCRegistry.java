package com.jiangyuefengyu.redstonecircuit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.ControllerBlock;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;
import com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneGogglesItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneWrenchItem;
import com.jiangyuefengyu.redstonecircuit.item.RotationRodItem;
import com.jiangyuefengyu.redstonecircuit.item.StructureBlockItem;
import com.jiangyuefengyu.redstonecircuit.item.SuperconductingRedstoneItem;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Central registry holders for the mod.
 *
 * <p>Two items, and one of them is armour - see {@link RedstoneGogglesItem} for why.
 */
public final class RCRegistry {

    public static final DeferredRegister.Items ITEMS =
            DeferredRegister.createItems(RedstoneCircuit.MODID);

    public static final DeferredRegister.Blocks BLOCKS =
            DeferredRegister.createBlocks(RedstoneCircuit.MODID);

    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, RedstoneCircuit.MODID);

    public static final DeferredRegister<DataComponentType<?>> DATA_COMPONENTS =
            DeferredRegister.create(Registries.DATA_COMPONENT_TYPE, RedstoneCircuit.MODID);

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, RedstoneCircuit.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RedstoneCircuit.MODID);

    /**
     * The material behind the goggles: a helmet slot with nothing in it, every defence value zero, so wearing
     * them shows no armour points and protects against nothing.
     *
     * <p>The layer texture is the one thing a material must have: the vanilla leather helmet shape recoloured
     * red, at {@code textures/models/armor/redstone_goggles_layer_1.png}.
     */
    public static final DeferredHolder<ArmorMaterial, ArmorMaterial> GOGGLES_MATERIAL =
            ARMOR_MATERIALS.register("redstone_goggles", () -> new ArmorMaterial(
                    defense(0),
                    0,
                    SoundEvents.ARMOR_EQUIP_LEATHER,
                    () -> Ingredient.of(Items.REDSTONE),
                    List.of(new ArmorMaterial.Layer(RedstoneCircuit.id("redstone_goggles"))),
                    0.0F,
                    0.0F));

    public static final DeferredHolder<Item, RedstoneGogglesItem> REDSTONE_GOGGLES =
            ITEMS.register("redstone_goggles",
                    // No durability, so they never wear out - and one per stack, or a player could put
                    // sixty-four pairs on their head at once.
                    () -> new RedstoneGogglesItem(GOGGLES_MATERIAL, new Item.Properties().stacksTo(1)));

    public static final DeferredHolder<Item, RedstoneWrenchItem> REDSTONE_WRENCH =
            ITEMS.register("redstone_wrench",
                    () -> new RedstoneWrenchItem(new Item.Properties().stacksTo(1)));

    /**
     * 方向转向杆 - the rotation rod, which points a component at a face rather than taking it out and
     * putting it back in. Like the wrench it only ever gets held, so it stacks to one.
     */
    public static final DeferredHolder<Item, RotationRodItem> ROTATION_ROD =
            ITEMS.register("rotation_rod",
                    () -> new RotationRodItem(new Item.Properties().stacksTo(1)));

    // ------------------------------------------------------------- repeaters --

    /** Vanilla's own repeater properties, so the new ones are placed, broken and pushed like it. */
    private static BlockBehaviour.Properties repeaterProperties() {
        return BlockBehaviour.Properties.of()
                .instabreak()
                .sound(SoundType.STONE)
                .pushReaction(PushReaction.DESTROY);
    }

    /** 维持中继器 - a repeater that hands on the strength it was given instead of restoring it. */
    public static final DeferredHolder<Block, VariableRepeaterBlock> RETAINING_REPEATER =
            BLOCKS.register("retaining_repeater",
                    () -> new VariableRepeaterBlock(repeaterProperties(), 1, true));

    /** 双倍红石中继器 - every delay setting lasts twice as long. */
    public static final DeferredHolder<Block, VariableRepeaterBlock> DOUBLE_REPEATER =
            BLOCKS.register("double_repeater",
                    () -> new VariableRepeaterBlock(repeaterProperties(), 2, false));

    /** 双倍维持中继器 - twice the delay, and the strength kept. */
    public static final DeferredHolder<Block, VariableRepeaterBlock> DOUBLE_RETAINING_REPEATER =
            BLOCKS.register("double_retaining_repeater",
                    () -> new VariableRepeaterBlock(repeaterProperties(), 2, true));

    public static final DeferredHolder<Item, BlockItem> RETAINING_REPEATER_ITEM =
            ITEMS.register("retaining_repeater",
                    () -> new BlockItem(RETAINING_REPEATER.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> DOUBLE_REPEATER_ITEM =
            ITEMS.register("double_repeater",
                    () -> new BlockItem(DOUBLE_REPEATER.get(), new Item.Properties()));

    public static final DeferredHolder<Item, BlockItem> DOUBLE_RETAINING_REPEATER_ITEM =
            ITEMS.register("double_retaining_repeater",
                    () -> new BlockItem(DOUBLE_RETAINING_REPEATER.get(), new Item.Properties()));

    // ---------------------------------------------------------------- weather --

    /**
     * 天气探测器 - a plate that holds a signal while the weather matches its setting.
     *
     * <p>Its properties are the daylight detector's, whose shape and gesture it copies: a two-tenths
     * wood-ish device that a player right-clicks to change what it watches.
     */
    public static final DeferredHolder<Block, WeatherDetectorBlock> WEATHER_DETECTOR =
            BLOCKS.register("weather_detector", () -> new WeatherDetectorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .instrument(NoteBlockInstrument.BASS)
                            .strength(0.2F)
                            .sound(SoundType.WOOD)
                            .ignitedByLava()));

    public static final DeferredHolder<Item, BlockItem> WEATHER_DETECTOR_ITEM =
            ITEMS.register("weather_detector",
                    () -> new BlockItem(WEATHER_DETECTOR.get(), new Item.Properties()));

    // -------------------------------------------------------- logic integrator --

    /**
     * 逻辑整合器 - a full cube with a front. {@code requiresCorrectToolForDrops} against the pickaxe tag only,
     * so any pickaxe takes it; otherwise it is placed, broken and pushed like stone.
     */
    public static final DeferredHolder<Block, LogicIntegratorBlock> LOGIC_INTEGRATOR =
            BLOCKS.register("logic_integrator", () -> new LogicIntegratorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_RED)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> LOGIC_INTEGRATOR_ITEM =
            ITEMS.register("logic_integrator",
                    () -> new BlockItem(LOGIC_INTEGRATOR.get(), new Item.Properties()));

    // ------------------------------------------------------------ block placer --

    /**
     * 方块放置器 - a machine with a front, a nine-slot box and one action. Its values are the dispenser's own
     * (stone map colour, strength 3.5) and its tool rule is the same pickaxe tag only.
     */
    public static final DeferredHolder<Block, BlockPlacerBlock> BLOCK_PLACER =
            BLOCKS.register("block_placer", () -> new BlockPlacerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> BLOCK_PLACER_ITEM =
            ITEMS.register("block_placer",
                    () -> new BlockItem(BLOCK_PLACER.get(), new Item.Properties()));

    /**
     * 传输门 - front face is the main input, back face the main output, and the other four faces are secondary
     * inputs. Properties as 逻辑整合器's and 方块放置器's: stone map colour, strength 3.5, pickaxe tag only.
     */
    public static final DeferredHolder<Block, TransmissionGateBlock> TRANSMISSION_GATE =
            BLOCKS.register("transmission_gate", () -> new TransmissionGateBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final DeferredHolder<Item, BlockItem> TRANSMISSION_GATE_ITEM =
            ITEMS.register("transmission_gate",
                    () -> new BlockItem(TRANSMISSION_GATE.get(), new Item.Properties()));

    // -------------------------------------------------------------- 控制器 --

    /**
     * 控制器 - a plate. No {@code requiresCorrectToolForDrops}, so a bare hand may take it, and a loot table
     * beside it, so taking it gives one back.
     *
     * <p>{@code noOcclusion} is the plate's own half - one eighth of a block tall, so it must not block light
     * or hide what is behind it - and it is also what keeps it from ever being a host: a component stored
     * inside a plate would be a second owner of a cell the plate already answers from.
     */
    public static final DeferredHolder<Block, ControllerBlock> CONTROLLER =
            BLOCKS.register("controller", () -> new ControllerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> CONTROLLER_ITEM =
            ITEMS.register("controller",
                    () -> new BlockItem(CONTROLLER.get(), new Item.Properties()));

    /**
     * 红石变压器 - the second of the four 带界面的红石元件. Properties identical to 控制器's: no tool requirement,
     * a loot table, and {@code noOcclusion} so an eighth-of-a-block plate neither blocks light nor can be a
     * host. The map colour is the recipe's own - copper and quartz.
     */
    public static final DeferredHolder<Block, TransformerBlock> TRANSFORMER =
            BLOCKS.register("transformer", () -> new TransformerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_ORANGE)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> TRANSFORMER_ITEM =
            ITEMS.register("transformer",
                    () -> new BlockItem(TRANSFORMER.get(), new Item.Properties()));

    /**
     * 时钟源 - a free-running clock, the third of the four 带界面的红石元件. Properties identical to 控制器's and
     * 红石变压器's; the map colour is the recipe's own gold.
     */
    public static final DeferredHolder<Block, ClockBlock> CLOCK =
            BLOCKS.register("clock", () -> new ClockBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> CLOCK_ITEM =
            ITEMS.register("clock",
                    () -> new BlockItem(CLOCK.get(), new Item.Properties()));

    /**
     * 信号延长器 - the fourth and last of the 带界面的红石元件. Properties identical to the other three; the map colour is
     * the recipe's own lapis.
     */
    public static final DeferredHolder<Block, ExtenderBlock> EXTENDER =
            BLOCKS.register("extender", () -> new ExtenderBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.LAPIS)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final DeferredHolder<Item, BlockItem> EXTENDER_ITEM =
            ITEMS.register("extender",
                    () -> new BlockItem(EXTENDER.get(), new Item.Properties()));

    /** 超导红石粉 - the item form of the superconductor component. */
    /**
     * 超导红石粉 as a block: an orange wire that loses nothing over distance, and is otherwise placed,
     * supported and broken exactly like vanilla redstone wire.
     */
    public static final DeferredHolder<Block, SuperconductingWireBlock> SUPERCONDUCTING_WIRE =
            BLOCKS.register("superconducting_redstone", () -> new SuperconductingWireBlock(
                    BlockBehaviour.Properties.of()
                            .noCollission()
                            .instabreak()
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.DESTROY)));

    /**
     * The item form of {@link #SUPERCONDUCTING_WIRE}: a plain right-click places the wire on the ground as
     * redstone does, while shift-right-click puts it inside a block - the mod's own behaviour, claimed before
     * vanilla ever sees the click.
     */
    public static final DeferredHolder<Item, SuperconductingRedstoneItem> SUPERCONDUCTING_REDSTONE =
            ITEMS.register("superconducting_redstone",
                    () -> new SuperconductingRedstoneItem(SUPERCONDUCTING_WIRE.get(),
                            new Item.Properties()));

    // -------------------------------------------------- redstone structure block --

    /**
     * 红石结构方块 - the controller: capture a region, or put one back.
     *
     * <p>Its properties are the vanilla structure block's - a block that only exists to be configured, so
     * it is not supposed to be the interesting part of a build. It drops itself, like any block.
     */
    public static final DeferredHolder<Block, RedstoneStructureBlock> REDSTONE_STRUCTURE_BLOCK =
            BLOCKS.register("redstone_structure_block", () -> new RedstoneStructureBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(3.5F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    /**
     * 结构 - the product: one captured structure, as a block.
     *
     * <p>Stacks to one as an <em>item</em> (see the item below) because it carries an identity, not a
     * count: two of them are two different structures, and a stack of sixty-four of them would be a
     * stack of sixty-four different things.
     */
    public static final DeferredHolder<Block, StructureBlock> STRUCTURE =
            BLOCKS.register("structure", () -> new StructureBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(3.5F)
                            .sound(SoundType.METAL)
                            // The block draws nothing - the miniature is its look - but an invisible block is
                            // still a full occluding cube, so every neighbour culls the face it shares with it
                            // and the ground reads as a hole you can see the sky through. noOcclusion: a block
                            // that draws nothing must not claim to hide what is behind it.
                            .noOcclusion()
                            .requiresCorrectToolForDrops()));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<RedstoneStructureBlockEntity>>
            REDSTONE_STRUCTURE_BLOCK_ENTITY = BLOCK_ENTITIES.register("redstone_structure_block",
                    () -> BlockEntityType.Builder.of(RedstoneStructureBlockEntity::new,
                            REDSTONE_STRUCTURE_BLOCK.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<StructureBlockEntity>>
            STRUCTURE_BLOCK_ENTITY = BLOCK_ENTITIES.register("structure",
                    () -> BlockEntityType.Builder.of(StructureBlockEntity::new,
                            STRUCTURE.get()).build(null));

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<BlockPlacerBlockEntity>>
            BLOCK_PLACER_ENTITY = BLOCK_ENTITIES.register("block_placer",
                    () -> BlockEntityType.Builder.of(BlockPlacerBlockEntity::new,
                            BLOCK_PLACER.get()).build(null));

    public static final DeferredHolder<Item, BlockItem> REDSTONE_STRUCTURE_BLOCK_ITEM =
            ITEMS.register("redstone_structure_block",
                    () -> new BlockItem(REDSTONE_STRUCTURE_BLOCK.get(), new Item.Properties()));

    /**
     * 结构's payload as an item component: the structure's id and the few fields that describe it.
     *
     * <p>{@code networkSynchronized} because a client handed a structure item has to be able to name it and
     * draw it: a component that is only persistent would arrive empty on the client, which reads as "the item
     * lost its structure".
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<StructureProductData>>
            STRUCTURE_DATA = DATA_COMPONENTS.register("structure",
                    () -> DataComponentType.<StructureProductData>builder()
                            .persistent(StructureProductData.CODEC)
                            .networkSynchronized(StructureProductData.STREAM_CODEC)
                            .cacheEncoding()
                            .build());

    public static final DeferredHolder<Item, StructureBlockItem> STRUCTURE_ITEM =
            ITEMS.register("structure",
                    () -> new StructureBlockItem(STRUCTURE.get(), new Item.Properties().stacksTo(1)));

    /** A structure item for a captured structure - the form the design says a player takes by hand. */
    public static ItemStack structureStack(StructureProductData data) {
        ItemStack stack = new ItemStack(STRUCTURE_ITEM.get());
        stack.set(STRUCTURE_DATA.get(), data);
        return stack;
    }

    /** The structure a stack describes, or {@code null} when it is an empty or plain structure item. */
    public static StructureProductData structureOf(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(STRUCTURE_DATA.get());
    }

    /** Our own creative tab; the items sit in it, and its icon is 红石结构方块. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.redstonecircuit"))
                    // Lazy, like every icon supplier: this runs when the tab is drawn, long after the holders
                    // below it have been bound, so reaching for one of our own items here is safe.
                    .icon(() -> new ItemStack(REDSTONE_STRUCTURE_BLOCK_ITEM.get()))
                    .displayItems((parameters, output) -> {
                        output.accept(REDSTONE_GOGGLES.get());
                        output.accept(REDSTONE_WRENCH.get());
                        output.accept(ROTATION_ROD.get());
                        output.accept(SUPERCONDUCTING_REDSTONE.get());
                        output.accept(RETAINING_REPEATER_ITEM.get());
                        output.accept(DOUBLE_REPEATER_ITEM.get());
                        output.accept(DOUBLE_RETAINING_REPEATER_ITEM.get());
                        output.accept(WEATHER_DETECTOR_ITEM.get());
                        output.accept(LOGIC_INTEGRATOR_ITEM.get());
                        output.accept(BLOCK_PLACER_ITEM.get());
                        output.accept(TRANSMISSION_GATE_ITEM.get());
                        output.accept(CONTROLLER_ITEM.get());
                        output.accept(TRANSFORMER_ITEM.get());
                        output.accept(CLOCK_ITEM.get());
                        output.accept(EXTENDER_ITEM.get());
                        output.accept(REDSTONE_STRUCTURE_BLOCK_ITEM.get());
                        // 结构 is not on offer: it is the block a captured structure is carried in,
                        // an implementation detail of 红石结构方块, while the machine is the item a player is meant
                        // to hold. STRUCTURE_ITEM stays registered - a release hands it back, and two game tests
                        // use it.
                    })
                    .build());

    private RCRegistry() {
    }

    private static Map<ArmorItem.Type, Integer> defense(int value) {
        Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type type : ArmorItem.Type.values()) {
            defense.put(type, value);
        }
        return defense;
    }

    /** The item id of the goggles, for the renderer's "am I wearing them" check. */
    public static boolean isGoggles(ItemStack stack) {
        return !stack.isEmpty() && stack.is(REDSTONE_GOGGLES.get());
    }

    /**
     * Whether {@code player} currently has the redstone goggles on their head - the one place that question is
     * answered, so the renderer and the Jade overlay cannot drift apart.
     *
     * <p>The parameter is {@link Player} rather than {@code LocalPlayer} on purpose: the question is about the
     * helmet slot, not about the side, and keeping the client-only type out of the registry means this stays
     * loadable on a dedicated server.
     */
    public static boolean isWearingGoggles(Player player) {
        return player != null && isGoggles(player.getItemBySlot(EquipmentSlot.HEAD));
    }

    /** The item id of the wrench. */
    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && stack.is(REDSTONE_WRENCH.get());
    }

    /** The item id of the rotation rod. */
    public static boolean isRotationRod(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ROTATION_ROD.get());
    }

    /** Convenience for the renderer, which only has the registry values. */
    public static ResourceLocation gogglesId() {
        return RedstoneCircuit.id("redstone_goggles");
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        BLOCKS.register(modEventBus);
        BLOCK_ENTITIES.register(modEventBus);
        DATA_COMPONENTS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
