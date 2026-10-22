package com.jiangyuefengyu.redstonecircuit.client;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.structure.StructureLiveView;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>What a placed structure looks like right now</b>, as far as this client has been told.
 *
 * <p>The server sends the cells that changed ({@code StructureLivePayload}) and this is where they are
 * kept: one map per placed structure, cell index to state. The captured preview is the <em>blueprint</em>
 * - what the structure is, what it is released as, what the item in the hand shows - and must never be
 * rewritten: these overrides are read only while drawing. Bounded like the other client caches
 * ({@link #LIMIT}), oldest first: a structure with no live view simply draws its captured preview.
 *
 * <p><b>The index is a preview cell, not a structure cell.</b> The server knows the preview, its scale and the real
 * blocks in the pocket, so it answers for one cell of the picture at a time - what that cell looks like now - and this
 * class only keeps the answer. A client-side conversion would be a second opinion about the same question, which is
 * exactly how a coarsened preview came to be drawn from blocks that were behind the surface.
 */
@Environment(EnvType.CLIENT)
public final class StructureLiveViews {

    /** How many structures keep a live view. Beyond this the least recently updated is forgotten. */
    public static final int LIMIT = 32;

    /**
     * How many packets pass between two log lines. A whole picture is always written down, because that is
     * the line that says the resend arrived; the rest is sampled.
     */
    private static final int LOG_EVERY = 20;

    private static int packets;

    /** Whether the previous packet was the first of a whole picture, so its rest is written down too. */
    private static boolean afterRestart;

    private static final Map<BlockPos, Map<Integer, BlockState>> VIEWS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, Map<Integer, BlockState>> eldest) {
                    return size() > LIMIT;
                }
            };

    /** A cell on its way from somewhere to where it is drawn: what it is, and the cell it left. */
    record Move(int stateId, int from, long startTick) {
    }

    /** Bounded like {@link #VIEWS} and for the same reason - and emptied the same way when a world is left. */
    private static final Map<BlockPos, Map<Integer, Move>> MOVES =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, Map<Integer, Move>> eldest) {
                    return size() > LIMIT;
                }
            };

    /**
     * <b>Cells that are in the middle of a move</b>: a piston's move takes two ticks and the pocket's block
     * entities are server-side, so the move itself is sent; slides are timed against this client's own tick,
     * and a record older than its own move is dropped rather than drawn sliding for ever.
     */
    public static void applyMoves(BlockPos pos, int[] indices, int[] states, int[] from) {
        long now = nowTick();
        Map<Integer, Move> moves = MOVES.computeIfAbsent(pos.immutable(), key -> new LinkedHashMap<>());
        moves.values().removeIf(move -> StructureLiveView.slideProgress(move.startTick(), now, 0.0F) >= 1.0F);
        int carried = Math.min(indices.length, Math.min(states.length, from.length));
        for (int i = 0; i < carried; i++) {
            moves.put(indices[i], new Move(states[i], from[i], now));
        }
        // A slide moves both the cell it covers and the cell it left, so what is drawn has changed.
        bumped(pos);
    }

    /** The moves being drawn for this structure, or null when it has none - the renderer's fast path. */
    @Nullable
    static Map<Integer, Move> movesAt(BlockPos pos) {
        return MOVES.get(pos);
    }

    /**
     * <b>How many times each placed structure's picture has changed</b>, as one number per position.
     *
     * <p>It exists so that the geometry cache has a key that cannot be mistaken for the blueprint: the captured
     * preview is the same picture all world long, while a live view of a running circuit is a new picture whenever
     * a packet arrives. Bumped on every packet that can change what is drawn and never reset except by
     * {@link #clear()} - a revision that could repeat would hand a cached miniature back for the wrong cells.
     *
     * <p>Nothing here decides <em>what</em> changed: the resolved cell list is one picture, so one counter per
     * structure is enough to say whether that picture has to be built again.
     */
    private static final Map<BlockPos, Integer> REVISIONS =
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<BlockPos, Integer> eldest) {
                    return size() > LIMIT;
                }
            };

    /** Every revision handed out, so two structures - or one structure over time - never share a number. */
    private static int revisions;

    /** The client's own tick: what a slide is measured against. */
    private static long nowTick() {
        net.minecraft.client.multiplayer.ClientLevel level = net.minecraft.client.Minecraft.getInstance().level;
        return level == null ? 0L : level.getGameTime();
    }

    /** Applies one packet's cells; {@code restart} begins a whole picture, which empties this view first. */
    public static void apply(BlockPos pos, int[] indices, int[] blockIds, boolean restart) {
        Map<Integer, BlockState> cells = VIEWS.computeIfAbsent(pos.immutable(), key -> new LinkedHashMap<>());
        int applied = 0;
        // Said outright rather than guessed from the index: a whole picture arrives as a stream of packets, and
        // an ordinary diff that happened to start at the structure's corner would empty this view.
        if (restart) {
            cells.clear();
            // A whole picture says what every cell is now, so a move that was still in flight is over.
            MOVES.remove(pos);
        }
        for (int i = 0; i < indices.length && i < blockIds.length; i++) {
            BlockState state = Block.BLOCK_STATE_REGISTRY.byId(blockIds[i]);
            if (state == null) {
                // An id this client does not know: the block comes from a mod it does not have. What was
                // captured stays, and the rest of the structure still changes.
                continue;
            }
            // The move for this cell is over - what it was moving is now what is there.
            Map<Integer, Move> moves = MOVES.get(pos);
            if (moves != null) {
                moves.remove(indices[i]);
            }
            if (state.is(Blocks.MOVING_PISTON)) {
                // A moving-piston placeholder renders as nothing, so the cell keeps what it had (or the
                // captured preview) until the world's move finishes and the real state is sent.
                continue;
            }
            cells.put(indices[i], state);
            applied++;
        }
        if (applied > 0 || restart) {
            // What this structure looks like changed, and only here is that known: a revision is what makes the
            // cached cells stale.
            bumped(pos);
        }
        boolean rest = afterRestart;
        afterRestart = restart;
        boolean tell = restart || rest || ++packets % LOG_EVERY == 0;
        if (applied > 0 && tell && RCConfig.debugLog()) {
            // The log says whether this packet began a whole picture, and how many cells this view knows.
            RCConfig.LOGGER.info("[redstonecircuit] live view of the structure at {}: {} of {} cell(s) {}, {} cell(s) "
                    + "known", pos.toShortString(), applied, indices.length,
                    restart ? "replaced (a whole picture begins)" : "changed", cells.size());
        }
    }

    /**
     * What the given cell of the given picture is doing now, or null when this client has not been told about it.
     *
     * <p>{@code index} is a preview cell (see {@link com.jiangyuefengyu.redstonecircuit.structure.StructurePreviewCells}):
     * for a picture at one cell per block the two numberings are the same, and coarser ones are answered by the server.
     */
    @Nullable
    public static BlockState stateAt(BlockPos pos, int index) {
        Map<Integer, BlockState> cells = VIEWS.get(pos);
        return cells == null ? null : cells.get(index);
    }

    /** How many times this structure's picture has changed; {@code 0} when nothing is live there. */
    static int revisionAt(BlockPos pos) {
        Integer revision = REVISIONS.get(pos);
        return revision == null ? 0 : revision;
    }

    private static void bumped(BlockPos pos) {
        REVISIONS.put(pos.immutable(), ++revisions);
    }

    /** Whether this structure has anything live to draw - the renderer's fast path when it does not. */
    public static boolean has(BlockPos pos) {
        Map<Integer, BlockState> cells = VIEWS.get(pos);
        return cells != null && !cells.isEmpty();
    }

    /** Forgets everything: called when leaving a world, like the screen memory. */
    public static void clear() {
        VIEWS.clear();
        MOVES.clear();
        REVISIONS.clear();
    }

    private StructureLiveViews() {
    }
}
