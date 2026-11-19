package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;

import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
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
 * <p><b>引雷挂在伤害判定里</b> ({@code ServerLivingEntityEvents.ALLOW_DAMAGE} - the one hook this loader has that is handed
 * both the victim and the damage source): 打中了才落雷, and the bolt's own damage never comes back through this hook,
 * because a bolt's source names no entity at all.
 *
 * <p>三个触发点全在 {@link #register()} 一处挂上，理由写在那里。
 */
public final class WeatherDetectorWearer {

    /** {@code +4} 攻击：加算，跟剑的加成同级。 */
    public static final ResourceLocation CLEAR_ATTACK =
            ResourceLocation.fromNamespaceAndPath("redstonecircuit", "weather_detector_attack");
    /** {@code +4} 生命上限（两颗心）：加算，脱下就撤。 */
    public static final ResourceLocation RAIN_HEALTH =
            ResourceLocation.fromNamespaceAndPath("redstonecircuit", "weather_detector_health");

    public static final double BONUS = 4.0D;

    /**
     * 两 tick（0.1 秒）看一次天气与头盔槽。天气按 tick 变，但人的手比这慢得多，所以这个数只由"换装要多久生效"定：
     * 这个加载器没有换装事件（见 {@link #register()}），头盔槽就是靠这一趟认的，收得越紧越接近另一个加载器的手感。
     *
     * <p>能收到 2 而不心虚，是因为 {@link #set} 现在是幂等的：状态没变就一个字节都不动，所以这一趟在绝大多数 tick 上
     * 只是两次属性查表 —— 先把重复动作去掉，才谈得上把间隔收小。
     */
    public static final int POLL = 2;

    /**
     * 唯一的注册入口，三个触发点各换一个回调。
     *
     * <ul>
     *   <li>{@code PlayerTickEvent.Post} → {@code ServerTickEvents.END_WORLD_TICK}: 关卡每 tick 一次，而一个玩家只属于
     *       一个关卡，所以每个人恰好被看一遍，不用自己再去重。</li>
     *   <li>{@code LivingEquipmentChangeEvent} → <b>这个加载器没有换装事件</b>：头盔槽由下面那次每 {@link #POLL} tick 的
     *       重算自己认。属性是从世界重新算出来的，所以晚一点只是手感，不是正确性；{@link #POLL} 收到 2 就是为了让这个
     *       "晚一点"小到看不见。</li>
     *   <li>{@code LivingDamageEvent.Post} → {@code ServerLivingEntityEvents.ALLOW_DAMAGE}: 它给的是被打的人加伤害来源，
     *       正是 {@link #onHit} 要的那两个。</li>
     * </ul>
     */
    public static void register() {
        ServerTickEvents.END_WORLD_TICK.register(WeatherDetectorWearer::onLevelTick);
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            onDamage(source, entity);
            return true;
        });
    }

    /** 半秒重算一次；这一趟就是原来那个逐玩家 tick 的钩子。 */
    private static void onLevelTick(ServerLevel level) {
        for (ServerPlayer player : level.players()) {
            if (player.tickCount % POLL == 0) {
                refresh(player);
            }
        }
    }

    /** 只问"该不该挂"，不关心是不是服务端：属性在 {@code LivingEntity} 上，两侧问法一样。 */

    /** 头上那件是不是天气探测器。 */
    public static boolean wearing(Player player) {
        ItemStack head = player.getItemBySlot(EquipmentSlot.HEAD);
        return !head.isEmpty() && head.is(RCRegistry.WEATHER_DETECTOR_ITEM);
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

    /**
     * 挂上或撤掉一条修饰符；返回"现在是否挂着"。
     *
     * <p><b>幂等</b>：状态已经是想要的就直接返回，一个字节都不动。每次 {@link #POLL} tick 都会走到这里，而这个状态
     * 绝大多数时候没变 —— 少了这一句，每个玩家每两 tick 都会重设一次属性（值一样，但会重算属性、连带血量与同步），
     * 那正是 {@link #POLL} 敢收到 2 的前提。
     */
    private static boolean set(Player player, Holder<Attribute> attribute, ResourceLocation id, boolean wanted) {
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return false;
        }
        if (instance.hasModifier(id) == wanted) {
            return wanted;
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
    private static void onDamage(DamageSource source, LivingEntity victim) {
        if (source.getEntity() instanceof Player player && victim != player) {
            onHit(player, victim, player.level().isThundering());
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
