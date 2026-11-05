package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.SuperconductingWireBlock;
import com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlockRemoval;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedSyncTorches;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>同步火把的组: 一趟 level tick 一次, 全组同 tick 一起亮.</b>
 *
 * <p>逐元件的延迟在这里没有位置: 一件"同步"器件的全部意义就是两个相隔很远的火把在同一 tick 里一起变, 所以答案不是每个
 * 元件自己算出来再排队, 而是<b>先看全组再看每个人</b> - 组内是 OR (任一成员在自己的吸附面收到信号), 亮了全组都答 15,
 * 没亮全组都是 0。
 *
 * <p>为什么需要一张表而不是就地遍历: 组的成员散在<b>两种形态</b>里 - 立在世界里的方块 (频道在 store 的 {@code syncTorch}
 * 表里) 和塞在方块内部的元件 (频道在 {@code Slot#syncGroup} 上, 因为活塞搬得动它)。{@link #collect} 把两者收进
 * {@link SyncChannels}, 于是"一个位置只属于一个频道"这类事只在那一处成立。
 *
 * <p>为什么还有第三种地址: 一台被搬着的机器里的元件既不在世界里那个坐标上, 也不在任何一个世界坐标上 - 它在
 * (carrier, local) 上。所以表键是一个 {@link Spot}: 世界形态的 {@code carrier} 为 null, 搬运形态的 {@code pos} 是机器
 * 内部的坐标。语义因此只有一句话: <b>同一频道的成员算同一组, 不论它在世界上还是在机器里</b>。
 *
 * <p>为什么有迭代上限: 一个组的输出可以正好是另一个组的输入, 于是"这一轮写下去, 下一轮又变"可以一直继续。按语义宁可停在
 * {@link SyncTorchRule#MAX_PASS_ITERATIONS} 轮上并把那一轮的答案交出去, 也不要把一个 tick 拖到不确定的长度 - 同步优先。
 */
public final class SyncGroups {

    /** 最近一趟 pass 看到了几个成员、真的写动了几个; 诊断用, 不参与语义 (见 {@link #lastPass()})。 */
    private static int lastPassMembers;

    /** 同上: 这一趟真的改动了几格。 */
    private static int lastPassWrites;

    private SyncGroups() {
    }

    /**
     * <b>刚刚那一趟 pass 的读数</b>: 成员数, 与真的写动了几格。
     *
     * <p>为什么要有: "口袋里的火把不亮"有两个长得一模一样的成因 - 名单收不到 (那一格在组 pass 眼里根本不是火把),
     * 与名单收到了但谁都没有输入。前者成员数是 0, 后者成员数大于 0 而写回次数是 0, 于是两个数就把两种成因分开了。
     * 它只说"最近这一趟": 同一 tick 里先跑世界再跑口袋, 于是口袋跑完之后它说的就是口袋那一趟。
     */
    public static int[] lastPass() {
        return new int[] {lastPassMembers, lastPassWrites};
    }

    /**
     * 一个成员的地址, 也是频道表的键。
     *
     * <p>{@code carrier == null} 是立在世界里的/塞在方块内部的那个元件, {@code pos} 就是它的世界坐标; 否则
     * {@code pos} 是它在 {@code carrier} 里的<b>局部</b>坐标 - 那里在世界中根本没有格子, 问世界坐标只会问到别人的空气。
     */
    record Spot(@Nullable CarrierId carrier, BlockPos pos) {

        static Spot standing(BlockPos pos) {
            return new Spot(null, pos.immutable());
        }

        static Spot carried(CarrierId carrier, BlockPos local) {
            return new Spot(carrier, local.immutable());
        }

        boolean isCarried() {
            return carrier() != null;
        }
    }

    /** 一个被搬着的成员: 哪台机器, 机器里的哪个坐标. 位姿不在这里 - 客户端有它自己的一份。 */
    public record CarriedSpot(CarrierId carrier, BlockPos local) {
    }

    /**
     * 一个维度里每个频道的名单, 分两段: 世界坐标上的成员, 与被搬着的成员。
     *
     * <p>两段而不是一段, 是因为它们的身份不是同一种东西: 一段是坐标, 一段是 (机器, 机器内的坐标)。客户端画线时两段
     * 都要, 但要用位姿的那一段只有它自己知道该往哪投。
     */
    public record Snapshot(Map<Integer, Collection<BlockPos>> standing,
                           Map<Integer, Collection<CarriedSpot>> carried) {
    }

    /**
     * 一趟 pass 看到的全部成员: 频道 → 成员, 加上"自成一组的那些"。
     *
     * <p>纯值, 不读世界, 于是 OR 这条语义可以脱层测 - 而两种形态在这张表里只是同一个 {@link Spot} 的两种取值, 所以
     * "世界成员 + 搬运成员"不需要第二套组合规则。
     */
    static final class Roster {

        /** 频道 → 成员, 按第一次出现的顺序, 于是同一份世界每次都得到同一份遍历顺序。 */
        private final Map<Integer, Set<Spot>> members = new LinkedHashMap<>();

        /** 没绑频道的成员: 每个都是自己一个人的一组, 所以它们彼此之间没有任何关系。 */
        private final Set<Spot> solo = new LinkedHashSet<>();

        /** 世界形态的一个成员, 绑在 {@code channel} 上; 非频道号是"没绑", 不该走到这里。 */
        void put(BlockPos pos, int channel) {
            putSpot(Spot.standing(pos), channel);
        }

        /** 世界形态的一个成员, 没绑频道。 */
        void putSolo(BlockPos pos) {
            solo.add(Spot.standing(pos));
        }

        /** 被搬着的一个成员, 绑在 {@code channel} 上 - 与世界成员同一个频道号就是同一组。 */
        void putCarried(CarrierId carrier, BlockPos local, int channel) {
            putSpot(Spot.carried(carrier, local), channel);
        }

        /** 被搬着的一个成员, 没绑频道: 与世界里那支同一条规则, 自成一个只含它自己的组。 */
        void putSoloCarried(CarrierId carrier, BlockPos local) {
            solo.add(Spot.carried(carrier, local));
        }

        private void putSpot(Spot spot, int channel) {
            if (!SyncTorchRule.isChannel(channel)) {
                return;
            }
            solo.remove(spot);
            members.computeIfAbsent(channel, ignored -> new LinkedHashSet<>()).add(spot);
        }

        /** 这个地址已经在表里了 (某个频道的成员, 或者自成一组的那个) - 收表时"同一支火把只算一个成员"要问这一句。 */
        boolean holds(Spot spot) {
            if (solo.contains(spot)) {
                return true;
            }
            for (Set<Spot> group : members.values()) {
                if (group.contains(spot)) {
                    return true;
                }
            }
            return false;
        }

        /** 每一个至少有一个成员的频道。 */
        Set<Integer> channels() {
            return Set.copyOf(members.keySet());
        }

        /** 一个频道上的全部成员, 世界形态的与搬运形态的混在一起。 */
        Set<Spot> membersOf(int channel) {
            Set<Spot> group = members.get(channel);
            return group == null ? Set.of() : Set.copyOf(group);
        }

        /** 没绑频道的那些成员, 每一个都是自己一组。 */
        Set<Spot> soloSpots() {
            return Set.copyOf(solo);
        }
    }

    /**
     * <b>一个频道这一趟的成员落在哪: 世界坐标上那几个, 以及每台载体机器里的那几个。</b>
     *
     * <p>它只回答一个问题 - "这一面读到的位置是不是同频道的另一个成员"。世界段的一个坐标与另一个世界段的坐标是同一个
     * 坐标系里的两个数; 一台载体机器里的 {@code local} 与同一台机器里的另一个 {@code local} 也是; 而两台<b>不同</b>载体
     * 的 {@code local} 是两个坐标系, 比不得 - 于是这张表只存前两种, 第三种只剩"画在世界上的那一格" ({@code drawn})
     * 与世界段那一份可比。
     *
     * <p>纯值: 从当趟名单 ({@link Spot}) 建出来, 不读世界、不问位姿, 于是"同组不算输入"能脱层测。
     */
    static final class GroupMembers {

        /** 世界段成员的世界坐标: 那几个位置上<b>真的</b>有一支同组的火把。 */
        private final Set<BlockPos> world;

        /** 载体 → 它机器里的那几个成员坐标 (local)。编号在不同载体之间不相通, 所以键是载体。 */
        private final Map<CarrierId, Set<BlockPos>> inside;

        /** 没有别人: 自成一组的那些成员看的就是这一份 (它自己那一个位置永远不会是"邻居")。 */
        static final GroupMembers ALONE = new GroupMembers(Set.of(), Map.of());

        private GroupMembers(Set<BlockPos> world, Map<CarrierId, Set<BlockPos>> inside) {
            this.world = world;
            this.inside = inside;
        }

        /** 一张成员名单折成这一份索引; 空名单与 {@link #ALONE} 是同一种内容。 */
        static GroupMembers of(Collection<Spot> members) {
            Set<BlockPos> world = new LinkedHashSet<>();
            Map<CarrierId, Set<BlockPos>> inside = new LinkedHashMap<>();
            for (Spot spot : members) {
                if (spot.isCarried()) {
                    inside.computeIfAbsent(spot.carrier(), ignored -> new LinkedHashSet<>()).add(spot.pos());
                } else {
                    world.add(spot.pos());
                }
            }
            Map<CarrierId, Set<BlockPos>> frozen = new LinkedHashMap<>();
            for (Map.Entry<CarrierId, Set<BlockPos>> entry : inside.entrySet()) {
                frozen.put(entry.getKey(), Set.copyOf(entry.getValue()));
            }
            return new GroupMembers(Set.copyOf(world), Map.copyOf(frozen));
        }

        /** 这个<b>世界</b>位置上是同频道的另一个成员。 */
        boolean holdsWorld(BlockPos pos) {
            return this.world.contains(pos);
        }

        /** 这台机器里的这一格上是同频道的另一个成员; 不属于这台载体的坐标一律不算。 */
        boolean holdsInside(@Nullable CarrierId carrier, BlockPos local) {
            Set<BlockPos> onCarrier = carrier == null ? null : this.inside.get(carrier);
            return onCarrier != null && onCarrier.contains(local);
        }
    }

    /** 一面被跳过的理由: 隔壁就是同频道的另一个成员 - 它答的 15 是"同一组自己的答案", 不是外部输入。 */
    static final String SAME_GROUP = "same-group";

    /**
     * <b>这一面读到的是不是同频道的另一个成员</b> - 是就不算输入。
     *
     * <p>为什么非这样不可: 同一频道的成员按 OR 联动, 于是 A 亮着就是 B 的输入、B 亮着又是 A 的输入 - 两支只要挨着 (一支
     * 立在世界里、一支被搬着也一样), 一次外部信号就把整组锁在 15 上: 谁都不必再给信号, 谁也都不会熄。那条
     * {@code side=west drawn=…=15 structure=…=15 answer=15 inputs=0x10 written=false} 正是它。
     *
     * <p>三种位置、三种比对, 而<b>全在这里</b> (调用点只有一处, 见 {@link #askAt}):
     *
     * <ul>
     *   <li>{@code drawn}: 邻居<b>画在世界上的那一格</b>对上世界段成员的坐标。立在世界里的那一支 {@code drawn} 就是它
     *       自己的邻居 (位姿为空), 于是这一条同时管住"世界↔世界"与"世界↔被搬着的那一支画在哪";</li>
     *   <li>{@code inStructure}: 机器里的那一格对上<b>同一台载体</b>上的搬运成员 - 那才是同一个坐标系里的两个数;</li>
     *   <li>两台<b>不同</b>载体的搬运成员: 它们的位置彼此不可比, 能读到的只有各自"画在哪"那一格 - 而那正是第一条已经
     *       在问的东西 ({@code drawn})。</li>
     * </ul>
     *
     * <p>纯值: 两个位置都由调用方算好交进来, 于是这条判据不碰位姿、不读世界, 能在没有游戏的地方钉住。判据<b>只此一处</b>:
     * 三分支里各写一遍就是三份迟早不一致的真值。
     */
    static boolean sameGroupNeighbour(GroupMembers group, Spot spot, BlockPos drawn, BlockPos inStructure) {
        return group.holdsWorld(drawn) || group.holdsInside(spot.carrier(), inStructure);
    }

    /**
     * 一趟走线最多走几格 (24 格, <b>按格子数算</b>: 世界里的粉与方块里的一根线各算一格, 于是它同时是一趟走线的上限与
     * 一次组 pass 里问元件存储的次数上限 - 不会变成一份全表扫描）。
     *
     * <p>一个数的理由: 普通粉一级强度只送一格, 15 级最远 15 步, 留一点余量就够; 走到这个数还没找到外部来源时
     * {@link #fedByGroupOnly} 答"不是我们的" - 宁可留下一个真输入, 也不许把一个真的吞掉 (那一面照旧按读数算)。
     */
    static final int MAX_WIRE_CELLS = 24;

    /**
     * <b>一根线里的电是谁喂的</b> - 我们自己喂出来的那一份不算输入。
     *
     * <p>为什么非有这一条: 掩码与 {@link SyncTorchRule#listensTo} 挡住的只是<b>一块</b> - 我们正在输出的那一面不听,
     * 而我们喂亮的那根粉只要绕回来贴到<b>另一面</b>, 那一面每趟都读到 15 ⇒ 掩码置位 ⇒ OR 恒成立, 撤掉外面的电也不熄
     * (那条 {@code side=west ... answer=15 power=15 written=false})。{@link #sameGroupNeighbour} 抓不到它:
     * 粉不是成员, 它答的 15 是我们自己的回声, 只是从另一格绕回来的。
     *
     * <p>判据一句话: <b>一根线里的电只有两个来源 - 它旁边本组之外的某个真源, 或者本组自己往它里面打的 15。</b>把这根线
     * 整根走完都没有前者, 那这一面读到的就只可能是后者: 是环, 不是输入。反过来, 线里有任何一个外部真源 (拉杆、红石块、
     * 另一支火把、被强充能的方块、机器投影过来的那一格) 就照旧算输入 - 这里断掉的是自己的回声, 不是外面的路。
     *
     * <p>"一根线"有两种形态, 而两种都要走: 世界里的方块 (原版粉 / 超导粉), 与<b>方块里的一根线</b>
     * ({@code ComponentType#DUST} / {@code SUPERCONDUCTOR} 的元件)。少了后者, 摆法里"相邻方块内的红石粉"就是
     * 一个答 15 的方块 - 那不是线, 于是它自己的回声被算成输入。
     *
     * <p>纯值: 走线要问的三件事由调用方包成一个 {@link WireLine}, 于是这条判据在没有方块注册表的地方也能钉住。
     *
     * @return {@code true} = 这一根线只有本组在喂 (那一份读数不算输入); {@code false} = 外面有人喂, 或者说不清
     */
    static boolean fedByGroupOnly(WireLine line, BlockPos readAt, int maxCells) {
        if (!line.wire(readAt)) {
            // 不是线就没有"我们喂它"这回事: 导体、纯源、中继器那几类照旧按读数算。
            return false;
        }
        Set<BlockPos> seen = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        seen.add(readAt);
        queue.add(readAt);
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (line.fedFromOutside(cell)) {
                return false;
            }
            for (BlockPos joined : line.joined(cell)) {
                if (seen.contains(joined)) {
                    continue;
                }
                if (seen.size() >= maxCells) {
                    // 还有走出上限的格没走 ⇒ 说不清 ⇒ 当外部, 与上面那一条同一个方向。
                    return false;
                }
                seen.add(joined);
                queue.add(joined);
            }
        }
        return true;
    }

    /** 一份读数算不算这一面的输入: "我们自己喂出来的那一份不算"只在这里落地 (0 进不了掩码)。 */
    static int credited(int answer, boolean fedByUs) {
        return fedByUs ? 0 : answer;
    }

    /**
     * 走一根线要问的三件事 - 交给一个函数, 于是 {@link #fedByGroupOnly} 自己不读世界。
     */
    interface WireLine {

        /**
         * 这一格是一根能送电的线吗 - 世界里的原版红石粉、这个 mod 的超导红石粉, 以及<b>方块里的一根线</b>
         * ({@code ComponentType#DUST} / {@code SUPERCONDUCTOR} 的元件)。
         */
        boolean wire(BlockPos pos);

        /** 这一格的线连到哪几格 (vanilla 的成线形状: 四向同层, 以及台阶上下的那一格)。 */
        List<BlockPos> joined(BlockPos pos);

        /** 这一格旁边有没有<b>本组之外</b>的真源 (本组自己的成员不算: 那是我们的回声)。 */
        boolean fedFromOutside(BlockPos pos);
    }

    /**
     * <b>一份存储里通向这根线的那一趟走</b>: 判据要的三件事都由这一份存储回答, 而且<b>只问这一份</b> - 画出来的那一格
     * (level) 与机器自己那一格 (载体视图) 是两份互不相通的存储, 于是"这一份的电是谁喂的"也是两件独立的事。
     *
     * <p>{@code self} 是这支火把在<b>同一份存储的坐标系</b>里的位置 (画出来那一份是位姿换算后的那一格), 而"本组"按同一
     * 个坐标系比: 世界那一份比世界坐标, 机器那一份比同一台载体的 local 坐标 - 两台不同载体的 local 不可混比, 与
     * {@link #sameGroupNeighbour} 同一条规矩, 也就是同一条理由。
     *
     * <p><b>线有两种形态, 而两种都要走</b>: 世界里的方块 (原版粉 / 超导粉) 与方块里的一根线
     * ({@code ComponentType#DUST} / {@code SUPERCONDUCTOR})。后者的位置在<b>元件那一份存储</b>里而不在方块状态里, 于是
     * "这一格是线吗"要问 {@code this.store} 或载体索引那一格 (见 {@code innerWire}); 它的邻居 (宿主的六面) 照旧
     * 用这一份方块视图问读数 - 一根线是谁喂的, 两种形态是同一个问题。
     */
    private static final class WireWalk implements WireLine {

        private final ServerLevel level;

        /** 这一趟问的那一份元件存储: 世界段与世界 store 逐字对应, 搬运段与载体索引那一格逐字对应。 */
        private final InnerRedstoneStore store;

        private final BlockGetter view;
        private final GroupMembers group;
        @Nullable
        private final CarrierId carrier;
        private final BlockPos self;
        private final boolean worldFrame;

        WireWalk(ServerLevel level, BlockGetter view, GroupMembers group, @Nullable CarrierId carrier, BlockPos self,
                 boolean worldFrame) {
            this.level = level;
            // 建一次而不是每格再问一次: 这两句在热路径上, 而一份存储不会在一条线里换掉。
            this.store = InnerRedstoneStore.get(level);
            this.view = view;
            this.group = group;
            this.carrier = carrier;
            this.self = self;
            this.worldFrame = worldFrame;
        }

        @Override
        public boolean wire(BlockPos pos) {
            return wireAt(pos, stateAt(this.view, pos));
        }

        @Override
        public List<BlockPos> joined(BlockPos pos) {
            List<BlockPos> joined = new ArrayList<>(6);
            if (innerWire(pos)) {
                // 方块里那根线连哪几格由元件自己说 (见 Slot 的连接判据): 六面都是它的邻居, 没有原版粉那套台阶上下。
                for (Direction direction : Direction.values()) {
                    BlockPos neighbour = pos.relative(direction);
                    if (wire(neighbour)) {
                        joined.add(neighbour.immutable());
                    }
                }
                return joined;
            }
            // 台阶那半条判据与方向无关, 于是问一次: 线能不能爬上邻居头顶, 由"这一格上面通不通"决定。
            boolean canClimb = !conductsAt(this.view, pos.above());
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos neighbour = pos.relative(direction);
                BlockState state = stateAt(this.view, neighbour);
                if (wireAt(neighbour, state)) {
                    // 邻居是线: 世界里的粉, 或者方块里的一根线 - 两种都由这一趟走完。
                    joined.add(neighbour.immutable());
                }
                BlockPos step = state != null && state.isRedstoneConductor(this.view, neighbour) && canClimb
                        ? neighbour.above()
                        : neighbour.below();
                if (wire(step)) {
                    joined.add(step.immutable());
                }
            }
            return joined;
        }

        @Override
        public boolean fedFromOutside(BlockPos pos) {
            for (Direction side : Direction.values()) {
                BlockPos neighbour = pos.relative(side);
                if (isGroupMember(neighbour)) {
                    // 本组打出去的 15 就是这个环的全部: 它不能给自己作证。
                    continue;
                }
                BlockState state = stateAt(this.view, neighbour);
                if (state == null || wireCell(state) || innerWire(neighbour)) {
                    // 线自己由这一趟走线回答 (方块里那一格也是线); 读不到的那一格什么也不答。
                    continue;
                }
                int answer = SyncTorchRule.ask(state.isRedstoneConductor(this.view, neighbour),
                        state.getSignal(this.view, neighbour, side),
                        state.getDirectSignal(this.view, neighbour, side));
                if (answer > 0) {
                    return true;
                }
            }
            return false;
        }

        /** 这一格是不是本组的一员, 在这一份存储的坐标系里问。 */
        private boolean isGroupMember(BlockPos pos) {
            if (pos.equals(this.self)) {
                return true;
            }
            return this.worldFrame ? this.group.holdsWorld(pos) : this.group.holdsInside(this.carrier, pos);
        }

        /**
         * 这一格是一根线吗 - <b>两种形态</b>: 世界里的粉 (看那一格方块), 以及<b>方块里的一根线</b> (看那一格的元件)。
         *
         * <p>少掉后者就是那条自锁: 方块内的粉是 {@code Slot} 而不是方块, 它对宿主方块答满强度 15
         * ({@code BlockStateSignalMixin}), 于是走线只看方块状态时它"不是线", 按"说明不了就不吞"被当成一个外部源 -
         * 我们自己喂出来的回声就这样变成了输入。于是"是不是线"必须问元件那一份存储, 与读数问的是同一格。
         */
        private boolean wireAt(BlockPos pos, @Nullable BlockState state) {
            return wireCell(state) || innerWire(pos);
        }

        /**
         * 这一格是不是<b>方块里的一根线</b>: 宿主方块里放着一个 {@code DUST} 或 {@code SUPERCONDUCTOR} 的元件。
         *
         * <p>"这一格里的元件"按这一趟的坐标系问 ({@link #elementAt}), 于是它与这一面读到的那份 15 是<b>同一个元件</b>:
         * 线与非线认错了, 下方"这一格旁边的源"就跟着错。
         */
        private boolean innerWire(BlockPos pos) {
            Slot slot = elementAt(pos);
            return slot != null && slot.type.isWire();
        }

        /**
         * 这一格里的元件, 或者 {@code null} - <b>与读数同一份存储</b>, 这正是 {@code SyncTorchRule#ask} 上面那一句读的
         * 地方: 世界段先问世界 store; 那一格是空的时候再问"被搬来这一格的那个宿主"
         * ({@code CarrierStore#projectedHostAt}) - 一台机器的方块画在世界上, 而它自己的格子不在那里, 于是画出来那一格
         * 只有投影表说得清它里面是什么。搬运段问<b>同一台载体的那一格</b> ({@code CarrierStore#carriedSlotAt})。
         *
         * <p>混用就是拿别人的一格当自己的线; 而少了投影那一支, 一台被搬着的机器里"方块内一根粉"在 {@code drawn} 那一
         * 半仍然是一个答 15 的方块 (它的坐标在投影里, 不在 store 里) - 残余就留在了那一半。
         */
        @Nullable
        private Slot elementAt(BlockPos pos) {
            if (!this.worldFrame) {
                return CarrierStore.carriedSlotAt(this.level, this.carrier, pos);
            }
            Slot stored = this.store.slotAt(pos);
            if (stored != null) {
                return stored;
            }
            CarrierProjection.ProjectedHost projected = CarrierStore.projectedHostAt(this.level, pos);
            return projected == null ? null : projected.slot();
        }
    }

    /** 一根能送电的线: 世界里的原版红石粉, 与这个 mod 的超导红石粉 (同一形状, 跳数 0 的那一种)。 */
    private static boolean wireCell(@Nullable BlockState state) {
        return state != null
                && (state.is(Blocks.REDSTONE_WIRE) || SuperconductingWireBlock.isSuperconductor(state));
    }

    /** 这一格是不是红石导体; 读不到 (没有这一份存储 / 区块没加载) 时答 {@code false}。 */
    private static boolean conductsAt(BlockGetter view, BlockPos pos) {
        BlockState state = stateAt(view, pos);
        return state != null && state.isRedstoneConductor(view, pos);
    }

    /**
     * 一份存储里的一格; 读不到答 {@code null}。
     *
     * <p>level 要先问 {@code isLoaded}: 没加载的区块上 {@code getBlockState} 不是空气, 是没有那一格。机器自己那一份视图
     * (contraption) 不是 level, 只能直接问它自己 - 这也是组 pass 里另外五面本来的问法。
     */
    @Nullable
    private static BlockState stateAt(BlockGetter view, BlockPos pos) {
        if (view instanceof Level world && !world.isLoaded(pos)) {
            return null;
        }
        return view.getBlockState(pos);
    }

    /**
     * 发一个新频道号: 每维度自增, 从 1 开始, 计数器随存档走。
     *
     * <p>不复用空出来的号码: 玩家昨天绑过的那支火把不该在他们今天绑一支新的时亮起来。
     */
    public static int newChannel(ServerLevel level) {
        return InnerRedstoneStore.get(level).allocateSyncChannel();
    }

    /**
     * 把 {@code pos} 上的同步火把绑到 {@code channel}, 形态自己认。
     *
     * <p>先问方块、再问槽位: 一个位置立着的是一支同步火把时, 它是那支方块; 否则它内部的元件才是要绑的东西。频道为 0 或
     * 更小等于 {@link #leave} - "解绑"和"绑到一个不存在的频道"是同一件事, 不该有两种写法。
     */
    public static void join(ServerLevel level, BlockPos pos, int channel) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        if (!SyncTorchRule.isChannel(channel)) {
            leave(level, pos);
            return;
        }
        if (level.getBlockState(pos).getBlock() instanceof SyncTorchBlock) {
            store.putSyncTorch(pos, channel);
            HostSync.broadcastSyncGroups(level);
            return;
        }
        Slot slot = store.slotAt(pos);
        if (slot != null && slot.type == ComponentType.SYNC_TORCH && slot.syncGroup != channel) {
            slot.syncGroup = channel;
            store.markDirty();
            // The client draws a copy of the component, so a change that is not broadcast is one the player cannot
            // read: without this the goggles keep showing the channel it used to be on. And the group list itself has
            // one more (or one fewer) member, which is a fact no single slot carries.
            HostSync.broadcast(level, pos, slot);
            HostSync.broadcastSyncGroups(level);
        }
    }

    /** 把 {@code pos} 上的同步火把从它的频道里拿出来, 两种形态都一样。 */
    public static void leave(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        boolean wasStanding = store.syncTorchChannelAt(pos) > 0;
        store.removeSyncTorch(pos);
        Slot slot = store.slotAt(pos);
        boolean wasStored = slot != null && slot.type == ComponentType.SYNC_TORCH
                && slot.syncGroup != SyncTorchRule.UNBOUND;
        if (wasStored) {
            slot.syncGroup = SyncTorchRule.UNBOUND;
            store.markDirty();
            HostSync.broadcast(level, pos, slot);
        }
        if (wasStanding || wasStored) {
            // 组的名单少了一个人, 这是哪个槽位都装不下的事实: 客户端那一份只能整份换掉。
            HostSync.broadcastSyncGroups(level);
        }
    }

    // ------------------------------------------------------- 被搬着的那个地址 --

    /**
     * 一个被搬着的同步火把属于哪一种形态 - 也就是它的频道写在<b>哪一份存档</b>上。
     *
     * <p>收表 ({@code roster}) 里两者是同一条: 只要 (机器, 机器内坐标) 落在同一频道号下就是同一组。但<b>写</b>必须分开,
     * 因为地址不同: 内嵌形态的号码在 {@code Slot#syncGroup} 上、跟着槽位走; 立着那一形态被搬走时它的行随方块钩子离开了
     * 世界 store, 号码住在 {@code CarriedSyncTorches} 的机器作用域表里。写错一份就是同一个格子有两个互相矛盾的真。
     */
    public enum CarriedForm {
        /** 塞在方块内部的那一支: 号码写在 {@code Slot#syncGroup} 上, 回写走搬运索引。 */
        EMBEDDED,
        /** 立在世界里、被机器搬着的那一支: 号码写在 {@code CarriedSyncTorches} 的那一行上。 */
        STANDING
    }

    /**
     * 一个搬运位上的同步火把: 哪台机器的哪一格、属于哪一种形态、此刻的号码 (非频道号 = 未绑定), 以及内嵌形态的那一份槽位.
     *
     * <p>{@code slot} 只在内嵌形态时非空, 而且是索引里那一个<b>活对象</b>: 写入就是改它 (再由搬运层回写), 与组 pass
     * 写答案走的是同一条路。
     */
    public record CarriedTarget(CarrierId carrier, BlockPos local, CarriedForm form, int channel,
                                @Nullable Slot slot) {

        /** 这一支此刻在一个真频道上 (0 与 {@link SyncTorchRule#UNBOUND} 都不算)。 */
        public boolean hasChannel() {
            return SyncTorchRule.isChannel(channel);
        }
    }

    /**
     * 一个搬运位上有没有同步火把, 有就是哪一种形态 - <b>纯值</b>, 不读世界、不广播。
     *
     * <p>两份记录都由调用方问好交进来, 于是"认哪种形态"这一个判据能脱层测: 内嵌形态赢在它是一份<b>元件自己的</b>存档
     * (同 {@code roster} 的顺序), 而世界形态被搬着的那一支根本没有槽位, 两者因此不可能同时成立 - 万一同时成立, 赢的是
     * 那个真的带着元件的。
     */
    @Nullable
    static CarriedTarget carriedTarget(@Nullable CarrierId carrier, BlockPos local,
                                       @Nullable Slot slot, @Nullable Integer rowChannel) {
        if (carrier == null || local == null) {
            return null;
        }
        if (slot != null && slot.type == ComponentType.SYNC_TORCH) {
            return new CarriedTarget(carrier, local.immutable(), CarriedForm.EMBEDDED, slot.syncGroup, slot);
        }
        if (rowChannel != null) {
            return new CarriedTarget(carrier, local.immutable(), CarriedForm.STANDING, rowChannel, null);
        }
        return null;
    }

    /**
     * 一个搬运位上的目标, 两份记录都问过之后; 那里不是同步火把就是 {@code null}。
     *
     * <p>{@link CarrierStore#isActive()} 先问, 于是没有物理后端时这一句只是一次静态布尔读。
     */
    @Nullable
    public static CarriedTarget carriedTarget(ServerLevel level, CarrierId carrier, BlockPos local) {
        if (level == null || carrier == null || local == null || !CarrierStore.isActive()) {
            return null;
        }
        CarriedHostIndex index = CarrierStore.indexOf(level);
        CarriedHost host = index == null ? null : index.at(carrier, local);
        CarriedSyncTorches.Row row = CarriedSyncTorches.at(carrier, local);
        return carriedTarget(carrier, local, host == null ? null : host.slot(),
                row == null ? null : Integer.valueOf(row.channel()));
    }

    /**
     * 把一个号码写进它自己那一份存档, 返回"真的改了没有" (调用方据此决定要不要广播)。
     *
     * <p>这里是两种形态唯一分岔的地方, 而它不读世界、不广播、不问索引, 于是"绑 / 解绑 / 换组"三条能脱层测。非频道号
     * 一律规约成"未绑定": 槽位用 {@link SyncTorchRule#UNBOUND}, 机器作用域那张表的行用 {@code 0} (与
     * {@code CarriedSyncTorches#capture} 和 {@code handBack} 同一个写法)。
     */
    static boolean writeChannel(CarriedTarget target, int channel) {
        boolean bound = SyncTorchRule.isChannel(channel);
        if (target.form() == CarriedForm.EMBEDDED) {
            Slot slot = target.slot();
            int wanted = bound ? channel : SyncTorchRule.UNBOUND;
            if (slot == null || slot.syncGroup == wanted) {
                return false;
            }
            slot.syncGroup = wanted;
            return true;
        }
        return CarriedSyncTorches.setChannel(target.carrier(), target.local(), bound ? channel : 0);
    }

    /**
     * 把一个<b>被搬着的</b>同步火把绑到 {@code channel}; 非频道号等于 {@link #leaveCarried} - 与
     * {@link #join(ServerLevel, BlockPos, int)} 同一条规矩。
     *
     * <p>写的是机器里的那一份, <b>不是</b>世界 store: 这台机器里这一格在世界中不是那个坐标, 而它落地时由搬运层
     * ({@code CarriedSyncTorches#handBack}) 把号码写回新位置 - 一支火把只有一个嘴, 一个号码只住一份存档。
     */
    public static void joinCarried(ServerLevel level, CarrierId carrier, BlockPos local, int channel) {
        CarriedTarget target = carriedTarget(level, carrier, local);
        if (target == null || !writeChannel(target, channel)) {
            return;
        }
        announceCarried(level, target);
    }

    /** 把一个被搬着的同步火把从它的频道里拿出来 - {@link #leave} 的搬运位写法。 */
    public static void leaveCarried(ServerLevel level, CarrierId carrier, BlockPos local) {
        joinCarried(level, carrier, local, SyncTorchRule.UNBOUND);
    }

    /**
     * 让客户端听到这次改动.
     *
     * <p>内嵌形态走组 pass 已经在用的那条回写通道 ({@code CarrierStore#writeBackCarriedSlot}: 索引 + 存档变脏 + 一个
     * 搬运包), 于是"机器里那一份"与"客户端画的那一份"不会有哪怕一 tick 的差; 世界形态那一行只活在服务端的机器作用域表里,
     * 客户端要的是名单 (它也画线、也读号码), 所以整份换掉。组的名单多了/少了一个成员, 是哪个槽位都装不下的事实。
     */
    private static void announceCarried(ServerLevel level, CarriedTarget target) {
        if (target.form() == CarriedForm.EMBEDDED) {
            CarrierStore.writeBackCarriedSlot(level, target.carrier(), target.local(), target.slot());
        }
        HostSync.broadcastSyncGroups(level);
    }

    /** 本维度每个频道的成员 (世界坐标那一段), 给客户端快照和诊断用。 */
    public static Map<Integer, Collection<BlockPos>> membersOf(ServerLevel level) {
        return collect(InnerRedstoneStore.get(level)).snapshot();
    }

    /**
     * 一个世界位置落进哪一段 - <b>纯值</b>: 归属与"说不说得出来它画在哪"都由调用方问好交进来, 于是
     * "站在 plot 坐标上的那一支算搬运段 / 换算不出来就两段都不要"能在没有游戏的地方钉住。
     *
     * @param owner 这个坐标归属的那台机器, 不归任何机器时 {@code null}
     * @param local 它在那台机器里的坐标; 归属成立但没有位姿说得出它画在哪时 {@code null}
     * @return 世界段的 spot、搬运段的 spot, 或者 {@code null} (两段都不出现)
     */
    @Nullable
    static Spot placed(BlockPos pos, @Nullable CarrierId owner, @Nullable BlockPos local) {
        if (owner == null) {
            return Spot.standing(pos);
        }
        return local == null ? null : Spot.carried(owner, local);
    }

    /**
     * 一个世界位置归谁, 也就是它落在哪一段。
     *
     * <p>一个函数而不是一张表: {@link #roster(SyncChannels, Ownership)} 于是是纯值, 而"归一台活着的机器的位置
     * 算搬运段"这条判据能脱层测。世界那一侧的实现是 {@link Inquiry#spotAt}。
     */
    @FunctionalInterface
    interface Ownership {

        /** @return 这个位置落在哪一段; {@code null} 表示两段都不放 */
        @Nullable
        Spot at(BlockPos pos);

        /** 谁也没搬它: 世界里的位置就是世界段的位置。没有物理后端时全是这一种。 */
        Ownership STANDING = Spot::standing;
    }

    /**
     * 一趟里按载体、按位置记下的那几件事: 位姿、读数框, 以及"这个位置归不归一台活着的机器"。
     *
     * <p>为什么必须记账: {@code CarrierStore#poseOf} 对一台 Sable 子层级要去容器里查一次,
     * {@code CarrierStore#ownedHostAtPlot} 要把这一关的每个子层级走一遍 - 而一趟会对同一台机器问好几次位姿、对
     * 同一个坐标问好几次归属。记在这里, 于是判据有了、热路径却没有多出查询: 位姿按载体一条、归属按位置一条, 与
     * 搬运求解一趟只问一次位姿是同一个做法。
     *
     * <p>一趟 = 一次 level tick 里的全部组 pass 轮次, 或者一次 {@link #snapshotOf}: 两者都是一份"这一刻"的名单,
     * 而一台机器的位姿不会在一份名单里变两次。
     */
    private static final class Inquiry {

        private final ServerLevel level;

        /** 载体 → 它这一刻的位姿; 值可以是 {@code null} ({@code containsKey} 区分"问过, 没有"与"还没问")。 */
        private final Map<CarrierId, CarrierPose> poses = new HashMap<>();

        /** 载体 → 它这一刻的读数框, 由上面那一份位姿建出来。 */
        private final Map<CarrierId, ProductFrame> frames = new HashMap<>();

        /** 位置 → 它落在哪一段; 值是 {@code null} 表示"归一台机器, 但说不清它画在哪, 两段都不要"。 */
        private final Map<BlockPos, Spot> segments = new HashMap<>();

        private Inquiry(ServerLevel level) {
            this.level = level;
        }

        /** 这个位置落在哪一段, {@code null} = 两段都不放; 同一个位置一趟只问一次归属。 */
        @Nullable
        Spot spotAt(BlockPos pos) {
            if (this.segments.containsKey(pos)) {
                return this.segments.get(pos);
            }
            Spot spot = resolve(pos);
            this.segments.put(pos, spot);
            return spot;
        }

        /**
         * 一个世界位置的归属。这一格要是归一台活着的机器, 它就在那台机器里 - 于是它不是世界段的一个坐标, 而是
         * (机器, 机器内坐标)。
         *
         * <p>入口是搬运层回答点击归属的那一句 ({@code CarrierStore#ownedHostAtPlot}, 判"活着的"用
         * {@code CarrierStore#structureIsAlive}): 一台 Sable 子层级的方块就站在自己的 plot 坐标上, 而那是一个真的
         * 存在、离玩家很远的世界坐标 - 于是"这一格归谁"必须问, 不能从"它是 level 里的一格"推出来。
         *
         * <p>那一次逆投影就在这一句里: 搬运层把这一格交给 {@code CarrierProjection#localBlockOf} 验一遍
         * ({@code CarrierStore#localForVerified}), 于是回来的 {@code localPos} 就是"那台机器里的哪一格"。对一台 Sable
         * 子层级, 那一格同时是 level 里的一格 (它的方块就躺在 plot 坐标上), 所以这里不必自己再换一次坐标 - 再换一次
         * 就是第二套换算, 而两套换算迟早不一致。
         *
         * <p>认出来之后还要一位姿: 说不出它此刻画在哪 ({@code drawnBlockOf} 答 {@code null}) 的那一支<b>两段都不
         * 放</b> - 宁可这一帧少画一条线, 也不许给客户端一个"很远的地方"。
         */
        @Nullable
        private Spot resolve(BlockPos pos) {
            if (!CarrierStore.isActive()) {
                return Spot.standing(pos);
            }
            CarriedBlockRemoval.Hit owned = CarrierStore.ownedHostAtPlot(this.level, pos,
                    CarrierStore::structureIsAlive);
            if (owned == null) {
                return Spot.standing(pos);
            }
            CarrierPose pose = poseOf(owned.carrier());
            boolean drawable = pose != null && CarrierProjection.drawnBlockOf(pose, owned.localPos()) != null;
            return placed(pos, owned.carrier(), drawable ? owned.localPos() : null);
        }

        /** 这台机器这一刻的位姿: 一趟一次。 */
        @Nullable
        CarrierPose poseOf(CarrierId carrier) {
            if (this.poses.containsKey(carrier)) {
                return this.poses.get(carrier);
            }
            CarrierPose pose = CarrierStore.poseOf(this.level, carrier);
            this.poses.put(carrier, pose);
            return pose;
        }

        /** 这台机器这一刻的读数框: 位姿读完一次, 框也只建一次。 */
        ProductFrame frameOf(CarrierId carrier) {
            ProductFrame frame = this.frames.get(carrier);
            if (frame == null) {
                frame = ProductFrame.forHost(carrier, poseOf(carrier));
                this.frames.put(carrier, frame);
            }
            return frame;
        }
    }

    /**
     * 本维度每个频道的完整名单, 两段都在: 世界坐标上的成员, 与被搬着的成员。
     *
     * <p>两段来自同一次收集, 因为它们必须说的是同一 tick 的话: "这一组还有没有别人"是一个关于<b>整份名单</b>的答案,
     * 分两次问就会分两次答。
     *
     * <p><b>世界那一段的每个位置都已经过一次归属判据</b> (本方法 → {@code roster(level, …)} →
     * {@link Inquiry#spotAt} → {@code CarrierStore#ownedHostAtPlot}): 归一台活着的机器的坐标被换算成
     * (载器, 机器内坐标) 进了搬运段, 换算不出来的两段都不放。于是客户端拿到的世界那一段里没有 plot 坐标。
     */
    public static Snapshot snapshotOf(ServerLevel level) {
        return snapshot(roster(level, InnerRedstoneStore.get(level), carriedIndexOf(level), new Inquiry(level)));
    }

    /** 一张名单折成两段 - 纯值, 不读世界: 成员在哪一段, 收表那一步已经按归属定好了。 */
    static Snapshot snapshot(Roster roster) {
        Map<Integer, Collection<BlockPos>> standing = new LinkedHashMap<>();
        Map<Integer, Collection<CarriedSpot>> carried = new LinkedHashMap<>();
        for (int channel : roster.channels()) {
            Set<BlockPos> world = new LinkedHashSet<>();
            List<CarriedSpot> spots = new ArrayList<>();
            for (Spot spot : roster.membersOf(channel)) {
                if (spot.isCarried()) {
                    spots.add(new CarriedSpot(spot.carrier(), spot.pos()));
                } else {
                    world.add(spot.pos());
                }
            }
            if (!world.isEmpty()) {
                standing.put(channel, Set.copyOf(world));
            }
            if (!spots.isEmpty()) {
                carried.put(channel, List.copyOf(spots));
            }
        }
        return new Snapshot(standing, carried);
    }

    /** 本维度所有频道号, 给诊断用; 只读两张表, 不读世界。 */
    public static Set<Integer> channelsOf(ServerLevel level) {
        return collect(InnerRedstoneStore.get(level)).channels();
    }

    /**
     * 那一趟组 pass: 先按 OR 算出每个频道的亮灭, 再把答案写回每一个成员。
     *
     * <p>在求解器之前跑 ({@code InnerRedstoneTickHandler#onLevelTick}), 因为它写的是<b>求解器的输入</b>: 同 tick 里
     * 立着的那支火把已经在答案里, 下游的线才会在同一 tick 里跟着动。
     *
     * <p>"世界里没东西"不再等于"这趟没事": 一台被搬着的机器可能正带着同步火把飞, 而它的元件在搬运索引里, 不在世界
     * store 里。于是这里先问一句"有没有东西在飞", 有就照样跑 - 这是被搬着的那一支能进组的前提。
     */
    public static void tick(ServerLevel level) {
        // 归零在最早处: 这一趟提前返回时, 读数必须说"看见了 0 个", 而不是上一趟留下的数。
        lastPassMembers = 0;
        lastPassWrites = 0;
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        CarriedHostIndex index = carriedIndexOf(level);
        if (store.isEmpty() && index == null && !carryingTorches()) {
            return;
        }
        // 诊断的开关<b>只在这里问一次</b> (见 {@link RCConfig#traceSyncGroup()}): 关着时 trace 是 {@code null}, 于是
        // 这一趟在每一条输出上只花一次 null 判断 - 一个字符串都不拼, 一行日志都不写。
        GroupTrace trace = traceOf(RCConfig.traceSyncGroup());
        // 归属与位姿在这一 tick 的每一轮之间共用一本账: 一台机器的位姿不会在 tick 中间变, 而问一次归属要走过这一关
        // 的每个子层级 - 每一轮都问一遍是白花。
        Inquiry inquiry = new Inquiry(level);
        // <b>"我们在往哪几面输出"由两个时刻合起来答, 这是其中的 (b): 一趟 tick 的常量, 只在进入轮次循环之前抓一次。</b>
        // 不能在轮次里现读: 第 1 轮算出"该灭"、把 LIT 写成 false 之后, 现读得到的是"我什么都没输出", 于是下一轮它去听
        // 自己上一 tick 刚打出去、邻居还来不及释放的那几面 (世界求解在组 pass 之后才跑), 读回自己的 15, 掩码置位, OR 把
        // 整组重新点亮 —— 亮熄亮熄, 或者自锁。抓在这里还多一件事: 这一次抓发生在任何写回之前, 于是它说的就是上一 tick
        // 结束时的那个事实。
        // 另一半 (a) 是"此刻", 由 {@link #askAt} 每轮在六面循环之前现读一次: 它补的是这一份抓不到的"同一 tick 里刚
        // 发生"的那几面 —— 第 1 轮点亮、邻居在同一 tick 里跟着亮起来之后, 第 2 轮必须把那一面算成"我们正在输出"。
        Drives drives = Drives.capture(level, store, index, inquiry);
        for (int pass = 0; pass < SyncTorchRule.MAX_PASS_ITERATIONS; pass++) {
            if (!runPass(level, store, index, inquiry, drives, trace, pass)) {
                return;
            }
        }
    }

    /**
     * 开关成立时给一个诊断收集器, 否则 {@code null}。
     *
     * <p>一句而不是在每个调用点各判一次: "默认配置下一条日志都没有"因此是<b>形状</b>上的 (没有对象, 谁都写不出),
     * 而不是一句约定。纯值, 于是"关着时什么都没有"能脱层测。
     */
    @Nullable
    static GroupTrace traceOf(boolean enabled) {
        return enabled ? new GroupTrace() : null;
    }

    /**
     * 有没有一台机器带着同步火把。
     *
     * <p>非问不可: 一台机器里唯一的那件红石器件就是一支立着的火把时, 世界 store 是空的 (它那一行随方块钩子离开了),
     * 搬运索引也可能是空的 (这一形态没有槽位) - 而组 pass 照样得跑, 否则它在机器上永远不亮。
     *
     * <p>{@code CarrierStore#isActive()} 先问, 于是没有物理后端时这一句只是一次静态布尔读, 载体层的表连加载都不会。
     */
    private static boolean carryingTorches() {
        return CarrierStore.isActive() && CarriedSyncTorches.any();
    }

    /** 一轮: 收表、问每一面、求答案、写回。返回这一轮有没有改动 - 没有改动就说明到答案了。 */
    private static boolean runPass(ServerLevel level, InnerRedstoneStore store,
                                   @Nullable CarriedHostIndex index, Inquiry inquiry, Drives drives,
                                   @Nullable GroupTrace trace, int pass) {
        Roster roster = roster(level, store, index, inquiry);
        Map<Spot, Member> present = presentMembers(level, store, index, roster);
        // 最后一轮是定点那一轮, 于是这两个数说的就是这一趟的结论。
        lastPassMembers = present.size();
        lastPassWrites = 0;
        if (trace != null) {
            trace.roster(pass, roster, present);
        }
        // "这一面是不是同频道的另一位"要问的那份名单: 一轮建一次, 于是六面里每面再收一次表是不可能的写法。
        Map<Spot, GroupMembers> groups = sameGroups(roster, present);
        Map<Spot, Integer> masks = new LinkedHashMap<>();
        for (Map.Entry<Spot, Member> entry : present.entrySet()) {
            masks.put(entry.getKey(), askAt(level, store, entry.getValue(), drives, inquiry,
                    groups.getOrDefault(entry.getKey(), GroupMembers.ALONE), trace));
        }
        boolean changed = false;
        for (Map.Entry<Spot, Answer> entry : decideRoster(roster, masks).entrySet()) {
            Member member = present.get(entry.getKey());
            if (member != null) {
                boolean wrote = write(level, store, member, entry.getValue());
                if (trace != null) {
                    // 写回真的改了什么没有: 这一趟算出的答案与被丢掉/写下去的那一格, 三件事在同一行上。
                    trace.answered(member, entry.getValue(), wrote);
                }
                changed |= wrote;
                if (wrote) {
                    lastPassWrites++;
                }
            }
        }
        return changed;
    }

    /**
     * 每一面"隔壁是不是同频道的另一位"要问的那份名单: 一轮一份, 每个成员一条。
     *
     * <p>只收这一趟真的还在的成员 ({@code present}): 名单里还留着一条、而那一格已经不是火把时, 它不参与这一轮 OR, 于是
     * 它也不该让别人的一面被跳过 - 那会把一个本该算数的输入悄悄吞掉。
     *
     * <p>自成一组的成员在这里<b>没有别人</b> ({@link GroupMembers#ALONE}): 它们彼此不是一组, 于是挨着的两支未绑定火把
     * 照旧互为输入 - 这是本判据不改变的东西。
     */
    private static Map<Spot, GroupMembers> sameGroups(Roster roster, Map<Spot, Member> present) {
        Map<Spot, GroupMembers> out = new HashMap<>();
        for (int channel : roster.channels()) {
            Set<Spot> members = roster.membersOf(channel);
            Set<Spot> live = new LinkedHashSet<>();
            for (Spot spot : members) {
                if (present.containsKey(spot)) {
                    live.add(spot);
                }
            }
            GroupMembers group = GroupMembers.of(live);
            for (Spot spot : members) {
                out.put(spot, group);
            }
        }
        for (Spot spot : roster.soloSpots()) {
            out.put(spot, GroupMembers.ALONE);
        }
        return out;
    }

    /**
     * <b>一个时刻在驱动什么</b>: 那一刻亮不亮, 以及当时的掩码 - 纯值, 于是"哪一面不听"可以脱层测。
     *
     * <p>它有两个来源: 一趟 tick 抓一次的那一份 (tick 开头, 见 {@link Drives#capture}), 与 {@link #askAt} 每轮从头
     * 读一次的那一份 (此刻)。
     *
     * <p>为什么 tick 开头那一份必须是快照而不是现读: {@link SyncTorchRule#listensTo} 问的是"我是不是正在往这一面输出", 而轮次
     * 之间的写回会改 LIT 与掩码。第 1 轮算出"该灭"并把 LIT 写成 false 之后, 现读得到的是"我什么都没输出" - 于是下一轮
     * 它去听自己上一 tick 刚打出去、邻居还来不及释放的那几面 (世界求解在组 pass 之后才跑), 读回自己的 15, 掩码置位,
     * OR 把整组重新点亮: 每 tick 翻转一次, 或者永远自锁。同一 tick 的四轮读同一份, 这条环路就没有入口。
     */
    record Drive(byte inputs, boolean lit) {

        /** 什么都没驱动: 灭着, 掩码空。{@link Drives#at} 对"这一趟没抓到的地址"答这一份。 */
        static final Drive DARK = new Drive((byte) 0, false);

        /** 这一面要不要听 - 判据在 {@link SyncTorchRule#listensTo} 里, 这里只是把两份事实一起交过去。 */
        boolean listensTo(Direction facing, Direction side) {
            return SyncTorchRule.listensTo(inputs, facing, lit, side);
        }
    }

    /**
     * <b>一趟 tick 的驱动集: 成员 → 上一 tick 的驱动事实。</b> 在 {@link #tick} 的开头抓一次, 之后每一轮只读。
     *
     * <p>它是{@link #askAt} 所用两半里的<b>第二半</b>: 另一半是同一轮从成员存储现读的"此刻"那一份 ({@link SyncGroups#driveAt})。
     * {@link #listensTo} 把两半合起来, 于是"往这一面输出"这句话只在这一趟里有一个答案。
     *
     * <p>为什么它是一个<b>只装这一件事</b>的对象: 名单、位姿、写回都还留在各自那一趟里, 于是写回改的是 store / 槽位 /
     * 那一格方块, 而它改不动这里 - "写回不许反过来改变这一趟 {@code listensTo} 的依据"因此在形状上成立, 不是一句约定。
     */
    static final class Drives {

        private final Map<Spot, Drive> bySpot;

        Drives(Map<Spot, Drive> bySpot) {
            this.bySpot = Map.copyOf(bySpot);
        }

        /**
         * 抓一份: 收一次表, 然后每个成员读一次它自己的掩码与 LIT。
         *
         * <p>{@code inquiry} 就是这一 tick 那一本账, 于是抓这一份不会把位姿与归属再问一遍。
         */
        static Drives capture(ServerLevel level, InnerRedstoneStore store, @Nullable CarriedHostIndex index,
                              Inquiry inquiry) {
            Roster roster = roster(level, store, index, inquiry);
            Map<Spot, Member> present = presentMembers(level, store, index, roster);
            Map<Spot, Drive> bySpot = new LinkedHashMap<>();
            for (Map.Entry<Spot, Member> entry : present.entrySet()) {
                bySpot.put(entry.getKey(), driveAt(level, store, entry.getValue()));
            }
            return new Drives(bySpot);
        }

        /**
         * 一个成员上一 tick 在驱动哪几面。
         *
         * <p>这一趟开头之后才落进名单的地址 (轮次之间一支火把被放了进来) 答 {@link Drive#DARK}: 它上一 tick 什么都没
         * 驱动, 于是六面都听, 而它自己也一个 15 都没打出去 - 读不到自己的东西。
         */
        Drive at(Spot spot) {
            Drive drive = this.bySpot.get(spot);
            return drive == null ? Drive.DARK : drive;
        }

        /**
         * 这一面要不要听 —— <b>两个时刻合起来答</b>: {@code start} 是 tick 开头那一份 (见 {@link #at}), {@code now}
         * 是此刻那一份 ({@link SyncGroups#driveAt})。<b>任一个</b>在往它输出, 这一面就不听。
         *
         * <p>两半都是 {@link Drive#listensTo}, 也就是 {@link SyncTorchRule#listensTo} 那一句, 于是"哪一面不算输入"
         * 的真值表仍然只有一处; 这里只多一件事: 两半取与。
         */
        static boolean listensTo(Drive start, Drive now, Direction facing, Direction side) {
            return start.listensTo(facing, side) && now.listensTo(facing, side);
        }
    }

    /**
     * 跳过这一面时写进诊断的那半句理由: 两个时刻里哪一个 (或者两个) 在驱动它。
     *
     * <p>判据仍然只有 {@link SyncTorchRule#listensTo} 那一句 - 这里只说出"哪一半", 于是从这一行一眼看得见是
     * (a) 同一 tick 里刚点亮, 还是 (b) 上一 tick 的那一份, 还是两半都成立。
     */
    private static String outputReason(Drive start, Drive now, Direction facing, Direction side) {
        boolean atTickStart = !start.listensTo(facing, side);
        boolean atThisMoment = !now.listensTo(facing, side);
        return "outputting-into-that-face:"
                + (atTickStart && atThisMoment ? "tick-start+now" : atTickStart ? "tick-start" : "now");
    }

    /**
     * 一个成员此刻的驱动事实: 掩码从哪读、亮没亮从哪读。
     *
     * <p>三处读音与写回那三处一一对应 (载体里那一格 / 世界那一格 / 内嵌的那个槽位), 而且<b>只有这一处</b>: 两半共用
     * 它, {@link Drives#capture} 在轮次循环之前为每个成员读一次 (tick 开头那一份, 那时还没有任何写回, 于是它说的就是
     * 上一 tick 的答案), {@link #askAt} 在六面循环之前为每个成员再读一次 (此刻那一份, 于是它说的是最近一次写回之后
     * 的答案)。两套时刻共用一条读法, 而各自只读一次。
     */
    private static Drive driveAt(ServerLevel level, InnerRedstoneStore store, Member member) {
        CarrierStore.CarriedCell cell = member.cell();
        if (cell != null) {
            // 被搬着、没有槽位的那一支: 这一格的方块与它自己的掩码都从载体层那一对入口读 (memberAt 已经读过一格, 于是
            // 这一趟不问第二遍)。掩码落在哪一份上是载体形态的事, 见 {@link CarrierStore#storageInLevel} - contraption
            // 那一份在机器作用域的行上, 不是 world store 里那个锚点相对的坐标。
            return new Drive(cell.inputs(), cell.state() != null && cell.state().getValue(SyncTorchBlock.LIT));
        }
        if (member.slot() == null) {
            BlockPos pos = member.spot().pos();
            return new Drive(store.syncTorchInputsAt(pos), level.getBlockState(pos).getValue(SyncTorchBlock.LIT));
        }
        return new Drive(member.slot().syncInputs, member.slot().power > 0);
    }

    /**
     * 一个成员这一 tick <b>哪几面</b>有输入, 一位一面.
     *
     * <p>为什么是掩码而不是一个强度: 输出是逐面的 —— 有输入的那一面不输出 ({@link SyncTorchRule#emitsOn})。整个不自锁的
     * 机制就在这张掩码上, 所以它必须由这里写下来、被 {@code SyncTorchBlock#getSignal} 和 {@code PowerSolver#emitsToward}
     * 读走, 而不是每次现算 (现算的那一瞬, 我们自己刚打出去的 15 已经在邻居的读数里了)。
     *
     * <p>读取顺序照旧: 吸附面用 {@code ProductFrame#signalInto}, 那是本 mod 对"邻居给了这一格多少"的唯一答案; 其余五面
     * 先换算到世界坐标再问, 于是"一台机器搬着火把"和"火把站在地上"不会有两个答案。
     *
     * <p>被搬着的那一支用<b>它自己那台机器的位姿</b>问 ({@code ProductFrame#forHost}, 位姿由 {@link Inquiry} 一趟
     * 问一次): 它的 local 坐标在世界里不是那一格, 直接问世界坐标会问到别人的空气; 而一台 Sable 子层级的方块本身就在
     * level 里, 于是那个框两侧都读 (画出来的那一格, 与机器里的那一格)。立在世界里的那一支照旧走 {@code carriedAt}。
     *
     * <p><b>那五面还多问一份, 形状永远是 {@code max(drawn, askInStructure(<机器自己那一份存储>, neighbour, side))}。</b>
     * 两份里哪一份是"机器自己那一份"由载体形态定 ({@link #ownWorldOf} → {@code CarrierStore#storageViewOf}): 一台
     * <b>Sable 子层级</b>的那一份就是 {@code level}, 坐标是 {@code neighbour} 那个 <b>plot</b> 坐标 - 贴在火把侧面那根粉
     * 就在那里, 而它不在机器被画出来的世界位置上 (那里是空气); 一台 <b>Create contraption</b> 的那一份是它自己的 world
     * ({@code CarrierStore#blockViewOf})。取较大的一个, 因为一个邻居最多只在其中一份里是方块。
     *
     * <p>判据在 {@link #ownWorldOf} 里问一次, 循环里只剩"有没有这一份"; 立在世界里的那一支永远没有第二份。
     *
     * <p><b>还有一条与读数无关的跳过</b>: 这一面读到的地方要是同频道的另一个成员 ({@link #sameGroupNeighbour}), 这一面
     * 不算输入。组的答案本来就不该由成员彼此之间产生 - 那是 OR 的自我循环, 一次外部信号之后整组锁在 15 上。判据只在
     * 那一处, 六面共用一次调用 (吸附面也在其中: 内嵌在方块里的同组成员正好可以是这一支挂着的那块方块)。
     */
    private static int askAt(ServerLevel level, InnerRedstoneStore store, Member member, Drives drives,
                             Inquiry inquiry, GroupMembers group, @Nullable GroupTrace trace) {
        Spot spot = member.spot();
        // 搬运段那一支用它自己那台机器的读数框 (位姿这一趟只问一次, 见 {@link Inquiry#frameOf}); 世界段那一支照旧走
        // carriedAt - 那条路自己会投影, 因为一个世界位置也可能正站在某台机器的 plot 坐标上 (那时它已经在搬运段了)。
        ProductFrame frame = spot.isCarried()
                ? inquiry.frameOf(spot.carrier())
                : ProductFrame.carriedAt(level, spot.pos());
        Direction support = member.facing();
        // "我们正在往这一面输出"由<b>两个时刻</b>合起来答, 两个时刻都在六面循环之前各读一次:
        //   (b) <b>tick 开头</b>: 抓下来的那一份 (见 {@link Drives}) - 上一 tick 我们正往那几面输出, 邻居还来不及释放
        //       (世界求解在组 pass 之后才跑)。它是这一趟的常量, 写回改不动它。
        //   (a) <b>此刻</b>: 从成员自己的存储现读一次 - 同一 tick 里第 1 轮刚点亮, 第 2 轮就在往那几面输出, 而 tick
        //       开头那一份里还没有它们。
        // 少了 (b) 就会把自己的输出读成输入 (第 1 轮写成灭之后去听上一 tick 打出去的那几面); 少了 (a) 同样会 (第 1 轮
        // 点亮之后去听邻居刚亮起来的那几面)。两个时刻各读一次而不是每面读一次: 每面读一次就是六个不同的时刻。
        Drive start = drives.at(spot);
        Drive now = driveAt(level, store, member);
        // 五面要多问的那一份存储: 两种载体之间唯一的判据在 {@link #ownWorldOf} 里问一次, 于是这里与循环里都没有第二个
        // "这台是哪一种载体"。一台 Sable 子层级交回来的是 level 自己 (它的邻居就躺在 plot 坐标上), 一台 contraption
        // 交回来的是它自己的方块视图, 立在世界上的是 null。
        BlockGetter ownWorld = ownWorldOf(level, spot);
        if (trace != null) {
            trace.member(member, start, now, storageName(ownWorld, level));
        }
        int mask = 0;
        for (Direction side : Direction.values()) {
            BlockPos neighbour = spot.pos().relative(side);
            // The position the question is asked at, which for a carried member is where the machine draws the
            // neighbour - never the local coordinate itself. For the in-place frame the two are the same number.
            BlockPos asked = frame.worldPosOf(neighbour);
            if (!level.isLoaded(asked)) {
                if (trace != null) {
                    trace.faceSkipped(member, side, asked, "drawn-not-loaded");
                }
                continue;
            }
            if (sameGroupNeighbour(group, spot, asked, neighbour)) {
                // 同一频道的另一个成员: 它答的那一份 15 是"同一组"自己的答案 - 组内部的输出对组不是消息。六面都问,
                // 吸附面也算: 一支内嵌在方块里的同组成员正好可以是这一支挂着的那块方块。
                if (trace != null) {
                    trace.faceSkipped(member, side, asked, SAME_GROUP);
                }
                continue;
            }
            if (!Drives.listensTo(start, now, support, side)) {
                // 两个时刻里任一在往这一面输出: 它答的 15 可能正是我们自己那一份, 不算输入。
                if (trace != null) {
                    trace.faceSkipped(member, side, asked, outputReason(start, now, support, side));
                }
                continue;
            }
            int answer;
            if (side == support) {
                answer = PowerSolver.clamp(frame.signalInto(level, neighbour, side));
                if (trace != null) {
                    trace.faceSupport(member, side, asked, neighbour, answer);
                }
            } else {
                // 那五面: 两份存储各读一次, 而每一份都先过"这根线是谁喂的"那条判据 (见 {@link #askSide})。
                answer = askSide(level, member, group, spot, frame, ownWorld, asked, neighbour, side, trace);
            }
            if (answer > 0) {
                mask |= SyncTorchRule.bit(side);
            }
        }
        // AND THE CELL ITSELF, WHEN THE KEY THAT PRESSED IT IS THE DEVICE STANDING IN IT. The injection ledger's own
        // sentence is "this cell is a source of S" (PocketInjection), so a torch standing IN the pressed cell reads
        // nothing at all: a device never sees its own cell as a neighbour. Redstone dust is the shape that already has
        // this exception and says why - an injected wire is pinned to that strength (RedStoneWireBlockMixin), because
        // 按键模式 means "press this cell's device". A 同步火把 is the second device that only ever reads its
        // neighbours, so a press would be answered by the dust around the torch and never by the torch - that is
        // the hole this line closes. One bit, on the one face it reads: a press has no direction, so it lands
        // where an ordinary supply lands, and the five output faces stay output faces.
        if (!spot.isCarried()) {
            int pressed = com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace.injectedStrength(
                    level, spot.pos());
            if (pressed > 0) {
                mask |= SyncTorchRule.bit(support);
            }
        }
        if (trace != null) {
            trace.mask(member, mask);
        }
        return mask;
    }

    /**
     * <b>五面里的一面读到多少</b>: 两份存储各读一次, 而每一份都先过"这根线是谁喂的"那道判据 ({@link #fedByGroupOnly})。
     *
     * <p>为什么一份一份过而不是把 {@code max} 之后再判: 两份读数来自两份<b>互不相通</b>的存储 - 画出来的那一格在 level
     * 里, 机器自己那一格在机器里 - 于是"这一份的电是谁喂的"也是两件独立的事。取大之前各判一次, 与读法逐字对应。
     *
     * <p>次序照旧: 先问画出来的那一格 (一台飞行机器旁边地上那根粉仍然算数, 与"立在世界里"那一支的问法逐字相同),
     * 再问机器自己那一份 ({@code -1} 是"没有这一份", 只给诊断用), 最后取较大的一个 - 正是
     * {@link ProductFrame#signalInto} 对吸附面做的事: 一个邻居最多只在其中一份里是方块。
     *
     * <p>吸附面不在这条路上: 它是我们的来源, 永远听。
     */
    private static int askSide(ServerLevel level, Member member, GroupMembers group, Spot spot, ProductFrame frame,
                               @Nullable BlockGetter ownWorld, BlockPos asked, BlockPos neighbour, Direction side,
                               @Nullable GroupTrace trace) {
        int drawn = SyncTorchRule.ask(level.getBlockState(asked).isRedstoneConductor(level, asked),
                level.getSignal(asked, frame.worldFace(side)),
                level.getDirectSignal(asked, frame.worldFace(side)));
        if (drawn > 0) {
            // 画出来那一份的坐标系是世界: 本组按世界坐标比, 而"我们自己"是这支火把被画在的那一格。
            drawn = withoutOurOwnLoop(drawn,
                    new WireWalk(level, level, group, spot.carrier(), frame.worldPosOf(spot.pos()), true),
                    asked, side, member, "drawn", trace);
        }
        int structure = ownWorld == null ? -1 : askInStructure(ownWorld, neighbour, side);
        if (structure > 0) {
            // 机器那一份的坐标系是 local: 本组比的是同一台载体的 local 坐标, "我们自己"就是这一格的 local。
            structure = withoutOurOwnLoop(structure,
                    new WireWalk(level, ownWorld, group, spot.carrier(), spot.pos(), false),
                    neighbour, side, member, "structure", trace);
        }
        int answer = ownWorld == null ? drawn : Math.max(drawn, structure);
        if (trace != null) {
            trace.faceRead(member, side, asked, neighbour, drawn, structure, answer);
        }
        return answer;
    }

    /**
     * 一份读数过一遍"这根线是谁喂的": 我们自己正在往它里面打的 15 绕回来的那一份不算输入。
     *
     * <p>判据只有一处 ({@link #fedByGroupOnly}), 这里只做两件事: 问一次, 以及 - 当它判成"我们的"时 - 在诊断里说清是
     * 哪一份读数 (drawn / structure) 被划掉的。两份存储各调一次, 因为它们是两次独立的读数。
     */
    private static int withoutOurOwnLoop(int answer, WireLine line, BlockPos readAt, Direction side, Member member,
                                         String half, @Nullable GroupTrace trace) {
        boolean fedByUs = fedByGroupOnly(line, readAt, MAX_WIRE_CELLS);
        if (fedByUs && trace != null) {
            trace.faceCreditedOut(member, side, readAt, half, answer);
        }
        return credited(answer, fedByUs);
    }

    /**
     * <b>那五面在"那台机器自己的存储"里要多问的那一份方块存储</b>, 或者 {@code null} - 那时 level 就是唯一的答案。
     *
     * <p>判据在 {@link CarrierStore#storageViewOf} 里只问一次, 而它答的是<b>哪一份存储</b>而不是"要不要多问":
     *
     * <ul>
     *   <li>一台 <b>Sable 子层级</b>交回来的就是 {@code level} <b>本身</b>。它的 {@code local} 就是 level 坐标, 于是
     *       "机器自己那一格"就是 {@code level} 的 {@code neighbour} (plot 坐标) - 贴在火把侧面那根粉就在那里, 而它
     *       <b>不在</b>这台机器被画出来的那个世界位置上 (那里是空气)。上一版对子层级答 {@code null}, 于是这一面只读了
     *       {@code drawn}, 机器内部侧面的输入一次都没被读到 - 修掉的就是它。子层级不加这一份时,"结构上的火把被激活"与
     *       "它自己旁边那根粉"之间根本没有读数, 撤源之后答案就留在了上一趟的那一份上。</li>
     *   <li>一台 <b>Create contraption</b> 交回来的是它自己的 world ({@link CarrierStore#blockViewOf}): 它的
     *       {@code local} 相对锚点, 拿它去问 level 只会问到别人的空气。这一支一个字未改。</li>
     *   <li>立在世界里的那一支 ({@code spot.isCarried()} 为假) 永远是 {@code null}: 它没有"机器", level 就是答案。</li>
     * </ul>
     *
     * <p>一处判据而不是每面、每个成员各判一次: 一台载体一趟是哪种载体不会在一趟里变, 而散在调用点的判据迟早给出两个
     * 答案。包内可见, 于是这一条能脱层测。
     */
    @Nullable
    static BlockGetter ownWorldOf(@Nullable BlockGetter level, Spot spot) {
        if (!spot.isCarried()) {
            return null;
        }
        return CarrierStore.storageViewOf(level, spot.carrier());
    }

    /** 五面那一份存储的短名, 只给诊断用: 没有 / level 自己 (子层级) / 载体视图 (contraption)。 */
    private static String storageName(@Nullable BlockGetter ownWorld, BlockGetter level) {
        if (ownWorld == null) {
            return "none";
        }
        return ownWorld == level ? "level" : "carrier-view";
    }

    /**
     * 一面在载体自己那一份存储里的读数: 与那五面在 level 上的问法<b>同一条</b> {@link SyncTorchRule#ask} (导体那一条判据
     * 在机器里同样成立), 只是位置与面都是那台机器的 local 坐标。于是 {@code ProductFrame#signalsIn} 不够用 - 它给的是
     * 弱信号与 direct 的较大者, 而这里还要问"它是不是导体"。
     *
     * <p>那一格读不到 (视图答 {@code null}) 时答 0: 于是调用方的"取较大的一个"仍然成立, 而"读不到"绝不被当成信号。
     */
    static int askInStructure(BlockGetter view, BlockPos localNeighbour, Direction localFace) {
        BlockState state = view.getBlockState(localNeighbour);
        if (state == null) {
            return 0;
        }
        return SyncTorchRule.ask(state.isRedstoneConductor(view, localNeighbour),
                state.getSignal(view, localNeighbour, localFace),
                state.getDirectSignal(view, localNeighbour, localFace));
    }

    /** 一个成员的答案: 这一组此刻输出多少, 以及它自己哪几面有输入。包内可见, 于是这条语义能脱层测。 */
    record Answer(int power, byte inputs) {
    }

    /**
     * 每个成员的答案。纯函数, 只读那张表和"每一面有没有电", 于是整件设备的语义可以脱开世界钉住。
     *
     * <p>共享频道是 OR - 任一成员任一面收到信号, 全组一起亮; 未绑定的成员自成一个组, 所以只看它自己。写回时每一个成员
     * 拿到的 power 相同、掩码各自不同: "一起亮"是答案, "哪一面不输出"是它自己的处境。
     *
     * <p>这里不区分成员在世界上还是在机器里: 那张表已经把它们放在同一个频道号下, 而"同一频道一起亮"就是
     * "跨机器同一组"。要区分的地方只有两处 - 怎么读它 (位姿) 和怎么写回 (哪一份存档), 都在下面。
     */
    static Map<Spot, Answer> decideRoster(Roster roster, Map<Spot, Integer> masks) {
        Map<Spot, Answer> out = new LinkedHashMap<>();
        for (int channel : roster.channels()) {
            Set<Spot> members = roster.membersOf(channel);
            boolean anyInput = false;
            for (Spot spot : members) {
                if (masks.getOrDefault(spot, 0) != 0) {
                    anyInput = true;
                    break;
                }
            }
            int power = SyncTorchRule.output(SyncTorchRule.lit(anyInput));
            for (Spot spot : members) {
                out.put(spot, new Answer(power, (byte) (int) masks.getOrDefault(spot, 0)));
            }
        }
        for (Spot spot : roster.soloSpots()) {
            int mask = masks.getOrDefault(spot, 0);
            out.put(spot, new Answer(SyncTorchRule.output(SyncTorchRule.lit(mask != 0)), (byte) mask));
        }
        return out;
    }

    /**
     * 只有世界形态时的答案, 形状照旧 (位置 → 答案)。
     *
     * <p>留着这个形状而不是让每个调用方自己转换: 表里只有世界成员的那条路仍然只有一种走法, 而 OR 的实现只有上面一处。
     */
    static Map<BlockPos, Answer> decide(SyncChannels channels, Map<BlockPos, Integer> masks) {
        Map<Spot, Integer> spots = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : masks.entrySet()) {
            spots.put(Spot.standing(entry.getKey()), entry.getValue());
        }
        Map<BlockPos, Answer> out = new LinkedHashMap<>();
        for (Map.Entry<Spot, Answer> entry : decideRoster(roster(channels), spots).entrySet()) {
            out.put(entry.getKey().pos(), entry.getValue());
        }
        return out;
    }

    /**
     * 世界那两种形态 (内嵌 + 立着) 取成名单, <b>每个位置先过一次归属判据</b>。
     *
     * <p>纯值: 归属由一个函数交进来 ({@link Ownership}), 于是"站在自己 plot 坐标上的那一支算搬运段"这条能脱层测。
     * 判据不在 {@code collect} 里, 因为那里只读两张表 - "这个坐标归不归一台活着的机器"是搬运层的事, 由
     * {@link Inquiry#spotAt} 问一次。
     */
    static Roster roster(SyncChannels channels, Ownership ownership) {
        Roster roster = new Roster();
        for (int channel : channels.channels()) {
            for (BlockPos pos : channels.membersOf(channel)) {
                put(roster, ownership.at(pos), channel);
            }
        }
        for (BlockPos pos : channels.soloPositions()) {
            putSolo(roster, ownership.at(pos));
        }
        return roster;
    }

    /** 谁也不归任何机器时的那一份名单; 纯值, 也就是没有物理后端时永远是的那一份。 */
    static Roster roster(SyncChannels channels) {
        return roster(channels, Ownership.STANDING);
    }

    /** 一个位置进名单: 世界段或搬运段; {@code null} (归属成立却说不出它画在哪) 就谁也没进。 */
    private static void put(Roster roster, @Nullable Spot spot, int channel) {
        if (spot == null) {
            return;
        }
        if (spot.isCarried()) {
            roster.putCarried(spot.carrier(), spot.pos(), channel);
        } else {
            roster.put(spot.pos(), channel);
        }
    }

    /** 与 {@link #put} 同一条规矩, 只是没绑频道的那些: 每一支自成一个只含它自己的组。 */
    private static void putSolo(Roster roster, @Nullable Spot spot) {
        if (spot == null) {
            return;
        }
        if (spot.isCarried()) {
            roster.putSoloCarried(spot.carrier(), spot.pos());
        } else {
            roster.putSolo(spot.pos());
        }
    }

    /** 把答案写回一个成员: 世界里那支改方块状态与它自己的掩码, 内嵌那个改 {@code slot.power} 与 {@code syncInputs}。 */
    private static boolean write(ServerLevel level, InnerRedstoneStore store, Member member, Answer answer) {
        if (member.slot() != null && member.spot().isCarried()) {
            return writeCarried(level, member, answer);
        }
        BlockPos pos = member.spot().pos();
        CarrierStore.CarriedCell cell = member.cell();
        if (cell != null) {
            // 被搬着、没有槽位的那一支: 写回走载体层那一对入口的另一半 ({@link CarrierStore#setCarriedCell}) - 方块
            // (LIT) 与这一格的掩码一起写, 而"写到哪一份存储"由 CarrierStore 那一条判据决定: Sable 子层级写 level 的
            // plot 那一格 (与上一版逐字节相同), Create contraption 写它自己的方块表。答案也留在机器作用域的表里, 于是
            // 方块晚一步被放回这一格时 (组 pass 那一次写落空), 驱动那一趟仍能把它写上去, 见
            // SyncTorchBlock#refreshCarried。世界要在它"看起来在哪"那一格听到, 因为这一格的邻居属于 plot, 不是世界。
            boolean lit = answer.power() > 0;
            boolean changed = CarrierStore.setCarriedCell(level, member.spot().carrier(), pos,
                    new CarrierStore.CarriedCell(
                            cell.state() == null ? null : cell.state().setValue(SyncTorchBlock.LIT, lit),
                            answer.inputs()));
            CarriedSyncTorches.noteAnswer(member.spot().carrier(), pos, answer.inputs(), lit);
            // 机器里自己那一格的邻居也要听见: 子层级那一格就是 level 的一格 (那根粉就在 plot 里), 而"写方块"这一半只
            // 在状态真的变了时通知它, 于是"只有掩码变了"那一半从来没有邻居通知 - 与下面世界形态那一支要补的正是同
            // 一件事。判据 (叫得到哪一格) 在载体层, 这里只说"这一格变了", 一次。
            if (changed || cell.inputs() != answer.inputs()) {
                CarrierStore.notifyCellChanged(level, member.spot().carrier(), pos);
            }
            if (changed) {
                pokeDrawn(level, member.spot().carrier(), pos);
            }
            return changed;
        }
        if (member.slot() == null) {
            boolean maskChanged = store.syncTorchInputsAt(pos) != answer.inputs();
            if (maskChanged) {
                store.putSyncTorchInputs(pos, answer.inputs());
            }
            boolean litChanged = SyncTorchBlock.applyLit(level, pos, answer.power() > 0);
            if (maskChanged && !litChanged) {
                // 输出面变了而亮灭没变: 邻居听到的那几个方向变了, 必须重新问一次。
                level.updateNeighborsAt(pos, level.getBlockState(pos).getBlock());
            }
            return maskChanged || litChanged;
        }
        boolean powerChanged = member.slot().power != answer.power();
        boolean maskChanged = member.slot().syncInputs != answer.inputs();
        if (!powerChanged && !maskChanged) {
            return false;
        }
        // 同步火把的 power 只有这里写: 求解器看不见它 (ComponentType#hasInputSide 对它是 false), 所以这张嘴上没有
        // 第二条路 - 这也是"组 pass 拥有这个答案"这句话在代码里的样子。
        member.slot().syncInputs = answer.inputs();
        member.slot().power = answer.power();
        store.markDirty();
        // The neighbours and the client both have to hear about it in this same tick, or the group would look like it
        // was answering one tick late - which is exactly what this device exists not to do.
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, member.slot());
        return true;
    }

    /**
     * 把一个<b>被搬着的</b>成员的答案写回它所属的那台机器。
     *
     * <p>写的是搬运索引里的那一份, 走的是搬运层已有的回写通道 ({@code CarrierStore#writeBackCarriedSlot}: 索引 +
     * 存档变脏 + 一个搬运包), 于是客户端画在机器里的那一份同 tick 就听到答案。世界坐标那边一个字都不写: 机器里的这个
     * 坐标在世界里不是一格, 而它被搬走时, 世界 store 里那一行已经被搬走了 - 一支火把只有一个嘴。
     *
     * <p>答案落地之后<b>同 tick</b> 通知机器里这一格的邻居 ({@link CarrierStore#notifyCellChanged}): 一根贴在它侧面
     * 的红石粉正是因为听见了 15 才亮, 也就必须在它不再输出那一刻听见"该重算了", 否则那根粉保留上一次的 15, 组 pass
     * 每趟把它读成输入 - 自锁。叫哪一格由载体层那一条判据答 (Sable 子层级: 这一格的 plot 坐标), 而机器<b>内部其他
     * 元件</b>的求解仍要等下一 tick 的搬运求解 (那一趟在本趟之前跑), 世界上的邻居仍要等下一 tick 的投影表重建 - 同步
     * 优先于新鲜度, 这是"同一 tick 里只有一处写 power"的代价, 也是它可以接受的理由。
     */
    private static boolean writeCarried(ServerLevel level, Member member, Answer answer) {
        Slot slot = member.slot();
        boolean powerChanged = slot.power != answer.power();
        boolean maskChanged = slot.syncInputs != answer.inputs();
        if (!powerChanged && !maskChanged) {
            return false;
        }
        slot.syncInputs = answer.inputs();
        slot.power = answer.power();
        // 索引里那一条已经不在了 (这趟收到答案之前它被放下了) 时, 上面两个赋值改的是一个已经没人持有的记录,
        // 所以"有没有落到地上"就是回写自己的答案, 而不是一句"我写了"。
        boolean written = CarrierStore.writeBackCarriedSlot(level, member.spot().carrier(), member.spot().pos(),
                slot);
        if (written) {
            // 答案真的落地了, 机器里<b>这一格</b>的邻居才听见 - 而这一格正是"我旁边那根粉"所在的那一份存储。上一版
            // 这里一句通知都没有, 于是粉保留上一次的 15, 组 pass 每趟把它读成输入: 自锁。叫哪一格 (子层级 = 这一格
            // 的 plot 坐标, contraption = 它的 world, level 里叫不到) 由载体层那一条判据答, 组 pass 只说"变了"。
            CarrierStore.notifyCellChanged(level, member.spot().carrier(), member.spot().pos());
        }
        return written;
    }

    /**
     * 让世界在"它看起来在哪"那一格听到这次变化.
     *
     * <p>机器里那一格的邻居属于 plot: 在 plot 里做一次邻居通知, 世界那一侧一个人也听不到, 而机器投影到它下面的红石粉
     * 正是要听的那一个。问一次位姿、算一格投影, 只在答案或输出面真的变了的时候发生。
     */
    private static void pokeDrawn(ServerLevel level, CarrierId carrier, BlockPos local) {
        CarrierPose pose = CarrierStore.poseOf(level, carrier);
        BlockPos drawn = CarrierProjection.drawnBlockOf(pose, local);
        if (drawn == null) {
            return;
        }
        level.updateNeighborsAt(drawn, level.getBlockState(drawn).getBlock());
        InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
        InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
    }

    /** 表里每个位置此刻的成员视图; 已经不是同步火把的位置不会出现在这里。 */
    private static Map<Spot, Member> presentMembers(ServerLevel level, InnerRedstoneStore store,
                                                    @Nullable CarriedHostIndex index, Roster roster) {
        Map<Spot, Member> out = new LinkedHashMap<>();
        for (int channel : roster.channels()) {
            for (Spot spot : roster.membersOf(channel)) {
                Member member = memberAt(level, store, index, spot);
                if (member != null) {
                    out.put(spot, member);
                }
            }
        }
        for (Spot spot : roster.soloSpots()) {
            Member member = memberAt(level, store, index, spot);
            if (member != null) {
                out.put(spot, member);
            }
        }
        return out;
    }

    /** 世界那两种形态取成名单, 每个位置先问一次归属 ({@link Inquiry#spotAt}); 被搬着的那一支不在这里。 */
    private static Roster roster(ServerLevel level, SyncChannels channels, Inquiry inquiry) {
        return roster(channels, inquiry::spotAt);
    }

    /**
     * 收表: 先内嵌形态, 再世界形态, 最后是被搬着的那一支。
     *
     * <p>顺序不是随意的: 一个位置上真立着一支同步火把时, 那支方块就是这里的设备, 所以世界形态最后写, 覆盖掉同一个坐标上
     * 万一还留着的槽位记录 - 两个索引说同一个位置时, 站着的那个赢。
     *
     * <p>搬运那一支必须在这里收, 而不是等搬运求解去发现它: {@code CarrierLightNetwork#isLogic} 只认 wire / 有输入面 /
     * 时钟源, 而同步火把的答案是"全组有谁收到信号", 它自己谁也不问 - 能同时看见全组和机器的那一趟只有这一趟。
     */
    private static Roster roster(ServerLevel level, InnerRedstoneStore store, @Nullable CarriedHostIndex index,
                                 Inquiry inquiry) {
        Roster roster = roster(level, collect(store), inquiry);
        if (index != null) {
            for (CarrierId carrier : index.carriers()) {
                for (CarriedHost host : index.liveHostsOf(carrier)) {
                    Slot slot = host.slot();
                    if (slot == null || !slot.type.isGroupDriven()) {
                        continue;
                    }
                    if (SyncTorchRule.isChannel(slot.syncGroup)) {
                        // 频道号是一个数, 没有"世界里的三号"和"机器里的三号"之分: 同号就是同一组, 这就是跨机器联动。
                        roster.putCarried(carrier, host.localPos(), slot.syncGroup);
                    } else {
                        // 未绑定: 自成一个只含它自己的组, 于是它照样能用, 但不跟任何人联动 - 与世界形态同一条规则。
                        roster.putSoloCarried(carrier, host.localPos());
                    }
                }
            }
        }
        // 立在世界里的那一形态被搬走时没有槽位, 所以搬运索引里没有它的位置: 它的号码住在机器作用域的表里
        // (CarriedSyncTorches), 而"机器里这一格还立着一支"由那一格自己回答 - 于是它与内嵌形态、世界形态进同一张频道表,
        // OR 也只有一处。
        if (CarrierStore.isActive()) {
            for (CarriedSyncTorches.Row row : CarriedSyncTorches.rows()) {
                Spot spot = Spot.carried(row.carrier(), row.local());
                // 一个地址只算一个成员: 这一格要是已经由它自己那份存档进了名单 (槽位, 或者世界那一行 - 后者正是一支
                // 站在 plot 坐标上的火把), 机器作用域的这一行就不再算第二个, 否则同一支火把会在两段里各出现一次。
                if (roster.holds(spot) || memberAt(level, store, index, spot) == null) {
                    continue;
                }
                if (SyncTorchRule.isChannel(row.channel())) {
                    roster.putCarried(row.carrier(), row.local(), row.channel());
                } else {
                    roster.putSoloCarried(row.carrier(), row.local());
                }
            }
        }
        return roster;
    }

    /** 收表: 先内嵌形态, 再世界形态。 */
    private static SyncChannels collect(InnerRedstoneStore store) {
        SyncChannels channels = new SyncChannels();
        for (long key : store.positionKeys()) {
            Slot slot = store.slotAt(BlockPos.of(key));
            if (slot == null || slot.type != ComponentType.SYNC_TORCH) {
                continue;
            }
            if (SyncTorchRule.isChannel(slot.syncGroup)) {
                channels.put(BlockPos.of(key), slot.syncGroup);
            } else {
                // 未绑定: 自成一个只含它自己的组, 于是它照样能用, 但不跟任何人联动。
                channels.putSolo(BlockPos.of(key));
            }
        }
        for (BlockPos pos : store.syncTorchPositions()) {
            int channel = store.syncTorchChannelAt(pos);
            if (SyncTorchRule.isChannel(channel)) {
                channels.put(pos, channel);
            } else {
                channels.putSolo(pos);
            }
        }
        return channels;
    }

    /**
     * 一个位置的成员视图, 或者 {@code null} 当那里已经不是同步火把了。
     *
     * <p>这一句是"表可能过期"的唯一防线, 而且是必要的: 频道表按位置存, 一个位置被挖掉之后那一行是由<b>方块自己的</b>
     * 钩子清的, 所以 pass 宁可每次问一次那里是什么, 也不要对一个已经不在的东西写答案。
     *
     * <p>被搬着的那一个问的是搬运索引: 它的槽位在那里, 而"还在不在"是索引里那一条记录还在不在。
     */
    @Nullable
    private static Member memberAt(ServerLevel level, InnerRedstoneStore store,
                                   @Nullable CarriedHostIndex index, Spot spot) {
        if (spot.isCarried()) {
            CarriedHost host = index == null ? null : index.at(spot.carrier(), spot.pos());
            Slot slot = host == null ? null : host.slot();
            if (slot != null) {
                return slot.type.isGroupDriven() ? new Member(spot, slot.facing, slot) : null;
            }
            // 世界形态被搬着的那一支: 机器里这一格真的立着一支同步火把, 而它没有槽位 - 于是它和"站在那里"的那一支一样
            // 处理, 读写的都是这一格。
            //
            // <b>问的是载体层那一对入口</b> ({@link CarrierStore#carriedCellAt}), 不是 level: 一台 Sable 子层级的方块
            // 就躺在自己的 plot 坐标上, 而一台 Create contraption 的 local 是相对锚点的坐标 - 那里在 level 里是别人的
            // 一格。上一版这里直接读 level, 于是 contraption 上那支被判成"不是火把", LIT 不被驱动、掩码也不写。
            CarrierStore.CarriedCell cell = CarrierStore.carriedCellAt(level, spot.carrier(), spot.pos());
            BlockState carried = cell == null ? null : cell.state();
            return carried != null && carried.getBlock() instanceof SyncTorchBlock
                    ? new Member(spot, carried.getValue(SyncTorchBlock.FACING), null, cell)
                    : null;
        }
        if (level.isLoaded(spot.pos())) {
            BlockState state = level.getBlockState(spot.pos());
            if (state.getBlock() instanceof SyncTorchBlock) {
                return new Member(spot, state.getValue(SyncTorchBlock.FACING), null);
            }
        }
        Slot slot = store.slotAt(spot.pos());
        if (slot != null && slot.type == ComponentType.SYNC_TORCH) {
            return new Member(spot, slot.facing, slot);
        }
        return null;
    }

    /**
     * 被搬着的那些 host 的索引, 或者 {@code null} 当此刻没有任何东西在飞。
     *
     * <p>{@link CarrierStore#isActive()} 一个布尔就挡掉了"Sable/Create 一个都没装"的整个世界: 那一支不加载时, 这里
     * 的每一次调用都只是一次静态布尔读。
     */
    @Nullable
    private static CarriedHostIndex carriedIndexOf(ServerLevel level) {
        if (!CarrierStore.isActive()) {
            return null;
        }
        CarriedHostIndex index = CarrierStore.indexOf(level);
        return index == null || index.isEmpty() ? null : index;
    }

    /**
     * <b>一趟组 pass 的诊断: 一行一条, 默认一条都不产生。</b>
     *
     * <p>为什么要有它: 两次自锁 (结构上的火把移走后地上那支亮熄亮熄、方块内那支和地上那支都亮着不熄) 在
     * 日志里<b>一条组 pass 的 trace 都没有</b>, 于是"答案为什么留在 15"既看不见落在哪一面, 也看不见是哪一份读数把 15
     * 读回来的。这里只回答三件事, 而三件就够定位:
     *
     * <ol>
     *   <li><b>成员是谁</b>: 每个频道一行名单 - 世界坐标 / (载体 shortId, 局部坐标), 内嵌形态还是世界形态;</li>
     *   <li><b>六面各自读到什么</b>: 画出来的那一格 (drawn) 与机器自己那一份 (structure) 各答多少、最终取哪一个、
     *       有没有被 {@link SyncTorchRule#listensTo} 跳过以及跳过的理由;</li>
     *   <li><b>答案与写回</b>: 这一组这一趟算出多少 (0 / 15)、掩码变成什么、写回真的改动了没有。</li>
     * </ol>
     *
     * <p>开关在 {@link RCConfig#traceSyncGroup()} 一处问 ({@link SyncGroups#traceOf(boolean)}): 关着时这里根本没有
     * 对象, 于是每一条输出只花一次 null 判断 - 不拼字符串, 不写日志。一行一条, 都带 {@code [redstonecircuit] syncGroup}
     * 与 {@code pass=}, 于是 {@code grep syncGroup} 就能按趟读下来。
     */
    static final class GroupTrace {

        /** 成员 → 它的频道号, 只在诊断打开时建一次; 0 表示"没绑频道" (自成一组的那些)。 */
        private final Map<Spot, Integer> channelOf = new HashMap<>();

        private int pass;

        /** 一个频道一行: 频道号、成员数、以及每个成员的地址与形态。 */
        void roster(int pass, Roster roster, Map<Spot, Member> present) {
            this.pass = pass;
            this.channelOf.clear();
            for (int channel : roster.channels()) {
                Set<Spot> members = roster.membersOf(channel);
                List<String> listed = new ArrayList<>();
                for (Spot spot : members) {
                    this.channelOf.put(spot, channel);
                    Member member = present.get(spot);
                    if (member != null) {
                        listed.add(describe(spot) + " form=" + formOf(member));
                    }
                }
                log("pass=" + pass + " channel=" + channel + " members=" + members.size()
                        + " present=" + listed.size() + " " + String.join(" | ", listed));
            }
            for (Spot spot : roster.soloSpots()) {
                this.channelOf.put(spot, 0);
                Member member = present.get(spot);
                if (member != null) {
                    log("pass=" + pass + " channel=solo " + describe(spot) + " form=" + formOf(member));
                }
            }
        }

        /** 一个成员一行: 吸附面朝向、<b>这一趟开头抓下来的</b>掩码与 LIT、以及五面要多问的那一份存储是哪一种。 */
        void member(Member member, Drive start, Drive now, String storage) {
            // 两半都写出来: 只写其中一份时, "这一面为什么被跳过"与"为什么读了答 0"在读日志时仍然要猜。
            log(prefix(member) + " support=" + member.facing()
                    + " mask=0x" + hex(start.inputs()) + " lit=" + start.lit()
                    + " now=0x" + hex(now.inputs()) + " lit=" + now.lit()
                    + " storage=" + storage);
        }

        /**
         * 这一面没读: 没加载 / 隔壁是同频道的另一位 ({@link SyncGroups#SAME_GROUP}) / 我们正在往那一面输出
         * ({@link SyncTorchRule#listensTo} 跳过)。
         */
        void faceSkipped(Member member, Direction side, BlockPos asked, String reason) {
            log(prefix(member) + " side=" + side + " asked=" + asked.toShortString() + " skipped=" + reason);
        }

        /** 吸附面那一行: 它只有一份答案 ({@link ProductFrame#signalInto} 自己已经两侧取大)。 */
        void faceSupport(Member member, Direction side, BlockPos asked, BlockPos neighbour, int answer) {
            log(prefix(member) + " side=" + side + " support=" + neighbour.toShortString()
                    + " asked=" + asked.toShortString() + " signalInto=" + answer);
        }

        /** 其余五面那一行: 画出来的那一格与机器自己那一份<b>各答多少</b>, 以及取大之后的答案。 */
        void faceRead(Member member, Direction side, BlockPos asked, BlockPos neighbour, int drawn,
                      int structure, int answer) {
            log(prefix(member) + " side=" + side
                    + " drawn=" + asked.toShortString() + "=" + drawn
                    + " structure=" + (structure < 0 ? "none" : neighbour.toShortString() + "=" + structure)
                    + " answer=" + answer);
        }

        /**
         * 这一份读数读到了, 但被判成"我们自己喂出来的" ({@link SyncGroups#fedByGroupOnly}): 哪一份 (drawn / structure)、
         * 哪一格、答了多少。
         *
         * <p>与 {@code skipped=same-group} 是两件事: 那一个是隔壁真的坐着同频道的成员, 这一个是我们自己喂亮的线从另一格
         * 绕回来。那条自锁的两个读数正是这样消失的, 于是从这一行一眼看得见"是哪一份被划掉"。
         */
        void faceCreditedOut(Member member, Direction side, BlockPos at, String half, int answer) {
            log(prefix(member) + " side=" + side + " at=" + at.toShortString()
                    + " skipped=group-loop-" + half + " answer=" + answer);
        }

        /** 这一面读完之后的掩码 - 它同时是"哪几面不输出"的那份记录。 */
        void mask(Member member, int mask) {
            log(prefix(member) + " mask=0x" + hex((byte) mask) + " faces=" + facesOf(mask));
        }

        /** 这一组这一趟算出的答案, 以及写回真的改了什么没有。 */
        void answered(Member member, Answer answer, boolean wrote) {
            log(prefix(member) + " power=" + answer.power() + " lit=" + (answer.power() > 0)
                    + " inputs=0x" + hex(answer.inputs()) + " written=" + wrote);
        }

        private String prefix(Member member) {
            return "pass=" + this.pass + " channel=" + channelLabel(member.spot())
                    + " member=" + describe(member.spot());
        }

        private String channelLabel(Spot spot) {
            Integer channel = this.channelOf.get(spot);
            return channel == null || channel <= 0 ? "solo" : String.valueOf(channel);
        }

        /** 一个成员在哪: 世界坐标, 或者 (载体 shortId, 机器里的局部坐标) - 两者不可混读, 于是两者都写出来。 */
        static String describe(Spot spot) {
            return spot.isCarried()
                    ? "carrier " + spot.carrier().shortId() + "@" + spot.pos().toShortString()
                    : "world " + spot.pos().toShortString();
        }

        /** 内嵌形态 (号码在 {@code Slot#syncGroup} 上) 还是世界形态 (号码在 store/机器作用域的那一行上)。 */
        private static String formOf(Member member) {
            if (member.spot().isCarried()) {
                return member.slot() != null ? "carried/embedded" : "carried/standing";
            }
            return member.slot() != null ? "world/embedded" : "world/standing";
        }

        /** 掩码里哪几面有输入, 按 {@link Direction} 的固定顺序写出来。 */
        static String facesOf(int mask) {
            List<String> faces = new ArrayList<>();
            for (Direction side : Direction.values()) {
                if ((mask & SyncTorchRule.bit(side)) != 0) {
                    faces.add(side.name());
                }
            }
            return faces.isEmpty() ? "-" : String.join(",", faces);
        }

        private static String hex(byte bits) {
            return String.format("%02x", bits & 0xFF);
        }

        private static void log(String body) {
            RCConfig.LOGGER.info("[redstonecircuit] syncGroup {}", body);
        }
    }

    /** 一个成员: 它在哪 (世界坐标, 或机器 + 局部坐标)、它粘着哪个面、以及它是内嵌/搬运形态时的那个槽位
     * ({@code null} = 世界里的方块形态)。 */
    private record Member(Spot spot, Direction facing, @Nullable Slot slot,
                          @Nullable CarrierStore.CarriedCell cell) {

        /** 世界形态 / 内嵌形态的成员: 它没有"载体里那一格"这一份读数 (那两份各在自己的存档里)。 */
        Member(Spot spot, Direction facing, @Nullable Slot slot) {
            this(spot, facing, slot, null);
        }
    }
}
