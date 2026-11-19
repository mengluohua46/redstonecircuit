package com.jiangyuefengyu.redstonecircuit.item;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Equipable;
import net.minecraft.world.level.block.Block;

/**
 * 天气探测器（方块物品）：方块与物品是同一件，戴上只能**手动放进头盔槽**。
 *
 * <p>Nothing puts this on by itself. It is {@link Equipable} with {@link EquipmentSlot#HEAD} because that is what makes
 * the helmet slot accept it - {@code ArmorSlot#mayPlace} asks {@code ItemStack#canEquip}, which answers with
 * {@link net.minecraft.world.entity.LivingEntity#getEquipmentSlotForItem} and therefore with this slot - and that is the
 * whole of the rule. There is deliberately no {@code use} override: a held right-click is a repeat action, and equipping
 * from it turns "put it on" into a gesture that also has to be de-bounced against the helmet that comes off (it is
 * wearable too, so the held button would swap the two all the way down). 右键对着空气什么都不做；对着方块照旧放置，
 * 因为放置走的是 {@code useOn}，与物品自己的 {@code use} 无关.
 */
public class WeatherDetectorItem extends BlockItem implements Equipable {

    public WeatherDetectorItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** 头槽 - the same slot a helmet goes in, so the two are mutually exclusive. */
    @Override
    public EquipmentSlot getEquipmentSlot() {
        return EquipmentSlot.HEAD;
    }
}
