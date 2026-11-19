package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingEquipmentChangeEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * 彩蛋：把天气探测器戴在头上，天气说了算。
 *
 * <p>Three effects, one per sky, and they are <b>mutually exclusive</b> - the author's call: 晴天 +4 攻击, 雨天 +4 生命上限,
 * 雷暴只给"攻击附带引雷"。Thunder is rain in vanilla, so a "both" reading would have been possible; it was ruled out on
 * purpose so that each setting of the detector has one thing of its own.
 *
 * <p><b>属性是动态的，靠两处保证正确</b>: a modifier is put on when the weather (or the head slot) says so and taken off
 * when it stops, and the same method does both directions, so a stale bonus cannot survive a weather change, taking the
 * item off, or dying - it is re-derived from the world every {@link #POLL} ticks rather than remembered. Losing +4 生命上限
 * clamps health by hand, because an attribute alone would leave a player at 24/20.
 *
 * <p><b>引雷挂在伤害之后</b> ({@link LivingDamageEvent.Post}): 打中了才落雷, and the bolt's own damage never comes back
 * through this hook, because a bolt's {@code getOwner} is empty and its source is not a player.
 */
public final class WeatherDetectorWearer {

    /** {@code +4} 攻击：加算，跟剑的加成同级。 */
    public static final ResourceLocation CLEAR_ATTACK =
            ResourceLocation.fromNamespaceAndPath("redstonecircuit", "weather_detector_attack");
    /** {@code +4} 生命上限（两颗心）：加算，脱下就撤。 */
    public static final ResourceLocation RAIN_HEALTH =
            ResourceLocation.fromNamespaceAndPath("redstonecircuit", "weather_detector_health");

    public static final double BONUS = 4.0D;

    /** 半秒看一次天气。天气按 tick 变，但人的手比这慢得多。 */
    public static final int POLL = 10;

    @SubscribeEvent
    public void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player && player.tickCount % POLL == 0) {
            refresh(player);
        }
    }

    /** 头盔槽一变就立刻重算，不必等那半秒：手动放进槽里的东西，玩家马上就想看见效果。 */
    @SubscribeEvent
    public void onEquipmentChange(LivingEquipmentChangeEvent event) {
        if (event.getSlot() == EquipmentSlot.HEAD && event.getEntity() instanceof Player player) {
            refresh(player);
        }
    }

    /** 只问"该不该挂"，不关心是不是服务端：属性在 {@code LivingEntity} 上，两侧问法一样。 */

    /** 头上那件是不是天气探测器。 */
    public static boolean wearing(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && head.is(RCRegistry.WEATHER_DETECTOR_ITEM.get());
    }

    /**
     * 按"戴着没有 + 现在什么天"重算一次属性。两条修饰符都走这里，所以挂上和撤下是同一段代码。
     */
    public static void refresh(Player player) {
        apply(player, wearing(player)
                ? WeatherDetectorBlock.weatherOf(player.level().isRaining(), player.level().isThundering())
                : null);
    }

    /**
     * 同样的重算，但天气由调用方给。
     *
     * <p>分开是有原因的：{@code GameTest} 的关卡连一场雨都下不了（原版 {@code setWeatherParameters} 在那里改不动
     * {@code isRaining()}，而且天气是关卡级的，会被别的用例串味），所以"哪个天给哪条加成"必须能在不碰天气的情况下问。
     * 天气到 {@link WeatherDetectorBlock.Mode} 的那一步是 {@code weatherOf} 的事，另有单测。
     *
     * @param sky {@code null} 表示头上没戴（两条都撤）
     */
    public static void apply(Player player, WeatherDetectorBlock.Mode sky) {
        set(player, Attributes.ATTACK_DAMAGE, CLEAR_ATTACK, sky == WeatherDetectorBlock.Mode.CLEAR);
        set(player, Attributes.MAX_HEALTH, RAIN_HEALTH, sky == WeatherDetectorBlock.Mode.RAIN);
        // 撤掉 +4 生命上限的那一瞬间血会留在上限之上（原版不会替你削），所以两个方向都在这儿收一次。
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(player.getMaxHealth());
        }
    }

    /** 挂上或撤掉一条修饰符；返回"现在是否挂着"。 */
    private static boolean set(Player player, Holder<Attribute> attribute, ResourceLocation id, boolean wanted) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        if (wanted) {
            instance.addOrUpdateTransientModifier(
                    new AttributeModifier(id, BONUS, AttributeModifier.Operation.ADD_VALUE));
            return true;
        }
        instance.removeModifier(id);
        return false;
    }

    /** 打中了：雷暴里，戴着它的人打中谁，谁头上就落一道雷。 */
    @SubscribeEvent
    public void onDamage(LivingDamageEvent.Post event) {
        if (event.getSource().getEntity() instanceof Player player && event.getEntity() != player) {
            onHit(player, event.getEntity(), player.level().isThundering());
        }
    }

    /**
     * 同一件事，但"现在打不打雷"由调用方给 —— 理由同 {@link #apply}：关卡里造不出雷暴。
     *
     * @return 是否真的落了一道雷
     */
    public static boolean onHit(Player attacker, LivingEntity victim, boolean thundering) {
        return wearing(attacker) && thundering && strike(victim.level(), victim);
    }

    /** 一道原版落雷，落在被打中的那个生物身上。公开出来是为了让测试能直接问"劈到了没有"。 */
    public static boolean strike(Level level, LivingEntity victim) {
        if (!(level instanceof ServerLevel server)) {
            return false;
        }
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(server);
        if (bolt == null) {
            return false;
        }
        bolt.moveTo(victim.position());
        return server.addFreshEntity(bolt);
    }
}
