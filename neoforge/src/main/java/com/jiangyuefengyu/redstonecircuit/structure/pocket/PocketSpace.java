package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.PlotAllocator;
import com.jiangyuefengyu.redstonecircuit.structure.PocketBudget;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.ServerLevelData;

/**
 * The plots of one level: who owns which cell, and which chunks are held loaded so the blocks inside a
 * structure keep being ticked. Keyed by level rather than by "the pocket", so a test can drive it in the test level.
 *
 * <p>Only the ownership table survives a restart ({@link PocketPlots}); the grid, the budget and the tickets are
 * rebuilt from it, and a plot whose owner block is gone is swept and handed back. A region ticket's level is
 * {@code 33 - distance}, so {@link #TICKING_DISTANCE} = 2 gives 31: entity-ticking, the level {@code /forceload}
 * uses. {@code forceTicks} stays false - only the natural-spawning check reads it.
 */
public final class PocketSpace {

    /** Region-ticket distance: 33 - 2 = level 31 = entity-ticking. Not a number to tune casually. */
    public static final int TICKING_DISTANCE = 2;

    /** Our own ticket type. {@code TicketType.create} is all a mod needs - there is no registry for it. */
    private static final TicketType<Integer> PLOT_TICKET =
            TicketType.create("redstonecircuit:structure_plot", Integer::compareTo);

    private static final Map<ResourceKey<Level>, PocketSpace> SPACES = new HashMap<>();

    private final ServerLevel level;
    private final PlotAllocator allocator = new PlotAllocator();
    private final PocketBudget budget;
    private final Map<Integer, Plot> plots = new HashMap<>();
    /**
     * The plots a materialise is filling right now: a claim happens first and the block learns its index only when the
     * write returns, so inside that window the owner of a plot looks exactly like a block that has gone. Cleared by
     * {@link #claim} and {@link #release} too, so a write that threw cannot leave a plot unclaimable.
     */
    private final Set<Integer> writing = new HashSet<>();
    /** What each plot's activation faces are pushing into it right now; empty for a plot nobody drives. */
    private final Map<Integer, PocketInjection> injections = new HashMap<>();
    private boolean restored;
    @Nullable
    private PocketPlots saved;
    /**
     * When each plot was last worked, by index: the budget is a cache, so it has to know which entries somebody is
     * still using, and a plot nothing has touched for {@link #IDLE_EVICT_TICKS} is one whose owner is not in the world.
     * A growable array rather than a map, because it is written once per tick per structure.
     */
    private long[] lastUse = new long[0];
    /** The same shape, for when a plot's tickets were last asserted again. */
    private long[] lastTicketAt = new long[0];

    /** How often a live plot's tickets are asserted again: one second. */
    private static final int TICKET_CHECK_TICKS = 20;
    /** The game time of the last budget refusal that reached the log; see {@link #REFUSAL_LOG_TICKS}. */
    private long lastRefusalLog = -REFUSAL_LOG_TICKS;
    /** How many plots one claim may take back before it gives up and refuses. */
    private static final int RECLAIM_PER_CLAIM = 4;
    /** How long a plot may go untouched before it is worth more to a new structure than to its owner. */
    private static final long IDLE_EVICT_TICKS = 100L;
    /** How often a budget that cannot be helped says so: a retry loop is not a log. */
    private static final int REFUSAL_LOG_TICKS = 200;

    /**
     * True when any plot anywhere is being driven: the gate in front of the signal overlay, so a world that uses no
     * injection answers one static boolean read and stops. Refreshed where a ledger can change, never on the read path.
     */
    private static boolean anyInjection;

    private PocketSpace(ServerLevel level) {
        this.level = level;
        this.budget = new PocketBudget(RCConfig.structureMaxLoadedColumns(),
                RCConfig.structureMaxLoadedPockets());
    }

    /** A claimed cell: which one, where it starts, and how many chunk columns it holds. */
    public record Plot(int index, BlockPos origin, int size, boolean shell, int columns) {
    }

