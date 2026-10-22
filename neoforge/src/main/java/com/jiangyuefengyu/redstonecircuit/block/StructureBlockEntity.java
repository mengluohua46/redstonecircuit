package com.jiangyuefengyu.redstonecircuit.block;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.logic.PublishedStructureOutput;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.ManualPulses;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadHeader;
import com.jiangyuefengyu.redstonecircuit.structure.StructureIndicator;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreviewCells;
import com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView;
import com.jiangyuefengyu.redstonecircuit.structure.StructureOutput;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketInjection;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketMaterializer;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构方块实体: carries the <b>identity</b> of a structure, not the structure.
 *
 * <p>The blocks live in this save's own file ({@code <world>/redstonecircuit/structures/<uuid>.nbt}); the block
 * carries only the id and a few bytes about it - name, size, io mode. An id with no file behind it is the
 * cross-save case, which shows a missing placeholder and may not be released, so {@link #structureId} may be
 * null ({@link #isMissing()}).
 */
public class StructureBlockEntity extends BlockEntity {

    @Nullable
    private UUID structureId;
    private String name = PayloadHeader.DEFAULT_NAME;
    private Vec3i size = new Vec3i(1, 1, 1);
    private IoMode ioMode = IoMode.ACTIVATION_FACES;
    private int nestingDepth;
    /** Which pocket plot is holding this structure's real blocks, or -1 when none is. */
    private int plotIndex = -1;
    /** When this block may ask for a plot again. The retry is throttled, and a refusal is reported once, not once per retry. */
    private long nextPlotRepairAt = Long.MIN_VALUE;
    private boolean plotRepairReported;
    /** How often a plot-less product block asks for a plot again - one second. */
    private static final int PLOT_REPAIR_TICKS = 20;
    /** Mode A: which cells each energized face pushes into - the table the structure was placed with. */
    private Map<Direction, List<ActivationTable.FaceEntry>> faces = Map.of();
    /** Mode B: which cell each key pushes into - the panel the structure was placed with. */
    private List<ActivationTable.ManualEntry> manual = List.of();
    /** The output table it was placed with: which cell makes which face answer with how much. */
    private List<ActivationTable.OutputEntry> output = List.of();
    /**
     * The strength each of the six faces answers with right now, worked out once a tick by {@link StructureOutput}.
     *
     * <p>A cache, not state: never saved, and the whole of what a redstone query from outside reads. Its one tick of
     * staleness is the same a redstone lamp's own lit property has, and the world is told whenever it changes.
     */
    private int[] outputSignals = new int[Direction.values().length];

    /**
     * Where this structure was last DRAWN and told about. A carrier can take over a block that is already answering,
     * and then no strength changes at the moment it arrives somewhere new - so the world has to be woken because the
     * position moved, not only because the answer did.
     */
    private long lastDrawnPos = Long.MIN_VALUE;
    /**
     * The manual pulses in flight. Never saved, and it does not need to be: a press is a pulse with an end of its own,
     * so the worst a restart can do is end one early.
     */
    private final ManualPulses pulses = new ManualPulses();
    /**
     * The downsampled picture of the structure, or {@code null} when this block cannot draw itself (the
     * missing-placeholder case).
     *
     * <p>Saved and sent in the update tag, because it is the <b>only</b> thing a client can draw a structure from -
     * the structure itself lives in this save's files, which a client never sees.
     */
    @Nullable
    private StructurePreview preview;
    /**
     * What each face was receiving last time it was read, indexed by {@link Direction#ordinal()}, or
     * {@link #FACE_UNKNOWN} while it has never been read.
     *
     * <p>Not saved, and it starts unknown: after a load, or after the plot is made, the first tick has to read the
     * world and inject whatever is there. Remembering a value across a restart would mean a structure whose face is
     * energized at load time never injects, because "nothing changed".
     */
    private int[] faceSignals = unknownFaceSignals();

    /** "This face has not been read yet", which is not the same as "this face is dark". */
    private static final int FACE_UNKNOWN = -1;

    private static int[] unknownFaceSignals() {
        int[] signals = new int[Direction.values().length];
        Arrays.fill(signals, FACE_UNKNOWN);
        return signals;
    }

    /**
     * The level's game time at which this block's own ticker last ran, or {@link Long#MIN_VALUE}.
     *
     * <p>A physicalised product block is not always ticked by a level - a Sable sub-level's own blocks live in an
     * embedded plot the level does not tick, and a Create contraption's block is not in the level's block grid at all
     * - so the carrier layer runs those blocks' tick itself. This is what keeps the two drivers from running the same
     * block twice in one tick.
     */
    private long selfTickAt = Long.MIN_VALUE;

    /** True when this block's own ticker has already run at {@code gameTime} of the level it stands in. */
    public boolean tickedAt(long gameTime) {
        return this.selfTickAt == gameTime;
    }

    /** The last game time this block's own ticker ran, for a caller asking whether it still does. */
    public long lastSelfTick() {
        return this.selfTickAt;
    }

    /**
     * The product blocks standing in <b>this</b> block's plot, by local coordinate.
     *
     * <p>The level does not always tick a nested structure's block entity - its chunk keeps ticking, but the block
     * entity drops out of the level's ticking list. The owner of the plot is a product block that <em>is</em> ticked,
     * so the plot's own contents are given to it at materialise time and driven from {@link #serverTick} - see
     * {@link #driveNestedProducts}. Never saved: it is a fact about this plot in this session, and a restart rebuilds
     * it from the payload.
     */
    private final java.util.List<BlockPos> nestedProducts = new java.util.ArrayList<>();

    /** Records that a product block was written into this block's plot at {@code local}. */
    public void noteNestedProduct(BlockPos local) {
        if (!this.nestedProducts.contains(local)) {
            this.nestedProducts.add(local.immutable());
            // One line per product written into this plot: the takeover line stays silent when the list is empty, and
            // "nothing to drive" and "the driver never ran" are otherwise the same absence.
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {} will drive the structure at local {} of its plot "
                            + "(list now {})", this.getBlockPos().toShortString(), local.toShortString(),
                    this.nestedProducts.size());
        }
    }

    /** Forgets them, for a materialise that is about to write a fresh set (never for a plot number change). */
    public void forgetNestedProducts() {
        this.nestedProducts.clear();
    }

    public StructureBlockEntity(BlockPos pos, BlockState state) {
        super(RCRegistry.STRUCTURE_BLOCK_ENTITY.get(), pos, state);
    }

    /** The plot holding this structure's blocks, or -1. A view with no plot is the placeholder case. */
    public int plotIndex() {
        return this.plotIndex;
    }

    public void setPlotIndex(int plotIndex) {
        this.plotIndex = plotIndex;
        // The list of nested products is NOT cleared here: placing a structure runs materialize -> setPlotIndex, so
        // clearing here would empty the list the materialise just filled. The materialise clears it itself (see
        // PocketMaterializer#forgetNestedProductsOf).
        // A new plot has to be pushed into from scratch: what the faces read before belonged to the plot
        // before it, and keeping that answer would mean "nothing changed" for a structure nobody drives.
        this.faceSignals = unknownFaceSignals();
        // The same for the outward half, and here it is also what tells the world: a new plot whose first
        // sample is non-zero has to be a change, or the neighbours would never be re-asked.
        this.outputSignals = new int[Direction.values().length];
        // And the sample belongs to the plot before it: the cells it names are that plot's cells.
        forgetLiveView();
        this.setChanged();
    }

    @Nullable
    public UUID structureId() {
        return this.structureId;
    }

    public String structureName() {
        return this.name;
    }

    public Vec3i size() {
        return this.size;
    }

    public IoMode ioMode() {
        return this.ioMode;
    }

    /**
     * How deep the structure inside this block goes, as capture recorded it.
     *
     * <p>Stored rather than looked up in the file, because capture has to decide whether a selection may be taken
     * <em>before</em> it writes anything, and reading every nested structure's file would make that decision depend on
     * files that may be missing.
     */
    public int nestingDepth() {
        return this.nestingDepth;
    }

    /** True when there is nothing to render or release: either no id was ever set, or its file is gone. */
    public boolean isMissing() {
        return this.structureId == null;
    }

    public void setStructure(StructureProductData data) {
        setStructure(data.id(), data.name(), data.size(), data.ioMode(), data.nestingDepth(), data.faces(),
                data.manual(), data.output(), data.preview());
    }

    /** This block's structure as an item's description - the form mining it hands back. */
    public StructureProductData asProduct() {
        return new StructureProductData(this.structureId, this.name, this.size, this.ioMode,
                this.nestingDepth, this.faces, this.manual, this.output, this.preview);
    }

    /** The activation tables this structure was placed with: which cells each energized face pushes into. */
    public Map<Direction, List<ActivationTable.FaceEntry>> faces() {
        return this.faces;
    }

    /** Mode B's panel: which key pushes into which cell, and how hard. */
    public List<ActivationTable.ManualEntry> manual() {
        return this.manual;
    }

    /**
     * The output table this structure was placed with - which cell makes which face answer.
     *
     * <p>Read-only here: the table is edited on the controller and travels with the capture (mining the block hands
     * the same table back as an item).
     */
    public List<ActivationTable.OutputEntry> output() {
        return this.output;
    }

    /** The manual pulses in flight, for the server-side press path. */
    public ManualPulses pulses() {
        return this.pulses;
    }

    /**
     * The picture a client draws for this structure, or {@code null} when there is nothing to draw.
     *
     * <p>It travels in the update tag, because the preview is the one part of a structure that is <em>meant</em> to
     * cross the network.
     */
    @Nullable
    public StructurePreview preview() {
        return this.preview;
    }

    public void setStructure(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth) {
        setStructure(id, name, size, ioMode, nestingDepth, Map.of(), List.of(), null);
    }

    public void setStructure(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                             Map<Direction, List<ActivationTable.FaceEntry>> faces) {
        setStructure(id, name, size, ioMode, nestingDepth, faces, List.of(), null);
    }

    public void setStructure(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                             Map<Direction, List<ActivationTable.FaceEntry>> faces,
                             @Nullable StructurePreview preview) {
        setStructure(id, name, size, ioMode, nestingDepth, faces, List.of(), preview);
    }

    /** The same, for a caller that has no output table. */
    public void setStructure(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                             Map<Direction, List<ActivationTable.FaceEntry>> faces,
                             List<ActivationTable.ManualEntry> manual,
                             @Nullable StructurePreview preview) {
        setStructure(id, name, size, ioMode, nestingDepth, faces, manual, List.of(), preview);
    }

    public void setStructure(UUID id, String name, Vec3i size, IoMode ioMode, int nestingDepth,
                             Map<Direction, List<ActivationTable.FaceEntry>> faces,
                             List<ActivationTable.ManualEntry> manual,
                             List<ActivationTable.OutputEntry> output,
                             @Nullable StructurePreview preview) {
        this.structureId = id;
        this.name = name == null || name.isEmpty() ? PayloadHeader.DEFAULT_NAME : name;
        this.size = size;
        // The picture this block shows is a different one from here on, so nothing the live view remembers about the
        // old one can be compared against it - not its cells, and not its size.
        forgetLiveView();
        this.ioMode = ioMode;
        this.nestingDepth = nestingDepth;
        this.faces = faces == null ? Map.of() : Map.copyOf(faces);
        this.manual = manual == null ? List.of() : List.copyOf(manual);
        this.output = output == null ? List.of() : List.copyOf(output);
        this.preview = preview;
        this.faceSignals = unknownFaceSignals();
        this.outputSignals = new int[Direction.values().length];
        // A different structure's pulses belong to that structure: a key that was held while the block was
        // swapped out must not keep driving the new one.
        this.pulses.clear();
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    /**
     * Asks for a plot again, throttled to one attempt per {@link #PLOT_REPAIR_TICKS} - the attempt itself is
     * {@link #repairPlot(ServerLevel)}, which is also the whole of what placing a structure item does.
     *
     * @return true when this block now has a plot
     */
    public boolean repairPlotIfDue(ServerLevel server) {
        if (this.structureId == null) {
            return false;
        }
        long now = server.getGameTime();
        if (now < this.nextPlotRepairAt) {
            return false;
        }
        this.nextPlotRepairAt = now + PLOT_REPAIR_TICKS;
        PocketMaterializer.Result result = this.repairPlot(server);
        if (result.materialized()) {
            this.plotRepairReported = false;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} was given plot {} again ({} block(s)) "
                            + "- it had none", this.getBlockPos().toShortString(), result.plot(), result.blocks());
            return true;
        }
        if (!this.plotRepairReported) {
            this.plotRepairReported = true;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} has no plot and could not be given "
                            + "one: {} (it keeps asking once a second)", this.getBlockPos().toShortString(),
                    result.refusal());
        }
        return false;
    }

    /**
     * <b>Puts this block's structure into a pocket plot</b> - at this block's own position and level, from the
     * structure file this save holds for it. A missing file is not an error: the block stays the placeholder.
     *
     * @return the plot it was given, or the reason it was not - never null
     */
    public PocketMaterializer.Result repairPlot(ServerLevel server) {
        StructurePayload payload = this.structureId == null ? null
                : StructureWorldStore.of(server.getServer(), RCConfig.structurePayloadLimits())
                        .loadOrNull(this.structureId);
        if (payload == null) {
            return new PocketMaterializer.Result(null, "this save has no structure file for "
                    + (this.structureId == null ? "a block with no id" : this.structureId.toString())
                    + " (import it, then use /rc structure materialize)", 0);
        }
        PocketMaterializer.Result result = PocketMaterializer.materialize(server, payload,
                RCConfig.structureCaptureContextShell(), server.dimension(), this.getBlockPos());
        if (result.materialized()) {
            this.setPlotIndex(result.plot());
        }
        return result;
    }

    /**
     * Mode A, the reading half: what each of the product block's six faces is receiving from the world, and what that
     * pushes into the plot - and the tick that also runs the indicator, the live view, the watchdog and the output.
     *
     * <p>A face counts as energized by the larger of {@code getSignal} and {@code getDirectSignal}. The reading is six
     * vanilla queries; the pushing is {@link PocketSpace#applyInjection}, which owns the ledger and the neighbour
     * notifications. A product with no table, no plot, or the manual io mode returns on the first statement.
     *
     * <p>Its shape is {@link BlockEntityTicker}'s, so the block can hand it straight to vanilla.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, StructureBlockEntity product) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        // A block with no plot asks for one again, above everything else; the attempt is throttled to one a second and
        // only happens while there is no plot, so the ordinary case pays one int comparison. When it succeeds the tick
        // carries on rather than returning, because a block that has just been given a plot is an ordinary block.
        ServerLevel pocket = PocketDimension.of(server.getServer());
        if (pocket == null) {
            return;
        }
        // An index is only worth anything while the plot behind it is still there. A plot can be given back under a
        // block that is not ticking at that moment (a sweep, /rc pocket clear, or the pocket making room for a new
        // structure), and a block that goes on claiming an index nobody answers for never asks for a plot again,
        // because asking is what the plotIndex < 0 branch below does.
        if (product.plotIndex >= 0 && !PocketSpace.of(pocket).hasPlot(product.plotIndex)) {
            product.setPlotIndex(-1);
        }
        if (product.plotIndex < 0) {
            product.repairPlotIfDue(server);
            if (product.plotIndex < 0) {
                return;
            }
        }
        // Say that this block has been ticked, so a driver running after the level's own block-entity ticks does not
        // run the same block a second time in this tick. See #tickedAt.
        product.selfTickAt = server.getGameTime();
        // The frame is resolved ONCE per tick and reused by both halves that ask where this structure is drawn - the
        // live view below and the six faces at the end. A block standing on the ground answers the in-place frame, so
        // the standing path pays one carrier-layer check and no allocation.
        ProductFrame frame = ProductFrame.carriedAt(server, pos);
        // The indicator comes first, because it must be updated on <b>every</b> tick - including the ticks where the
        // mode work below returns early, which are most of them. Light cannot drive redstone, so this is a way of being
        // seen without being a way of being heard, and it uses UPDATE_CLIENTS: no neighbour update, no observer, no
        // signal of any kind leaves this block.
        product.updateIndicator(pocket);
        product.sendLiveView(server, pocket, frame.worldPosOf(pos));
        // The watchdog, on the same ten-tick phase as the live view and for the same reason - a cell is only worth a
        // second look when somebody could be watching. A placeholder that never resolves disables the machine inside
        // for good, so this is the one place the mod writes to a plot's blocks by itself.
        long watchdogNow = server.getGameTime();
        // A stranded placeholder must be finished fast, or the miniature shows a piston that teleports or loses its
        // head. While something is being driven the pass runs every tick - that is the only window a placeholder can
        // appear in, and it is bounded by the same burst the live view uses. Otherwise it keeps its ten-tick phase.
        if (watchdogNow < product.liveViewBurstUntil
                || Math.floorMod(watchdogNow, LIVE_VIEW_INTERVAL)
                        == Math.floorMod(pos.asLong(), LIVE_VIEW_INTERVAL)) {
            product.watchStuckMoves(pocket, watchdogNow);
        }
        // An input faster than the limit is held rather than lost, and this is the tick that lets it through when its
        // window opens. It has to be here, above both input modes: each of them returns early when its own sources have
        // not changed, which is exactly the tick a held change is waiting for.
        product.flushWaitingInjection(pocket);
        // The structures inside this one are driven from here: the level stops ticking a nested block entity while its
        // chunk keeps ticking, and the owner of a plot is a product block that is ticked by definition. selfTickAt
        // keeps the level's driver and this one from running one block twice in a tick.
        product.driveNestedProducts(pocket, server.getGameTime());
        // The outward half. It sits above both input modes because it is not a third way of being driven, it is what
        // the structure answers with, and "nothing is driving it" is one of the states that has to be able to change
        // the answer.
        product.updateOutput(server, pocket, frame);
        //
        // This is the whole of "a physicalised structure keeps its plot". A carrier can put this block down anywhere,
        // and the plot table is the only thing that would still be pointing at the old position, where a sweep would
        // hand the plot back. Rather than chase every way a block can move, the one thing that is always true is asked:
        // the block is here, it knows which plot is its own, and if the table disagrees then the table is wrong.
        // It is re-owned even inside somebody else's plot: a Sable sub-level keeps the level's own blocks in a reserved
        // plot region, so this position IS the block's position. The record is still stamped travelling from the
        // pickup, and reown's landing branch is what writes it here; skipping this call would leave the record pointing
        // at a world position the assembler had already emptied.
        PocketSpace.of(pocket).reown(product.plotIndex, server.dimension(), pos, product.structureId);
        if (product.ioMode == IoMode.MANUAL) {
            // Mode B reads nothing: its input is a player, and the only thing a tick has to do is end the pulses that
            // have run out - which is a change the pocket has to hear about, exactly like a face losing its signal.
            if (product.manual.isEmpty() || !product.pulses.expire(server.getGameTime())) {
                return;
            }
            product.pushInjection(pocket);
            return;
        }
        if (product.faces.isEmpty()) {
            return;
        }
        // The six faces are read where this block looks like it stands. A physicalised structure is not in the world at
        // the position the player sees it, so asking at its own coordinate asks about a neighbour of the machine's
        // storage instead of the neighbour in front of the player. A block standing on the ground resolves to the
        // in-place frame and reads what it always read.
        int[] now = product.readFaces(server, pos, frame);
        if (Arrays.equals(now, product.faceSignals)) {
            // The same diagnostic on the other side of the decision: without it, "the block ticked and its six faces
            // were frozen" and "the block stopped ticking at all" are the same three words in the log.
            if (RCConfig.debugLog()
                    && server.getGameTime() - product.lastFaceUnchangedLog >= LIVE_VIEW_LOG_TICKS) {
                product.lastFaceUnchangedLog = server.getGameTime();
                RCConfig.LOGGER.info("[redstonecircuit] faces unchanged at {}: plot={} row(s)={} signals={}",
                        pos.toShortString(), product.plotIndex, product.faces.size(), Arrays.toString(now));
            }
            return;
        }
        product.faceSignals = now;
        product.pushInjection(pocket);
    }

    /**
     * The six faces of one product block, read in the frame it is drawn in.
     *
     * <p>{@code localPos} is the coordinate the faces are counted from: the block's own position when the level ticks
     * it, and the carrier's own local coordinate when it does not (see {@link #carriedTick}).
     */
    public int[] readFaces(ServerLevel level, BlockPos localPos, ProductFrame frame) {
        int[] now = new int[Direction.values().length];
        for (Direction face : Direction.values()) {
            now[face.ordinal()] = frame.signalInto(level, localPos.relative(face), face);
        }
        return now;
    }

    /**
     * Runs the product blocks standing in this block's plot, when the level has stopped doing it.
     *
     * <p>Not "always tick them": a nested block the level is still ticking has already stamped {@code selfTickAt} for
     * this tick (or will), and running it twice in one tick would double every injection and notification.
     */
    private void driveNestedProducts(ServerLevel pocket, long gameTime) {
        if (this.plotIndex < 0) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        if (this.nestedProducts.isEmpty()) {
            // A carried copy arrives with an empty list, and it still has a plot with the same blocks in it. The list
            // is not saved, so a copy that comes back from a sub-level or a contraption searches its own plot once
            // instead of waiting for a materialise that is never going to run while the machine is flying.
            scanForNestedProducts(pocket, plot);
        }
        int taken = 0;
        for (BlockPos local : this.nestedProducts) {
            BlockPos cell = plot.origin().offset(local);
            if (!(pocket.getBlockEntity(cell) instanceof StructureBlockEntity nested) || nested.tickedAt(gameTime)) {
                continue;
            }
            taken++;            if (nested.lastSelfTick() != Long.MIN_VALUE && gameTime - nested.lastSelfTick() > 20
                    && !nested.drivenAfterSilenceReported) {
                nested.drivenAfterSilenceReported = true;
                RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} had not been ticked for {} tick(s) "
                                + "(its chunk is ticking and its plot is {}), so the structure it stands in drives it "
                                + "from here", cell.toShortString(), gameTime - nested.lastSelfTick(),
                        nested.plotIndex);
            }
            serverTick(pocket, cell, pocket.getBlockState(cell), nested);
        }
        if (RCConfig.debugLog() && gameTime - this.lastDriveReportAt >= 20) {
            this.lastDriveReportAt = gameTime;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: the plot of {} holds {} product(s) to drive; took over {} "
                            + "this tick", this.getBlockPos().toShortString(), this.nestedProducts.size(), taken);
        }
    }

    /** The throttle for the line above. */
    private long lastDriveReportAt;

    /** Whether this block has searched its plot for product blocks (once, for a copy that arrived without them). */
    private boolean nestedProductsScanned;

    /**
     * Finds the product blocks standing in this block's plot, once.
     *
     * <p>A block that travelled inside a Sable sub-level or a Create contraption comes back as a <em>copy</em> of its
     * block entity, and the list of what stands in its plot is not part of the saved data. The plot is the same plot
     * with the same blocks in it, and those blocks have no other driver while the machine is away, so it looks for them
     * once. The search is bounded by this structure's own size.
     */
    private void scanForNestedProducts(ServerLevel pocket, PocketSpace.Plot plot) {
        if (this.nestedProductsScanned) {
            return;
        }
        this.nestedProductsScanned = true;
        for (int y = 0; y < this.size.getY(); y++) {
            for (int z = 0; z < this.size.getZ(); z++) {
                for (int x = 0; x < this.size.getX(); x++) {
                    if (pocket.getBlockEntity(plot.origin().offset(x, y, z)) instanceof StructureBlockEntity) {
                        this.noteNestedProduct(new BlockPos(x, y, z));
                    }
                }
            }
        }
    }

    /** Whether the line above has already been said for this block. */
    private boolean drivenAfterSilenceReported;

    /**
     * One tick of a structure carried by something that does not tick it: the reading half, at the carrier's own local
     * coordinate and in the frame its pose puts that coordinate in.
     *
     * <p>Three things are absent, and each is a statement rather than an omission:
     * <ul>
     *   <li>no {@code reown} - the block is not standing in a level at this coordinate, and telling the plot table
     *       that it is would move the record to a coordinate that is not a level coordinate at all. A contraption's
     *       plot is protected while it travels by the travelling stamp, and re-owned on the way back;</li>
     *   <li>no indicator and no world notification - there is no block of ours standing in the level at either
     *       position, so there is no state to write a light property into and no neighbour to tell. The live view is
     *       here, and a packet needs no block;</li>
     *   <li>no manual mode - a structure driven by keys has a panel somebody presses, and a key presses nothing while
     *       the block it belongs to is inside a moving machine.</li>
     * </ul>
     */
    public static void carriedTick(ServerLevel level, ServerLevel pocket, StructureBlockEntity product,
                                   ProductFrame frame, BlockPos localPos) {
        if (level == null || pocket == null || product == null || frame == null || localPos == null
                || product.plotIndex < 0) {
            return;
        }
        // The client-facing half, addressed to where this structure is DRAWN - the product's own coordinate stays the
        // payload's key, because that is where a client-side renderer of the carrier builds the miniature from. It
        // stands above the two "nothing to read" returns below, because a miniature goes stale while the faces are
        // quiet; the call decides for itself whether this tick is a sampling tick.
        product.sendLiveView(level, pocket, frame.worldPosOf(localPos));
        // The tick the standing path gives a held input, given here too, and for the same reason: each of the returns
        // below happens on exactly the tick a rate-limited change is waiting for, so without this the change would wait
        // for the NEXT face change instead of for its window.
        product.flushWaitingInjection(pocket);
        // And the structures inside this one: a carried owner is the only driver its plot's blocks have while it is
        // away, and the level stopped ticking them (see driveNestedProducts).
        product.driveNestedProducts(pocket, level.getGameTime());
        // And the outward half: what a carried structure answers with has to reach the world wherever the machine is
        // drawn, and "nothing is driving it" is one of the states that has to be able to change the answer.
        product.updateOutputDrawn(pocket, frame);
        if (product.ioMode == IoMode.MANUAL || product.faces.isEmpty()) {
            return;
        }
        int[] now = product.readFaces(level, localPos, frame);
        if (Arrays.equals(now, product.faceSignals)) {
            return;
        }
        product.faceSignals = now;
        // One line per change, because "the machine does not react" and "the machine is not being read" look identical
        // from outside - and the coordinates in it say which frame the faces were asked in.
        if (com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] carried product at {} read {} "
                            + "in frame {} (plot {})", localPos.toShortString(), Arrays.toString(now),
                    frame.describe(), product.plotIndex);
        }
        product.pushInjection(pocket);
    }

    /**
     * <b>One key pressed</b> on this structure's panel: starts the entry's pulse and pushes it.
     *
     * <p>The caller has already checked the press ({@code ManualTrigger}: the mode, the entry, the distance and
     * the cooldown); this is the half that cannot be checked without the block, and it re-checks what it can -
     * a press that names an entry the panel does not have is refused rather than trusted, because this is also
     * reachable from a packet.
     *
     * @return true when the pulse started
     */
    public boolean pressManual(int entry, long gameTime, int pulseTicks, ServerLevel pocket) {
        if (this.ioMode != IoMode.MANUAL || entry < 0 || entry >= this.manual.size()) {
            return false;
        }
        this.pulses.press(entry, gameTime, pulseTicks);
        this.pushInjection(pocket);
        return true;
    }

    /**
     * Pushes everything this structure is being driven with into its plot.
     *
     * <p>One place decides which table is the input, because the two modes are alternatives ({@code ioMode}): a
     * structure in activation-face mode is driven by its faces and its keys do nothing, and the other way round. Two
     * callers push - the tick, and a press - and both come through here.
     */
    public int pushInjection(ServerLevel pocket) {
        if (this.plotIndex < 0) {
            // The early return is named too: a manual press that reaches a product driving no plot at all. (The rules a
            // press is checked against are the mode and the row count, so a block with no plot passes every check and
            // stops here.)
            if (this.ioMode == IoMode.MANUAL && RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] manual press dropped at {}: this block drives no plot "
                                + "(plotIndex=-1), row(s)={}", this.getBlockPos().toShortString(),
                        this.manual.size());
            }
            return 0;
        }
        PocketInjection.Sources sources = this.ioMode == IoMode.MANUAL
                ? PocketInjection.Sources.manual(this.manual, this.pulses.pressed())
                : PocketInjection.Sources.faces(this.faces, this.faceSignals);
        int changed = PocketSpace.of(pocket).applyInjection(this.plotIndex, sources);
        // A manual press that changes nothing, said out loud: it names the three things that can stop it - the plot
        // record the press is aimed at, the rows it drives, and the plot it thinks it is driving.
        if (changed == 0 && this.ioMode == IoMode.MANUAL && RCConfig.debugLog()) {
            // And WHICH cell the key drives: the nested block only reacts to its own six neighbours, and "the press
            // changed nothing" plus "the cell it names is not the nested block's neighbour" is a different defect from
            // "the press arrived".
            StringBuilder rows = new StringBuilder();
            for (ActivationTable.ManualEntry row : this.manual) {
                rows.append(row.localPos().toShortString()).append('=').append(row.strength()).append(' ');
            }
            RCConfig.LOGGER.info("[redstonecircuit] manual press changed nothing at {}: plot={} row(s)={} rows={} "
                            + "plot still live={}", this.getBlockPos().toShortString(), this.plotIndex,
                    this.manual.size(), rows, PocketSpace.of(pocket).plots().containsKey(this.plotIndex));
        }
        // And the face-driven half speaks too, throttled: a block that ticks, reads its faces and pushes a value the
        // plot already holds is completely silent (changed == 0), exactly like a block that never ticked at all. One
        // line per second says which it is.
        if (changed == 0 && this.ioMode != IoMode.MANUAL && RCConfig.debugLog()) {
            long now = this.getLevel() == null ? 0L : this.getLevel().getGameTime();
            if (now - this.lastFaceNoChangeLog >= LIVE_VIEW_LOG_TICKS) {
                this.lastFaceNoChangeLog = now;
                // And the cells this table names, so the log can say WHERE the push was aimed as well as that it
                // changed nothing.
                StringBuilder targets = new StringBuilder();
                for (Map.Entry<Direction, List<ActivationTable.FaceEntry>> face : this.faces.entrySet()) {
                    for (ActivationTable.FaceEntry entry : face.getValue()) {
                        targets.append(face.getKey().getSerializedName()).append(':')
                                .append(entry.localPos().toShortString()).append('=').append(entry.strength())
                                .append(' ');
                    }
                }
                RCConfig.LOGGER.info("[redstonecircuit] face-driven push at {} changed nothing: plot={} row(s)={} "
                                + "signals={} targets={} plot still live={}", this.getBlockPos().toShortString(),
                        this.plotIndex, this.faces.size(), Arrays.toString(this.faceSignals), targets,
                        PocketSpace.of(pocket).plots().containsKey(this.plotIndex));
            }
        }
        if (changed > 0) {
            // Something moved inside, so the miniature is worth looking at every tick for a short while.
            this.armLiveViewBurst(pocket.getGameTime());
        }
        if (changed > 0 && com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] injection at {} changed {} "
                    + "cell(s) inside plot {}", this.getBlockPos().toShortString(), changed, this.plotIndex);
        }
        // A source that moves faster than the limit is held rather than lost, and this line is how that is visible from
        // the outside - throttled, because a fast clock holds something back on every single tick.
        if (changed == 0 && com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            PocketInjection ledger = PocketSpace.of(pocket).injection(this.plotIndex);
            long gameTime = pocket.getGameTime();
            if (ledger != null && ledger.hasWaiting() && gameTime - this.lastInjectionLimitLog >= LIVE_VIEW_LOG_TICKS) {
                this.lastInjectionLimitLog = gameTime;
                com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] injection at {} is holding "
                                + "{} cell(s) back inside plot {}: the input is moving faster than one change per {} "
                                + "tick(s)", this.getBlockPos().toShortString(), ledger.waitingCells(), this.plotIndex,
                        com.jiangyuefengyu.redstonecircuit.RCConfig.structureInjectionLimitTicks());
            }
        }
        return changed;
    }

    /**
     * Lets a rate-limited change through the moment its window opens.
     *
     * <p>A held change is not lost, it is owed: it happens when the sources are offered again, which on a real server
     * is this block's own tick.
     */
    private void flushWaitingInjection(ServerLevel pocket) {
        if (this.plotIndex < 0) {
            return;
        }
        PocketInjection ledger = PocketSpace.of(pocket).injection(this.plotIndex);
        if (ledger == null || !ledger.hasWaiting()) {
            return;
        }
        this.pushInjection(pocket);
    }

    /**
     * The outward half: what this structure answers with, on each of its six faces.
     *
     * <p>The rule is a level rather than a pulse, the row's own strength rather than the cell's, and any non-zero
     * signal counting as "that cell has one"; the arithmetic is {@link StructureOutput}'s, and this is the two halves
     * only a running server has - reading the pocket, and telling the world when the answer moved.
     *
     * <p>The notification is vanilla's {@code updateNeighborsAt}, aimed at the block's own position in the world it
     * stands in, not at the pocket: what changed is what the <em>outside</em> reads. Nothing about this block's state
     * changes, so nothing is re-rendered and no client is sent anything - a redstone query is answered live.
     */
    private void updateOutput(ServerLevel server, ServerLevel pocket, ProductFrame frame) {
        if (this.output.isEmpty() || this.plotIndex < 0) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        this.applyOutput(pocket, plot.origin(), frame, true);
    }

    /**
     * The outward half for a structure a Create contraption is carrying.
     *
     * <p>The same arithmetic as {@link #updateOutput}, with two differences: it runs from {@code carriedTick}, and it
     * tells <b>only</b> the position the structure is drawn at, never {@code this.getBlockPos()} - a contraption's
     * product stands at a local coordinate that is not a position of any level.
     */
    public void updateOutputDrawn(ServerLevel pocket, ProductFrame frame) {
        if (this.output.isEmpty() || this.plotIndex < 0 || frame == null) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        this.applyOutput(pocket, plot.origin(), frame, false);
    }

    /**
     * The evaluation and the notification, over one plot's cells.
     *
     * <p>Split out from the tick because it needs nothing but the level the plot is in and where that plot starts -
     * which is also what lets a test drive it directly, since the test harness cannot host the pocket dimension.
     *
     * @return how many of the six faces changed their answer
     */
    public int applyOutput(ServerLevel plotLevel, BlockPos plotOrigin) {
        return applyOutput(plotLevel, plotOrigin, null, true);
    }

    /**
     * The same half, for a structure the carrier DRAWS somewhere other than where its block stands.
     *
     * <p>When a frame says the structure is drawn elsewhere, three things happen instead of one: the six strengths are
     * {@linkplain PublishedStructureOutput published} at the drawn position (indexed by world face, so a query there
     * can be answered at all), that position's neighbours are woken as well, and - only when the carrier is a Sable
     * sub-level, where the stored coordinate is a real level position the machine's own blocks live at - the stored
     * position keeps its own notification.
     *
     * @param frame the frame this structure is drawn in, or {@code null} when it stands in the world at its own
     *     position
     * @param tellStoredPosition whether the block's own coordinate is also woken. False for a Create contraption,
     *     whose block entity stands at a contraption-local coordinate that is not a position of any level
     */
    public int applyOutput(ServerLevel plotLevel, BlockPos plotOrigin, @Nullable ProductFrame frame,
                           boolean tellStoredPosition) {
        int[] now = StructureOutput.faceStrengths(this.output,
                local -> signalIn(plotLevel, plotOrigin.offset(local)));
        Level level = this.getLevel();
        // Published on EVERY tick this half runs, above the "did it change" test below: a published answer expires
        // (PublishedStructureOutput#PUBLISH_TICKS), so publishing only on a change would let a steady output go dark.
        BlockPos drawn = frame != null && frame.carried() ? frame.worldPosOf(this.getBlockPos()) : null;
        if (level != null && drawn != null) {
            PublishedStructureOutput.publish(level.dimension(), drawn, byWorldFace(now, frame), level.getGameTime());
        }
        int changed = 0;
        for (int face = 0; face < now.length; face++) {
            if (now[face] != this.outputSignals[face]) {
                changed++;
            }
        }
        // Arriving is a reason of its own: a structure that was ALREADY answering when a carrier took it over changes
        // nothing at the moment it is drawn somewhere new, so a notification that waits for a change never fires.
        boolean arrived = drawn != null && drawn.asLong() != this.lastDrawnPos;
        if (changed == 0 && !arrived) {
            return 0;
        }
        this.outputSignals = now;
        // The position this structure has left is told as well, after its published answer is withdrawn: the record
        // outlives the machine by PUBLISH_TICKS ticks, so a device woken at the old position inside that window would
        // read an answer the structure no longer gives.
        BlockPos left = arrived && this.lastDrawnPos != Long.MIN_VALUE ? BlockPos.of(this.lastDrawnPos) : null;
        if (drawn != null) {
            this.lastDrawnPos = drawn.asLong();
        }
        if (level instanceof ServerLevel serverLevel) {
            if (left != null) {
                PublishedStructureOutput.forget(serverLevel.dimension(), left);
                serverLevel.updateNeighborsAt(left, this.getBlockState().getBlock());
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChanged(serverLevel, left);
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(serverLevel,
                        left);
            }
            if (tellStoredPosition) {
                serverLevel.updateNeighborsAt(this.getBlockPos(), this.getBlockState().getBlock());
            }
            if (drawn != null && !drawn.equals(this.getBlockPos())) {
                serverLevel.updateNeighborsAt(drawn, this.getBlockState().getBlock());
                // The mod's own network at that position, which is a different notification for a different reader.
                // `updateNeighborsAt` is vanilla's: it wakes the blocks around the drawn position, which is what a dust
                // or a lamp outside needs. A HOST BLOCK of ours (a block in the world holding inner redstone) does not
                // re-solve on that alone - it is the mod's own wake-up at a world position that tells the solver.
                //
                // Both of the mod's calls, and the queued one is the one that matters: `notifyOutputChanged` is the
                // pipeline the solver's world pass listens to, while `notifyOutputChangedNow` only fires the immediate
                // neighbour update.
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChanged(serverLevel,
                        drawn);
                com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork.notifyOutputChangedNow(serverLevel,
                        drawn);
            }
        }
        if (drawn != null && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] product at {} answers with {} where it is drawn ({}), and its "
                            + "neighbours there are woken as well",
                    this.getBlockPos().toShortString(), java.util.Arrays.toString(now), drawn.toShortString());
        }
        return changed;
    }

    /** One strength per world face, from the structure's own faces through the carrier's frame. */
    private static int[] byWorldFace(int[] localStrengths, ProductFrame frame) {
        int[] world = new int[localStrengths.length];
        for (Direction face : Direction.values()) {
            world[frame.worldFace(face).ordinal()] = localStrengths[face.ordinal()];
        }
        return world;
    }

    /**
     * What one cell inside a plot is carrying right now.
     *
     * <p><b>Two questions, because either answer is what a player means by "this cell has a signal":</b> what arrives
     * at the cell from around it (vanilla's own "would a lamp here light up", {@code getBestNeighborSignal}), and what
     * the cell's own block gives out (a torch, a lit wire, a repeater <em>in</em> that cell). The strength that is
     * sent is the row's own, so only whether the answer is non-zero matters - but both are asked, and the larger is
     * answered.
     */
    private static int signalIn(ServerLevel pocket, BlockPos cell) {
        int arriving = pocket.getBestNeighborSignal(cell);
        if (arriving > 0) {
            return arriving;
        }
        BlockState state = pocket.getBlockState(cell);
        int emitted = 0;
        for (Direction side : Direction.values()) {
            emitted = Math.max(emitted, state.getSignal(pocket, cell, side));
        }
        return emitted;
    }

    /**
     * What a placed structure answers with on one face, for a redstone query from outside.
     *
     * <p>Reached from {@code StructureBlock#getSignal} and {@code StructureBlock#getDirectSignal}, which is why it is
     * static and tolerant: a query can be made at any position, in any level, with any side, and the answer for "that
     * is not one of ours" is 0. A client answers 0 as well - the cached strengths are worked out by the server's tick.
     */
    public static int outputTowards(@Nullable net.minecraft.world.level.BlockGetter level,
                                    @Nullable BlockPos pos, @Nullable Direction face) {
        if (level == null || pos == null || face == null) {
            return 0;
        }
        return level.getBlockEntity(pos) instanceof StructureBlockEntity product
                ? product.outputSignals[face.ordinal()]
                : 0;
    }

    /** The selection-local cells this structure's tables drive, in a stable order - see {@link StructureIndicator}. */
    private List<BlockPos> tableCells() {
        List<BlockPos> cells = new java.util.ArrayList<>(this.manual.size() + this.faces.size());
        for (ActivationTable.ManualEntry entry : this.manual) {
            cells.add(entry.localPos());
        }
        for (Map.Entry<Direction, List<ActivationTable.FaceEntry>> row : this.faces.entrySet()) {
            for (ActivationTable.FaceEntry entry : row.getValue()) {
                cells.add(entry.localPos());
            }
        }
        return cells;
    }

    /** How often a placed structure is compared against what was last sent to the clients watching it. */
    private static final int LIVE_VIEW_INTERVAL = 10;

    /** How far away a player still counts as watching a structure - the same reach as a chunk's own rendering. */
    private static final double LIVE_VIEW_RANGE = 48.0;

    /** How long a structure is sampled every tick after something changed inside it. */
    private static final int LIVE_VIEW_BURST_TICKS = 12;

    /** The last sample sent, so the next one can be a diff rather than a resend. */
    private int[] lastLiveSample;

    /**
     * The two buffers a believed sample is written into, reused between passes - see {@link #sample}.
     *
     * <p>A 48³ structure is 110,592 cells, so a pass that built its arrays afresh would hand the collector 442 KB every
     * interval, and again on every tick of a whole picture's 216 passes, for values that are dropped one pass later.
     * Two of them, because one may still be {@link #lastLiveSample}: that one is what the new sample is compared
     * against, so it cannot also be what the new sample is written into.
     */
    private final int[][] liveSampleBuffers = new int[2][0];

    /** What {@link #sample} reads the world into: the placeholder rule is applied afterwards, into the sample. */
    private int[] liveWorldBuffer = new int[0];

    /**
     * The observers that have been sent a whole picture of this structure, so the picture is not repeated on a timer
     * for players who are already up to date - see {@link StructureLiveView#needsWholePicture}.
     */
    private final java.util.Set<java.util.UUID> liveViewPictureHolders = new java.util.HashSet<>();

    /** The cells of a full resend still to send, or null when none is in flight. */
    private StructureLiveView.Resend liveResend;

    /**
     * How long a moving-piston placeholder may stand before this mod finishes the move itself.
     *
     * <p>Vanilla's own move takes two ticks, so a placeholder still standing after four is not a move in progress - it
     * is a move that will never finish. That is not cosmetic: a {@code moving_piston} cannot be pushed, so a piston
     * whose head cell holds one stops pushing for ever. Four ticks is still twice what vanilla needs.
     */
    public static final int STUCK_PLACEHOLDER_TICKS = 4;

    /** Per cell, the game time a moving-piston placeholder was first seen at; 0 means "not one". */
    private long[] stuckPlaceholderSince = new long[0];

    /**
     * The cells that were moving in the last sample, as {@code {cell index, moved block id, packed from}}, so the
     * client can slide them instead of blinking them.
     */
    private final java.util.List<int[]> sampleMoves = new java.util.ArrayList<>();

    /** The last game time the cells of a packet were written to the log, so a running circuit cannot flood it. */
    private long lastLiveLog;

    /** The last game time the injection's rate limit was written to the log - see {@link #lastLiveLog}'s reason. */
    private long lastInjectionLimitLog;
    /** The same throttle for "a face-driven push that changed nothing". */
    private long lastFaceNoChangeLog;
    /** And for "this block ticked and its six faces were the same as last tick". */
    private long lastFaceUnchangedLog;

    /** How often the sent cells are written to the log while the debug log is on. */
    private static final int LIVE_VIEW_LOG_TICKS = 20;

    /** How many cells of a packet the debug line names. */
    private static final int LIVE_VIEW_LOG_CELLS = 6;

    /**
     * Sample every tick until this game time, because something just changed.
     *
     * <p>A piston extends for two or three ticks, and a ten-tick interval can miss the whole thing. Arming it only when
     * something really changed is what makes it end by itself: armed on every sampling tick it would re-arm on every
     * tick it caused, and a structure would then be sampled for ever.
     */
    private long liveViewBurstUntil;

    /**
     * Forgets everything the live view remembers about this structure: the sample it is compared against, the whole
     * picture still being streamed, the buffers, and which observers already hold a picture.
     *
     * <p>Called wherever this block is given a different picture to show - another structure (a capture applied onto a
     * placed block, a save read under a live view) or another plot. A sample of another size is not a diff against
     * anything: {@link StructureLiveView#changed} refuses it, and that refusal would be raised inside a tick, where an
     * exception takes the server with it. Forgetting is always the safe answer - it costs one whole picture to every
     * observer still watching - and it is also the correct one when only the cells moved, because those observers hold
     * cells that are no longer there.
     */
    private void forgetLiveView() {
        this.lastLiveSample = null;
        this.liveResend = null;
        this.liveSampleBuffers[0] = new int[0];
        this.liveSampleBuffers[1] = new int[0];
        this.liveWorldBuffer = new int[0];
        this.liveViewPictureHolders.clear();
        // The armed window was cut to the old structure's volume, so it is not an allowance the new one has earned.
        this.liveViewBurstUntil = 0;
    }

    /** How many cells this structure has, or zero before it has a size: the volume decides what a sample costs. */
    private int liveViewCells() {
        return this.size == null ? 0 : StructureLiveView.count(this.size);
    }

    /**
     * The buffer the next believed sample is written into: one of the two this block entity keeps, and never
     * {@link #lastLiveSample}, which is the value the sample is compared against.
     */
    private int[] sampleBuffer(int count) {
        int slot = this.liveSampleBuffers[0] == this.lastLiveSample ? 1 : 0;
        if (this.liveSampleBuffers[slot].length != count) {
            this.liveSampleBuffers[slot] = new int[count];
        }
        return this.liveSampleBuffers[slot];
    }

    /**
     * Arms the window in which this structure is sampled on every tick, cut to what this volume can afford - see
     * {@link StructureLiveView#burstTicks}.
     */
    private void armLiveViewBurst(long gameTime) {
        this.liveViewBurstUntil = Math.max(this.liveViewBurstUntil,
                gameTime + StructureLiveView.burstTicks(liveViewCells(), LIVE_VIEW_BURST_TICKS));
    }

    /**
     * Samples the structure's own cells on a timer and sends the cells whose block differs from the last sample, so the
     * miniature follows what is happening inside.
     *
     * <p>The miniature is drawn from the captured preview, which is a snapshot - so a lamp lighting up, a piston
     * extending or dust losing its power inside the pocket was invisible from outside.
     *
     * <p>Three guards keep it affordable: only every {@link #LIVE_VIEW_INTERVAL} ticks, only when somebody is within
     * {@link #LIVE_VIEW_RANGE}, and only while somebody is watching at all.
     *
     * <p>A preview coarsened by its own cell budget is sent like any other, but the cells it names are the
     * <b>preview's</b>: a changed structure cell is attributed to the preview cell that stands for it here, and what
     * is sent for that preview cell is recomputed from all {@code scale}³ cells behind it by the same rule the capture
     * samples by ({@code StructurePreviewCells#representative}). This end is the only one that can do it - it has the
     * payload, the scale and the real blocks in the pocket - and it has to be done here rather than by the client
     * because "which cell of the group is the picture" is a question with one answer, not one answer per frame. Sending
     * the changed cells in the structure's own numbering instead left the client to pick the last one that arrived,
     * which is how the solid layer behind a decorated wall became the wall.
     *
     * <p>The audience is {@code drawnPos}: a carrier-local coordinate is not where the player stands, so a reach test
     * asked about the block's own position would answer "nobody is within {@link #LIVE_VIEW_RANGE}" for the whole ride.
     * The payload still carries {@link #getBlockPos()}, because <b>that is where the client's block entity is</b> and
     * where a client-side renderer builds the miniature out of the live view ({@code client.StructureLiveViews}, keyed
     * by {@code StructureLivePayload#pos}). A structure standing in the world is drawn where it stands, so the overload
     * below hands its own position in.
     */
    void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos) {
        if (this.size == null || this.preview == null) {
            return;
        }
        // floorMod, not `%`: a position is negative in negative coordinates, and a sign-opinionated remainder never
        // matches the positive one the game time gives.
        long gameTime = server.getGameTime();
        // One sample reads every cell of the pocket, so what a pass costs is this structure's volume rather than what
        // changed inside it - see StructureLiveView#sampleIntervalScale.
        int cells = liveViewCells();
        // Belt and braces on the rule that a remembered sample belongs to one structure: whatever wrote a size, a
        // sample it no longer matches is compared against nothing - the comparison throws, and this runs inside a tick.
        if (!StructureLiveView.sampleMatches(this.lastLiveSample, cells)) {
            forgetLiveView();
        }
        int interval = LIVE_VIEW_INTERVAL * StructureLiveView.sampleIntervalScale(cells);
        boolean resending = this.liveResend != null;
        if (!resending && gameTime >= this.liveViewBurstUntil
                && Math.floorMod(gameTime, interval) != Math.floorMod(this.worldPosition.asLong(), interval)) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        List<ServerPlayer> watchers = new java.util.ArrayList<>();
        for (ServerPlayer player : server.players()) {
            // The position asked about here is the one the structure is DRAWN at, not the one this block entity stands
            // at - see #sendLiveView(ServerLevel, ServerLevel, BlockPos).
            if (player.distanceToSqr(Vec3.atCenterOf(drawnPos)) <= LIVE_VIEW_RANGE * LIVE_VIEW_RANGE) {
                watchers.add(player);
            }
        }
        java.util.Set<java.util.UUID> watching = new java.util.HashSet<>(watchers.size() * 2);
        for (ServerPlayer player : watchers) {
            watching.add(player.getUUID());
        }
        // The bookkeeping is pruned to who is watching BEFORE it is read, so a player who left the range - or left the
        // server - stops being remembered here, and one who comes back is sent the whole picture again.
        boolean wholePicture = !watchers.isEmpty()
                && StructureLiveView.needsWholePicture(this.liveViewPictureHolders, watching);
        if (wholePicture) {
            // Recorded here, beside the question itself, and not where the pass that answers it starts: an answer this
            // end never writes down is asked again on the next pass, and a pass that asks twice for the same audience
            // streams the whole structure for ever. Nothing - no branch, no early exit - may sit between the two, which
            // is what this block's own guard pins. The record is pruned by the question above, so it is this list and
            // only this list that is bounded by who is watching now.
            this.liveViewPictureHolders.addAll(watching);
        }
        if (watchers.isEmpty()) {
            // Nobody is looking: forget the sample, so the next viewer gets a full picture rather than a diff against
            // a state nobody ever saw.
            this.lastLiveSample = null;
            this.liveResend = null;
            this.liveViewPictureHolders.clear();
            return;
        }
        int[] now = sample(pocket, plot);
        if (this.lastLiveSample == null) {
            // Nothing has ever been sent about this structure, so the first thing to send is the structure itself.
            this.lastLiveSample = now;
        }
        // While something is still changing, keep looking every tick - for as many ticks as this volume can afford.
        int[] changed = StructureLiveView.changed(this.lastLiveSample, now);
        if (changed.length > 0) {
            this.armLiveViewBurst(gameTime);
        }
        if (wholePicture) {
            // A whole picture starts at the first batch, even when a pass is already running: a player who walked in
            // halfway through one would otherwise never be sent the cells it had already passed, and the client would
            // draw the capture-time block there for as long as the structure stands (client.StructurePreviewGeometry).
            // An observer that is already up to date pays one more pass, which is bounded by the structure's own size.
            // A whole picture is a picture: the run is over the preview's cells, not the structure's.
            this.liveResend = new StructureLiveView.Resend(this.preview.cellCount());
        }
        // From here on the cells this pass speaks about are the preview's: a coarsened picture has fewer of them, and
        // which structure cell a changed one stands for is a question only this end can answer (see the javadoc above).
        int[] changedPreview = previewCellsOf(changed);
        if (this.liveResend == null && changedPreview.length == 0) {
            return;
        }
        // A scan of a structure does not fit in one packet, and the client's resend handler empties its view before
        // filling it - so a resend is a stream with a cursor, and it says when it starts. Handing over only the first
        // batch and recording the whole sample as sent would leave the cells above one packet stuck at capture time.
        boolean restart = this.liveResend != null && this.liveResend.first();
        int[] batch = this.liveResend != null ? this.liveResend.next()
                : StructureLiveView.firstBatch(changedPreview);
        if (this.liveResend != null && this.liveResend.done()) {
            this.liveResend = null;
        }
        int[] ids = new int[batch.length];
        for (int i = 0; i < batch.length; i++) {
            ids[i] = representativeId(now, batch[i]);
        }
        // The payload's position is this block entity's own and not the drawn one: it is the key the client's
        // renderer looks the cells up by - see the javadoc above.
        RCNetwork.StructureLivePayload payload =
                new RCNetwork.StructureLivePayload(this.worldPosition, batch, ids, restart);
        for (ServerPlayer player : watchers) {
            PacketDistributor.sendToPlayer(player, payload);
        }
        sendMoves(watchers);
        if (StructureLiveView.remaining(changedPreview) > 0 && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] live view of {} is behind by {} cell(s); the next interval "
                    + "carries them", this.worldPosition.toShortString(), StructureLiveView.remaining(changedPreview));
        }
        // The log says which cells moved, not only how many - "the head is gone and does not come back" and "the head
        // was never sent" are otherwise the same line. Throttled to one line a second.
        if (RCConfig.debugLog() && gameTime - this.lastLiveLog >= LIVE_VIEW_LOG_TICKS) {
            this.lastLiveLog = gameTime;
            RCConfig.LOGGER.info("[redstonecircuit] live view of {} sent {} cell(s){}: {}",
                    this.worldPosition.toShortString(), batch.length,
                    restart ? " (a whole picture, first packet)" : "", describe(batch, ids));
        }
        this.lastLiveSample = claimSent(now, batch);
    }

    /** The in-place form of {@link #sendLiveView(ServerLevel, ServerLevel, BlockPos)}: drawn where it stands. */
    void sendLiveView(ServerLevel server, ServerLevel pocket) {
        sendLiveView(server, pocket, this.getBlockPos());
    }

    /**
     * Sends the cells that were moving in this sample, so the client can draw them travelling instead of jumping: what
     * each one is, and the cell it is coming from.
     *
     * <p>One packet of its own rather than part of the live view, because a move is not a state: it is the one thing a
     * diff of block ids cannot say, and keeping it out of the live payload leaves that packet's shape unchanged.
     */
    private void sendMoves(List<ServerPlayer> watchers) {
        if (this.sampleMoves.isEmpty()) {
            return;
        }
        int count = Math.min(this.sampleMoves.size(), StructureLiveView.MAX_MOVES_PER_PACKET);
        int[] indices = new int[count];
        int[] states = new int[count];
        int[] from = new int[count];
        for (int i = 0; i < count; i++) {
            int[] move = this.sampleMoves.get(i);
            indices[i] = move[0];
            states[i] = move[1];
            from[i] = move[2];
        }
        RCNetwork.StructureMovePayload payload =
                new RCNetwork.StructureMovePayload(this.worldPosition, indices, states, from);
        for (ServerPlayer player : watchers) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** A packet's cells as "which cell of the picture, which block", in the preview's own coordinates. */
    private String describe(int[] batch, int[] ids) {
        Vec3i size = this.preview.size();
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < batch.length && i < LIVE_VIEW_LOG_CELLS; i++) {
            int index = batch[i];
            int x = index % size.getX();
            int z = index / size.getX() % size.getZ();
            int y = index / (size.getX() * size.getZ());
            if (i > 0) {
                text.append(", ");
            }
            text.append('(').append(x).append(',').append(y).append(',').append(z).append(")=")
                    .append(BuiltInRegistries.BLOCK.getKey(Block.stateById(ids[i]).getBlock()).getPath());
        }
        if (batch.length > LIVE_VIEW_LOG_CELLS) {
            text.append(", ...");
        }
        return text.toString();
    }

    /**
     * The preview cells a pass speaks about, given the structure cells that changed: once each, in the order they were
     * met.
     *
     * <p>At one cell per block the two numberings are the same and the list is handed back as it is. Coarser, several
     * changed cells fall in one preview cell and it may only be described once - the picture has one cell there, not one
     * per cell behind it - and a changed cell can be attributed to a preview cell that is nowhere near it in the array,
     * so the result is deduplicated rather than sliced.
     */
    private int[] previewCellsOf(int[] changed) {
        if (this.preview.scale() <= 1) {
            return changed;
        }
        java.util.LinkedHashSet<Integer> cells = new java.util.LinkedHashSet<>();
        for (int raw : changed) {
            cells.add(StructurePreviewCells.previewIndex(raw, this.size, this.preview.scale(), this.preview.size()));
        }
        int[] previews = new int[cells.size()];
        int at = 0;
        for (int cell : cells) {
            previews[at++] = cell;
        }
        return previews;
    }

    /**
     * What one cell of the picture is now: the block the coarsening rule picks out of the {@code scale}³ structure cells
     * behind it, or the air id when it holds nothing.
     *
     * <p>Read from the sample this pass just took rather than from the pocket again: the sample is the pocket's state
     * with a moving piston's placeholder already stood in for (see {@link #sample}), and reading the pocket a second
     * time would report the placeholder as a hole. At one cell per block the answer is the cell's own state, and this
     * returns it unchanged - the full-detail case is not re-decided, only the coarsened one.
     */
    private int representativeId(int[] now, int previewCell) {
        if (this.preview.scale() <= 1) {
            return now[previewCell];
        }
        Vec3i previewSize = this.preview.size();
        int px = previewCell % previewSize.getX();
        int pz = previewCell / previewSize.getX() % previewSize.getZ();
        int py = previewCell / (previewSize.getX() * previewSize.getZ());
        Vec3i raw = this.size;
        return StructurePreviewCells.representative(raw,
                (x, y, z) -> now[StructureLiveView.index(x, y, z, raw)],
                StructureBlockEntity::isEmptyState,
                airId(),
                px, py, pz, this.preview.scale(),
                StructureBlockEntity::rankById);
    }

    /**
     * Which of two state ids to draw when a coarsened cell holds as many of one block as of the other: the block whose
     * name comes first, never the smaller id.
     *
     * <p>A state id is a numbering this save's registry happens to have, and a capture votes over palette indices
     * instead; comparing the two orders against each other is how a picture on the ground and the one this server sent
     * a moment later came out 120 cells apart, every one of them a log where a plank belonged - the extra ring that
     * grew around every plank panel of a placed structure. The name is the one key both numberings produce, and it is
     * read from the registry only when two ids tie.
     */
    private static int rankById(int left, int right) {
        return nameOf(left).compareTo(nameOf(right));
    }

    /** The name a state id stands for, or nothing for an id this registry does not have. */
    private static String nameOf(int id) {
        BlockState state = Block.stateById(id);
        return state == null ? "" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /** The state id a picture cell that holds nothing is sent as - resolved here so no class load touches a registry. */
    private static int airId() {
        return Block.getId(Blocks.AIR.defaultBlockState());
    }

    /** Whether a block state id names nothing: what the coarsening rule counts as an empty cell. */
    private static boolean isEmptyState(int id) {
        BlockState state = Block.stateById(id);
        return state == null || state.isAir();
    }

    /**
     * What a cell that is in the middle of a move is moving, and which way it is coming from.
     *
     * <p>A piston's own move takes two ticks and the live view carries two <em>states</em>, so without this the arm and
     * the block it pushes jump a whole cell. Vanilla looks smooth because the moving block entity is drawn between the
     * cells, and the pocket's block entities are server-side, so the client cannot know this on its own.
     *
     * @return {@code {moved block id, the packed cell the move comes from}}, or null when that cell is not moving
     */
    @Nullable
    public static int[] movingRecord(ServerLevel pocket, BlockPos cell) {
        if (!(pocket.getBlockEntity(cell) instanceof PistonMovingBlockEntity moving)) {
            return null;
        }
        BlockState moved = moving.getMovedState();
        if (moved.isAir() || moved.is(Blocks.MOVING_PISTON)) {
            return null;
        }
        // Vanilla renders the moving block at this offset, which points from the cell back towards where the move
        // started: its sign is the way the block is travelling, and one cell along it is the cell the block left.
        // Asking for the offset rather than a direction and a flag is what makes this the same code path for both
        // halves of a piston's cycle - a push and a pull look identical from here.
        int dx = (int) Math.signum(moving.getXOff(0.0F));
        int dy = (int) Math.signum(moving.getYOff(0.0F));
        int dz = (int) Math.signum(moving.getZOff(0.0F));
        if (dx == 0 && dy == 0 && dz == 0) {
            // The move has arrived: there is nothing left to slide, and the cell's own state says what is there.
            return null;
        }
        return new int[] { Block.getId(moved), StructureLiveView.packFrom(dx, dy, dz) };
    }

    /** The watchdog's own pass over this structure's plot: see {@link #finishStuckMoves}. */
    private void watchStuckMoves(ServerLevel pocket, long gameTime) {
        if (this.size == null) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        int count = StructureLiveView.count(this.size);
        if (this.stuckPlaceholderSince.length != count) {
            this.stuckPlaceholderSince = new long[count];
        }
        finishStuckMoves(pocket, plot.origin(), this.size, this.stuckPlaceholderSince, gameTime);
    }

    /**
     * <b>Finishes every moving-piston placeholder that has stood for {@link #STUCK_PLACEHOLDER_TICKS} or longer</b>.
     *
     * <p>What the cell should hold is what its own block entity says it was moving - exactly what vanilla's
     * {@code finish()} writes - and air when there is no block entity left to ask. Static and separate from the block
     * entity so a test can drive it directly against a hand-made placeholder.
     *
     * @param since per cell, the game time a placeholder was first seen there (0 = not one); <b>updated in place</b>
     * @return how many cells were finished
     */
    public static int finishStuckMoves(ServerLevel pocket, BlockPos origin, Vec3i size, long[] since, long gameTime) {
        int finished = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int at = StructureLiveView.index(x, y, z, size);
                    BlockPos cell = origin.offset(x, y, z);
                    if (!pocket.getBlockState(cell).is(Blocks.MOVING_PISTON)) {
                        since[at] = 0;
                        continue;
                    }
                    if (since[at] == 0) {
                        since[at] = gameTime;
                        continue;
                    }
                    long stood = gameTime - since[at];
                    if (stood < STUCK_PLACEHOLDER_TICKS) {
                        continue;
                    }
                    since[at] = 0;
                    BlockState moved = pocket.getBlockEntity(cell) instanceof PistonMovingBlockEntity moving
                            ? moving.getMovedState()
                            : Blocks.AIR.defaultBlockState();
                    if (moved.is(Blocks.MOVING_PISTON)) {
                        moved = Blocks.AIR.defaultBlockState();
                    }
                    pocket.setBlock(cell, moved, 3);
                    finished++;
                    if (RCConfig.debugLog()) {
                        // Why it stood: a placeholder is finished by its own block entity, and a block entity is only
                        // ticked once its chunk is, so the answer is usually in these two flags.
                        boolean blocks = pocket.shouldTickBlocksAt(
                                net.minecraft.world.level.ChunkPos.asLong(cell.getX() >> 4, cell.getZ() >> 4));
                        boolean entities = pocket.isPositionEntityTicking(cell);
                        RCConfig.LOGGER.info("[redstonecircuit] pocket: finished a moving-piston placeholder that had "
                                        + "stood for {} tick(s) at {} of the plot at {}: it was moving {} (that "
                                        + "chunk: blockTicking={}, entityTicking={})",
                                stood, cell.toShortString(), origin.toShortString(),
                                BuiltInRegistries.BLOCK.getKey(moved.getBlock()).getPath(), blocks, entities);
                    }
                }
            }
        }
        return finished;
    }

    /**
     * The sample to remember: the new values for the cells that were sent, and the <b>old</b> ones for the cells a batch
     * could not carry, so a later interval reports the changed ones again rather than dropping them for ever. The cells
     * a <em>resend</em> could not fit are carried by the resend's own cursor instead, which does not care whether they
     * changed.
     */
    private int[] claimSent(int[] now, int[] sent) {
        if (sent.length == this.preview.cellCount() || this.lastLiveSample == now) {
            // The packet carried the whole picture, or there is no earlier sample to merge with - which is the pass that
            // recorded this very sample as the one to compare against. Remembering the sample itself says exactly the
            // right thing in both: every cell either was sent, or is still the value it was.
            return now;
        }
        if (this.preview.scale() <= 1) {
            // One cell of the picture is one structure cell, so the batch is a list of structure cells already.
            return StructureLiveView.remember(now, sent, this.lastLiveSample);
        }
        // Coarser, a sent picture cell has said everything there is to say about all scale³ of its cells: leaving any of
        // them unclaimed would report the same picture cell as changed on every pass, for ever.
        StructurePreviewCells.claim(now, this.lastLiveSample, sent, this.size, this.preview.size(),
                this.preview.scale());
        return this.lastLiveSample;
    }

    /**
     * The structure's cells as block ids, in {@link StructureLiveView#index}'s order, written into buffers this block
     * entity keeps - see {@link #liveSampleBuffers}.
     */
    private int[] sample(ServerLevel pocket, PocketSpace.Plot plot) {
        int count = StructureLiveView.count(this.size);
        if (this.liveWorldBuffer.length != count) {
            this.liveWorldBuffer = new int[count];
        }
        int[] believed = sampleBuffer(count);
        int[] ids = this.liveWorldBuffer;
        this.sampleMoves.clear();
        for (int y = 0; y < this.size.getY(); y++) {
            for (int z = 0; z < this.size.getZ(); z++) {
                for (int x = 0; x < this.size.getX(); x++) {
                    BlockPos cell = plot.origin().offset(x, y, z);
                    BlockState sampled = pocket.getBlockState(cell);
                    int at = StructureLiveView.index(x, y, z, this.size);
                    // A moving_piston placeholder is a block that renders as nothing, so reporting it would blink the
                    // piston out of existence; the last real state is what the miniature should show, so the
                    // placeholder counts as no change at all (see StructureLiveView#believe).
                    ids[at] = Block.getId(sampled);
                    // And the cell is <em>moving</em>, which a state cannot say. One record per moving cell, so the
                    // client can draw the slide vanilla draws - see {@link #movingRecord}.
                    if (sampled.is(Blocks.MOVING_PISTON)) {
                        int[] moving = movingRecord(pocket, cell);
                        if (moving != null) {
                            this.sampleMoves.add(new int[] { at, moving[0], moving[1] });
                        }
                    }
                }
            }
        }
        // The placeholder rule lives in StructureLiveView#believe. Reporting the placeholder itself is what put a hole
        // where the piston is.
        StructureLiveView.believeInto(ids, Block.getId(Blocks.MOVING_PISTON.defaultBlockState()),
                this.lastLiveSample, believed);
        return believed;
    }
    /**
     * The indicator: the brightest thing the structure is showing inside, written into the block's light property.
     *
     * <p>The watched cells are therefore the structure's six face-centre cells one step in from each face - the shell a
     * player would look at - and of each the brighter of its two visible facts: the redstone power in it and the light
     * it gives off. The result is written into the light property and nothing else: no neighbour update, no
     * {@code getSignal} answer, which stays zero for ever.
     */
    void updateIndicator(ServerLevel pocket) {
        Level level = this.getLevel();
        if (level == null || this.size == null) {
            return;
        }
        PocketSpace.Plot plot = PocketSpace.of(pocket).plots().get(this.plotIndex);
        if (plot == null) {
            return;
        }
        int lit = 0;
        for (BlockPos local : StructureIndicator.watchedCells(this.size, this.tableCells())) {
            BlockPos cell = plot.origin().offset(local);
            int power = level.getBlockState(cell).getSignal(level, cell, Direction.UP);
            power = Math.max(power, pocket.getBrightness(LightLayer.BLOCK, cell));
            lit = Math.max(lit, Math.min(15, power));
        // A lit structure is at least half brightness: the exact level still shows above that, and "something inside is
        // happening" becomes visible from across a room.
        if (lit > 0) {
            lit = Math.max(7, lit);
        }
        }
        BlockState state = this.getBlockState();
        if (!(state.getBlock() instanceof StructureBlock block) || state.getValue(StructureBlock.LIGHT) == lit) {
            return;
        }
        level.setBlock(this.getBlockPos(), state.setValue(StructureBlock.LIGHT, lit), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (this.structureId != null) {
            UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, this.structureId).result()
                    .ifPresent(encoded -> tag.put("structure", encoded));
        }
        tag.putString("name", this.name);
        Vec3i.CODEC.encodeStart(NbtOps.INSTANCE, this.size).result()
                .ifPresent(encoded -> tag.put("size", encoded));
        tag.putString("io_mode", this.ioMode.getSerializedName());
        tag.putInt("nesting_depth", this.nestingDepth);
        tag.putInt("plot", this.plotIndex);
        putFaces(tag);
        putManual(tag);
        putOutput(tag);
        putPreview(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.structureId = tag.contains("structure")
                ? UUIDUtil.CODEC.parse(NbtOps.INSTANCE, tag.get("structure")).result().orElse(null)
                : null;
        this.name = tag.contains("name") ? tag.getString("name") : PayloadHeader.DEFAULT_NAME;
        this.size = tag.contains("size")
                ? Vec3i.CODEC.parse(NbtOps.INSTANCE, tag.get("size")).result().orElse(new Vec3i(1, 1, 1))
                : new Vec3i(1, 1, 1);
        forgetLiveView();
        this.ioMode = readIoMode(tag.getString("io_mode"));
        this.nestingDepth = tag.getInt("nesting_depth");
        this.plotIndex = tag.contains("plot") ? tag.getInt("plot") : -1;
        this.faces = readFaces(tag);
        this.manual = readManual(tag);
        this.output = readOutput(tag);
        this.preview = tag.contains("preview")
                ? StructurePreview.CODEC.parse(NbtOps.INSTANCE, tag.get("preview")).result().orElse(null)
                : null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        if (this.structureId != null) {
            UUIDUtil.CODEC.encodeStart(NbtOps.INSTANCE, this.structureId).result()
                    .ifPresent(encoded -> tag.put("structure", encoded));
        }
        tag.putString("name", this.name);
        Vec3i.CODEC.encodeStart(NbtOps.INSTANCE, this.size).result()
                .ifPresent(encoded -> tag.put("size", encoded));
        tag.putString("io_mode", this.ioMode.getSerializedName());
        tag.putInt("nesting_depth", this.nestingDepth);
        putFaces(tag);
        putManual(tag);
        putOutput(tag);
        putPreview(tag);
        return tag;
    }

    /**
     * Mode B's panel, in the payload's own format.
     *
     * <p>In the update tag as well as in the save, and for the manual mode it is the <b>essential</b> half: a
     * client cannot poll a key that does not exist, and the panel is where it learns which key drives which
     * entry. A structure whose panel never reached its client is a structure with no controls.
     */
    private void putManual(CompoundTag tag) {
        if (this.manual.isEmpty()) {
            return;
        }
        ActivationTable.ManualEntry.CODEC.listOf().encodeStart(NbtOps.INSTANCE, this.manual).result()
                .ifPresent(encoded -> tag.put("manual", encoded));
    }

    private static List<ActivationTable.ManualEntry> readManual(CompoundTag tag) {
        return tag.contains("manual")
                ? ActivationTable.ManualEntry.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("manual"))
                        .result().orElse(List.of())
                : List.of();
    }

    /**
     * The output table, in the payload's own format.
     *
     * <p>Saved and synced like the other two tables. Synced matters even though only the server answers a redstone
     * query: the client draws the block, and a screen that shows what a structure is set to answer with has to be
     * able to read the table it was placed with.
     */
    private void putOutput(CompoundTag tag) {
        if (this.output.isEmpty()) {
            return;
        }
        ActivationTable.OUTPUT_CODEC.encodeStart(NbtOps.INSTANCE, this.output).result()
                .ifPresent(encoded -> tag.put("output", encoded));
    }

    private static List<ActivationTable.OutputEntry> readOutput(CompoundTag tag) {
        return tag.contains("output")
                ? ActivationTable.OUTPUT_CODEC.parse(NbtOps.INSTANCE, tag.get("output"))
                        .result().orElse(List.of())
                : List.of();
    }

    /**
     * The preview, in the payload's own format.
     *
     * <p>Saved as well as synced, and both halves matter: without it in the save a server restart would leave
     * every placed structure a block that draws nothing until it is captured again, and without it in the
     * update tag a client would have no picture to draw at all.
     */
    private void putPreview(CompoundTag tag) {
        if (this.preview == null) {
            return;
        }
        StructurePreview.CODEC.encodeStart(NbtOps.INSTANCE, this.preview).result()
                .ifPresent(encoded -> tag.put("preview", encoded));
    }

    /**
     * The activation tables, in the payload's own format.
     *
     * <p>One codec for the controller, the payload and the product, so a table cannot mean one thing in front of a
     * structure and another inside it. It also travels in the sync tag: a face table is only a handful of cells.
     */
    private void putFaces(CompoundTag tag) {
        if (this.faces.isEmpty()) {
            return;
        }
        ActivationTable.TABLE_CODEC.encodeStart(NbtOps.INSTANCE, this.faces).result()
                .ifPresent(encoded -> tag.put("faces", encoded));
    }

    private static Map<Direction, List<ActivationTable.FaceEntry>> readFaces(CompoundTag tag) {
        return tag.contains("faces")
                ? ActivationTable.TABLE_CODEC.parse(NbtOps.INSTANCE, tag.get("faces")).result()
                        .orElse(Map.of())
                : Map.of();
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static IoMode readIoMode(String name) {
        for (IoMode mode : IoMode.values()) {
            if (mode.getSerializedName().equals(name)) {
                return mode;
            }
        }
        return IoMode.ACTIVATION_FACES;
    }
}
