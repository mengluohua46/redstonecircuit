package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.structure.NestedPreviews;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewLod;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreviewCells;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView;

/**
 * A preview as something drawable, and the one transform that puts it in a block-sized box.
 *
 * <p>Block and item drawing are two renderer entry points with different origins, so the transform, the cell
 * loop and the resolved states behind them live here, cached because a structure's mesh is shared by every place
 * it appears: under {@code preview.hash()} for the blueprint, and under the live view's revision where the cells
 * can still change.
 *
 * <p>The transform: to the box's centre on its floor, scaled by {@code 1 / (longest + 0.5)} so the structure
 * fits inside one block with a margin, then centred. Cells are drawn by the game's own dispatcher at full
 * brightness, since the miniature sits inside a block or an item.
 *
 * <p>That walk is the expensive half of a draw, and the same picture is drawn several times a frame, so it is
 * emitted once into uploaded vertex data and redrawn from there ({@link StructurePreviewMesh}); what remains of a
 * draw is the caller's pose and one upload per render type. The cell-by-cell walk is still the whole of the picture
 * when the cache cannot serve it, which is what makes the switch safe to turn off.
 */
@Environment(EnvType.CLIENT)
final class StructurePreviewGeometry {

    /** How many previews' worth of resolved geometry to keep; several structures usually share one. */
    private static final int CACHE_LIMIT = 64;

    private static final Map<CellKey, List<Cell>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CellKey, List<Cell>> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private static boolean reportedUnbalanced;

    /**
     * What a resolved cell list is cached under.
     *
     * <p>A live view is a second source of cells for the same picture, so "the same preview" is no longer the same
     * miniature: the place it is live at, and how many changes that view has seen, are part of its identity.
     * Without {@code revision} every frame of a running circuit would resolve the whole miniature again, which is
     * what made a placed structure cost more than the same structure in the hand.
     */
    private record CellKey(int hash, boolean live, long livePos, int revision, Detail detail) {
    }

    /**
     * How much of a picture a place is drawn with: a placed structure is the structure, an icon is a photograph of it.
     *
     * <p>A hand, a slot and the model hand in a screen are all drawn at icon size, and all three used to pay for the
     * full picture - the same 3 MB of vertices per draw as the block on the ground. The distinction travels with the
     * resolved cells and with the emitted vertices, because the two must never be confused: an icon drawn from the full
     * picture is what the cache would hand a placed block if they shared an entry.
     */
    enum Detail {
        /** Every cell of the preview: what a placed structure is. */
        FULL,
        /** Half the resolution on every axis: what a structure is wherever it is drawn at icon size. */
        ICON
    }

    /**
     * One cell of the miniature: which block, where in the structure it stands, and which of its six faces the
     * picture itself hides ({@code hidden}, the bits of {@link Direction#ordinal()}).
     *
     * <p>The hidden faces are worked out once, with the cells, and carried here because the emission must not ask: a
     * cell-by-cell draw walks the whole picture every frame when the cache cannot serve it, and a six-way neighbour
     * question per cell per frame is the cost this removes. Zero means "nothing is hidden", which is what a cell drawn
     * outside a resolved picture - a moving one, a nested one - answers.
     */
    record Cell(BlockState state, int x, int y, int z, @Nullable StructurePreview nested, List<Cell> inside,
                int hidden) {

        /**
         * A cell may hold a structure instead of a block: a product block answers {@code RenderShape.INVISIBLE}
         * because its miniature <em>is</em> its look, and one standing inside a structure has no client-side
         * block entity to draw it, so the outer miniature carries the inner structure's own picture here.
         */
        Cell(BlockState state, int x, int y, int z) {
            this(state, x, y, z, null, List.of(), 0);
        }

        Cell(BlockState state, int x, int y, int z, @Nullable StructurePreview nested, List<Cell> inside) {
            this(state, x, y, z, nested, inside, 0);
        }

        /** The same cell, with this many of its faces hidden by the cells standing beside it. */
        Cell withHidden(int mask) {
            return new Cell(this.state, this.x, this.y, this.z, this.nested, this.inside, mask);
        }
    }

    /** How deep a miniature may nest another one; the capture limit is the real bound, this one is a seat belt. */
    private static final int MAX_NESTED_DEPTH = 3;

