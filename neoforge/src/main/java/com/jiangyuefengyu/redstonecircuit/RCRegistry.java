package com.jiangyuefengyu.redstonecircuit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneGogglesItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneWrenchItem;
import com.jiangyuefengyu.redstonecircuit.item.RotationRodItem;
import com.jiangyuefengyu.redstonecircuit.item.SuperconductingRedstoneItem;

import net.minecraft.core.Holder;
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

    public static final DeferredRegister<ArmorMaterial> ARMOR_MATERIALS =
            DeferredRegister.create(Registries.ARMOR_MATERIAL, RedstoneCircuit.MODID);

    public static final DeferredRegister<CreativeModeTab> CREATIVE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, RedstoneCircuit.MODID);

    /**
     * The material behind the goggles: a helmet slot with nothing in it.
     *
     * <p>Every defence value is zero, so wearing the goggles shows no armour points and protects
     * against nothing - "佩戴栏位头，无护甲保护". The layer texture is the one thing a material must
     * have; it is the vanilla leather helmet shape recoloured red, in
     * {@code textures/models/armor/redstone_goggles_layer_1.png}.
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

    /**
     * 超导红石粉 - the item form of {@link com.jiangyuefengyu.redstonecircuit.data.ComponentType#SUPERCONDUCTOR}.
     *
     * <p>Stacks to sixteen like vanilla redstone, since the recipe produces nine at a time.
     */
    /**
     * 超导红石粉 as a block: an orange wire that loses nothing over distance.
     *
     * <p>Its properties are vanilla redstone wire's, so it is placed, supported, shaped and broken
     * exactly like the wire everyone already knows - the only difference is the strength calculation.
     */
    public static final DeferredHolder<Block, SuperconductingWireBlock> SUPERCONDUCTING_WIRE =
            BLOCKS.register("superconducting_redstone", () -> new SuperconductingWireBlock(
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
    public static final DeferredHolder<Item, SuperconductingRedstoneItem> SUPERCONDUCTING_REDSTONE =
            ITEMS.register("superconducting_redstone",
                    () -> new SuperconductingRedstoneItem(SUPERCONDUCTING_WIRE.get(),
                            new Item.Properties()));

    /** Our own creative tab; the items sit in it next to a stack of redstone. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.redstonecircuit"))
                    .icon(() -> new ItemStack(Items.REDSTONE))
                    .displayItems((parameters, output) -> {
                        output.accept(REDSTONE_GOGGLES.get());
                        output.accept(REDSTONE_WRENCH.get());
                        output.accept(ROTATION_ROD.get());
                        output.accept(SUPERCONDUCTING_REDSTONE.get());
                        output.accept(RETAINING_REPEATER_ITEM.get());
                        output.accept(DOUBLE_REPEATER_ITEM.get());
                        output.accept(DOUBLE_RETAINING_REPEATER_ITEM.get());
                        output.accept(WEATHER_DETECTOR_ITEM.get());
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
        ARMOR_MATERIALS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
