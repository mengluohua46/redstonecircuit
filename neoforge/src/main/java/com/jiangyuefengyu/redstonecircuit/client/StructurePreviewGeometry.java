package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.structure.NestedPreviews;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A preview as something drawable, and the one transform that puts it in a block-sized box.
 *
 * <p>Block and item drawing are two renderer entry points with different origins, so the transform, the cell
 * loop and the resolved states behind them live here, cached once under {@code preview.hash()} because a
 * structure's mesh is shared by every place it appears.
 *
 * <p>The transform: to the box's centre on its floor, scaled by {@code 1 / (longest + 0.5)} so the structure
 * fits inside one block with a margin, then centred. Cells are drawn by the game's own dispatcher at full
 * brightness, since the miniature sits inside a block or an item.
 */
@OnlyIn(Dist.CLIENT)
final class StructurePreviewGeometry {

    /** How many previews' worth of resolved geometry to keep; several structures usually share one. */
    private static final int CACHE_LIMIT = 64;

    private static final Map<Integer, List<Cell>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, List<Cell>> eldest) {
            return size() > CACHE_LIMIT;
        }
    };

    private static boolean reportedUnbalanced;

    /** One cell of the miniature: which block, and where in the structure it stands. */
    record Cell(BlockState state, int x, int y, int z, @Nullable StructurePreview nested, List<Cell> inside) {

        /**
         * A cell may hold a structure instead of a block: a product block answers {@code RenderShape.INVISIBLE}
         * because its miniature <em>is</em> its look, and one standing inside a structure has no client-side
         * block entity to draw it, so the outer miniature carries the inner structure's own picture here.
         */
        Cell(BlockState state, int x, int y, int z) {
            this(state, x, y, z, null, List.of());
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
     * The cells to draw for a preview: its non-air cells, with the palette resolved to block states. Empty
     * cells are dropped here rather than drawn as air, so the drawing loop has nothing to skip.
     */
    static List<Cell> cells(Level level, StructurePreview preview) {
        return cells(level, preview, null);
    }

    /**
     * The same, with the cells this client has been told about overriding the captured ones. The live view
     * arrives as a diff ({@code StructureLivePayload}) and is applied here, at the one place that turns a
     * preview into geometry - only here, so the preview itself stays the blueprint and what can be released,
     * saved or shown in the hand is untouched. The cache is skipped while anything is live.
     */
    static List<Cell> cells(Level level, StructurePreview preview, @Nullable BlockPos liveAt) {
        return cells(level, preview, liveAt, 0);
    }

    private static List<Cell> cells(Level level, StructurePreview preview, @Nullable BlockPos liveAt, int depth) {
        // The live view is the pocket's own cells, so it belongs to the outermost miniature only: a structure
        // nested inside this one is drawn from its own blueprint.
        boolean live = depth == 0 && liveAt != null && StructureLiveViews.has(liveAt);
        List<Cell> cached = live ? null : CACHE.get(preview.hash());
        if (cached != null) {
            return cached;
        }
        // A cell a block is moving away from is drawn by the slide itself, so its standing copy is skipped here.
        Set<Integer> leaving = live ? leavingCells(liveAt, preview) : Set.of();
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
                    BlockState state = live ? StructureLiveViews.stateAt(liveAt, index) : null;
                    if (state == null) {
                        if (paletteIndex < 0 || paletteIndex >= palette.length) {
                            continue;
                        }
                        state = palette[paletteIndex];
                    }
                    if (state.isAir()) {
                        continue;
                    }
                    if (state.getBlock() == RCRegistry.STRUCTURE.get()) {
                        StructurePreview inner = paletteIndex >= 0 && paletteIndex < nestedInEntry.length
                                ? nestedInEntry[paletteIndex] : null;
                        if (inner != null && inner.cellCount() > 0 && depth < MAX_NESTED_DEPTH) {
                            cells.add(new Cell(state, x, y, z, inner, cells(level, inner, null, depth + 1)));
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
        List<Cell> resolved = List.copyOf(cells);
        if (!live) {
            CACHE.put(preview.hash(), resolved);
        }
        return resolved;
    }

    /** The cells this structure's blocks are moving away from, in the same numbering as {@link Cell}. */
    private static Set<Integer> leavingCells(@Nullable BlockPos liveAt, StructurePreview preview) {
        Map<Integer, StructureLiveViews.Move> moves = StructureLiveViews.movesAt(liveAt);
        if (moves == null || moves.isEmpty()) {
            return Set.of();
        }
        Set<Integer> leaving = new java.util.HashSet<>();
        Vec3i size = preview.size();
        for (Map.Entry<Integer, StructureLiveViews.Move> entry : moves.entrySet()) {
            int index = entry.getKey();
            int from = entry.getValue().from();
            int x = index % size.getX() + StructureLiveView.fromX(from);
            int z = index / size.getX() % size.getZ() + StructureLiveView.fromZ(from);
            int y = index / (size.getX() * size.getZ()) + StructureLiveView.fromY(from);
            if (x >= 0 && x < size.getX() && y >= 0 && y < size.getY() && z >= 0 && z < size.getZ()) {
                leaving.add(StructureLiveView.index(x, y, z, size));
            }
        }
        return leaving;
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
     */
    static void draw(PoseStack pose, MultiBufferSource buffers, List<Cell> cells, StructurePreview preview,
                     BlockRenderDispatcher dispatcher, @Nullable Map<Integer, StructureLiveViews.Move> moves,
                     long nowTick, float partialTick) {
        PoseStack.Pose entry = pose.last();
        try {
            pose.pushPose();
            pose.translate(0.5, 0.0, 0.5);
            drawInsideCube(pose, buffers, cells, preview, dispatcher, moves, nowTick, partialTick);
        } finally {
            if (PoseStackGuard.restore(pose, entry) > 1 && !reportedUnbalanced) {
                reportedUnbalanced = true;
                RCConfig.LOGGER.warn("[redstonecircuit] structure preview: a draw pass left the pose stack "
                        + "deeper than it found it; the stack was restored, but that is a bug");
            }
        }
    }

    /**
     * The miniature itself, inside the unit cube the pose is already at the corner of. A nested miniature is
     * drawn by calling this again with the pose standing in that cell; the recentring belongs to the block the
     * miniature is drawn on, so it may not happen twice.
     */
    private static void drawInsideCube(PoseStack pose, MultiBufferSource buffers, List<Cell> cells,
                                       StructurePreview preview, BlockRenderDispatcher dispatcher,
                                       @Nullable Map<Integer, StructureLiveViews.Move> moves, long nowTick,
                                       float partialTick) {
        int longest = Math.max(preview.size().getX(), Math.max(preview.size().getY(), preview.size().getZ()));
        float scale = 1.0f / (longest + 0.5f);
        pose.pushPose();
        try {
            pose.scale(scale, scale, scale);
            pose.translate(-preview.size().getX() / 2.0, 0.0, -preview.size().getZ() / 2.0);
            for (Cell cell : cells) {
                pose.pushPose();
                pose.translate(cell.x(), cell.y(), cell.z());
                if (cell.nested() != null) {
                    // The nested picture is centred in ITS CELL, so it needs the half-cell the outer call
                    // applies to the block - without it the inner structure hangs half a cell towards -x/-z.
                    pose.translate(0.5, 0.0, 0.5);
                    drawInsideCube(pose, buffers, cell.inside(), cell.nested(), dispatcher, null, nowTick,
                            partialTick);
                } else {
                    dispatcher.renderSingleBlock(cell.state(), pose, buffers, LightTexture.FULL_BRIGHT,
                            OverlayTexture.NO_OVERLAY);
                }
                pose.popPose();
            }
            drawMoves(pose, buffers, preview, dispatcher, moves, nowTick, partialTick);
        } finally {
            pose.popPose();
        }
    }

    /**
     * Draws every cell that is on its way: at the cell it is arriving at, less however much of the way it still has
     * to come. The pose is already the miniature's, so the offsets are in cells like everything else here.
     */
    private static void drawMoves(PoseStack pose, MultiBufferSource buffers, StructurePreview preview,
                                  BlockRenderDispatcher dispatcher,
                                  @Nullable Map<Integer, StructureLiveViews.Move> moves, long nowTick,
                                  float partialTick) {
        if (moves == null || moves.isEmpty()) {
            return;
        }
        Vec3i size = preview.size();
        for (Map.Entry<Integer, StructureLiveViews.Move> entry : moves.entrySet()) {
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