    /**
     * The stand-in for a structure whose own picture is not here - an older payload, or one whose nested
     * previews did not fit the budget. The vanilla structure block is what "there is a structure in this cell"
     * looks like; leaving a hole is not an option.
     */
    private static final BlockState NESTED_STAND_IN = net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK
            .defaultBlockState();

    /**
     * The box the cells of {@code detail} are numbered in: a preview's own size, or half of it for an icon.
     *
     * <p>The picture must fill the box it is drawn in at <b>every</b> resolution - an icon is the same structure,
     * smaller in detail and not in size - so the transform is derived from the cells that are actually drawn and never
     * from the preview they came from. Getting this wrong draws an icon at half size in the corner of the block.
     */
    static Vec3i drawnSize(StructurePreview preview, Detail detail) {
        return detail == Detail.ICON ? PreviewLod.half(preview.size()) : preview.size();
    }

    /**
     * The cells to draw for a preview: its non-air cells, with the palette resolved to block states. Empty
     * cells are dropped here rather than drawn as air, so the drawing loop has nothing to skip.
     */
    static List<Cell> cells(Level level, StructurePreview preview) {
        return cells(level, preview, null, null, 0, Detail.FULL);
    }

    /**
     * The detail a place actually draws at, after the switch: the one answer a caller may work from.
     *
     * <p>Read once, by the caller, and used for both things it decides - the cells and the box they are numbered in.
     * Two places resolving it separately is how an icon came out at half size: the cells obeyed the switch, the
     * transform did not, and the picture was measured in one box and drawn in another.
     */
    static Detail effective(Detail requested) {
        return requested == Detail.ICON && RCConfig.structurePreviewIconDetail() ? Detail.ICON : Detail.FULL;
    }

    /**
     * The cells to draw for a picture that is drawn at {@code detail}, with no live view: the hand, the icon.
     *
     * <p>{@code detail} is what the caller resolved with {@link #effective}: this answers that picture and no other, so
     * the cells and the box they are drawn in can never disagree.
     */
    static List<Cell> cells(Level level, StructurePreview preview, Detail detail) {
        return cells(level, preview, null, null, 0, detail);
    }

    /**
     * The same, with the cells this client has been told about overriding the captured ones. The live view
     * arrives as a diff ({@code StructureLivePayload}) and is applied here, at the one place that turns a
     * preview into geometry - only here, so the preview itself stays the blueprint and what can be released,
     * saved or shown in the hand is untouched.
     *
     * <p>A cell of the view is a cell of the <b>preview</b>: the server samples the pocket and answers for one preview
     * cell at a time, so a coarsened picture is looked up exactly like a full-detail one (see
     * {@link StructurePreviewCells#representative}). {@code structureSize} is still what a <b>move</b> is read through -
     * a piston travels structure cells, and which preview cell that lands in is decided here, once, for the slide. The
     * hand and the icon have no live view and pass null.
     *
     * <p>What is live is cached like everything else, under the view's revision: a placed structure whose
     * redstone is quiet must cost exactly what the same structure in the hand costs, and only a revision that
     * actually moved may resolve the cells again.
     */
    static List<Cell> cells(Level level, StructurePreview preview, @Nullable BlockPos liveAt,
                            @Nullable Vec3i structureSize) {
        return cells(level, preview, liveAt, structureSize, 0, Detail.FULL);
    }

