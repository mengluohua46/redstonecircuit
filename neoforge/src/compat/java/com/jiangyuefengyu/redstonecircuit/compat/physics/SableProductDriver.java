package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.SubLevelSpace;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.EmbeddedPlotLevelAccessor;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The tick a placed structure does not get once a Sable sub-level is carrying it.
 *
 * <p>A sub-level's own blocks are real blocks of the level at their plot coordinates, but they live in the
 * sub-level's <b>plot</b>, which Sable holds through plot chunk holders and an <em>embedded</em> level
 * accessor rather than through the level's own ticking chunk map - so nothing ticks them. Two routes are
 * walked: the plot's loaded chunks and their block entity maps first (cheap, and correct when they do hold
 * the entities), then the plot's own local box through the embedded accessor, one bounded slice per pass
 * with a cursor per structure - a carried product's place inside its plot does not move, so meeting every
 * block within a second or two is soon enough.
 *
 * <p>What runs is the block's <em>own</em> tick at the coordinate it really stands at, so the indicator,
 * the watchdog, the rate-limited injection and the re-owning of the plot all happen exactly as they do on
 * the ground. Only the frame matters differently: the six faces are read in the frame the structure is
 * <em>drawn</em> in, and the live view's audience is the players near that drawn position rather than the
 * players near the plot coordinate, whose distance is nobody's.
 *
 * <p>Gated four times, cheapest first: Sable installed, the server has a plot at all, a budget of
 * {@link #MAX_PRODUCTS} blocks per pass, and {@link #MAX_CELLS} positions per structure per pass. A
 * failure is logged and swallowed: this runs inside the level tick, where an exception would stop the
 * server.
 */
final class SableProductDriver {

    /** The most product blocks one pass may tick. */
    private static final int MAX_PRODUCTS = 64;

    /** The most positions one pass may look at <b>per structure</b>, so a big plot cannot make a tick unbounded. */
    private static final int MAX_CELLS = 4096;

    /** Where each structure's box walk got to, so the whole box is covered over several passes. */
    private static final Map<UUID, Integer> CURSORS = new HashMap<>();

    /** What the last line said about each structure, so a line is written when the picture changes - not per tick. */
    private static final Map<UUID, String> REPORTED = new HashMap<>();

    /**
     * The sub-levels this driver has really ticked a product in, kept apart from what the walk
     * <em>found</em>: "a sub-level was found" and "a product inside it was ticked" are two different facts.
     * One line per sub-level, with the sub-level's UUID in full, because its whole purpose is to be matched
     * by eye against the carrier side's line.
     */
    private static final java.util.Set<UUID> TICKED = new java.util.HashSet<>();

    /**
     * The product blocks found inside a plot that had already lost their plot: a silent walk cannot tell
     * "the plot holds no product" from "the plot holds one the filter threw away". Its plot is given back
     * before the block is carried, so one line per position, capped, settles which one it was.
     */
    private static final java.util.Set<String> UNPLOTTED = new java.util.HashSet<>();

    /** How many positions the diagnostic above may name in one session. */
    private static final int MAX_UNPLOTTED = 16;

    /** The most plot cells one pass may look at, looking for the blocks that answer a signal query. */
    private static final int MAX_SOURCE_CELLS = 4096;

    /** Where each sub-level's source walk got to, so a big machine is covered over several passes. */
    private static final Map<UUID, Integer> SOURCE_CURSORS = new HashMap<>();

    /**
     * The positions the world has been told about, per sub-level, with the pose they were told under.
     *
     * <p>The pose says "this machine moved" immediately and for free: while the pose is the one the world was
     * told under, every told position is still the right one and nothing has to be re-walked; the moment it
     * changes, every position that pose produced is wrong and the world is woken at each of them - in the
     * same tick, before the new ones are found.
     */
    private static final Map<UUID, Told> TOLD = new HashMap<>();

    /** Everything the world was woken at while one sub-level held one pose. */
    private static final class Told {
        private String pose;
        private final java.util.Set<Long> spots = new java.util.HashSet<>();
    }

    private SableProductDriver() {
    }

    /** One pass over every live sub-level of {@code level}, ticking the placed structures inside them. */
    static void tick(ServerLevel level, ServerLevel pocket) {
        if (level == null || pocket == null) {
            return;
        }
        // A per-structure report cannot describe the state where the pass never reached a structure at all, so
        // each gate says so once, by name, and the next reader learns which gate stopped it rather than "nothing
        // happened".
        if (!PhysicsCompat.sableLoaded()) {
            reportGate("sable is not installed");
            return;
        }
        if (PocketSpace.of(pocket).plots().isEmpty()) {
            reportGate("the server holds no pocket plot at all");
            return;
        }
        try {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                reportGate("no sub-level container for " + level.dimension().location());
                return;
            }
            // The bodies of freshly assembled structures are read here rather than where they were made: a plot's
            // bounds and a structure's mass are written after the assembly returns, so this pass is the first place
            // they can be believed. Asked before the gates below, so a structure that is already gone is judged too.
            SableAssemblyWatch.resolvePending(container, level);
            if (!container.getAllSubLevels().iterator().hasNext()) {
                reportGate("the container of " + level.dimension().location()
                        + " holds no live sub-level (it was found and asked)");
                return;
            }
            long now = level.getGameTime();
            int budget = MAX_PRODUCTS;
            reviewTold(level, container);
            for (SubLevel subLevel : container.getAllSubLevels()) {
                if (budget <= 0) {
                    return;
                }
                if (subLevel == null || subLevel.isRemoved()) {
                    continue;
                }
                LevelPlot plot = subLevel.getPlot();
                if (plot == null) {
                    continue;
                }
                int chunks = 0;
                int inChunks = 0;
                for (PlotChunkHolder holder : plot.getLoadedChunks()) {
                    LevelChunk chunk = holder == null ? null : holder.getChunk();
                    if (chunk == null) {
                        continue;
                    }
                    chunks++;
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        if (blockEntity instanceof StructureBlockEntity product) {
                            if (product.plotIndex() < 0) {
                                noteUnplotted(subLevel.getUniqueId(), product.getBlockPos());
                                continue;
                            }
                            inChunks++;
                            budget -= tick(level, product, now, subLevel.getUniqueId());
                        }
                    }
                }
                int walked = 0;
                int inBox = 0;
                if (budget > 0) {
                    tellDrawnSources(level, subLevel, plot, now);
                    SubLevelSpace space = SableCarrierClaim.describe(subLevel);
                    BoundingBox3ic box = plot.getBoundingBox();
                    if (space.hasPose() && box != null) {
                        EmbeddedPlotLevelAccessor accessor = plot.getEmbeddedLevelAccessor();
                        int[] walkedAndFound = walk(accessor, subLevel.getUniqueId(), box, level, now, budget);
                        walked = walkedAndFound[0];
                        inBox = walkedAndFound[1];
                        budget -= walkedAndFound[2];
                    }
                }
                report(subLevel.getUniqueId(), "subLevel=" + subLevel.getUniqueId().toString().substring(0, 8)
                        + " chunks=" + chunks + " chunkEntities=" + inChunks + " checked=" + walked
                        + " boxFound=" + inBox);
            }
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] sable product tick failed: {}", failed.toString());
        }
    }

    /**
     * What the block a carrier DRAWS at a world position answers a signal query with.
     *
     * <p>A sub-level keeps its blocks in a reserved plot and draws them where its pose puts them, so the world
     * position the player is looking at holds air and no source answers there at all - while the block that is
     * really drawn there (a redstone block, a torch, a repeater, or another structure's product) has been standing
     * in the plot the whole time. This is the query-time half: turn the world position back into each live
     * sub-level's own coordinates through Sable's own inverse, and ask the block that is there, in the direction
     * the asker is in once the pose has turned it into that structure's frame.
     *
     * <p>Asked only when every other source answered nothing, and bounded by the live sub-levels: a handful of
     * comparisons and one block lookup each, with nothing to keep in step and nothing that can go stale - the
     * block that is asked is the block itself.
     *
     * @param worldDirection vanilla's direction: the way the signal travels from the asker to the queried position,
     *     which is exactly how the block in the plot must be asked once the pose has turned it
     */
    static int signalAt(ServerLevel level, BlockPos worldPos, net.minecraft.core.Direction worldDirection,
                        boolean strong) {
        if (level == null || worldPos == null || worldDirection == null) {
            return 0;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        int answer = 0;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
                continue;
            }
            SubLevelSpace space = SableCarrierClaim.describe(subLevel);
            if (!space.hasPose() || space.pose() == null || !space.containsWorld(worldPos)) {
                continue;
            }
            BlockPos local = space.localFor(worldPos);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(local);
            if (state.isAir()) {
                continue;
            }
            net.minecraft.core.Direction localDirection =
                    com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections.localDirection(space.pose(),
                            worldDirection);
            if (localDirection == null) {
                continue;
            }
            int emitted = strong ? state.getDirectSignal(level, local, localDirection)
                    : state.getSignal(level, local, localDirection);
            answer = Math.max(answer, emitted);
        }
        return answer;
    }

    /**
     * What the block a carrier DRAWS at a world position answers a comparator with.
     *
     * <p>A comparator's back input is not a redstone signal - vanilla asks the block behind it for its
     * <em>analogue</em> output - and that question is the only way a ship's helm's steering angle can be read at
     * all. It is the same walk as the signal half, and it was missing for the same reason: a sub-level keeps its
     * blocks in a reserved plot and draws them where its pose puts them, so the world position the comparator is
     * looking at holds air and answers 0.
     *
     * <p>Vanilla's analogue question involves no direction, but a helm may implement only the directional one, so
     * the asker's world direction is turned into this sub-level's direction and both are asked, the larger answer
     * winning - the same "additive, never a replacement" rule the world half follows.
     *
     * <p>Asked only when the world block itself answered 0, and bounded by the live sub-levels.
     */
    static int analogAt(ServerLevel level, BlockPos worldPos,
                        net.minecraft.core.Direction worldDirection) {
        if (level == null || worldPos == null) {
            return 0;
        }
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        int answer = 0;
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
                continue;
            }
            SubLevelSpace space = SableCarrierClaim.describe(subLevel);
            if (!space.hasPose() || space.pose() == null || !space.containsWorld(worldPos)) {
                continue;
            }
            BlockPos local = space.localFor(worldPos);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(local);
            if (state.isAir()) {
                continue;
            }
            answer = Math.max(answer, state.getAnalogOutputSignal(level, local));
            net.minecraft.core.Direction localDirection = worldDirection == null ? null
                    : com.jiangyuefengyu.redstonecircuit.carrier.CarrierDirections.localDirection(space.pose(),
                            worldDirection);
            answer = Math.max(answer, com.jiangyuefengyu.redstonecircuit.logic.DirectionalAnalog
                    .analogOutputFrom(state, level, local, localDirection));
        }
        return answer;
    }

    /**
     * The world is TOLD where this machine's emitting blocks are drawn.
     *
     * <p>Answering a query correctly is not enough: a vanilla device re-reads its neighbours when something pokes
     * it, and a machine that appears, moves or turns pokes nothing at all. This is the poke - one bounded slice of
     * the plot per pass, and one wake-up per drawn position the first time it is seen. A position that stops being
     * seen is woken once more, so a machine that leaves turns the dust around it off instead of leaving it lit.
     *
     * <p>The only question asked of each cell is vanilla's own "does this block answer a signal query", which covers
     * a redstone block, a torch, a repeater, a lever and any product of ours alike.
     */
    private static void tellDrawnSources(ServerLevel level, SubLevel subLevel, LevelPlot plot, long now) {
        SubLevelSpace space = SableCarrierClaim.describe(subLevel);
        BoundingBox3ic box = plot.getBoundingBox();
        if (!space.hasPose() || space.pose() == null || box == null) {
            return;
        }
        int minX = box.minX();
        int minY = box.minY();
        int minZ = box.minZ();
        int dx = Math.max(1, box.maxX() - minX + 1);
        int dy = Math.max(1, box.maxY() - minY + 1);
        int dz = Math.max(1, box.maxZ() - minZ + 1);
        long volume = (long) dx * dy * dz;
        if (volume <= 0) {
            return;
        }
        UUID uuid = subLevel.getUniqueId();
        int start = SOURCE_CURSORS.getOrDefault(uuid, 0);
        if (start >= volume) {
            start = 0;
        }
        int checked = 0;
        long index = 0;
        for (int x = minX; x < minX + dx; x++) {
            for (int y = minY; y < minY + dy; y++) {
                for (int z = minZ; z < minZ + dz; z++) {
                    if (index++ < start) {
                        continue;
                    }
                    if (checked >= MAX_SOURCE_CELLS) {
                        SOURCE_CURSORS.put(uuid, (int) Math.min(Integer.MAX_VALUE, index - 1));
                        return;
                    }
                    checked++;
                    BlockPos cell = new BlockPos(x, y, z);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(cell);
                    // A machine inside a plot is never told that the world changed next to where it is drawn: its plot
                    // cell's neighbours are the plot's, so a redstone torch put down on the ground beside the drawn
                    // machine never reached a neighbour update at all. The walk that already visits every sub-level
                    // every tick asks it instead, and the machine answers with the same rising edge it would have
                    // answered a neighbour change with.
                    if (state.getBlock()
                            instanceof com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock) {
                        com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock.refreshCarried(level, cell, state);
                        continue;
                    }
                    if (!state.isSignalSource()) {
                        continue;
                    }
                    // The block the world is woken at below, and it has to be the block this machine is DRAWN in:
                    // the renderer and the world-side signal query answer at that block, so waking any other one
                    // leaves the machine supplying signal from a cell nobody is looking at. See
                    // `CarrierProjection#drawnBlockOf` for why a rounded block corner is not that block once the
                    // structure has turned.
                    BlockPos drawn = CarrierProjection.drawnBlockOf(space.pose(), cell);
                    if (drawn == null) {
                        continue;
                    }
                    // A gate's answer is not its own state alone: it depends on its five input faces, and for a
                    // physicalised one those faces include the world outside the structure, which never notifies a plot
                    // cell (a lever in the world wakes the world's six neighbours, and the plot coordinate is not one of
                    // them). The world read is live, so the inputs are right and the cached answer is what goes stale,
                    // so the walk that already visits every signal source asks the block to derive it again.
                    com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock.refreshCarried(level, cell, state);
                    // 传输门 has exactly the same shape of staleness: its answer is a function of five input faces,
                    // each of which may be outside the structure and therefore unable to notify a plot cell.
                    com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock.refreshCarried(level, cell, state);
                    // 控制器 has that same shape: one input face that may be outside the structure, and an answer
                    // cached in the block state.
                    com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.refreshCarried(level, cell, state);
                    // 红石变压器, for the same reason with the same shape: one input face that may be outside the
                    // structure and an answer cached beside the block, so a plate added without being polled here would
                    // go stale only while it is riding a machine.
                    com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.refreshCarried(level, cell, state);
                    // 时钟源, for the reason that is this device's own: its answer comes from the world clock rather
                    // than from anything in the world, so this walk is what turns its edges over while it rides a
                    // machine - a plot's chunks are not the level's ticking chunks, so its own scheduled tick may never
                    // come, and it therefore schedules nothing itself.
                    com.jiangyuefengyu.redstonecircuit.block.ClockBlock.refreshCarried(level, cell, state);
                    // 信号延长器 needs this walk for both of its halves at once: its input face may be outside the
                    // structure (so the answer it is following goes stale), and a hold runs out on the world clock
                    // rather than on anything in the world (so nothing else would ever end it while it rides).
                    com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.refreshCarried(level, cell, state);
                    if (toldFor(uuid).spots.add(drawn.asLong())) {
                        pokeWorld(level, drawn, state);
                    }
                }
            }
        }
        SOURCE_CURSORS.put(uuid, 0);
    }

    /** Wakes the world at one drawn position: vanilla's neighbours and the mod's own solver alike. */
    private static void pokeWorld(ServerLevel level, BlockPos drawn,
                                  net.minecraft.world.level.block.state.BlockState state) {
        level.updateNeighborsAt(drawn, state.getBlock());
        // The QUEUED one as well - that is the notification the solver's world pass listens to, and it is the single
        // line the carrier layer's own world half is made of.
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
        com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
    }

    /**
     * A machine that MOVED is not a machine that expired.
     *
     * <p>Once per level tick, per live sub-level: the pose is compared with the one the world was told under. Same
     * pose - every told position is still the right one and nothing is done at all. Different pose - every position
     * that pose produced is woken (once) and forgotten, in this very tick, and the walk then tells the new ones as it
     * finds them. A sub-level that is gone is woken the same way, so nothing waits for a lease.
     */
    private static void reviewTold(ServerLevel level, SubLevelContainer container) {
        java.util.Set<UUID> live = new java.util.HashSet<>();
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel == null || subLevel.isRemoved() || subLevel.getPlot() == null) {
                continue;
            }
            UUID uuid = subLevel.getUniqueId();
            live.add(uuid);
            SubLevelSpace space = SableCarrierClaim.describe(subLevel);
            String pose = space.hasPose() && space.pose() != null ? space.pose().describe() : null;
            if (pose == null) {
                continue;
            }
            Told told = TOLD.computeIfAbsent(uuid, key -> new Told());
            if (told.pose == null) {
                told.pose = pose;
                continue;
            }
            if (pose.equals(told.pose)) {
                continue;
            }
            wakeTold(level, told);
            told.pose = pose;
        }
        java.util.Iterator<Map.Entry<UUID, Told>> entries = TOLD.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Told> entry = entries.next();
            if (!live.contains(entry.getKey())) {
                wakeTold(level, entry.getValue());
                entries.remove();
            }
        }
    }

    /** Wakes the world at every position one pose produced, and forgets them. */
    private static void wakeTold(ServerLevel level, Told told) {
        for (long spot : told.spots) {
            BlockPos drawn = BlockPos.of(spot);
            // Withdraw the published answer FIRST, or the wake-up below hands the world the answer the machine no
            // longer gives and nothing pokes it again.
            com.jiangyuefengyu.redstonecircuit.logic.PublishedStructureOutput.forget(level.dimension(), drawn);
            level.updateNeighborsAt(drawn, level.getBlockState(drawn).getBlock());
            com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChanged(level, drawn);
            com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);
        }
        told.spots.clear();
    }

    /** The told positions of one sub-level, made on first use. */
    private static Told toldFor(UUID uuid) {
        return TOLD.computeIfAbsent(uuid, key -> new Told());
    }

    /**
     * A product block the filter refused, said once: its plot was given back before the block was carried. Naming
     * the position and the sub-level is the whole value of the line - it tells the next reader whether the plot is
     * still there and the index filter is what hides the block, or the plot's own copy has no index at all.
     */
    private static void noteUnplotted(UUID uuid, BlockPos pos) {
        if (UNPLOTTED.size() >= MAX_UNPLOTTED || !UNPLOTTED.add(uuid + "@" + pos.asLong())) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] sable product driver: a structure stands at {} inside subLevel={} "
                        + "with no plot of its own (plotIndex=-1), so it can neither read nor drive anything",
                pos.toShortString(), uuid);
    }

    /**
     * One bounded slice of a structure's local box, asking the plot's own accessor for the block entity at each
     * position.
     *
     * @return {@code [positionsChecked, productsTicked, productsFoundInThisSlice]} - the last is how many the
     *     walk found, which is what the diagnostics line reports
     */
    private static int[] walk(EmbeddedPlotLevelAccessor accessor, UUID uuid, BoundingBox3ic box,
                              ServerLevel level, long now, int budget) {
        int minX = box.minX();
        int minY = box.minY();
        int minZ = box.minZ();
        int dx = Math.max(1, box.maxX() - minX + 1);
        int dy = Math.max(1, box.maxY() - minY + 1);
        int dz = Math.max(1, box.maxZ() - minZ + 1);
        long volume = (long) dx * dy * dz;
        if (volume <= 0) {
            return new int[] {0, 0, 0};
        }
        int start = CURSORS.getOrDefault(uuid, 0);
        if (start >= volume) {
            start = 0;
        }
        int checked = 0;
        int found = 0;
        int ticked = 0;
        long index = 0;
        for (int x = minX; x < minX + dx; x++) {
            for (int y = minY; y < minY + dy; y++) {
                for (int z = minZ; z < minZ + dz; z++) {
                    if (index++ < start) {
                        continue;
                    }
                    if (checked >= MAX_CELLS) {
                        CURSORS.put(uuid, (int) Math.min(Integer.MAX_VALUE, index - 1));
                        return new int[] {checked, ticked, found};
                    }
                    checked++;
                    BlockEntity blockEntity = accessor.getBlockEntity(new BlockPos(x, y, z));
                    if (blockEntity instanceof StructureBlockEntity product) {
                        if (product.plotIndex() < 0) {
                            noteUnplotted(uuid, product.getBlockPos());
                            continue;
                        }
                        found++;
                        if (budget - ticked > 0) {
                            ticked += tick(level, product, now, uuid);
                        }
                    }
                }
            }
        }
        CURSORS.put(uuid, 0);
        return new int[] {checked, ticked, found};
    }

    /** The block's own tick, unless it already ran in this tick (see {@code StructureBlockEntity#tickedAt}). */
    private static int tick(ServerLevel level, StructureBlockEntity product, long now, UUID subLevel) {
        if (product.tickedAt(now)) {
            return 0;
        }
        if (TICKED.add(subLevel)) {
            RCConfig.LOGGER.info("[redstonecircuit] sable product driver: ticking a placed structure inside "
                            + "subLevel={} local={}", subLevel, product.getBlockPos().toShortString());
        }
        StructureBlockEntity.serverTick(level, product.getBlockPos(), product.getBlockState(), product);
        return 1;
    }

    /** Gates already reported, so each one is named once rather than once per tick. */
    private static final java.util.Set<String> GATES = new java.util.HashSet<>();

    /** Why the pass did not run at all - once per distinct reason, because "nothing happened" is not a diagnosis. */
    private static void reportGate(String why) {
        if (GATES.add(why)) {
            RCConfig.LOGGER.info("[redstonecircuit] sable product driver did NOT run: {}", why);
        }
    }

    /** One line per structure, written when the picture changes - which is what a log can afford every tick. */
    private static void report(UUID uuid, String state) {
        String before = REPORTED.put(uuid, state);
        if (!state.equals(before) && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] sable product driver: {}", state);
        }
    }
}
