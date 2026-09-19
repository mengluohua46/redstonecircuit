package com.jiangyuefengyu.redstonecircuit;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.item.RedstoneGogglesItem;
import com.jiangyuefengyu.redstonecircuit.item.RedstoneWrenchItem;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
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
                    () -> new RedstoneGogglesItem(GOGGLES_MATERIAL, new Item.Properties()));

    public static final DeferredHolder<Item, RedstoneWrenchItem> REDSTONE_WRENCH =
            ITEMS.register("redstone_wrench",
                    () -> new RedstoneWrenchItem(new Item.Properties().stacksTo(1)));

    /** Our own creative tab; the two items sit in it next to a stack of redstone. */
    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> MAIN_TAB =
            CREATIVE_TABS.register("main", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.redstonecircuit"))
                    .icon(() -> new ItemStack(Items.REDSTONE))
                    .displayItems((parameters, output) -> {
                        output.accept(REDSTONE_GOGGLES.get());
                        output.accept(REDSTONE_WRENCH.get());
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

    /** The item id of the wrench. */
    public static boolean isWrench(ItemStack stack) {
        return !stack.isEmpty() && stack.is(REDSTONE_WRENCH.get());
    }

    /** Convenience for the renderer, which only has the registry values. */
    public static ResourceLocation gogglesId() {
        return RedstoneCircuit.id("redstone_goggles");
    }

    public static void register(IEventBus modEventBus) {
        ITEMS.register(modEventBus);
        ARMOR_MATERIALS.register(modEventBus);
        CREATIVE_TABS.register(modEventBus);
    }
}