    private static List<Cell> cells(Level level, StructurePreview preview, @Nullable BlockPos liveAt,
                                    @Nullable Vec3i structureSize, int depth, Detail detail) {
        // The live view is the pocket's own cells, so it belongs to the outermost miniature only: a structure
        // nested inside this one is drawn from its own blueprint.
        boolean live = depth == 0 && liveAt != null && StructureLiveViews.has(liveAt);
        CellKey key = new CellKey(preview.hash(), live, live ? liveAt.asLong() : 0L,
                live ? StructureLiveViews.revisionAt(liveAt) : 0, detail);
        List<Cell> cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        // A cell a block is moving away from is drawn by the slide itself, so its standing copy is skipped here.
        Slides moving = live ? slides(StructureLiveViews.movesAt(liveAt), structureSize, preview) : null;
        Set<Integer> leaving = moving == null ? Set.of() : moving.leaving();
        BlockState[] palette = resolvePalette(level, preview);
        // Which palette entries carry a nested structure's own picture - see NestedPreviews. One entry per
        // nested cell, so this is exact rather than a guess about which cell the entry was made for.
        StructurePreview[] nestedInEntry = new StructurePreview[palette.length];
        for (int i = 0; i < palette.length; i++) {
            nestedInEntry[i] = NestedPreviews.inside(preview.palette().get(i));
        }
        List<Cell> cells = new ArrayList<>(preview.cellCount());
        for (int y = 0; y < preview.size().getY(); y++) {
            for (int z = 0; z < preview.size().getZ(); z++) {
                for (int x = 0; x < preview.size().getX(); x++) {
                    int index = StructureLiveView.index(x, y, z, preview.size());
                    if (leaving.contains(index)) {
                        continue;
                    }
                    int paletteIndex = preview.cells().get(preview.index(x, y, z));
                    BlockState state = live ? stateOf(liveAt, index) : null;
                    if (state == null) {
                        if (paletteIndex < 0 || paletteIndex >= palette.length) {
                            continue;
                        }
                        state = palette[paletteIndex];
                    }
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.getBlock() == RCRegistry.STRUCTURE) {
                        StructurePreview inner = paletteIndex >= 0 && paletteIndex < nestedInEntry.length
                                ? nestedInEntry[paletteIndex] : null;
                        if (inner != null && inner.cellCount() > 0 && depth < MAX_NESTED_DEPTH) {
                            cells.add(new Cell(state, x, y, z, inner,
                                    cells(level, inner, null, null, depth + 1, detail)));
                            continue;
                        }
                        // Its own picture is not here: draw what "there is a structure in this cell" looks like.
                        cells.add(new Cell(NESTED_STAND_IN, x, y, z));
                        continue;
                    }
                    cells.add(new Cell(state, x, y, z));
                }
            }
        }
        List<Cell> resolved = List.copyOf(cull(cells, preview.size()));
        if (detail == Detail.ICON) {
            // The icon is a smaller photograph of the same resolved picture, and it is culled again at its own size: a
            // cell buried at half resolution is a cell nothing can see there.
            resolved = List.copyOf(cull(icon(resolved, preview.size()), PreviewLod.half(preview.size())));
        }
        remember(key, resolved);
        return resolved;
    }

    /**
     * The same picture at half the resolution, as cells: the ids are handed to {@link PreviewLod} and read back, so the
     * rule that decides what a coarsened cell looks like stays in one place.
     *
     * <p>A cell that holds another structure answers with that structure's block here: an icon of an icon is the
     * stand-in rather than a miniature of a miniature, which at this size is the same few pixels.
     *
     * <p>The grid is renumbered before it is handed on, so a tie at half resolution is decided by the same thing a
     * capture and a live view decide it by; see the comment on the renumbering below.
     */
    private static List<Cell> icon(List<Cell> cells, Vec3i size) {
        int[] states = new int[size.getX() * size.getY() * size.getZ()];
        for (Cell cell : cells) {
            states[StructurePreviewVoxels.index(cell.x(), cell.y(), cell.z(), size)] = Block.getId(cell.state());
        }
        // Handed over in the one order every caller of the rule can produce - what the blocks are called - and not as
        // state ids, because PreviewLod decides a tie by the number it is given: state ids put a log before a plank
        // where a capture's palette puts the plank first, which is how an icon grew the same extra ring the live view
        // did (see StructurePreviewCells.Rank).
        StructurePreviewCells.Numbering ranked =
                StructurePreviewCells.numbering(states, StructurePreviewGeometry::rankById);
        int[] ids = ranked.cells();
        Vec3i half = PreviewLod.half(size);
        int[] reduced = PreviewLod.half(ids, 0, size, half);
        List<Cell> icons = new ArrayList<>();
        for (int y = 0; y < half.getY(); y++) {
            for (int z = 0; z < half.getZ(); z++) {
                for (int x = 0; x < half.getX(); x++) {
                    int id = reduced[StructurePreviewVoxels.index(x, y, z, half)];
                    if (id != 0) {
                        icons.add(new Cell(Block.stateById(ranked.value(id)), x, y, z));
                    }
                }
            }
        }
        return icons;
    }

    /**
     * Which of two state ids an icon draws when half-resolution cells tie: the block whose name comes first, never the
     * smaller id; see {@link StructurePreviewCells.Rank}.
     */
    private static int rankById(int left, int right) {
        return nameOf(left).compareTo(nameOf(right));
    }

    /** The name a state id stands for, or nothing for an id this registry does not have. */
    private static String nameOf(int id) {
        BlockState state = Block.stateById(id);
        return state == null ? "" : BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    /**
     * Stores a resolved list, dropping this live view's older revisions on the way.
     *
     * <p>A structure whose redstone is running resolves a new list every time the server reports a change, and the
     * previous ones are unreachable the moment the revision moves: keeping them would hold one copy of the
     * miniature per tick, for a preview that can be tens of thousands of cells.
     */
    private static void remember(CellKey key, List<Cell> cells) {
        if (key.live()) {
            CACHE.keySet().removeIf(other -> other.live() && other.livePos() == key.livePos());
        }
        CACHE.put(key, cells);
    }

    /**
     * Drops the cells nothing can see: only a cell whose six neighbours all hide what is behind them <em>and</em>
     * render as a full cube is skipped.
     *
     * <p>Both halves are needed. {@code canOcclude} is vanilla's own answer to "does this hide the face behind it",
     * and air, glass, leaves, a slab, a stair, a fence, a torch and a wire all fail it; {@code isSolidRender} then
     * rules out the blocks that claim to occlude while leaving part of their cube empty. The level handed in is an
     * empty one on purpose - these are a picture of a structure, not the world's blocks, and the shape of a cell
     * must not depend on where the structure was built - and nothing outside the preview counts as a neighbour, so
     * a surface cell always survives. A hole in a miniature is the one outcome this may not produce.
     */
    private static List<Cell> cull(List<Cell> cells, Vec3i size) {
        boolean[] blocking = new boolean[size.getX() * size.getY() * size.getZ()];
        for (Cell cell : cells) {
            // A nested miniature is drawn smaller than its cell, so it hides nothing: counting it as solid would
            // drop a neighbour that is visible through the gap around it.
            if (cell.nested() == null) {
                blocking[StructurePreviewVoxels.index(cell.x(), cell.y(), cell.z(), size)] =
                        cell.state().canOcclude()
                                && cell.state().isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
            }
        }
        // The switch is handed to the rule rather than read by it, so "nothing is dropped" is arithmetic a test can
        // check (see StructurePreviewVoxels#kept).
        boolean[] kept = StructurePreviewVoxels.kept(size, blocking, RCConfig.structurePreviewCull());
        // What a buried cell used to save in full, a surface cell now saves in part: the same rule asked one direction
        // at a time, so a wall of one cell spends two faces out of six on the picture instead of six.
        boolean faces = RCConfig.structurePreviewFaces();
        List<Cell> visible = new ArrayList<>(cells.size());
        for (Cell cell : cells) {
            if (kept[StructurePreviewVoxels.index(cell.x(), cell.y(), cell.z(), size)]) {
                visible.add(faces
                        ? cell.withHidden(StructurePreviewVoxels.faces(cell.x(), cell.y(), cell.z(), size, blocking))
                        : cell);
            }
        }
        return visible;
    }

    /**
     * The live view's moves, seen at the preview's own coarseness: the ones that are visible, keyed by the preview cell
     * they arrive at, and the preview cells whose standing copy each of them replaces.
     *
     * <p>At one cell per block the two numberings are the same and a move is visible by definition. Coarser than that
     * a piston travels one structure cell and often <b>no</b> preview cell at all, and such a move has to be neither
     * slid nor hidden: sliding it would drag a block inside a cell that stands for nine of them, and hiding its source
     * would punch a hole where nothing moved.
     */
    private record Slides(Map<Integer, StructureLiveViews.Move> arriving, Set<Integer> leaving) {
    }

    /**
     * What one cell of the preview is doing now, or null when this client has not been told about it.
     *
     * <p>The index is the <b>preview's</b> own: the server samples the pocket and answers for one preview cell at a
     * time (see {@code StructurePreviewCells#representative}), so there is no attribution left to do here - a coarsened
     * preview is looked up exactly like a full-detail one, which is the only way the two can be guaranteed to agree.
     */
    @Nullable
    private static BlockState stateOf(BlockPos liveAt, int index) {
        return StructureLiveViews.stateAt(liveAt, index);
    }

    /**
     * What a live view's moves look like at the preview's coarseness, or null when nothing is moving visibly.
     *
     * <p>A move arrives at a preview cell and left another; both are read off the structure's own numbering, so a
     * coarsened preview attributes a piston to the cells a player can actually see move.
     */
    @Nullable
    private static Slides slides(@Nullable Map<Integer, StructureLiveViews.Move> moves,
                                 @Nullable Vec3i structureSize, StructurePreview preview) {
        if (moves == null || moves.isEmpty()) {
            return null;
        }
        // A live view with no structure size behind it can only be a scale-one one, where the two sizes are the same.
        Vec3i raw = structureSize != null ? structureSize : preview.size();
        Vec3i size = preview.size();
        Map<Integer, StructureLiveViews.Move> arriving = new LinkedHashMap<>();
        Set<Integer> leaving = new HashSet<>();
        for (Map.Entry<Integer, StructureLiveViews.Move> entry : moves.entrySet()) {
            int index = entry.getKey();
            int x = StructurePreviewCells.rawX(index, raw);
            int y = StructurePreviewCells.rawY(index, raw);
            int z = StructurePreviewCells.rawZ(index, raw);
            int from = entry.getValue().from();
            int sourceX = x + StructureLiveView.fromX(from);
            int sourceY = y + StructureLiveView.fromY(from);
            int sourceZ = z + StructureLiveView.fromZ(from);
            if (sourceX < 0 || sourceY < 0 || sourceZ < 0
                    || sourceX >= raw.getX() || sourceY >= raw.getY() || sourceZ >= raw.getZ()) {
                // A move that came from outside the structure has nowhere to slide from.
                continue;
            }
            int toX = StructurePreviewCells.previewCoordinate(x, preview.scale(), size.getX());
            int toY = StructurePreviewCells.previewCoordinate(y, preview.scale(), size.getY());
            int toZ = StructurePreviewCells.previewCoordinate(z, preview.scale(), size.getZ());
            int dx = StructurePreviewCells.previewCoordinate(sourceX, preview.scale(), size.getX()) - toX;
            int dy = StructurePreviewCells.previewCoordinate(sourceY, preview.scale(), size.getY()) - toY;
            int dz = StructurePreviewCells.previewCoordinate(sourceZ, preview.scale(), size.getZ()) - toZ;
            if (dx == 0 && dy == 0 && dz == 0) {
                // The move stays inside one preview cell: that is a change of what the cell shows, not a slide.
                continue;
            }
            arriving.put(StructureLiveView.index(toX, toY, toZ, size), new StructureLiveViews.Move(
                    entry.getValue().stateId(), StructureLiveView.packFrom(dx, dy, dz), entry.getValue().startTick()));
            leaving.add(StructureLiveView.index(toX - dx, toY - dy, toZ - dz, size));
        }
        return new Slides(arriving, leaving);
    }

    /** The preview's palette as block states; an entry this client cannot resolve becomes air. */
    private static BlockState[] resolvePalette(Level level, StructurePreview preview) {
        HolderGetter<Block> blocks = level.holderLookup(Registries.BLOCK);
        BlockState[] states = new BlockState[preview.palette().size()];
        for (int i = 0; i < states.length; i++) {
            BlockState state;
            try {
                state = NbtUtils.readBlockState(blocks, NestedPreviews.stateTag(preview.palette().get(i)));
            } catch (RuntimeException unknown) {
                // A structure built with a mod this client does not have: draw the rest of it.
                state = net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
            }
            states[i] = state;
        }
        return states;
    }

    /**
     * Draws {@code cells} as a miniature inside the unit cube the pose is already at the corner of, then the
     * cells in the middle of a move at the position they have reached: a state snapshot has two frames of a
     * piston's move, and drawing the moved block between the cells is what makes it look smooth.
     *
     * <p>The pose stack is always given back, and a pass that left it deeper than it found it is reported
     * once - a stage ending with a non-empty stack ends the game, so it is not cosmetic.
     *
     * <p>{@code liveAt} is where the picture is drawn from the world, and it is what the uploaded copy is keyed by as
     * well as the cells (see {@link #liveRevision}); the hand and the icon pass {@code null} and share the blueprint's
     * copy with every placed structure that has nothing live around it.
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, List<Cell> cells, StructurePreview preview,
                     BlockRenderDispatcher dispatcher, @Nullable Map<Integer, StructureLiveViews.Move> moves,
                     long nowTick, float partialTick, @Nullable Vec3i structureSize, @Nullable BlockPos liveAt,
                     String site, Detail detail) {
        PoseStack.Pose entry = pose.last();
        try {
            Slides slides = slides(moves, structureSize, preview);
            // Emitted once per picture and redrawn after that (see StructurePreviewMesh); false means the caller's
            // own picture was not uploaded and the cells are drawn here, one block model at a time.
            boolean uploaded = StructurePreviewMesh.draw(pose.last().pose(), preview, liveRevision(liveAt), cells,
                    dispatcher, detail);
            pose.pushPose();
            pose.translate(0.5, 0.0, 0.5);
            if (uploaded) {
                // A cell in the middle of a move is where it has got to THIS frame, so it cannot be part of an
                // uploaded picture: it is drawn on its own, through the same transform and into the caller's buffer.
                if (slides != null) {
                    drawInsideCube(pose, buffers, List.of(), preview, detail, dispatcher, slides, nowTick, partialTick);
                }
            } else {
                drawInsideCube(pose, buffers, cells, preview, detail, dispatcher, slides, nowTick, partialTick);
            }
        } finally {
            if (PoseStackGuard.restore(pose, entry) > 1 && !reportedUnbalanced) {
                reportedUnbalanced = true;
                RCConfig.LOGGER.warn("[redstonecircuit] structure preview: a draw pass left the pose stack "
                        + "deeper than it found it; the stack was restored, but that is a bug");
            }
        }
    }

    /**
     * The live view's revision for the picture drawn at {@code liveAt}, or {@code 0} when the blueprint is what is
     * drawn there.
     *
     * <p>It is asked here, from the same position {@link #cells} is given, so the key the cells are cached under and
     * the key the vertices are cached under are the same question answered the same way - a picture uploaded for one
     * revision and drawn for another would be the wrong blocks, in the right colours.
     */
    static int liveRevision(@Nullable BlockPos liveAt) {
        return liveAt != null && StructureLiveViews.has(liveAt) ? StructureLiveViews.revisionAt(liveAt) : 0;
    }

    /**
     * Emits the whole miniature into {@code sink} from the identity pose.
     *
     * <p>This is the same walk a placed block draws - including the half block that centres the picture, the scale
     * that fits it in a cube and every nested picture inside it - and nothing of the caller's pose: that is what
     * makes one emitted copy serve the block, the hand and the icon.
     */
    static void bake(MultiBufferSource sink, List<Cell> cells, StructurePreview preview, Detail detail,
                     BlockRenderDispatcher dispatcher) {
        PoseStack pose = new PoseStack();
        pose.pushPose();
        pose.translate(0.5, 0.0, 0.5);
        try {
            drawInsideCube(pose, sink, cells, preview, detail, dispatcher, null, 0L, 0.0F);
        } finally {
            pose.popPose();
        }
    }

    /**
     * The miniature itself, inside the unit cube the pose is already at the corner of. A nested miniature is
     * drawn by calling this again with the pose standing in that cell; the recentring belongs to the block the
     * miniature is drawn on, so it may not happen twice.
     */
    private static void drawInsideCube(PoseStack pose, MultiBufferSource buffers, List<Cell> cells,
                                       StructurePreview preview, Detail detail, BlockRenderDispatcher dispatcher,
                                       @Nullable Slides slides, long nowTick, float partialTick) {
        Vec3i size = drawnSize(preview, detail);
        float scale = StructurePreviewVoxels.miniatureScale(size);
        pose.pushPose();
        try {
            pose.scale(scale, scale, scale);
            pose.translate(-size.getX() / 2.0, 0.0, -size.getZ() / 2.0);
            for (Cell cell : cells) {
                pose.pushPose();
                pose.translate(cell.x(), cell.y(), cell.z());
                if (cell.nested() != null) {
                    // The nested picture is centred in ITS CELL, so it needs the half-cell the outer call
                    // applies to the block - without it the inner structure hangs half a cell towards -x/-z.
                    pose.translate(0.5, 0.0, 0.5);
                    drawInsideCube(pose, buffers, cell.inside(), cell.nested(), detail, dispatcher, null, nowTick,
                            partialTick);
                } else {
                    drawCell(pose, buffers, cell, dispatcher);
                }
                pose.popPose();
            }
            drawMoves(pose, buffers, preview, dispatcher, slides, nowTick, partialTick);
        } finally {
            pose.popPose();
        }
    }

    /**
     * One cell of the miniature: its whole block model, or only the faces of it that this picture leaves visible.
     *
     * <p>Everything that is not a plain model goes through vanilla's own draw unchanged - a fluid, an animated block, a
     * cell that is really another structure - and so does a cell with nothing hidden, which is every cell of the
     * cell-by-cell path that has no resolved picture behind it. This may not change what a cell looks like: it decides
     * which faces are not drawn, and a face is only skipped when the cell behind it is drawn as a full opaque cube
     * (see {@link StructurePreviewVoxels#faces}).
     *
     * <p>Emitted the way vanilla emits a model - the tint of the block, at the brightness the rest of the miniature is
     * drawn at, the model's own quads per direction - so a culled picture is the same picture with fewer faces in it,
     * not a different one. The render type is the state's own chunk layer, which is the one
     * {@code renderSingleBlock} above draws into: this loader has no per-model render type list, and a face drawn into
     * a different layer from the cell-by-cell path's would be a difference between a picture and its smaller copy.
     */
    private static void drawCell(PoseStack pose, MultiBufferSource buffers, Cell cell, BlockRenderDispatcher dispatcher) {
        BlockState state = cell.state();
        if (cell.hidden() == 0 || cell.nested() != null || state.getRenderShape() != RenderShape.MODEL) {
            dispatcher.renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            return;
        }
        BakedModel model = dispatcher.getBlockModel(state);
        int tint = Minecraft.getInstance().getBlockColors().getColor(state, null, null, 0);
        float red = (tint >> 16 & 0xFF) / 255.0F;
        float green = (tint >> 8 & 0xFF) / 255.0F;
        float blue = (tint & 0xFF) / 255.0F;
        VertexConsumer consumer = buffers.getBuffer(ItemBlockRenderTypes.getChunkRenderType(state));
        RandomSource random = RandomSource.create();
        for (Direction side : Direction.values()) {
            if ((cell.hidden() & (1 << side.ordinal())) != 0) {
                continue;
            }
            random.setSeed(42L);
            putQuads(pose, consumer, model.getQuads(state, side, random), red, green, blue);
        }
        // The quads no direction owns are always drawn: a model may say it has no side and still be visible from all
        // of them.
        random.setSeed(42L);
        putQuads(pose, consumer, model.getQuads(state, null, random), red, green, blue);
    }

    /** Vanilla's own per-quad emission, including its rule that only a tinted quad takes the block's colour. */
    private static void putQuads(PoseStack pose, VertexConsumer consumer, List<BakedQuad> quads,
                                 float red, float green, float blue) {
        for (BakedQuad quad : quads) {
            if (quad.isTinted()) {
                consumer.putBulkData(pose.last(), quad, Mth.clamp(red, 0.0F, 1.0F), Mth.clamp(green, 0.0F, 1.0F),
                        Mth.clamp(blue, 0.0F, 1.0F), 1.0F, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            } else {
                consumer.putBulkData(pose.last(), quad, 1.0F, 1.0F, 1.0F, 1.0F, LightTexture.FULL_BRIGHT,
                        OverlayTexture.NO_OVERLAY);
            }
        }
    }

    /**
     * Draws every cell that is on its way: at the cell it is arriving at, less however much of the way it still has
     * to come. The pose is already the miniature's, so the offsets are in cells like everything else here.
     */
    private static void drawMoves(PoseStack pose, MultiBufferSource buffers, StructurePreview preview,
                                  BlockRenderDispatcher dispatcher, @Nullable Slides slides, long nowTick,
                                  float partialTick) {
        if (slides == null || slides.arriving().isEmpty()) {
            return;
        }
        Vec3i size = preview.size();
        for (Map.Entry<Integer, StructureLiveViews.Move> entry : slides.arriving().entrySet()) {
            StructureLiveViews.Move move = entry.getValue();
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(move.stateId());
            if (state == null || state.isAir()) {
                continue;
            }
            float left = 1.0F - StructureLiveView.slideProgress(move.startTick(), nowTick, partialTick);
            if (left <= 0.0F) {
                continue;
            }
            int index = entry.getKey();
            int x = index % size.getX();
            int z = index / size.getX() % size.getZ();
            int y = index / (size.getX() * size.getZ());
            pose.pushPose();
            pose.translate(x + StructureLiveView.fromX(move.from()) * left,
                    y + StructureLiveView.fromY(move.from()) * left,
                    z + StructureLiveView.fromZ(move.from()) * left);
            dispatcher.renderSingleBlock(state, pose, buffers, LightTexture.FULL_BRIGHT,
                    OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    private StructurePreviewGeometry() {
    }
}
