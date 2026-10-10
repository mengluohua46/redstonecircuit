package com.jiangyuefengyu.redstonecircuit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.ClockBlock;
import com.jiangyuefengyu.redstonecircuit.block.ControllerBlock;
import com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock;
import com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlock;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransformerBlock;
import com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneGogglesItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneWrenchItem;
import com.jiangyuefengyu.redstonecircuit.item.RotationRodItem;
import com.jiangyuefengyu.redstonecircuit.item.StructureBlockItem;
import com.jiangyuefengyu.redstonecircuit.item.SuperconductingRedstoneItem;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
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

/**
 * Central registry holders for the mod.
 *
 * <p>Two items, and one of them is armour - see {@link RedstoneGogglesItem} for why.
 *
 * <h2>Why the holders are {@code Holder} and no longer {@code DeferredHolder}</h2>
 * NeoForge's {@code DeferredRegister} hands out a lazy holder that only names the entry once the
 * registry event runs. Fabric has no such thing: {@code Registry.register} puts the object in the
 * registry there and then, and gives it back. The fields below therefore hold the real objects, which
 * is why {@link #register()} has to run before anything reads one - it is called first in
 * {@code RedstoneCircuit.onInitialize}, and the registry does not freeze until mod initialisation is
 * over.
 *
 * <p>The one thing a lazy holder bought that a plain field does not is the ability to reference an
 * entry from another entry's initialiser regardless of order. Java initialises static fields top to
 * bottom, and this class is written so the dependency order is already correct (the armour material
 * before the goggles, the blocks before their {@code BlockItem}s), so nothing had to be deferred.
 */
public final class RCRegistry {

    /**
     * The material behind the goggles: a helmet slot with nothing in it.
     *
     * <p>Every defence value is zero, so wearing the goggles shows no armour points and protects
     * against nothing - "佩戴栏位头，无护甲保护". The layer texture is the one thing a material must
     * have; it is the vanilla leather helmet shape recoloured red, in
     * {@code textures/models/armor/redstone_goggles_layer_1.png}.
     *
     * <p>Registered through {@code registerForHolder} rather than {@code register}, because that is the
     * one thing {@link RedstoneGogglesItem} actually needs: {@code ArmorItem}'s constructor takes a
     * {@code Holder<ArmorMaterial>}, and unlike NeoForge's {@code DeferredHolder} a plain
     * {@code ArmorMaterial} is not one - in 1.21.1 it is a record that <em>has</em> tags, so it needs a
     * {@code Holder.Reference} to look them up through.
     */
    public static final Holder.Reference<ArmorMaterial> GOGGLES_MATERIAL =
            Registry.registerForHolder(
                    BuiltInRegistries.ARMOR_MATERIAL, RedstoneCircuit.id("redstone_goggles"),
                    new ArmorMaterial(
                            defense(0),
                            0,
                            SoundEvents.ARMOR_EQUIP_LEATHER,
                            () -> Ingredient.of(Items.REDSTONE),
                            List.of(new ArmorMaterial.Layer(RedstoneCircuit.id("redstone_goggles"))),
                            0.0F,
                            0.0F));

