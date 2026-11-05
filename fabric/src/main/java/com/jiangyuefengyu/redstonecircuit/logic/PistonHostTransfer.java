package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import com.jiangyuefengyu.redstonecircuit.InnerRedstoneInteraction;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 方块内那一个跟着方块走: a piston carries the component with the block it sits in.
 *
 * <p>The store is keyed by position (see {@link InnerRedstoneStore}), which is what lets redstone sit inside untouched
 * vanilla blocks - and also what left it standing in the cell a moved block had just left. The block is the thing that
 * moved; the component belongs to the block, so it moves too.
 *
 * <p>Called from {@code PistonStructureResolverMixin}, at the moment vanilla's own resolver has decided what moves and
 * before anything does. That is the only moment both facts are true: the store still holds every component at the cell
 * it is leaving, and the positions are vanilla's answer rather than a second guess. It also covers a retraction, which
 * the NeoForge piston event cannot answer (asking its helper resolver mutates the world and answers false for a pull).
 *
 * <p><b>Nothing may touch the world from here.</b> The component's arrival has to notify its neighbours, and a
 * neighbour update reaches the piston - which asks what to move again, and resolves again. Measured at four re-runs for
 * one four-block push; each run shifted every component one more cell, so the blocks nearest the piston ended up holding
 * nothing and the far ones held the wrong component. So this class only moves data, one move per piston per tick, and
 * everything that touches the world waits for {@link #flushDeferred} at the end of that tick.
 *
 * <p>Nothing is destroyed here: a block the piston breaks rather than moves keeps its component where it was, which is
 * the behaviour this mod already had for every kind of destruction.
 *
 * <p>结构里的那一半不在这个 store 里: 一台机器按 (机器, 机器内坐标) 记的东西由 {@code CarrierStore} 一起搬, 而在子层级里
 * 「世界位置变了」与「机器内坐标变了」是同一件事 —— 所以那是同一次调用里的另一句, 不是另一条路。
 */
public final class PistonHostTransfer {

    /** One piston's identity: a piston moves at most once per tick, and two dimensions can hold one position. */
    private record PistonKey(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /**
     * One block's journey, with what it is carrying.
     *
     * <p>Package-private so the message plan below can be tested without a level: what a client is told about a row of
     * moved blocks is a fact about this list alone.
     */
    record Move(BlockPos from, BlockPos to, InnerRedstoneStore.Stored stored) {
    }

    /**
     * How deep the current call is inside {@code PistonBaseBlock#moveBlocks}; zero when vanilla is only asking whether a
     * move is possible. A counter rather than a flag, because a piston pushing another piston nests.
     */
    private static int moveDepth;

    /** Client messages waiting for the end of the tick; one list per dimension. */
    private static final Map<ResourceKey<Level>, List<HostEntry>> DEFERRED_MESSAGES = new HashMap<>();

    /** Positions whose surroundings have to be re-derived, waiting for the end of the tick. */
    private static final Map<ResourceKey<Level>, Set<BlockPos>> DEFERRED_REFRESH = new HashMap<>();

    /**
     * Carries the components of the blocks about to move from where they are to where they are going.
     *
     * @param travel the direction the blocks move in - for both an extension and a retraction, which is why the
     *     resolver's own answer is used instead of the piston's facing
     * @param toPush the blocks vanilla has decided to move, in its order
     */
    public static void blocksAreAboutToMove(Level level, BlockPos pistonPos, Direction travel, List<BlockPos> toPush) {
        if (!(level instanceof ServerLevel serverLevel) || travel == null || toPush == null || toPush.isEmpty()) {
            return;
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        List<BlockPos> sources = List.copyOf(toPush);
        // 结构里那台活塞: 载体按 (载体, 机器内坐标) 记的东西一起搬 —— 与下面那两批同一次调用, 而它排在 isEmpty 之前,
        // 因为结构里的元件与频道根本不在世界 store 里 (认领它们的那一句已经把世界那一行收走了), 于是「这个 level
        // 什么都没存」与「这格里没有东西」不是同一句话。判据只有一处 (这一格属于哪台载体, 由 CarrierStore 问一次),
        // 而没有载体时这里一句都不做。
        CarrierStore.blocksMovedInStructure(serverLevel, sources, travel);
        // A level with nothing stored anywhere has nothing to carry, and that is one boolean: no snapshot for the vast
        // majority of pistons in a world that does not use this mod.
        if (store.isEmpty()) {
            return;
        }

        Set<BlockPos> leaving = new HashSet<>(sources);
        // 世界形态的同步火把的频道也按位置存, 所以它也要跟着方块走 - 与下面那一批元件同一次快照、同一批写入, 而不是
        // 走另一条路。它先做, 因为一个只推着一支同步火把的活塞根本进不到下面那一批里 (那里没有元件可搬)。
        carrySyncTorchChannels(store, sources, leaving, travel, pistonPos);
        List<Move> moves = new ArrayList<>();
        for (BlockPos from : sources) {
            InnerRedstoneStore.Stored stored = store.peek(from);
            if (stored.isEmpty()) {
                continue;
            }
            BlockPos to = from.relative(travel);
            if (!leaving.contains(to) && !store.peek(to).isEmpty()) {
                // Something that is staying put is already stored in the cell the block is about to occupy - a
                // component standing in air, which this mod allows. One of the two would have to be thrown away, so
                // neither is: the block arrives and its component stays behind, as it did before this class existed.
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] the piston at {} moves a block into {} from {}, and that "
                            + "cell keeps a component of its own, so the moved block's component stays at {}",
                            pistonPos.toShortString(), to.toShortString(), from.toShortString(),
                            from.toShortString());
                }
                continue;
            }
            moves.add(new Move(from, to, stored));
        }
        if (moves.isEmpty()) {
            return;
        }

        // Every source is emptied before any destination is written. A row of moved blocks arrives one cell apart, so
        // the nearer block's destination is the further block's origin: filling on the way would put one component on
        // top of another that has not left yet. Two passes, and the order of the list stops mattering.
        for (Move move : moves) {
            store.forgetAll(move.from());
        }
        for (Move move : moves) {
            store.put(move.to(), move.stored());
        }

        ResourceKey<Level> dimension = serverLevel.dimension();
        DEFERRED_MESSAGES.computeIfAbsent(dimension, key -> new ArrayList<>())
                .addAll(clientMessagesFor(moves).values());
        Set<BlockPos> touched = DEFERRED_REFRESH.computeIfAbsent(dimension, key -> new LinkedHashSet<>());
        for (Move move : moves) {
            touched.add(move.from());
            touched.add(move.to());
        }
    }

    /**
     * 一次搬移里, 频道行该怎么动: 旧位置 → 新位置。
     *
     * <p>纯函数, 因为那条规则本身 (推走的那一支必须还在原来那组里) 不需要世界就能钉住; {@code occupied} 由调用方给出,
     * 判据与内嵌元件那一批完全相同: 目标格上还站着一个不动的占用者, 两个就都要留着 - 一个格子里不能有两支火把。
     */
    static Map<BlockPos, Integer> channelMovesFor(Map<BlockPos, Integer> moving, Set<BlockPos> leaving,
                                                  Direction travel, Predicate<BlockPos> occupied) {
        Map<BlockPos, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : moving.entrySet()) {
            BlockPos to = entry.getKey().relative(travel);
            if (!leaving.contains(to) && occupied.test(to)) {
                continue;
            }
            out.put(to, entry.getValue());
        }
        return out;
    }

    /**
     * 把每一支被推动的世界形态同步火把的频道行从旧格搬到新格。
     *
     * <p>两段式 (先把所有旧行读出来、删掉, 再一起写新的), 理由与 {@link #blocksAreAboutToMove} 里对元件说的那句完全
     * 一样: 一排方块里一个格子既是落点又是起点, 边读边写会把一个行写到还没离开的那一行上面。未绑定的那一支 (频道 0)
     * 也搬: 它同样是一行, 而丢掉它等于让新位置的那支火把在组 pass 眼里不存在。
     */
    private static void carrySyncTorchChannels(InnerRedstoneStore store, List<BlockPos> sources,
                                               Set<BlockPos> leaving, Direction travel, BlockPos pistonPos) {
        Map<BlockPos, Integer> moving = new LinkedHashMap<>();
        for (BlockPos from : sources) {
            if (store.hasSyncTorch(from)) {
                moving.put(from, store.syncTorchChannelAt(from));
            }
        }
        if (moving.isEmpty()) {
            return;
        }
        Map<BlockPos, Integer> moves = channelMovesFor(moving, leaving, travel, store::hasSyncTorch);
        for (BlockPos from : moving.keySet()) {
            BlockPos to = from.relative(travel);
            if (!moves.containsKey(to) && !leaving.contains(to) && RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] the piston at {} moves a sync torch into {} from {}, and that "
                        + "cell already holds a sync torch, so the moved torch's channel stays at {}",
                        pistonPos.toShortString(), to.toShortString(), from.toShortString(), from.toShortString());
            }
        }
        for (BlockPos from : moving.keySet()) {
            store.removeSyncTorch(from);
        }
        for (Map.Entry<BlockPos, Integer> move : moves.entrySet()) {
            store.putSyncTorch(move.getKey(), move.getValue());
        }
    }

    /**
     * The half of a component's arrival that cannot run where the move is decided: the clients, and the neighbours.
     *
     * <p>Called once per level tick, before the level's own solver pass, so a component that moved this tick is settled
     * by the same tick's pass.
     */
    public static void flushDeferred(ServerLevel level) {
        ResourceKey<Level> dimension = level.dimension();
        List<HostEntry> messages = DEFERRED_MESSAGES.remove(dimension);
        if (messages != null && !messages.isEmpty()) {
            HostSync.broadcast(level, List.copyOf(messages));
        }
        Set<BlockPos> touched = DEFERRED_REFRESH.remove(dimension);
        if (touched == null) {
            return;
        }
        for (BlockPos pos : touched) {
            refresh(level, pos);
        }
    }

    /** Entered the piston method that carries a move out; see {@code PistonBaseBlockMixin}. */
    public static void enteredMove() {
        moveDepth++;
    }

    /** Left it - including the paths that decided not to move after all. */
    public static void leftMove() {
        if (moveDepth > 0) {
            moveDepth--;
        }
    }

    /** Whether a resolve is being asked as part of a move rather than as a "would this push work". */
    public static boolean insideAMove() {
        return moveDepth > 0;
    }

    /**
     * What the clients have to be told, <b>one message per position</b>.
     *
     * <p>Announcing per move cannot work: in a row of moved blocks one cell is both a destination and an origin, so the
     * departure from it would be sent after the arrival that must survive it, and the client would end up drawing
     * nothing there - every component of a pushed row but the outermost vanished from the goggles while the server kept
     * all of them. A position is announced once, in its final state: the arriving block wins over the departure.
     */
    static Map<BlockPos, HostEntry> clientMessagesFor(List<Move> moves) {
        Map<BlockPos, HostEntry> messages = new LinkedHashMap<>();
        for (Move move : moves) {
            messages.put(move.from(), HostEntry.removed(move.from()));
        }
        for (Move move : moves) {
            Slot slot = move.stored().slot();
            messages.put(move.to(), new HostEntry(move.to(), slot == null ? null : slot.copy()));
        }
        return messages;
    }

    /**
     * Re-derives one position after a component moved into or out of it, and drops everything that answers <em>for a
     * position</em> rather than for a block: an answer, a hold's deadline and the wrench's pick all mean nothing at the
     * other cell.
     */
    private static void refresh(ServerLevel level, BlockPos pos) {
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneInteraction.notifyNeighbours(level, pos);
        BlockAnswerCache.forget(level, pos);
        ExtenderDeadlines.forget(level, pos);
        WrenchSelection.clearIf(level, pos);
    }

    /** Forgets every waiting position, as a freshly started server would. Every test starts here. */
    static void clearForTests() {
        DEFERRED_MESSAGES.clear();
        DEFERRED_REFRESH.clear();
    }
}