    /** The outcome of a claim: either a plot, or the reason the budget refused it. */
    public record Plotted(@Nullable Plot plot, String refusal) {

        public boolean allowed() {
            return this.plot != null;
        }
    }

    public static PocketSpace of(ServerLevel level) {
        PocketSpace space = SPACES.computeIfAbsent(level.dimension(), key -> new PocketSpace(level));
        space.restore();
        return space;
    }

    /**
     * The pocket dimension's plots, or {@code null} when this server has no such dimension: a datapack problem
     * (`data/redstonecircuit/dimension/structure_space.json`) rather than a runtime one, which callers must treat as
     * "the feature is unavailable".
     */
    @Nullable
    public static PocketSpace pocket(MinecraftServer server) {
        ServerLevel pocket = PocketDimension.of(server);
        return pocket == null ? null : of(pocket);
    }

    /**
     * Rebuilds the runtime half from the saved table, once per session, so a structure that was running before a
     * restart is running again. A plot that no longer fits the budget, or whose owner block has gone, is released.
     */
    private void restore() {
        if (this.restored) {
            return;
        }
        this.restored = true;
        this.saved = PocketPlots.get(this.level);
        for (Map.Entry<Integer, PocketPlots.Entry> entry : this.saved.entries().entrySet()) {
            int index = entry.getKey();
            PocketPlots.Entry claim = entry.getValue();
            int longest = Math.max(claim.size().getX(), Math.max(claim.size().getY(), claim.size().getZ()));
            PocketBudget.Verdict verdict = this.budget.tryClaim(claim.columns());
            if (!verdict.allowed()) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} no longer fits the budget, "
                            + "clearing it ({})", index, verdict.reason());
                }
                clearBlocks(index, claim.size(), claim.shell());
                this.saved.remove(index);
                continue;
            }
            Plot plot = new Plot(index, originOf(index), longest, claim.shell(), claim.columns());
            this.plots.put(index, plot);
            this.allocator.reserve(index);
            ticket(plot, true);
        }
        sweep();
        if (RCConfig.debugLog() && !this.plots.isEmpty()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: restored {} plot(s) in {}",
                    this.plots.size(), this.level.dimension().location());
        }
    }

    /**
     * Hands back every plot whose owning product block is gone. An owner in an unloaded chunk is left alone: not being
     * able to see a block is not evidence that it is not there, and releasing it would throw away a player's build.
     */
    public List<Integer> sweep() {
        List<Integer> released = new ArrayList<>();
        if (this.saved == null) {
            return released;
        }
        for (Map.Entry<Integer, PocketPlots.Entry> entry : this.saved.entries().entrySet()) {
            if (ownerIsGone(entry.getKey(), entry.getValue())) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} has no owner any more, "
                            + "reclaiming it", entry.getKey());
                }
                clearBlocks(entry.getKey(), entry.getValue().size(), entry.getValue().shell());
                release(entry.getKey());
                released.add(entry.getKey());
            }
        }
        return released;
    }

    private boolean ownerIsGone(int plot, PocketPlots.Entry claim) {
        MinecraftServer server = this.level.getServer();
        ServerLevel owner = server == null ? null : server.getLevel(claim.ownerDimension());
        if (owner == null) {
            return true;
        }
        // A block carried out of the world inside a moving structure is not gone but away: the travelling stamp covers
        // the ride, and the lease bounds a machine destroyed mid-flight.
        long now = this.level.getGameTime();
        if (claim.travelling(now, TRAVEL_LEASE_TICKS)) {
            return false;
        }
        if (!owner.isLoaded(claim.ownerPos())) {
            return false;
        }
        return !(owner.getBlockEntity(claim.ownerPos()) instanceof StructureBlockEntity product)
                || product.plotIndex() != plot;
    }

    /**
     * Whether this plot is out of the world with its block right now, for the paths that would otherwise give it back.
     * A player breaking the block must free the plot and a carrier taking it must not; the travelling stamp says which.
     */
    public static boolean awayFromHome(ServerLevel owner, int plot) {
        ServerLevel pocket = PocketDimension.of(owner.getServer());
        if (pocket == null) {
            return false;
        }
        PocketPlots.Entry claim = PocketPlots.get(pocket).entry(plot);
        return claim != null && claim.travelling(pocket.getGameTime(), TRAVEL_LEASE_TICKS);
    }

    /**
     * A product block has left the world inside a moving structure: its plot is on a lease until the block lands and
     * ticks again. A position whose plot is not claimed simply has nothing to protect.
     */
    public boolean noteTravelling(ResourceKey<Level> ownerDimension, BlockPos ownerPos, long gameTime) {
        PocketPlots plots = savedPlots();
        for (Map.Entry<Integer, PocketPlots.Entry> entry : plots.entries().entrySet()) {
            PocketPlots.Entry claim = entry.getValue();
            if (claim.ownerDimension().equals(ownerDimension) && claim.ownerPos().equals(ownerPos)
                    && !claim.travelling(gameTime, TRAVEL_LEASE_TICKS)) {
                plots.put(entry.getKey(), claim.travelling(gameTime));
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} is travelling with the block that "
                            + "left {} at {}", entry.getKey(), ownerDimension.location(),
                            ownerPos.toShortString());
                }
                return true;
            }
        }
        return false;
    }

    /**
     * Moves a plot's owner record to the block standing here and clears any travelling lease. The record is only taken
     * over when its old owner is genuinely gone ({@link #ownerIsGone}), so a copy of a product block cannot steal a plot.
     */
    public boolean reown(int plot, ResourceKey<Level> ownerDimension, BlockPos ownerPos, @Nullable UUID structureId) {
        PocketPlots plots = savedPlots();
        PocketPlots.Entry claim = plots.entry(plot);
        if (claim == null) {
            // No such plot: a copied block, or one whose plot was reclaimed while it was away.
            return false;
        }
        if (claim.ownerDimension().equals(ownerDimension) && claim.ownerPos().equals(ownerPos)) {
            // Where the table already says it is, which is every product block on every tick: two comparisons, no allocation.
            //
            // A live plot re-asserts that its chunk is still ticking: the record outlives the region ticket, and only
            // silence reports it (see assertTickets).
            assertTickets(plot);
            if (claim.travellingSince() != 0) {
                plots.put(plot, claim.standingAt(ownerDimension, ownerPos));
                return true;
            }
            return false;
        }
        if (claim.travellingSince() != 0) {
            // The plot is away with a block unseen since, so a block ticking here with this index is it coming home: a
            // plot has exactly one owner at a time.
            plots.put(plot, claim.standingAt(ownerDimension, ownerPos));
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} came home to {} in {}", plot,
                        ownerPos.toShortString(), ownerDimension.location());
            }
            return true;
        }
        // Elsewhere and not travelling: either the old owner is gone, or this block is the same structure as the
        // recorded one: a stale record of where it stood before.
        if (!sameStructureAt(claim, structureId) && !ownerIsGone(plot, claim)) {
            return false;
        }
        plots.put(plot, claim.standingAt(ownerDimension, ownerPos));
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} changed hands from {} in {} to {} in {} "
                            + "(the block moved)", plot, claim.ownerPos().toShortString(),
                    claim.ownerDimension().location(), ownerPos.toShortString(), ownerDimension.location());
        }
        return true;
    }

    /**
     * Whether the block where a plot record points is the same structure the caller is: a product block carries the
     * structure's UUID, so a matching UUID is this block's memory of where it stood, not a second owner.
     */
    private boolean sameStructureAt(PocketPlots.Entry claim, @Nullable UUID structureId) {
        if (structureId == null) {
            return false;
        }
        MinecraftServer server = this.level.getServer();
        ServerLevel owner = server == null ? null : server.getLevel(claim.ownerDimension());
        if (owner == null || !owner.isLoaded(claim.ownerPos())) {
            return false;
        }
        return owner.getBlockEntity(claim.ownerPos()) instanceof StructureBlockEntity product
                && structureId.equals(product.structureId());
    }

    /** How long a plot whose block is inside a moving structure is held before the reclaim gives up: five in-game days. */
    public static final long TRAVEL_LEASE_TICKS = 5L * 24000L;

    /**
     * Claims a cell for a structure of this size and starts ticking it. The size does not pick a cell; it decides how
     * many chunk columns the claim costs, and therefore whether the budget allows it at all.
     */
    public Plotted claim(int size, boolean shell, ResourceKey<Level> ownerDimension, BlockPos ownerPos) {
        int columns = PlotAllocator.columnsNeeded(size, shell);
        PocketBudget.Verdict verdict = this.budget.tryClaim(columns);
        // A full budget is answered with room, not with a refusal: a pocket whose owner is not in the world pays
        // tickets for nobody, so the plot worth least is handed back (see {@link #pickReclaimable}) and the claim tried
        // again. The block asks for another plot on its own next tick, which is what makes handing it back safe.
        for (int attempt = 0; !verdict.allowed() && attempt < RECLAIM_PER_CLAIM; attempt++) {
            if (!this.handBackOnePlot()) {
                break;
            }
            verdict = this.budget.tryClaim(columns);
        }
        if (!verdict.allowed()) {
            // A refused block retries once a second (StructureBlockEntity#repairPlotIfDue), so this line is throttled:
            // the reason is worth knowing, the retry is not.
            long now = this.level.getGameTime();
            if (RCConfig.debugLog() && now - this.lastRefusalLog >= REFUSAL_LOG_TICKS) {
                this.lastRefusalLog = now;
                RCConfig.LOGGER.info("[redstonecircuit] pocket: refused a {}³ structure in {} - nothing could be "
                                + "handed back either: {}", size, this.level.dimension().location(),
                        verdict.reason());
            }
            return new Plotted(null, verdict.reason());
        }
        int index = this.allocator.allocate();
        Plot plot = new Plot(index, originOf(index), size, shell, columns);
        this.plots.put(index, plot);
        // A plot just claimed is the newest here, not the idlest: `lastUse` starts at zero, so without this a fresh
        // plot could be handed straight back to the next claim.
        touch(index);
        // A fresh claim of this index starts a new write window, so a marker left by a throw cannot make it
        // unreclaimable - see #beginWriting.
        this.writing.remove(index);
        savedPlots().put(index, new PocketPlots.Entry(ownerDimension, ownerPos, new Vec3i(size, size, size),
                shell, columns));
        ticket(plot, true);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} at {} for a {}³ structure, {} column(s), "
                            + "{} pocket(s) and {}/{} column(s) in use",
                    index, plot.origin().toShortString(), size, columns, this.budget.usedPockets(),
                    this.budget.usedColumns(), this.budget.maxColumns());
        }
        return new Plotted(plot, "");
    }

    /**
     * Whether this index is still a plot of this pocket, asked once a tick by every product block holding one. An index
     * can outlive its plot - {@link #release} hands plots back under blocks that may be in an unloaded chunk - and such
     * a block would otherwise never ask for a plot again. Both the runtime grid and the saved table are checked.
     */
    public boolean hasPlot(int index) {
        return index >= 0 && (this.plots.containsKey(index) || savedPlots().entry(index) != null);
    }

    /**
     * Hands one plot back so that a new structure can have it: the least useful one there is. Public so a test can empty
     * a pocket without filling the budget first; {@link #claim} calls it in a loop.
     */
    public boolean handBackOnePlot() {
        // A plot whose owner is itself inside the pocket is asked for last: that owner is a machine a player built, and
        // taking its plot would wipe those blocks (clearBlocks) on the outer structure's own churn.
        Integer victim = this.pickReclaimable(false);
        if (victim == null) {
            victim = this.pickReclaimable(true);
        }
        if (victim == null) {
            return false;
        }
        PocketPlots.Entry claim = savedPlots().entry(victim);
        if (claim != null) {
            clearBlocks(victim, claim.size(), claim.shell());
        }
        RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} was handed back to make room for a new structure "
                        + "({})", victim, claim == null ? "no record left behind it"
                        : claim.travellingSince() != 0
                                ? "its block was stamped travelling at " + claim.travellingSince()
                                : "nothing answers for it at " + claim.ownerPos().toShortString());
        return release(victim);
    }

    /**
     * Says that a materialise has started filling one plot: the window is exactly the write, and a nested structure
     * asking for a plot inside it finds this one marked and takes something else (see {@link #pickReclaimable}).
     * Answered by {@link #endWriting} before the write returns.
     */
    public void beginWriting(int index) {
        this.writing.add(index);
    }

    /** The other half of {@link #beginWriting}: the write is over, so the plot is ordinary again. */
    public void endWriting(int index) {
        this.writing.remove(index);
    }

    /**
     * Which plot is worth less to its owner than to a structure waiting for one, most abandoned first: a record whose
     * position holds no block claiming it, then the oldest travelling stamp, then an unloaded owner, then the plot that
     * has gone untouched longest ({@link #IDLE_EVICT_TICKS}). What is given up is never the blocks; the block that lost
     * its plot is told (see {@link #forgetOwner}) and asks for a new one on its next tick.
     */
    @Nullable
    private Integer pickReclaimable(boolean nestedOwnersAllowed) {
        MinecraftServer server = this.level.getServer();
        long now = this.level.getGameTime();
        Integer dead = null;
        Integer travelling = null;
        long oldestStamp = Long.MAX_VALUE;
        Integer unloaded = null;
        Integer idle = null;
        long oldestUse = Long.MAX_VALUE;
        for (Map.Entry<Integer, PocketPlots.Entry> entry : savedPlots().entries().entrySet()) {
            int index = entry.getKey();
            PocketPlots.Entry claim = entry.getValue();
            // An owner standing in this very level is a structure nested inside another one, and is the last thing worth
            // taking - see handBackOnePlot.
            if (!nestedOwnersAllowed && claim.ownerDimension().equals(this.level.dimension())) {
                continue;
            }
            if (claim.travellingSince() != 0) {
                if (claim.travellingSince() < oldestStamp) {
                    oldestStamp = claim.travellingSince();
                    travelling = index;
                }
                continue;
            }
            ServerLevel owner = server == null ? null : server.getLevel(claim.ownerDimension());
            if (owner == null || !owner.isLoaded(claim.ownerPos())) {
                if (unloaded == null) {
                    unloaded = index;
                }
                continue;
            }
            long used = index < this.lastUse.length ? this.lastUse[index] : 0L;
            if (!(owner.getBlockEntity(claim.ownerPos()) instanceof StructureBlockEntity product)
                    || product.plotIndex() != index) {
                // The window is the write itself, not an idle one: a materialise claims its plot and the block it is
                // writing learns the number only when the call returns (repairPlot → materialize → setPlotIndex), so
                // for the whole of it the owner looks like a block that has moved on. `writing` says only that somebody
                // is filling this plot at this instant.
                if (dead == null && !this.writing.contains(index)) {
                    dead = index;
                }
                continue;
            }
            if (now - used > IDLE_EVICT_TICKS && used < oldestUse) {
                oldestUse = used;
                idle = index;
            }
        }
        if (dead != null) {
            return dead;
        }
        if (travelling != null) {
            return travelling;
        }
        if (unloaded != null) {
            return unloaded;
        }
        return idle;
    }

    /** Stops ticking a plot, clears what it held and gives its cell and its budget back. */
    public boolean release(int index) {
        Plot plot = this.plots.remove(index);
        PocketPlots.Entry claim = savedPlots().remove(index);
        this.injections.remove(index);
        // A released plot is nobody's, so it is nobody's unfinished write either.
        this.writing.remove(index);
        refreshInjectionGate();
        if (plot == null && claim == null) {
            return false;
        }
        // The block that owned it is told: a block still claiming a plot that is somebody else's passes every check a
        // press makes, never asks for a plot again (the plotIndex < 0 branch) and injects into another structure's cells.
        forgetOwner(claim, index);
        if (plot != null) {
            ticket(plot, false);
            this.budget.release(plot.columns());
        } else {
            // Restored from a table this session never put into the grid (a budget refusal): its tickets were never added.
            this.budget.release(claim.columns());
        }
        this.allocator.free(index);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: released plot {} in {}, {} pocket(s) and "
                            + "{}/{} column(s) still in use",
                    index, this.level.dimension().location(), this.budget.usedPockets(),
                    this.budget.usedColumns(), this.budget.maxColumns());
        }
        return true;
    }

    /**
     * The block a released plot was recorded against forgets it, so it asks for another plot instead of aiming every
     * press at a cell that may belong to another structure. Skipped when the owner is not there to tell.
     */
    private void forgetOwner(@Nullable PocketPlots.Entry claim, int index) {
        if (claim == null) {
            return;
        }
        MinecraftServer server = this.level.getServer();
        ServerLevel owner = server == null ? null : server.getLevel(claim.ownerDimension());
        if (owner == null || !owner.isLoaded(claim.ownerPos())) {
            return;
        }
        if (owner.getBlockEntity(claim.ownerPos()) instanceof StructureBlockEntity product
                && product.plotIndex() == index) {
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} lost plot {}, so it is told "
                                + "(it will ask for another one on its next tick)", claim.ownerPos().toShortString(),
                        index);
            }
            product.setPlotIndex(-1);
        }
    }

    /** Empties the region a plot used - the blocks themselves and the slot data that belongs to them. */
    public void clearBlocks(int index, Vec3i size, boolean shell) {
        int longest = Math.max(size.getX(), Math.max(size.getY(), size.getZ()));
        int span = longest + (shell ? 2 : 0);
        BlockPos origin = originOf(index);
        if (shell) {
            origin = origin.offset(-1, -1, -1);
        }
        InnerRedstoneStore store = InnerRedstoneStore.get(this.level);
        for (int y = 0; y < span; y++) {
            for (int z = 0; z < span; z++) {
                for (int x = 0; x < span; x++) {
                    BlockPos pos = origin.offset(x, y, z);
                    if (!this.level.getBlockState(pos).isAir()) {
                        this.level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
                    }
                    if (store.get(pos) != null) {
                        store.remove(pos);
                    }
                }
            }
        }
        store.markDirty();
    }

    private PocketPlots savedPlots() {
        if (this.saved == null) {
            this.saved = PocketPlots.get(this.level);
        }
        return this.saved;
    }

    /**
     * Adds or removes the region tickets for a plot's chunk columns. Public because it is the whole of "make this
     * region tick" and the GameTest harness cannot host a datapack dimension at all.
     */
    public void ticket(int key, BlockPos origin, int columns, boolean add) {
        ticket(new Plot(key, origin, 0, false, columns), add);
    }

    private void ticket(Plot plot, boolean add) {
        int side = (int) Math.sqrt(plot.columns());
        ChunkPos base = new ChunkPos(Math.floorDiv(plot.origin().getX(), PlotAllocator.CHUNK),
                Math.floorDiv(plot.origin().getZ(), PlotAllocator.CHUNK));
        for (int dx = 0; dx < side; dx++) {
            for (int dz = 0; dz < side; dz++) {
                ChunkPos pos = new ChunkPos(base.x + dx, base.z + dz);
                if (add) {
                    this.level.getChunkSource().addRegionTicket(PLOT_TICKET, pos, TICKING_DISTANCE,
                            plot.index());
                } else {
                    this.level.getChunkSource().removeRegionTicket(PLOT_TICKET, pos, TICKING_DISTANCE,
                            plot.index());
                }
            }
        }
    }

    /**
     * Makes this level's time and weather agree with {@code source}: a captured machine has to behave the way it did
     * where it stood, and a weather detector or a daylight-dependent block reads both. Copied rather than asked for, so
     * an unwatched pocket costs two comparisons per tick.
     */
    public void syncEnvironmentFrom(ServerLevel source) {
        if (this.level.getDayTime() != source.getDayTime()) {
            this.level.setDayTime(source.getDayTime());
        }
        if (!(source.getLevelData() instanceof ServerLevelData from)
                || !(this.level.getLevelData() instanceof ServerLevelData to)) {
            return;
        }
        boolean same = from.isRaining() == to.isRaining()
                && from.isThundering() == to.isThundering()
                && from.getRainTime() == to.getRainTime();
        if (same) {
            return;
        }
        this.level.setWeatherParameters(from.getClearWeatherTime(), from.getRainTime(),
                from.isRaining(), from.isThundering());
        to.setThunderTime(from.getThunderTime());
    }

    /** Where a cell starts in this level. */
    public static BlockPos originOf(int index) {
        return new BlockPos(PlotAllocator.originX(index), PocketDimension.PLOT_BASE_Y,
                PlotAllocator.originZ(index));
    }

    /**
     * The injection ledger of a plot, or {@code null} when that plot is not live: a ledger without a plot would be power
     * pushed into a cell nobody owns.
     */
    @Nullable
    public PocketInjection injection(int index) {
        if (!this.plots.containsKey(index)) {
            return null;
        }
        // Every tick of every owner comes through here - the standing tick and the carried one - so this is where
        // "somebody is still working this plot" is recorded (see #pickReclaimable).
        this.touch(index);
        return this.injections.computeIfAbsent(index, key -> new PocketInjection());
    }

    /**
     * A plot that is being worked keeps its chunk ticking, once a second. What a block in a plot needs to be ticked at
     * all is the region ticket the plot was claimed with, and nothing may assume that ticket is still there: the saved
     * record outlives it and the only thing that notices is silence. Adding a ticket that is already there is a no-op.
     */
    private void assertTickets(int index) {
        Plot plot = this.plots.get(index);
        if (plot == null) {
            return;
        }
        long now = this.level.getGameTime();
        long last = index < this.lastTicketAt.length ? this.lastTicketAt[index] : 0L;
        if (now - last < TICKET_CHECK_TICKS) {
            return;
        }
        if (index >= this.lastTicketAt.length) {
            this.lastTicketAt = Arrays.copyOf(this.lastTicketAt,
                    Math.max(index + 1, this.lastTicketAt.length * 2 + 8));
        }
        this.lastTicketAt[index] = now;
        if (this.level.shouldTickBlocksAt(plot.origin())) {
            return;
        }
        PocketPlots.Entry claim = savedPlots().entry(index);
        RCConfig.LOGGER.info("[redstonecircuit] pocket: plot {} at {} was NOT ticking in {}, so its tickets are put "
                        + "back (its record says travelling={})", index, plot.origin().toShortString(),
                this.level.dimension().location(), claim != null && claim.travellingSince() != 0);
        ticket(plot, true);
    }

    /**
     * Records that plot {@code index} was worked at this instant. Growable, never boxed - see {@link #lastUse}.
     *
     * <p>Public because the reclaim order reads it as "somebody is using this": a plot nothing has touched for
     * {@link #IDLE_EVICT_TICKS} is handed back to make room for a new structure, which is right for a plot whose owner
     * block has gone and wrong for one a holder is still standing on. A holder that is neither writing
     * ({@link #beginWriting}) nor having signal injected into it says so with this.
     */
    public void touch(int index) {
        if (index < 0) {
            return;
        }
        if (index >= this.lastUse.length) {
            this.lastUse = Arrays.copyOf(this.lastUse, Math.max(index + 1, this.lastUse.length * 2 + 8));
        }
        this.lastUse[index] = this.level.getGameTime();
    }

    /**
     * The activation-face step: applies a structure's six-face table and the strength each face is receiving now
     * ({@code faceSignals} indexed by {@link Direction#ordinal()}), and notifies every cell whose injected power changed.
     */
    public int applyInjection(int index, Map<Direction, List<ActivationTable.FaceEntry>> faces,
                              int[] faceSignals) {
        return applyInjection(index, PocketInjection.Sources.faces(faces, faceSignals));
    }

    /**
     * The same, for everything that can push into a plot at once: the faces and the manual entries. One method for both
     * modes, because a press does not depend on whether a face or a player made it.
     */
    public int applyInjection(int index, PocketInjection.Sources sources) {
        Plot plot = this.plots.get(index);
        PocketInjection ledger = injection(index);
        if (plot == null || ledger == null) {
            return 0;
        }
        List<BlockPos> changed = ledger.update(sources, this.level.getGameTime(),
                RCConfig.structureInjectionLimitTicks());
        if (changed.isEmpty()) {
            return 0;
        }
        // The gate must be up to date before the notifications, not after: dust recomputing inside this very loop asks
        // the signal question while the gate is being read, so refreshing afterwards leaves the first cell dark.
        refreshInjectionGate();
        for (BlockPos local : changed) {
            BlockPos cell = plot.origin().offset(local);
            net.minecraft.world.level.block.Block block = this.level.getBlockState(cell).getBlock();
            this.level.updateNeighborsAt(cell, block);
            // And the cell itself: an injected dust cell is pinned to the filled strength by
            // {@code RedStoneWireBlockMixin}, so its own state can be stale when the injection goes away, and
            // `updateNeighborsAt` only tells the six neighbours.
            this.level.neighborChanged(cell, block, cell);
        }
        return changed.size();
    }

    /**
     * The strength a position is being injected with, 0 when nothing is pushing into it. Answered only for the plot's
     * own cells: a coordinate outside the structure can never appear in a face table, so it is not read from a neighbour.
     */
    private int injectedAt(BlockPos pos) {
        int index = PlotAllocator.indexAt(pos.getX(), pos.getZ());
        if (index < 0) {
            return 0;
        }
        PocketInjection ledger = this.injections.get(index);
        Plot plot = this.plots.get(index);
        if (ledger == null || plot == null) {
            return 0;
        }
        int localX = pos.getX() - plot.origin().getX();
        int localY = pos.getY() - plot.origin().getY();
        int localZ = pos.getZ() - plot.origin().getZ();
        if (localX < 0 || localY < 0 || localZ < 0
                || localX >= plot.size() || localY >= plot.size() || localZ >= plot.size()) {
            return 0;
        }
        return ledger.strengthAt(localX, localY, localZ);
    }

    /**
     * How much a position inside a plot is being pushed with, asked by the vanilla signal query itself. Free when no
     * plot anywhere is driven ({@link #anyInjection}), and it never creates a space for a level that has none.
     */
    public static int injectedStrength(ServerLevel level, BlockPos pos) {
        if (!anyInjection) {
            return 0;
        }
        PocketSpace space = SPACES.get(level.dimension());
        return space == null ? 0 : space.injectedAt(pos);
    }

    /** Recomputes {@link #anyInjection}. Called where a ledger can change, never from a signal query. */
    private static void refreshInjectionGate() {
        for (PocketSpace space : SPACES.values()) {
            for (PocketInjection ledger : space.injections.values()) {
                if (!ledger.isEmpty()) {
                    anyInjection = true;
                    return;
                }
            }
        }
        anyInjection = false;
    }

    public ServerLevel level() {
        return this.level;
    }

    public PlotAllocator allocator() {
        return this.allocator;
    }

    public PocketBudget budget() {
        return this.budget;
    }

    /** The live plots, by cell index. */
    public Map<Integer, Plot> plots() {
        return Map.copyOf(this.plots);
    }

    /**
     * Forgets every level's plots, as a restart does: only the in-memory half, the saved table stays on the level.
     * The injection ledgers go with it, and the product blocks re-read them on their next tick.
     */
    public static void forgetAll() {
        SPACES.clear();
        anyInjection = false;
    }

    /** For tests and diagnostics: a stable view of what the budget thinks, lowest cell first. */
    public List<Integer> livePlots() {
        List<Integer> indices = new ArrayList<>(this.plots.keySet());
        indices.sort(Comparator.naturalOrder());
        return indices;
    }
}