    public static final RedstoneGogglesItem REDSTONE_GOGGLES = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("redstone_goggles"),
            // No durability, so they never wear out - and one per stack, or a player could put
            // sixty-four pairs on their head at once.
            new RedstoneGogglesItem(GOGGLES_MATERIAL, new Item.Properties().stacksTo(1)));

    public static final RedstoneWrenchItem REDSTONE_WRENCH = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("redstone_wrench"),
            new RedstoneWrenchItem(new Item.Properties().stacksTo(1)));

    /**
     * 方向转向杆 - the rotation rod, which points a component at a face rather than taking it out and
     * putting it back in. Like the wrench it only ever gets held, so it stacks to one.
     */
    public static final RotationRodItem ROTATION_ROD = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("rotation_rod"),
            new RotationRodItem(new Item.Properties().stacksTo(1)));

    // ------------------------------------------------------------- repeaters --

    /** Vanilla's own repeater properties, so the new ones are placed, broken and pushed like it. */
    private static BlockBehaviour.Properties repeaterProperties() {
        return BlockBehaviour.Properties.of()
                .instabreak()
                .sound(SoundType.STONE)
                .pushReaction(PushReaction.DESTROY);
    }

    /** 维持中继器 - a repeater that hands on the strength it was given instead of restoring it. */
    public static final VariableRepeaterBlock RETAINING_REPEATER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("retaining_repeater"),
            new VariableRepeaterBlock(repeaterProperties(), 1, true));

    /** 双倍红石中继器 - every delay setting lasts twice as long. */
    public static final VariableRepeaterBlock DOUBLE_REPEATER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("double_repeater"),
            new VariableRepeaterBlock(repeaterProperties(), 2, false));

    /** 双倍维持中继器 - twice the delay, and the strength kept. */
    public static final VariableRepeaterBlock DOUBLE_RETAINING_REPEATER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("double_retaining_repeater"),
            new VariableRepeaterBlock(repeaterProperties(), 2, true));

    public static final BlockItem RETAINING_REPEATER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("retaining_repeater"),
            new BlockItem(RETAINING_REPEATER, new Item.Properties()));

    public static final BlockItem DOUBLE_REPEATER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("double_repeater"),
            new BlockItem(DOUBLE_REPEATER, new Item.Properties()));

    public static final BlockItem DOUBLE_RETAINING_REPEATER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("double_retaining_repeater"),
            new BlockItem(DOUBLE_RETAINING_REPEATER, new Item.Properties()));

    // ---------------------------------------------------------------- weather --

    /**
     * 天气探测器 - a plate that holds a signal while the weather matches its setting.
     *
     * <p>Its properties are the daylight detector's, whose shape and gesture it copies: a two-tenths
     * wood-ish device that a player right-clicks to change what it watches.
     */
    public static final WeatherDetectorBlock WEATHER_DETECTOR = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("weather_detector"),
            new WeatherDetectorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.WOOD)
                            .instrument(NoteBlockInstrument.BASS)
                            .strength(0.2F)
                            .sound(SoundType.WOOD)
                            .ignitedByLava()));

    public static final BlockItem WEATHER_DETECTOR_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("weather_detector"),
            new BlockItem(WEATHER_DETECTOR, new Item.Properties()));

    /**
     * 超导红石粉 as a block: an orange wire that loses nothing over distance.
     *
     * <p>Its properties are vanilla redstone wire's, so it is placed, supported, shaped and broken
     * exactly like the wire everyone already knows - the only difference is the strength calculation.
     */
    public static final SuperconductingWireBlock SUPERCONDUCTING_WIRE = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("superconducting_redstone"),
            new SuperconductingWireBlock(
                    BlockBehaviour.Properties.of()
                            .noCollission()
                            .instabreak()
                            .sound(SoundType.STONE)
                            .pushReaction(PushReaction.DESTROY)));

    /**
     * The item form of {@link #SUPERCONDUCTING_WIRE}.
     *
     * <p>A {@code BlockItem}, so a plain right-click places the wire on the ground exactly as redstone
     * does, while shift-right-click puts it inside a block - which is the mod's own behaviour, and is
     * claimed before vanilla ever sees the click.
     */
    public static final SuperconductingRedstoneItem SUPERCONDUCTING_REDSTONE = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("superconducting_redstone"),
            new SuperconductingRedstoneItem(SUPERCONDUCTING_WIRE, new Item.Properties()));

    // -------------------------------------------------------- logic integrator --

    /**
     * 逻辑整合器 - a full cube with a front. {@code requiresCorrectToolForDrops} against the pickaxe tag
     * only, so any pickaxe takes it; otherwise it is placed, broken and pushed like stone.
     */
    public static final LogicIntegratorBlock LOGIC_INTEGRATOR = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("logic_integrator"),
            new LogicIntegratorBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_RED)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final BlockItem LOGIC_INTEGRATOR_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("logic_integrator"),
            new BlockItem(LOGIC_INTEGRATOR, new Item.Properties()));

    // ----------------------------------------------------------- block placer --

    /**
     * 方块放置器 - a machine with a front, a nine-slot box and one action. Its values are the dispenser's
     * own (stone map colour, strength 3.5) and its tool rule is the same pickaxe tag only.
     */
    public static final BlockPlacerBlock BLOCK_PLACER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("block_placer"),
            new BlockPlacerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final BlockItem BLOCK_PLACER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("block_placer"),
            new BlockItem(BLOCK_PLACER, new Item.Properties()));

    // --------------------------------------------------------- transmission gate --

    /**
     * 传输门 - front face is the main input, back face the main output, and the other four faces are
     * secondary inputs. Properties as 逻辑整合器's and 方块放置器's.
     */
    public static final TransmissionGateBlock TRANSMISSION_GATE = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("transmission_gate"),
            new TransmissionGateBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.STONE)
                            .strength(3.5F)
                            .sound(SoundType.STONE)
                            .requiresCorrectToolForDrops()));

    public static final BlockItem TRANSMISSION_GATE_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("transmission_gate"),
            new BlockItem(TRANSMISSION_GATE, new Item.Properties()));

    // ------------------------------------------------- the four plate devices --

    /**
     * 控制器 - a plate. No {@code requiresCorrectToolForDrops}, so a bare hand may take it, and a loot
     * table beside it, so taking it gives one back.
     *
     * <p>{@code noOcclusion} is the plate's own half - one eighth of a block tall, so it must not block
     * light or hide what is behind it - and it is also what keeps it from ever being a host: a component
     * stored inside a plate would be a second owner of a cell the plate already answers from.
     */
    public static final ControllerBlock CONTROLLER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("controller"),
            new ControllerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.GOLD)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final BlockItem CONTROLLER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("controller"),
            new BlockItem(CONTROLLER, new Item.Properties()));

    /**
     * 红石变压器 - the second of the four 带界面的红石元件. Properties identical to 控制器's; the map colour
     * is the recipe's own - copper and quartz.
     */
    public static final TransformerBlock TRANSFORMER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("transformer"),
            new TransformerBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_ORANGE)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final BlockItem TRANSFORMER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("transformer"),
            new BlockItem(TRANSFORMER, new Item.Properties()));

    /** 时钟源 - a free-running clock, the third of the four; the map colour is the recipe's own gold. */
    public static final ClockBlock CLOCK = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("clock"),
            new ClockBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_YELLOW)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final BlockItem CLOCK_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("clock"),
            new BlockItem(CLOCK, new Item.Properties()));

    /** 信号延长器 - the fourth and last of the four; the map colour is the recipe's own lapis. */
    public static final ExtenderBlock EXTENDER = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("extender"),
            new ExtenderBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.LAPIS)
                            .strength(1.0F)
                            .sound(SoundType.STONE)
                            .noOcclusion()));

    public static final BlockItem EXTENDER_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("extender"),
            new BlockItem(EXTENDER, new Item.Properties()));

    // -------------------------------------------------- redstone structure block --

    /**
     * 红石结构方块 - the controller: capture a region, or put one back.
     *
     * <p>Its properties are the vanilla structure block's - a block that only exists to be configured, so
     * it is not supposed to be the interesting part of a build. It drops itself, like any block.
     */
    public static final RedstoneStructureBlock REDSTONE_STRUCTURE_BLOCK = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("redstone_structure_block"),
            new RedstoneStructureBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(3.5F)
                            .sound(SoundType.METAL)
                            .requiresCorrectToolForDrops()));

    /**
     * 结构 - the product: one captured structure, as a block.
     *
     * <p>{@code noOcclusion}: the block draws nothing, but an invisible block is still a full occluding
     * cube, so every neighbour would cull the face it shares with it and the ground would read as a hole
     * you can see the sky through. A block that draws nothing must not claim to hide what is behind it.
     *
     * <p>Not on offer in the creative tab - it is the block a captured structure is carried in, while the
     * machine is the item a player is meant to hold. It stays registered because a release hands it back.
     */
    public static final StructureBlock STRUCTURE = Registry.register(
            BuiltInRegistries.BLOCK, RedstoneCircuit.id("structure"),
            new StructureBlock(
                    BlockBehaviour.Properties.of()
                            .mapColor(MapColor.COLOR_PURPLE)
                            .strength(3.5F)
                            .sound(SoundType.METAL)
                            .noOcclusion()
                            // The indicator the block entity writes into its own state is the light it emits.
                            // Declared here rather than as a method on the block, because how bright a state is
                            // is one of the properties a block is built with, and the light engine reads it
                            // there - so the emitted light follows the property with no second place to update.
                            .lightLevel(state -> state.getValue(StructureBlock.LIGHT))
                            .requiresCorrectToolForDrops()));

    public static final BlockEntityType<RedstoneStructureBlockEntity> REDSTONE_STRUCTURE_BLOCK_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    RedstoneCircuit.id("redstone_structure_block"),
                    BlockEntityType.Builder.of(RedstoneStructureBlockEntity::new,
                            REDSTONE_STRUCTURE_BLOCK).build(null));

    public static final BlockEntityType<StructureBlockEntity> STRUCTURE_BLOCK_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RedstoneCircuit.id("structure"),
                    BlockEntityType.Builder.of(StructureBlockEntity::new, STRUCTURE).build(null));

    public static final BlockEntityType<BlockPlacerBlockEntity> BLOCK_PLACER_ENTITY =
            Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, RedstoneCircuit.id("block_placer"),
                    BlockEntityType.Builder.of(BlockPlacerBlockEntity::new, BLOCK_PLACER).build(null));

    public static final BlockItem REDSTONE_STRUCTURE_BLOCK_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("redstone_structure_block"),
            new BlockItem(REDSTONE_STRUCTURE_BLOCK, new Item.Properties()));

    /**
     * 结构's payload as an item component: the structure's id and the few fields that describe it.
     *
     * <p>{@code networkSynchronized} because a client handed a structure item has to be able to name it
     * and draw it: a component that is only persistent would arrive empty on the client, which reads as
     * "the item lost its structure".
     */
    public static final DataComponentType<StructureProductData> STRUCTURE_DATA = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE, RedstoneCircuit.id("structure"),
            DataComponentType.<StructureProductData>builder()
                    .persistent(StructureProductData.CODEC)
                    .networkSynchronized(StructureProductData.STREAM_CODEC)
                    .cacheEncoding()
                    .build());

    /** Stacks to one: a structure block carries an identity, not a count. */
    public static final StructureBlockItem STRUCTURE_ITEM = Registry.register(
            BuiltInRegistries.ITEM, RedstoneCircuit.id("structure"),
            new StructureBlockItem(STRUCTURE, new Item.Properties().stacksTo(1)));

    /** Our own creative tab; the items sit in it next to a stack of redstone. */
    public static final CreativeModeTab MAIN_TAB = Registry.register(
            BuiltInRegistries.CREATIVE_MODE_TAB, RedstoneCircuit.id("main"),
            CreativeModeTab.builder(CreativeModeTab.Row.TOP, 0)
                    .title(Component.translatable("itemGroup.redstonecircuit"))
                    .icon(() -> new ItemStack(REDSTONE_STRUCTURE_BLOCK_ITEM))
                    .displayItems((parameters, output) -> {
                        output.accept(REDSTONE_GOGGLES);
                        output.accept(REDSTONE_WRENCH);
                        output.accept(ROTATION_ROD);
                        output.accept(SUPERCONDUCTING_REDSTONE);
                        output.accept(RETAINING_REPEATER_ITEM);
                        output.accept(DOUBLE_REPEATER_ITEM);
                        output.accept(DOUBLE_RETAINING_REPEATER_ITEM);
                        output.accept(WEATHER_DETECTOR_ITEM);
                        output.accept(LOGIC_INTEGRATOR_ITEM);
                        output.accept(BLOCK_PLACER_ITEM);
                        output.accept(TRANSMISSION_GATE_ITEM);
                        output.accept(CONTROLLER_ITEM);
                        output.accept(TRANSFORMER_ITEM);
                        output.accept(CLOCK_ITEM);
                        output.accept(EXTENDER_ITEM);
                        output.accept(REDSTONE_STRUCTURE_BLOCK_ITEM);
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
        return !stack.isEmpty() && stack.is(REDSTONE_GOGGLES);
    }

    /**
     * Whether {@code player} currently has the redstone goggles on their head.
     *
     * <p>This is the one place the "is the wearer looking through the goggles" question is answered.
     * It lives here rather than in the renderer because more than the renderer asks it - the Jade
     * overlay shows the inside of a block under exactly the same condition the shell is drawn, and two
     * copies of the check would be free to drift apart, which is how a player ends up seeing the
     * information without the item. See {@code HostRenderPass.wearingGoggles}, which now delegates here
     * and whose rendering behaviour is unchanged by the move.
     *
     * <p>The parameter is {@link Player} rather than {@code LocalPlayer} on purpose: the question is
     * about the helmet slot, not about the side, and keeping the client-only type out of the registry
     * means this stays loadable on a dedicated server.
     */
    public static boolean isWearingGoggles(Player player) {
        return player != null && isGoggles(player.getItemBySlot(EquipmentSlot.HEAD));
    }

    /** The item id of the wrench. */
    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && stack.is(REDSTONE_WRENCH);
    }

    /** The item id of the rotation rod. */
    public static boolean isRotationRod(ItemStack stack) {
        return !stack.isEmpty() && stack.is(ROTATION_ROD);
    }

    /** Convenience for the renderer, which only has the registry values. */
    public static ResourceLocation gogglesId() {
        return RedstoneCircuit.id("redstone_goggles");
    }

    /** A structure item for a captured structure - the form a player takes by hand. */
    public static ItemStack structureStack(StructureProductData data) {
        ItemStack stack = new ItemStack(STRUCTURE_ITEM);
        stack.set(STRUCTURE_DATA, data);
        return stack;
    }

    /** The structure a stack describes, or {@code null} when it is an empty or plain structure item. */
    public static StructureProductData structureOf(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(STRUCTURE_DATA);
    }

    /**
     * Touches the class, which is all that is needed: every entry above registers itself as it is
     * initialised, in declaration order. Kept as a named call so the entrypoint reads as a list of
     * registrations and so a future entry with a side condition has somewhere to live.
     */
    public static void register() {
        // Static initialisation does the work; see the class comment.
    }
}

