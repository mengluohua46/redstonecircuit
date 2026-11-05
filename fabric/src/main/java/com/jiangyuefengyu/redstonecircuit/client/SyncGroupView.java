package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * 客户端手里那一份"哪个频道有谁", 只做缓存.
 *
 * <p>一个客户端只能看见自己脚下的世界, 而一组同步火把的定义就是"它们相隔很远, 但一起亮": 所以要画出颜色、要让玩家看见
 * 一组是哪些人, 客户端手里必须有整组的名单, 而那份名单只有服务器知道。这里存的正是它, 一个维度一份, 收到新快照就整份换掉。
 *
 * <p><b>两段</b>: 立在世界里的成员是一个方块坐标, 被 Sable 那样的机器搬着的成员是"哪台机器 + 机器里的哪个坐标" -
 * 后者的世界位置只有那位姿算得出来, 所以两段分开存、由画线的人各自换算。一个频道可以只有其中一段, 也可以两段都有。
 *
 * <p>不做渲染: 颜色不跟着包走, 两端各自用 {@link SyncTorchRule#colour} 从频道号算出来, 于是"服务器说的颜色"和"客户端
 * 画的颜色"是同一个数, 而不是两个碰巧一样的数。
 */
public final class SyncGroupView {

    /** 一个维度一份: 频道 → 立在世界里的成员。整份替换, 于是"上一次的组"不需要谁去删。 */
    private static final Map<ResourceKey<Level>, Map<Integer, Set<BlockPos>>> GROUPS = new LinkedHashMap<>();

    /**
     * 一个维度一份: 频道 → 被搬着的成员, 身份是"哪台机器 + 机器里的哪个坐标" (包的形状, 见
     * {@link RCNetwork.SyncGroupPayload.CarriedMember})。
     *
     * <p>没有世界坐标: 那是位姿的产物, 位姿每帧在变, 所以它不属于这一份缓存。
     */
    private static final Map<ResourceKey<Level>, Map<Integer, Set<RCNetwork.SyncGroupPayload.CarriedMember>>>
            CARRIED = new LinkedHashMap<>();

    /**
     * 接上包的搬运段: 客户端这一半只装一次。
     *
     * <p>两块分开装, 因为"哪台机器在哪"只有客户端知道: 世界那一段跟着包直接落进 {@link #apply}, 搬运这一段落进
     * 这里装进去的接收方。顺序是包那边定的 - 世界段先换、搬运段后到 - 而 {@link #apply} 正好把搬运段清空, 于是
     * "这一份快照没有搬运成员"这个事实不必靠一条额外的消息来表达: 接收方没被叫, 上一份就跟着世界段一起走了。
     */
    static {
        RCNetwork.SyncGroupPayload.installCarriedSink(SyncGroupView::applyCarried);
    }

    private SyncGroupView() {
    }

    /**
     * 世界段: 服务器发来的整份名单里"立在世界里的那些成员"。换了维度、刚登录, 以及组本身变了, 都走这一条。
     *
     * <p>搬运段跟着清空, 所以这一步必须发生在 {@link #applyCarried} 之前 - 包那边的顺序就是这样。于是同一份快照的
     * 两段不会来自两个时刻, 而"这一份快照里有没被搬着的成员吗"也不必另有一条消息: 接收方没被叫, 上一份就跟着世界段
     * 一起走了 - 落地的机器把火把还回世界时正是这一幕。
     */
    public static void apply(ResourceKey<Level> dimension, Map<Integer, ? extends Collection<BlockPos>> groups) {
        Map<Integer, Set<BlockPos>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, ? extends Collection<BlockPos>> entry : groups.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        GROUPS.put(dimension, copy);
        CARRIED.put(dimension, Map.of());
    }

    /**
     * 搬运段: 被搬着的成员, 同样整份替换。
     *
     * <p>单独一个入口, 因为这一段走的是 {@link RCNetwork.SyncGroupPayload.CarriedSink}: 包把两段交给两个接收方,
     * 而认识位姿的那一半只有客户端有。
     */
    public static void applyCarried(ResourceKey<Level> dimension,
                                    Map<Integer, ? extends Collection<RCNetwork.SyncGroupPayload.CarriedMember>>
                                            carried) {
        Map<Integer, Set<RCNetwork.SyncGroupPayload.CarriedMember>> copy = new LinkedHashMap<>();
        for (Map.Entry<Integer, ? extends Collection<RCNetwork.SyncGroupPayload.CarriedMember>> entry
                : carried.entrySet()) {
            copy.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        CARRIED.put(dimension, copy);
    }

    /**
     * 一份快照进来之后的两半: 世界段先换, 搬运段交给装进来的接收方。
     *
     * <p>这一层胶放在客户端而不是包那一侧, 是为了让 {@code network/RCNetwork} 一个客户端类都不提 - 专用服务器
     * 上那个类照旧会被加载, 而它引用的东西不必跟着一起进来。
     */
    public static void accept(RCNetwork.SyncGroupPayload payload) {
        Map<Integer, Collection<BlockPos>> world = new LinkedHashMap<>();
        Map<Integer, List<RCNetwork.SyncGroupPayload.CarriedMember>> carried = new LinkedHashMap<>();
        for (RCNetwork.SyncGroupPayload.SyncGroup group : payload.groups()) {
            world.put(group.channel(), group.members());
            if (!group.carried().isEmpty()) {
                carried.put(group.channel(), group.carried());
            }
        }
        apply(payload.dimension(), world);
        RCNetwork.SyncGroupPayload.CarriedSink sink = RCNetwork.SyncGroupPayload.carriedSink();
        if (sink != null && !carried.isEmpty()) {
            sink.accept(payload.dimension(), carried);
        }
    }
    /** 一个频道的世界成员, 没有就空集。 */
    public static Set<BlockPos> membersOf(ResourceKey<Level> dimension, int channel) {
        Map<Integer, Set<BlockPos>> groups = GROUPS.get(dimension);
        if (groups == null) {
            return Set.of();
        }
        Set<BlockPos> members = groups.get(channel);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    /** 一个频道里被搬着的成员, 没有就空集。 */
    public static Set<RCNetwork.SyncGroupPayload.CarriedMember> carriedOf(ResourceKey<Level> dimension,
                                                                         int channel) {
        Map<Integer, Set<RCNetwork.SyncGroupPayload.CarriedMember>> carried = CARRIED.get(dimension);
        if (carried == null) {
            return Set.of();
        }
        Set<RCNetwork.SyncGroupPayload.CarriedMember> members = carried.get(channel);
        return members == null ? Set.of() : Set.copyOf(members);
    }

    /**
     * 一个维度里已知的全部频道, 只读。
     *
     * <p>画组线的人要问的是"这个维度有哪些组", 而不是"我看得见哪几个火把": 立在世界里的同步火把没有槽位, 也就
     * 永远不在任何"画到的宿主"名单里, 从那些名单里推频道等于让一片方块形态的组一条线都画不出来。
     *
     * <p>两段都算: 一个整组都在机器里的频道, 世界那半张表里一个人也没有, 但它照样是一个要画的组。
     */
    public static Set<Integer> channels(ResourceKey<Level> dimension) {
        Map<Integer, Set<BlockPos>> groups = GROUPS.get(dimension);
        Map<Integer, Set<RCNetwork.SyncGroupPayload.CarriedMember>> carried = CARRIED.get(dimension);
        if (groups == null && carried == null) {
            return Set.of();
        }
        Set<Integer> out = new LinkedHashSet<>();
        if (groups != null) {
            out.addAll(groups.keySet());
        }
        if (carried != null) {
            out.addAll(carried.keySet());
        }
        return Set.copyOf(out);
    }

    /**
     * 这一格属于哪个频道, 0 表示不属于任何一个 - 画颜色的人问这个。
     *
     * <p>只问世界那一段: 参数是一个世界坐标, 而被搬着的成员的世界位置每帧在变, 它自己就是那位姿的产物。
     */
    public static int channelAt(ResourceKey<Level> dimension, BlockPos pos) {
        Map<Integer, Set<BlockPos>> groups = GROUPS.get(dimension);
        if (groups == null) {
            return 0;
        }
        for (Map.Entry<Integer, Set<BlockPos>> entry : groups.entrySet()) {
            if (entry.getValue().contains(pos)) {
                return entry.getKey();
            }
        }
        return 0;
    }

    /** 这一格的成员坐标, 一个维度里一共有多少; 诊断用。 */
    public static int memberCount(ResourceKey<Level> dimension) {
        Map<Integer, Set<BlockPos>> groups = GROUPS.get(dimension);
        if (groups == null) {
            return 0;
        }
        int count = 0;
        for (Set<BlockPos> members : groups.values()) {
            count += members.size();
        }
        return count;
    }

    /** 被搬着的成员一个维度里一共有多少; 诊断用。 */
    public static int carriedCount(ResourceKey<Level> dimension) {
        Map<Integer, Set<RCNetwork.SyncGroupPayload.CarriedMember>> carried = CARRIED.get(dimension);
        if (carried == null) {
            return 0;
        }
        int count = 0;
        for (Set<RCNetwork.SyncGroupPayload.CarriedMember> members : carried.values()) {
            count += members.size();
        }
        return count;
    }

    /** 掉线、换服务器: 客户端手里不留任何一份别人的世界。 */
    public static void clear() {
        GROUPS.clear();
        CARRIED.clear();
    }
}
