package com.jiangyuefengyu.redstonecircuit.item;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.SyncGroups;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.logic.TunerRule;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * 调频器 - the frequency tuner: it carries one channel number in its own item component, and one right-click puts a
 * 同步火把 into that channel or takes a 同步火把's channel into the item.
 *
 * <p>Why the channel lives on the <b>item</b> and not in the player or the level: it has to survive being moved
 * between pockets, dropped, and handed to somebody else, and the one place that survives all three is the stack itself.
 * A data component ({@link RCRegistry#TUNER_CHANNEL}) rather than hand-written NBT, for the reason the structure item
 * already chose: a component is validated, synced and saved by the game, and one that is not {@code networkSynchronized}
 * would arrive empty on the client - which is exactly where the tooltip is drawn.
 *
 * <p><b>What it never does</b>: swallow a click that is not about a 同步火把 - with one deliberate exception. A
 * <b>sneaking</b> click anywhere clears the channel in hand ({@link #clear}), because "put the channel down" has to be
 * reachable without first finding a torch to point at: the player who wants a fresh group is, by definition, standing
 * next to nothing bound yet. Every other click on a non-torch answers false and the caller leaves the event exactly as it
 * arrived, so placing against a block, opening a chest and using the wrench all behave as if this item were not in hand.
 *
 * <p>The action itself is not decided here: {@link TunerRule#decide} is the table, and this class only carries it out.
 */
public class FrequencyTunerItem extends Item {

    public FrequencyTunerItem(Item.Properties properties) {
        super(properties);
    }

    /**
     * 一次右键: 目标位置真有同步火把才动手.
     *
     * <p>两种形态都认, 而且按 {@code SyncGroups#collect} 的同一顺序: 一个位置上真立着一支时, 站着的那个赢 - 否则这里
     * 绑的和组 pass 算的会是两个不同的东西。
     *
     * <p><b>目标有两种地址</b>: 世界坐标 (立着的、塞在世界里某个方块里的), 或者一个<b>搬运位</b> - 一台机器里的那一格。
     * 后者先问, 因为它可能与世界形态重叠: 被机器搬着的那一支, 它的方块仍然躺在那一格 (Sable 子层级的方块就是 level 的方块),
     * 而它的号码已经不在世界 store 里了 - 先问世界那一份会把号码写进一个别人读不到的地方。
     *
     * @return 这次点击是不是我们的。false 表示手没有碰过任何东西, 调用方必须把事件原样放过去
     */
    public static boolean use(ServerLevel level, ServerPlayer player, InteractionHand hand, BlockPos pos) {
        SyncGroups.CarriedTarget carried = carriedTargetAt(level, pos);
        Target target = carried != null ? Target.of(carried) : targetAt(level, pos);
        if (!target.present()) {
            // 潜行右击一个不是火把的位置 = 清空手里的频道; 不潜行时仍然什么都不做 ("手上拿着东西点了一下世界"不是清空)。
            return clear(level, player, hand);
        }
        ItemStack stack = player.getItemInHand(hand);
        TunerRule.Channel held = channelOf(stack);
        boolean handHasChannel = held != null && held.isBound();
        String here = dimensionId(level);
        boolean foreign = handHasChannel && !held.inDimension(here);
        TunerRule.Action action = TunerRule.decide(handHasChannel, foreign, target.hasChannel(),
                handHasChannel && target.channel() == held.number(), player.isSecondaryUseActive());

        int joined = handHasChannel ? held.number() : 0;
        switch (action) {
            case BIND, MOVE -> {
                if (!handHasChannel) {
                    // "手里没有频道" and "手里那个号码不算数" are the same thing here: open one and write it into both
                    // the item and the torch, so the number a player reads back is the one that actually got bound.
                    joined = SyncGroups.newChannel(level);
                    setChannel(stack, new TunerRule.Channel(joined, here));
                }
                if (carried != null) {
                    // 号码写进机器里那一份存档 (内嵌形态的槽位, 或世界形态在机器作用域表里的那一行)。
                    SyncGroups.joinCarried(level, carried.carrier(), carried.local(), joined);
                } else {
                    SyncGroups.join(level, pos, joined);
                }
                // The group just gained a member, so this tick's answer has to follow immediately rather than wait for
                // the next level tick. SyncGroups.tick is exactly that pass and it is idempotent, so running it here
                // costs one walk of the channel table and cannot disagree with the pass the tick handler runs later.
                SyncGroups.tick(level);
            }
            case COPY -> {
                // 取到的频道永远属于眼前这个维度, 因为它的号码本来就是在这里发下去的。
                setChannel(stack, new TunerRule.Channel(target.channel(), here));
            }
            case UNBIND -> {
                // 这一支退出它现在的频道, 回到"没有频道"(未绑定, 自成一个组)。组里少了一个成员, 让它这一 tick 就生效。
                if (carried != null) {
                    SyncGroups.leaveCarried(level, carried.carrier(), carried.local());
                } else {
                    SyncGroups.leave(level, pos);
                }
                SyncGroups.tick(level);
            }
            case NONE -> {
                // 对着"本来就没有频道"的火把潜行右键: 没有可清的东西, 而手里的频道这一次不该被动到。
            }
            case OTHER_DIMENSION -> {
                // Nothing is changed: the number in hand names a different dimension's group, and binding with it here
                // would leave both sides unreadable.
            }
        }
        TunerRule.Message said = action.message();
        if (said != null) {
            message(player, said, action == TunerRule.Action.COPY ? target.channel() : joined);
        }
        return true;
    }

    /**
     * 潜行右击时把手里的频道放下 - 空中右击、点非火把方块都走这里.
     *
     * <p>为什么需要这一条: 取频和建组是两件事, 而"我想换一组"的人手上正拿着上一组的号码、眼前却<b>还没有</b>任何可以
     * 指的组。只有对着火把才能清空的话, 他就得先找一支不属于任何组的火把, 而那种东西并不存在。
     *
     * @return 这次点击是不是我们的。没有潜行、或者手里本来就没有频道, 都答 false - "没有可清的东西"不该吞掉点击
     */
    public static boolean clear(ServerLevel level, ServerPlayer player, InteractionHand hand) {
        if (!player.isSecondaryUseActive()) {
            return false;
        }
        if (lookingAtSyncTorch(level, player)) {
            // 准星指着同步火把时, 这一下属于<b>那支火把</b> (UNBIND / NONE), 手里那个频道不归它管。
            // 这一条不只是"读起来对": 同一次点击在客户端与服务端、方块与物品两条路上都可能各走一遍,
            // 于是"先被 UNBIND 处理过"和"这一次 clear 也被叫到"能同时发生 —— 谁都可能先到, 所以判据必须放在这里。
            return false;
        }
        ItemStack stack = player.getItemInHand(hand);
        if (channelOf(stack) == null) {
            return false;
        }
        setChannel(stack, null);
        message(player, TunerRule.Message.CLEARED, 0);
        return true;
    }

    /** 准星此刻指着的是不是一支同步火把 (站着的或塞在方块里的)。 */
    private static boolean lookingAtSyncTorch(ServerLevel level, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 reach = eye.add(player.getLookAngle().scale(player.blockInteractionRange() + 1.0D));
        BlockHitResult hit = level.clip(new ClipContext(eye, reach, ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit == null) {
            return false;
        }
        // 准星指着一台机器里的那一格也是"指着火把": 两种地址都要问, 否则 Shift+右键搬运位会变成"清空手里"。
        return carriedTargetAt(level, hit.getBlockPos()) != null
                || targetAt(level, hit.getBlockPos()).present();
    }

    /** 对着空气的那一次右键: 只有潜行清空会在这里发生, 其余一律交回给原版。 */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level instanceof ServerLevel serverLevel && player instanceof ServerPlayer serverPlayer
                && clear(serverLevel, serverPlayer, hand)) {
            return InteractionResultHolder.success(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    /** 手里这个频道, 或者 {@code null}; 一个空栈没有频道。 */
    @Nullable
    public static TunerRule.Channel channelOf(ItemStack stack) {
        return stack.isEmpty() ? null : stack.get(RCRegistry.TUNER_CHANNEL);
    }

    /** 换掉手里的频道, {@code null} 表示清空 - "没有频道"就是没有这个 component, 不是存一个 0。 */
    public static void setChannel(ItemStack stack, @Nullable TunerRule.Channel channel) {
        if (channel == null) {
            stack.remove(RCRegistry.TUNER_CHANNEL);
        } else {
            stack.set(RCRegistry.TUNER_CHANNEL, channel);
        }
    }

    /** 一个维度在物品上的写法 - 也是 {@link TunerRule.Channel#inDimension} 的比较对象。 */
    private static String dimensionId(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** 目标位置上那支同步火把: {@code present} 是"这里真有一支", {@code channel} 是它的频道 (0 = 还没绑)。 */
    private record Target(boolean present, int channel) {

        static final Target NOT_A_TORCH = new Target(false, 0);

        /** 一个搬运位上的目标: 同一句话, 只是"这里"是机器里的那一格。 */
        static Target of(SyncGroups.CarriedTarget carried) {
            return new Target(true, carried.hasChannel() ? carried.channel() : 0);
        }

        boolean hasChannel() {
            return SyncTorchRule.isChannel(channel);
        }
    }

    /**
     * 这个世界坐标指的是不是一台机器里的一格同步火把; 是就答出 (机器, 机器内坐标).
     *
     * <p><b>坐标换算一个字节也不写在这里</b>: {@link CarrierStore#hitAt} 是搬运层自己的点击解析 (投影表 → 结构自己的方块
     * 存储 → 局部坐标扫描), 与扳手/旋转棒走的是同一条; 它答不出时还有 {@link CarrierStore#ownedHostAtPlot} - 那一条是
     * "这个坐标归一台活着的机器所有, 哪怕索引里没有它的记录", 而<b>立在被搬着的世界形态</b>正是这种目标: 它没有槽位, 于是
     * 认领时刻意不给它建记录 ({@code SableCarrierClaim#claimSyncTorch}), 号码住在机器作用域的表里。
     *
     * <p>{@link CarrierStore#isActive()} 先问: 没有物理后端时这里一次静态布尔读就结束了, 搬运层的表连加载都不会。
     */
    @Nullable
    private static SyncGroups.CarriedTarget carriedTargetAt(ServerLevel level, BlockPos pos) {
        if (!CarrierStore.isActive()) {
            return null;
        }
        CarriedBlockRemoval.Hit hit = CarrierStore.hitAt(level, pos);
        if (hit == null) {
            hit = CarrierStore.ownedHostAtPlot(level, pos, CarrierStore::structureIsAlive);
        }
        return hit == null ? null : SyncGroups.carriedTarget(level, hit.carrier(), hit.localPos());
    }

    private static Target targetAt(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (level.getBlockState(pos).getBlock() instanceof SyncTorchBlock) {
            int channel = store.syncTorchChannelAt(pos);
            return new Target(true, SyncTorchRule.isChannel(channel) ? channel : 0);
        }
        Slot slot = store.slotAt(pos);
        if (slot != null && slot.type == ComponentType.SYNC_TORCH) {
            return new Target(true, SyncTorchRule.isChannel(slot.syncGroup) ? slot.syncGroup : 0);
        }
        return Target.NOT_A_TORCH;
    }

    private static void message(ServerPlayer player, TunerRule.Message message, int channel) {
        MutableComponent text = message.namesChannel()
                ? Component.translatable(message.key(), channel)
                : Component.translatable(message.key());
        player.displayClientMessage(text.withStyle(ChatFormatting.YELLOW), true);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        TunerRule.Channel channel = channelOf(stack);
        // The channel is a reading, not a manual: "which channel am I holding" is useless behind [Shift], so it stays
        // on the first line and the how-to stays where every other item keeps it (RcTooltips).
        tooltip.add(channel != null && channel.isBound()
                ? RcTooltips.state("item.redstonecircuit.frequency_tuner.channel", channel.number())
                : RcTooltips.state("item.redstonecircuit.frequency_tuner.channel.none"));
        RcTooltips.append(tooltip, flag, "item.redstonecircuit.frequency_tuner.tip",
                RcTooltips.detail("item.redstonecircuit.frequency_tuner.tip.detail"));
    }
}
