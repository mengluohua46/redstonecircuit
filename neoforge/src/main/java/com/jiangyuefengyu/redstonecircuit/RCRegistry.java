package com.jiangyuefengyu.redstonecircuit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.ControllerBlock;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonBlock;
import com.jiangyuefengyu.redstonecircuit.block.HoneyPistonHeadBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;
import com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock;
import com.jiangyuefengyu.redstonecircuit.block.TriggerBlock;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.item.FrequencyTunerItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneGogglesItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneWrenchItem;
import com.jiangyuefengyu.redstonecircuit.item.RotationRodItem;
import com.jiangyuefengyu.redstonecircuit.item.StructureBlockItem;
import com.jiangyuefengyu.redstonecircuit.item.SuperconductingRedstoneItem;
import com.jiangyuefengyu.redstonecircuit.logic.TunerRule;
import com.jiangyuefengyu.redstonecircuit.recipe.GoggleFittingRecipe;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.mojang.serialization.Codec;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Unit;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
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

    public static final DeferredRegister<RecipeSerializer<?>> RECIPE_SERIALIZERS =
            DeferredRegister.create(Registries.RECIPE_SERIALIZER, RedstoneCircuit.MODID);

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

    // ------------------------------------------------------ 同步火把 / 调频器 --

    /**
     * 同步火把 as a block: 红石火把's shape, direction rules, place and break behaviour, with the polarity reversed -
     * 吸附面 <b>有</b>信号才亮 - and the answer decided by the whole channel instead of by this torch
     * ({@code SyncGroups#tick} is the only writer).
     *
     * <p>Its properties are the vanilla redstone torch's, {@code lightLevel} included: a torch whose channel is driven
     * glows, which is the one thing visible from the outside that tells a player this is not a dead torch.
     */
    public static final DeferredHolder<Block, SyncTorchBlock> SYNC_TORCH =
            BLOCKS.register("sync_torch", () -> new SyncTorchBlock(
                    BlockBehaviour.Properties.of()
                            .instabreak()
                            .sound(SoundType.WOOD)
                            .noCollission()
                            .pushReaction(PushReaction.DESTROY)
                            .lightLevel(state -> state.getValue(SyncTorchBlock.LIT) ? 7 : 0)));

    /**
     * The item a player holds. A plain right-click on the ground puts the torch in the world as a block (vanilla's own
     * block placement, with the torch's own 附着 rule); shift-right-click on a host block stores it inside as the
     * {@code SYNC_TORCH} component - the same two gestures every other component item has ({@code HostRules}).
     */
    public static final DeferredHolder<Item, BlockItem> SYNC_TORCH_ITEM =
            ITEMS.register("sync_torch",
                    () -> new BlockItem(SYNC_TORCH.get(), new Item.Properties()));

    // ------------------------------------------------------ 红石触发器 --

    /**
     * 红石触发器 - 一条上升沿切换一次开关的元件, 站着的时候是贴在方块上/下的一小段柱子 (不是薄板: 它没有界面).
     *
     * <p>Properties as a lever's, minus {@code noCollission}: it has a shape of its own (a lever is flat against its
     * support and has none). {@code noOcclusion} like the four 薄板, which is also half of why it can never be a host -
     * the other half is that it is not a full cube, and a component inside it would be a second owner of a cell it
     * already answers from.
     */
    public static final DeferredHolder<Block, TriggerBlock> TRIGGER =
            BLOCKS.register("trigger", () -> new TriggerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(1.0F)
                            .sound(SoundType.WOOD)
                            .noOcclusion()));

    /**
     * The item a player holds: a plain right-click puts the trigger in the world as a block (贴地 or 贴天花板, and a wall
     * click is refused), while shift-right-click on a host block stores it inside as the {@code TRIGGER} component - the
     * same two gestures every other component item has ({@code HostRules}).
     */
    public static final DeferredHolder<Item, BlockItem> TRIGGER_ITEM =
            ITEMS.register("trigger", () -> new BlockItem(TRIGGER.get(), new Item.Properties()));

    /**
     * 调频器 - the item that binds 同步火把 into one channel and reads a channel back out of one.
     *
     * <p>Stacks to one because it carries an identity (one channel) rather than a count, the same reason
     * {@link #STRUCTURE_ITEM} does.
     */
    public static final DeferredHolder<Item, FrequencyTunerItem> FREQUENCY_TUNER_ITEM =
            ITEMS.register("frequency_tuner",
                    () -> new FrequencyTunerItem(new Item.Properties().stacksTo(1)));

    /**
     * 调频器's channel as an item component: the number <b>and</b> the dimension it belongs to.
     *
     * <p>Channels are handed out per dimension, so a bare number is not a channel: "3" in the overworld and "3" in
     * 下界 are two unrelated groups, and an item carrying only the number would bind across that line.
     *
     * <p>{@code networkSynchronized} for the same reason as {@link #STRUCTURE_DATA}: the tooltip is drawn on the
     * client, so a component that is only persistent arrives there empty - which reads as "the tuner lost its channel".
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<TunerRule.Channel>>
            TUNER_CHANNEL = DATA_COMPONENTS.register("tuner_channel",
                    () -> DataComponentType.<TunerRule.Channel>builder()
                            .persistent(TunerRule.Channel.CODEC)
                            .networkSynchronized(TunerRule.Channel.STREAM_CODEC)
                            .cacheEncoding()
                            .build());

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

    // ------------------------------------------------------------- 蜂蜜活塞 --

    /**
     * 蜂蜜活塞 - vanilla's piston plus one bit of state (see {@link HoneyPistonBlock}). Its properties are
     * {@code Blocks.PISTON}'s own, taken from the vanilla declaration rather than approximated, so it is placed, broken
     * and pushed like a piston; the two suffocating/view-blocking predicates are the vanilla ones, which answer by
     * {@code EXTENDED} because an extended base is not a full cube.
     *
     * <p>{@code Blocks.never} is private, hence the constants written out: this is what it says.
     */
    public static final DeferredHolder<Block, HoneyPistonBlock> HONEY_PISTON =
            BLOCKS.register("honey_piston", () -> new HoneyPistonBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(1.5F)
                            .isRedstoneConductor((state, level, pos) -> false)
                            .isSuffocating((state, level, pos) -> !state.getValue(HoneyPistonBlock.EXTENDED))
                            .isViewBlocking((state, level, pos) -> !state.getValue(HoneyPistonBlock.EXTENDED))
                            .pushReaction(PushReaction.BLOCK)));

    /** The head: {@code Blocks.PISTON_HEAD}'s properties, loot table included - it is never taken by a player. */
    public static final DeferredHolder<Block, HoneyPistonHeadBlock> HONEY_PISTON_HEAD =
            BLOCKS.register("honey_piston_head", () -> new HoneyPistonHeadBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(1.5F)
                            .noLootTable()
                            .pushReaction(PushReaction.BLOCK)));

    public static final DeferredHolder<Item, BlockItem> HONEY_PISTON_ITEM =
            ITEMS.register("honey_piston",
                    () -> new BlockItem(HONEY_PISTON.get(), new Item.Properties()));

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
                        output.accept(HONEY_PISTON_ITEM.get());
                        // HONEY_PISTON_HEAD has no item: it is the piston's own arm, and vanilla's head is not an item
                        // either.
                        output.accept(CONTROLLER_ITEM.get());
                        output.accept(TRANSFORMER_ITEM.get());
                        output.accept(CLOCK_ITEM.get());
                        output.accept(EXTENDER_ITEM.get());
                        output.accept(SYNC_TORCH_ITEM.get());
                        output.accept(TRIGGER_ITEM.get());
                        output.accept(FREQUENCY_TUNER_ITEM.get());
                        output.accept(REDSTONE_STRUCTURE_BLOCK_ITEM.get());
                        // 结构 is not on offer: it is the block a captured structure is carried in,
                        // an implementation detail of 红石结构方块, while the machine is the item a player is meant
                        // to hold. STRUCTURE_ITEM stays registered - a release hands it back, and two game tests
                        // use it.
                    })
                    .build());

    private RCRegistry() {
    }

    /**
     * The armour points every slot gets. A zero is left <b>out</b> rather than written as a zero: an attribute
     * modifier of 0 is still a modifier, so vanilla would draw the "when on head: +0 armour" block on the goggles -
     * which is exactly the line a pair of goggles must not have (they protect against nothing, and the tooltip is
     * where it says so).
     */
    private static Map<ArmorItem.Type, Integer> defense(int value) {
        Map<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        if (value == 0) {
            return defense;
        }
        for (ArmorItem.Type type : ArmorItem.Type.values()) {
            defense.put(type, value);
        }
        return defense;
    }

    /**
     * 红石眼镜 fitted into somebody else's helmet: a unit marker, because the <b>item stays whoever's it was</b> - the
     * ability is ours, the helmet is not, and a copy of it is what the player gets back.
     *
     * <p>Synced, because the tooltip and the ability are both read on the client.
     */
    public static final DeferredHolder<DataComponentType<?>, DataComponentType<Unit>> GOGGLES =
            DATA_COMPONENTS.register("goggles",
                    () -> DataComponentType.<Unit>builder()
                            .persistent(Codec.unit(Unit.INSTANCE))
                            .networkSynchronized(StreamCodec.unit(Unit.INSTANCE))
                            .build());

    /**
     * Headwear the fitting recipe accepts <b>besides</b> anything {@link Equipable} that names the helmet slot: the
     * escape hatch, for a mod that wears something on the head without saying so through the game's own interface.
     * The one entry shipped is Create's engineer's goggles - they are an {@code Equipable}, so the general rule
     * already takes them; naming them is the promise that they keep working if that ever changes.
     */
    public static final TagKey<net.minecraft.world.item.Item> GOGGLE_FIT_TARGETS =
            ItemTags.create(RedstoneCircuit.id("goggle_fit_targets"));

    /** 红石眼镜 fitting: one bare helmet plus one pair of goggles in, the same helmet back with the goggles inside. */
    public static final DeferredHolder<RecipeSerializer<?>, SimpleCraftingRecipeSerializer<GoggleFittingRecipe>>
            GOGGLE_FITTING_SERIALIZER = RECIPE_SERIALIZERS.register("goggle_fitting",
                    () -> new SimpleCraftingRecipeSerializer<>(GoggleFittingRecipe::new));

    /**
     * Whether this stack <b>is</b> the redstone goggles - the item, or a helmet they were fitted into. The one place
     * the question is answered: the renderer, the Jade overlay and every bare-hand rule read it, so a merged helmet
     * takes hold everywhere at once instead of in the places somebody remembered.
     */
    public static boolean isGoggles(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(REDSTONE_GOGGLES.get()) || stack.has(GOGGLES.get()));
    }

    /**
     * Whether {@code stack} is headwear the goggles may be fitted into: anything the game itself puts in the helmet
     * slot, or anything listed in {@link #GOGGLE_FIT_TARGETS}. A stack that already has them, or the goggles themselves,
     * is not a target - which is what makes a second fitting have no recipe at all.
     *
     * <p>{@link Equipable} and not {@link ArmorItem}: since 1.21 a wearable is whatever says which slot it goes in, and
     * wearing something on the head is not the same as being armour. Create's engineer's goggles are exactly that case
     * - a plain {@code Item implements Equipable} - and so is anything else a mod builds the same way.
     */
    public static boolean isBareHelmet(ItemStack stack) {
        if (stack.isEmpty() || isGoggles(stack)) {
            return false;
        }
        return stack.is(GOGGLE_FIT_TARGETS)
                || stack.getItem() instanceof Equipable wearable
                        && wearable.getEquipmentSlot() == EquipmentSlot.HEAD;
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
        RECIPE_SERIALIZERS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
