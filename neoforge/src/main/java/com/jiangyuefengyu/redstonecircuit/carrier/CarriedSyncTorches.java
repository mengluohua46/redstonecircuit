package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.block.SyncTorchBlock;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * 一台机器带着的同步火把: 频道、这一刻的输入掩码、以及组 pass 定下的答案.
 *
 * <p>为什么需要这张表: 世界形态的频道按<b>世界坐标</b>存在 {@code InnerRedstoneStore} 的 {@code syncTorch} 表里, 而一支
 * 被搬走的火把在世界里没有格子了 (它在机器的 plot 坐标上), 那一行按世界坐标存也带不走。内嵌形态没有这个问题, 因为它的
 * 频道在 {@code Slot#syncGroup} 上、跟着槽位走; 立着的那一支没有槽位, 于是它的频道需要一个按 (机器, 机器内坐标) 记的
 * 家 —— 就是这里。口径只有一句: <b>频道跟着走</b>, 搬运中与同频道成员一起亮灭, 落地之后在原号码上恢复。
 *
 * <p>两个部分, 都<b>不进存档</b>: 它们是"此刻"的事实, 与 {@code syncTorchInputs} 同类 —— 存下来只会在重载之后给出过期
 * 答案。跟着存档走的是<strong>位置</strong>: 搬运期间世界那一行已经收起 (它属于搬走的那个坐标), 落地时由
 * {@link #handBack} 写到投影算出的新坐标上, 那一刻起它又是世界 store 里的一行、照旧随存档走。
 *
 * <p>没有物理后端的世界里这张表永远是空的, 而它的每一个入口都在 {@code CarrierStore#isActive()} 之后, 于是 Sable 没装时
 * 连这个类都不会被加载。
 */
public final class CarriedSyncTorches {

    /** How long a row lifted out of the world store waits to be claimed: the claim is the level sweep that
     * follows, so one second is already generous - an older one is a leftover, not a hand-over. */
    private static final long PENDING_TICKS = 20;

    /** 方块离开世界那一刻的行, 在机器认领它之前寄存在这里. */
    private static final Map<BlockPos, Capture> LIFTED = new LinkedHashMap<>();

    /** 机器 → (机器内坐标 → 行). 一台机器带着的同步火把通常只有一支, 所以这是一张小表。 */
    private static final Map<CarrierId, Map<BlockPos, Row>> BY_CARRIER = new LinkedHashMap<>();

    private CarriedSyncTorches() {
    }

    // ------------------------------------------------------------------ 类型 --

    /** 一支火把离开世界时带走的东西: 号码与那一刻的读取掩码, 以及它是在哪一 tick 走的。 */
    public record Capture(int channel, byte inputs, long at) {
    }

    /**
     * 机器带着的一支火把。
     *
     * <p>{@code local} 是它在机器里的坐标 (一份 Sable 子层级的方块站在自己的 plot 坐标上), {@code origin} 是它离开世界
     * 世界时的坐标 —— 没有位姿可问时, 那就是它落回来的地方。{@code inputs} 与 {@code lit} 每趟由组 pass 重写: 一支火把
     * 的答案是全组 OR, 而哪几面有输入是它自己的处境。
     */
    public static final class Row {

        /**
         * Which machine this row rides. Not final: a contraption is claimed under a provisional identity
         * when the assembly has not attached its entity yet, and the row has to be re-filed under the
         * entity's own name when it appears - see {@link #rekey}. Everything else about a row is what it
         * <em>is</em> (where it rides, where it came from, which channel), so nothing else moves.
         */
        private CarrierId carrier;
        private final BlockPos local;
        private final BlockPos origin;
        private int channel;
        private byte inputs;
        private boolean lit;

        private Row(CarrierId carrier, BlockPos local, BlockPos origin, int channel, byte inputs, boolean lit) {
            this.carrier = carrier;
            this.local = local.immutable();
            this.origin = origin.immutable();
            this.channel = channel;
            this.inputs = inputs;
            this.lit = lit;
        }

        public CarrierId carrier() {
            return this.carrier;
        }

        /** The one field that is a name rather than a fact: re-filed by {@link CarriedSyncTorches#rekey}. */
        private void renamed(CarrierId carrier) {
            this.carrier = carrier;
        }

        /** 它在机器里的坐标。 */
        public BlockPos local() {
            return this.local;
        }

        /** 它离开世界时的那一格。 */
        public BlockPos origin() {
            return this.origin;
        }

        /** 频道号, {@code 0} 表示没绑频道 (自成一个只含自己的组)。 */
        public int channel() {
            return this.channel;
        }

        /** 这一刻哪几面有输入, 一位一面。 */
        public byte inputs() {
            return this.inputs;
        }

        /** 组 pass 这一刻的答案: 亮或灭。 */
        public boolean lit() {
            return this.lit;
        }
    }

    // --------------------------------------------------------------- 离开世界 --

    /**
     * 一支同步火把正在离开世界: 留下它的号码与掩码, 等一台机器来认领。
     *
     * <p>为什么必须在这里留一手: 那一行是按世界坐标存的, 方块自己的钩子 ({@code SyncTorchBlock#onRemove}) 必须把它删掉
     * —— 否则下一支站到这个坐标上的火把就会读到别人的号码。而"这次离开是不是一台机器在搬"在方块钩子里<b>答不出来</b>
     * (机器认领发生在方块已经消失之后), 所以这里不猜: 留下来的这一份只等一个消费者, 而唯一的消费者是下面那趟认领。没人
     * 认领的会在 {@link #pending} 里过期。
     */
    public static void stash(ServerLevel level, BlockPos origin, int channel, byte inputs) {
        if (level == null || origin == null) {
            return;
        }
        LIFTED.put(origin.immutable(), new Capture(Math.max(0, channel), inputs, level.getGameTime()));
    }

    /** 还在等人认领的那几个坐标, 顺带丢掉过期的; 认领的候选集要把它也算上。 */
    public static List<BlockPos> pending(ServerLevel level) {
        if (level == null) {
            return List.of();
        }
        long now = level.getGameTime();
        LIFTED.values().removeIf(capture -> now - capture.at() > PENDING_TICKS);
        return new ArrayList<>(LIFTED.keySet());
    }

    /** 把 {@code origin} 上寄存的那一份取走; 没有就是 {@code null}。认领的门全过完之后才调用。 */
    @Nullable
    public static Capture take(ServerLevel level, BlockPos origin) {
        if (level == null || origin == null) {
            return null;
        }
        return LIFTED.remove(origin);
    }

    // ----------------------------------------------------------------- 认领 --

    /**
     * 一台机器认领了一支: 它的号码从此按 (机器, 机器内坐标) 记, 不再按世界坐标。
     *
     * <p>同一支只认领一次: 机器里那一格已经有行时这一句什么都不做 —— 重复认领会把一个真号码覆盖成"没绑频道", 因为第二次
     * 认领时世界那一行早就不在了。
     */
    public static void capture(CarrierId carrier, BlockPos local, BlockPos origin, int channel, byte inputs,
                               boolean lit) {
        if (carrier == null || local == null || origin == null) {
            return;
        }
        Map<BlockPos, Row> rows = BY_CARRIER.computeIfAbsent(carrier, ignored -> new LinkedHashMap<>());
        BlockPos key = local.immutable();
        if (rows.containsKey(key)) {
            return;
        }
        rows.put(key, new Row(carrier, key, origin, Math.max(0, channel), inputs, lit));
    }

    /**
     * 这台机器换了名字: provisional → 实体 UUID, 与搬运索引同一次改名。
     *
     * <p>为什么必须有这一条: 一台 Create 机器在 {@code startMoving} 里常常还没有实体, 于是它的认领被记在一个临时名字
     * 下, 而实体出现之后由搬运层把索引改到实体的 UUID 上。这张表按同一个 {@link CarrierId} 记, 不改的话落地那一趟会
     * 拿实体的名字来问, 而这里一条都找不到 —— 号码于是留在表里, 那支火把落地后变回未绑定。
     *
     * <p>同一格已经有行时新的不动它 (那一格只认领一次), 于是重复改名不会把真号码覆盖成"没绑频道"。
     *
     * @return 改了多少行; 0 表示这台机器在表里没有东西, 调用方不必多说一句
     */
    public static int rekey(@Nullable CarrierId from, @Nullable CarrierId to) {
        if (from == null || to == null || from.equals(to)) {
            return 0;
        }
        Map<BlockPos, Row> rows = BY_CARRIER.remove(from);
        if (rows == null) {
            return 0;
        }
        Map<BlockPos, Row> target = BY_CARRIER.computeIfAbsent(to, ignored -> new LinkedHashMap<>());
        for (Map.Entry<BlockPos, Row> entry : rows.entrySet()) {
            Row row = entry.getValue();
            row.renamed(to);
            target.putIfAbsent(entry.getKey(), row);
        }
        return rows.size();
    }

    /**
     * <b>机器内部换格子: 这台机器里那几行的机器内坐标跟着换。</b>
     *
     * <p>行里除了坐标的四样东西都是它<b>是</b>什么, 不是它<b>在哪</b>: {@code origin} 是它离开世界时的那一格 (结构飞多远
     * 都不变), 号码、这一刻的输入掩码、组 pass 定的亮灭同样不跟着走。只有键 (机器内坐标) 换, 因为那是这一行唯一与
     * 方块位置绑在一起的东西 —— 而结构里被活塞推走的那格方块真的换了位置。
     *
     * <p>去重与 {@link #rekey} 同一句: 目标格上已经有一行时新的不动它 (那一格只认领一次), 于是重复搬动不会把真号码
     * 覆盖成别人的。撞上的那一行留在原格, 与搬运索引里那些记录同一句判据。
     */
    public static CarriedHostIndex.LocalMoves remap(@Nullable CarrierId carrier,
                                                    @Nullable Map<BlockPos, BlockPos> moves) {
        if (carrier == null || moves == null || moves.isEmpty()) {
            return CarriedHostIndex.LocalMoves.NONE;
        }
        Map<BlockPos, Row> rows = BY_CARRIER.get(carrier);
        if (rows == null || rows.isEmpty()) {
            return CarriedHostIndex.LocalMoves.NONE;
        }
        Map<BlockPos, Row> leaving = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, BlockPos> move : moves.entrySet()) {
            if (move.getKey() == null || move.getValue() == null) {
                continue;
            }
            Row row = rows.get(move.getKey().immutable());
            if (row != null) {
                leaving.put(move.getKey().immutable(), row);
            }
        }
        if (leaving.isEmpty()) {
            return CarriedHostIndex.LocalMoves.NONE;
        }
        Map<BlockPos, BlockPos> planned = new LinkedHashMap<>();
        int blocked = 0;
        for (BlockPos from : leaving.keySet()) {
            BlockPos to = moves.get(from).immutable();
            if (rows.containsKey(to) && !leaving.containsKey(to)) {
                blocked++;
                continue;
            }
            planned.put(from, to);
        }
        if (planned.isEmpty()) {
            return new CarriedHostIndex.LocalMoves(0, blocked);
        }
        for (BlockPos from : planned.keySet()) {
            rows.remove(from);
        }
        int moved = 0;
        for (Map.Entry<BlockPos, BlockPos> entry : planned.entrySet()) {
            Row row = leaving.get(entry.getKey());
            BlockPos to = entry.getValue();
            // 新行而不是改 key: Row 的 local 是 final, 而它本来就不该变 —— 换的是这一行挂在哪一格。
            rows.put(to, new Row(row.carrier(), to, row.origin(), row.channel(), row.inputs(), row.lit()));
            moved++;
        }
        return new CarriedHostIndex.LocalMoves(moved, blocked);
    }

    /** 每一支被搬着的火把, 按认领的顺序; 组 pass 每趟从这里收成员。 */
    public static List<Row> rows() {
        List<Row> out = new ArrayList<>();
        for (Map<BlockPos, Row> rows : BY_CARRIER.values()) {
            out.addAll(rows.values());
        }
        return out;
    }

    /** 机器里那一格上的那一支, 或者 {@code null}。 */
    @Nullable
    public static Row at(BlockPos local) {
        if (local == null) {
            return null;
        }
        for (Map<BlockPos, Row> rows : BY_CARRIER.values()) {
            Row row = rows.get(local);
            if (row != null) {
                return row;
            }
        }
        return null;
    }

    /**
     * 这台机器的这一格上的那一支, 或者 {@code null}.
     *
     * <p>与 {@link #at(BlockPos)} 的区别正是"谁问": 组 pass 手里只有地址, 而一个被点击解析出来的目标手里有
     * (机器, 机器内坐标) 两个分量 - 同一格在两台机器上各有一行是可能的, 所以那一边必须按整副地址问。
     */
    @Nullable
    public static Row at(@Nullable CarrierId carrier, @Nullable BlockPos local) {
        if (carrier == null || local == null) {
            return null;
        }
        Map<BlockPos, Row> rows = BY_CARRIER.get(carrier);
        return rows == null ? null : rows.get(local);
    }

    /**
     * 换掉机器里那一支的号码, 返回"真的改了没有".
     *
     * <p>号码是这一行的<b>身份</b>, 与它此刻的读数 ({@code inputs} / {@code lit}, 组 pass 每趟重写) 不是同一类东西:
     * 只在玩家动调频器时变。那一格没有行时答 {@code false} - 调用方据此不去广播一次落空的写入。
     */
    public static boolean setChannel(@Nullable CarrierId carrier, @Nullable BlockPos local, int channel) {
        Row row = at(carrier, local);
        if (row == null || row.channel == channel) {
            return false;
        }
        row.channel = channel;
        return true;
    }

    /** 有没有机器带着同步火把; 组 pass 的早退条件要问这一句, 那时世界 store 可能是空的。 */
    public static boolean any() {
        return !BY_CARRIER.isEmpty();
    }

    /** 组 pass 写下一个成员的答案: 一台机器里那一支的亮灭与它自己那几面输入。 */
    public static void noteAnswer(CarrierId carrier, BlockPos local, byte inputs, boolean lit) {
        Map<BlockPos, Row> rows = carrier == null ? null : BY_CARRIER.get(carrier);
        Row row = rows == null ? null : rows.get(local);
        if (row == null) {
            return;
        }
        row.inputs = inputs;
        row.lit = lit;
    }

    // ----------------------------------------------------------------- 落地 --

    /**
     * 一台机器落地时每一支落在哪: 世界坐标 → 那一行.
     *
     * <p>纯函数 (一个把机器内坐标换算成世界坐标的 lambda), 于是这条换算能脱层测: 没有位姿可问时落回它离开世界时的那一
     * 格, 有位姿时落在投影算出的那一格 —— 被搬走两格的火把不该回到它被拆下来的地方。
     */
    public static Map<BlockPos, Row> landingPlan(CarrierId carrier, @Nullable CarriedHostIndex.Landing landing) {
        Map<BlockPos, Row> out = new LinkedHashMap<>();
        Map<BlockPos, Row> rows = carrier == null ? null : BY_CARRIER.get(carrier);
        if (rows == null) {
            return out;
        }
        for (Map.Entry<BlockPos, Row> entry : rows.entrySet()) {
            BlockPos at = landing == null ? entry.getValue().origin() : landing.landingFor(entry.getKey());
            if (at != null) {
                out.put(at, entry.getValue());
            }
        }
        return out;
    }

    /**
     * <b>机器落地: 把频道行写回新位置.</b>
     *
     * <p>搬运期间世界 store 里没有这一行 (它属于搬走的那个坐标), 于是落地这一句就是这一支重新进入世界 store 的唯一时刻:
     * 从这一刻起它照旧随存档走, 组 pass 按世界坐标找到它, 客户端也把它画在世界段里而不是搬运段里。
     *
     * <p>顺序不能反: 行先写、方块状态后写。机器被拆下来时方块通常还没放回世界 (先有移除回调, 后有方块), 而
     * {@code SyncTorchBlock#onPlace} 看到行就不改号码 —— 于是写在前面的这一行才是它落地之后用的号码。
     *
     * @return how many torches were handed back
     */
    public static int handBack(ServerLevel level, CarrierId carrier, @Nullable CarriedHostIndex.Landing landing) {
        if (level == null || carrier == null) {
            return 0;
        }
        Map<BlockPos, Row> plan = landingPlan(carrier, landing);
        forgetCarrier(carrier);
        if (plan.isEmpty()) {
            return 0;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        for (Map.Entry<BlockPos, Row> entry : plan.entrySet()) {
            BlockPos at = entry.getKey();
            Row row = entry.getValue();
            store.putSyncTorch(at, row.channel());
            // 这一刻的读数也跟着回来: 否则落地之后的第一趟 pass 读到一面都没有, 那一支会先灭一下再亮。
            store.putSyncTorchInputs(at, row.inputs());
            // 方块有可能还没放回来, 那时这一句什么也不做, 而它自己的 onPlace 会带着写好的行进来。
            SyncTorchBlock.applyLit(level, at, row.lit());
        }
        HostSync.broadcastSyncGroups(level);
        return plan.size();
    }

    /** 一台机器的全部行走掉了 (落地, 或者这台机器彻底没了)。 */
    public static void forgetCarrier(CarrierId carrier) {
        if (carrier != null) {
            BY_CARRIER.remove(carrier);
        }
    }

    /** 这个维度的一张表都清掉; 调用方是 level 卸载。 */
    public static void clear() {
        LIFTED.clear();
        BY_CARRIER.clear();
    }
}
