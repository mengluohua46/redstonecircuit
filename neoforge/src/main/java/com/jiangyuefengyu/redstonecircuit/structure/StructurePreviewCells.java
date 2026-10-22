package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

import net.minecraft.core.Vec3i;

/**
 * How a structure's own cells become the cells of a preview: which preview cell stands for which raw cell, and
 * <b>what one preview cell stands for</b>.
 *
 * <p>A preview coarsened to scale {@code s} stands for {@code s}³ of the structure's cells. Two numberings meet here -
 * the structure's own, which is what the server samples inside the pocket, and the preview's, which is what a client
 * draws - and the conversion is one division, <b>clamped</b> into the preview rather than wrapped, because a wrap would
 * draw a lamp in the miniature's first column and read as a structure that is not the one standing in the block.
 *
 * <p>The second half is the rule that decides what a coarsened cell <em>looks like</em>, and it is the only reason a
 * miniature of a decorated wall used to come out as bare planks. A miniature is a picture of a structure's
 * <b>surface</b>; the most common block of a {@code scale}³ group is not: a one-cell-thick decorated wall has three
 * cells to a {@code scale}³ group of eight, and the layer behind it has four, so the layer a player is never meant to
 * see wins every group. Nor is "the cells that touch air" enough on its own, because the layer behind the wall touches
 * air too - through the holes, and through the cavity it encloses - and its four still beat the wall's three.
 *
 * <p>So the vote is over three tiers, in order: the cells with a face of their own pointing <b>out of the structure</b>
 * (its skin, {@link #skinned}); then the cells with a face pointing into air ({@link #exposed}), which is how a surface
 * inside the structure's own box is described; and only a group with neither - an interior cell, which can be seen from
 * nowhere - answers with the most common block of the whole group.
 *
 * <p>Pure: no level, no blocks, no packets. The structure's cells and the meaning of "empty" are handed in, so the same
 * code decides a capture's palette indices and a pocket's block states.
 */
public final class StructurePreviewCells {

    /** The cells of a structure, addressed by its own coordinates; anything outside it is empty. */
    @FunctionalInterface
    public interface RawCells {

        /** The value at {@code (x, y, z)}, which is always a coordinate inside the structure. */
        int at(int x, int y, int z);
    }

    /**
     * What two values of a caller's own numbering stand for, when a vote over a coarsened cell ends in a tie.
     *
     * <p>A vote needs a tie-break, and "the lower number" is not one: the numbers are the caller's - a capture's
     * palette index, a live view's block state id, an icon's place in the preview - and two of those orders disagree
     * about which block comes first. A capture of a 32³ log-and-plank structure and the live view of the same
     * structure once differed in 120 cells, every one of them a log where a plank belonged: the extra ring a player
     * saw appear around every plank panel a moment after the structure was placed, and the icon carried it too. A rank
     * is the caller saying what its numbers mean, so a tie is decided by the blocks in the group and not by which
     * numbering happened to reach this class.
     *
     * <p>An implementation must order two values that stand for two different blocks; values it calls equal stand for
     * the same block, and their own numbers then separate them (see {@link #order}).
     *
     * <p>That last step is a boundary this class knows about and leaves in place on purpose: two states of one block - a
     * log lying along its {@code x} and one along its {@code y} - are separated by their own numbers again, so a capture
     * (palette index) and a live view (state id) may pick different states of it. The block a player sees is the same
     * either way and only its property values can differ, which is why it is not worth the registry lookup inside this
     * class that ordering by properties would take; it is written down here so nobody has to discover it.
     */
    @FunctionalInterface
    public interface Rank {

        /** Negative when {@code left} is the block to draw on a tie, zero when the two stand for the same block. */
        int compare(int left, int right);
    }

    /**
     * The rank of a caller that has none: the numbers decide, which is the same answer for every caller only when the
     * cells are already in the common order - what {@link #numbering} is for.
     */
    private static final Rank NUMBERS = Integer::compare;

    private StructurePreviewCells() {
    }

    /**
     * The preview coordinate that stands for {@code raw}, clamped into {@code 0..limit-1}.
     *
     * <p>A scale of zero means one: it is reachable from a payload read out of a hand-edited file, and a division
     * that cannot happen is not a reason to throw inside a render pass.
     */
    public static int previewCoordinate(int raw, int scale, int limit) {
        int coordinate = raw / Math.max(1, scale);
        return Math.min(Math.max(1, limit) - 1, Math.max(0, coordinate));
    }

    /** The preview cell that stands for the structure cell at {@code rawIndex}, in both numberings' own order. */
    public static int previewIndex(int rawIndex, Vec3i rawSize, int scale, Vec3i previewSize) {
        return StructureLiveView.index(
                previewCoordinate(rawX(rawIndex, rawSize), scale, previewSize.getX()),
                previewCoordinate(rawY(rawIndex, rawSize), scale, previewSize.getY()),
                previewCoordinate(rawZ(rawIndex, rawSize), scale, previewSize.getZ()),
                previewSize);
    }

    /** The {@code x} of a structure cell from its flat index - the inverse of {@link StructureLiveView#index}. */
    public static int rawX(int rawIndex, Vec3i rawSize) {
        return rawIndex % Math.max(1, rawSize.getX());
    }

    public static int rawY(int rawIndex, Vec3i rawSize) {
        return rawIndex / Math.max(1, rawSize.getX() * Math.max(1, rawSize.getZ()));
    }

    public static int rawZ(int rawIndex, Vec3i rawSize) {
        return rawIndex / Math.max(1, rawSize.getX()) % Math.max(1, rawSize.getZ());
    }

    /**
     * Whether the structure cell at {@code (x, y, z)} can be seen from outside it: one of its six neighbours is empty.
     *
     * <p>This is the weaker of the two questions the rule asks - a cell one step behind a hole in a wall is "exposed"
     * by it, and so is every cell of the layer that faces a structure's inner cavity - which is why it is only the
     * second tier: what a player sees is the {@link #skinned} layer.
     */
    public static boolean exposed(Vec3i rawSize, RawCells cells, IntPredicate empty, int x, int y, int z) {
        return emptyNeighbour(rawSize, cells, empty, x - 1, y, z)
                || emptyNeighbour(rawSize, cells, empty, x + 1, y, z)
                || emptyNeighbour(rawSize, cells, empty, x, y - 1, z)
                || emptyNeighbour(rawSize, cells, empty, x, y + 1, z)
                || emptyNeighbour(rawSize, cells, empty, x, y, z - 1)
                || emptyNeighbour(rawSize, cells, empty, x, y, z + 1);
    }

    /**
     * Whether the structure cell at {@code (x, y, z)} has a face of its own pointing <b>out of the structure</b>: one of
     * its six neighbours lies outside the structure's own box.
     *
     * <p>This, and not "{@link #exposed}" alone, is the skin a player reads a picture of. A one-cell-thick decorated
     * wall and the solid layer immediately behind it are both "exposed" - the second one to the cavity it encloses, and
     * to every hole in the wall - so a vote among exposed cells still counts four cells of the hidden layer against
     * three of the wall and still shows the player the back of the wall. The skin is the only tier where the wall wins
     * by construction: a cell of it has a face pointing out of the box, and nothing behind it has.
     */
    public static boolean skinned(Vec3i rawSize, int x, int y, int z) {
        return x - 1 < 0 || y - 1 < 0 || z - 1 < 0
                || x + 1 >= rawSize.getX() || y + 1 >= rawSize.getY() || z + 1 >= rawSize.getZ();
    }

    private static boolean emptyNeighbour(Vec3i rawSize, RawCells cells, IntPredicate empty, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= rawSize.getX() || y >= rawSize.getY() || z >= rawSize.getZ()) {
            return true;
        }
        return empty.test(cells.at(x, y, z));
    }

    /**
     * What one preview cell stands for: the most common value among the cells of its group that are {@link #exposed},
     * or - when the group has no exposed cell, which means it is buried inside the structure - the most common value of
     * the whole group; {@code emptyValue} when the group holds nothing at all.
     *
     * <p>Ties go to {@code rank}'s order - what the caller's numbers stand for - so the answer is the same block
     * whichever numbering the caller uses, and never depends on the order the cells are visited in.
     *
     * @param emptyValue the value that means "nothing" in the caller's numbering, and what a group with nothing in it
     *                   answers
     * @param rank       what the caller's values stand for; see {@link Rank}
     */
    public static int representative(Vec3i rawSize, RawCells cells, IntPredicate empty, int emptyValue,
                                     int px, int py, int pz, int scale, Rank rank) {
        int fromX = px * scale;
        int fromY = py * scale;
        int fromZ = pz * scale;
        int toX = Math.min(fromX + scale, rawSize.getX());
        int toY = Math.min(fromY + scale, rawSize.getY());
        int toZ = Math.min(fromZ + scale, rawSize.getZ());
        // First what a player reads the picture by: the cells with a face of their own pointing out of the structure.
        int skin = mostCommon(rawSize, cells, empty, fromX, fromY, fromZ, toX, toY, toZ, Tile.SKIN, rank);
        if (skin >= 0) {
            return skin;
        }
        // Then the cells with a face pointing into air, which is how a surface inside the structure's own box is
        // described - a hole through the middle of it, a cavity opening sideways.
        int seen = mostCommon(rawSize, cells, empty, fromX, fromY, fromZ, toX, toY, toZ, Tile.EXPOSED, rank);
        if (seen >= 0) {
            return seen;
        }
        // And only a group with nothing exposed - an interior cell - answers with its own majority: nothing in it can be
        // seen, so what most of it is is the honest answer.
        int whole = mostCommon(rawSize, cells, empty, fromX, fromY, fromZ, toX, toY, toZ, Tile.WHOLE, rank);
        return whole >= 0 ? whole : emptyValue;
    }

    /**
     * The same vote for a caller whose numbering already <b>is</b> the order it wants.
     *
     * <p>{@link PreviewLod} reaches this one, and may only because its caller renumbers the grid first
     * ({@link #numbering}): handing it raw block state ids is the defect this class used to be able to make by
     * accident, and a caller outside this package cannot repeat it without naming a {@link Rank} first.
     */
    static int representative(Vec3i rawSize, RawCells cells, IntPredicate empty, int emptyValue,
                              int px, int py, int pz, int scale) {
        return representative(rawSize, cells, empty, emptyValue, px, py, pz, scale, NUMBERS);
    }

    /** Which cells of a group a vote is taken over; see {@link #representative}. */
    private enum Tile {
        /** A cell with a face of its own pointing out of the structure. */
        SKIN,
        /** A cell with a face pointing into air. */
        EXPOSED,
        /** Every cell of the group. */
        WHOLE
    }

    /**
     * The most common value of a box of cells, optionally only counting the cells that are exposed, or {@code -1} when
     * it holds no such cell: every value a caller hands in is a palette index or a block state id, so nothing legal is
     * negative.
     */
    private static int mostCommon(Vec3i rawSize, RawCells cells, IntPredicate empty,
                                  int fromX, int fromY, int fromZ, int toX, int toY, int toZ, Tile tier, Rank rank) {
        Map<Integer, Integer> counts = new HashMap<>();
        int best = -1;
        int bestCount = 0;
        for (int y = fromY; y < toY; y++) {
            for (int z = fromZ; z < toZ; z++) {
                for (int x = fromX; x < toX; x++) {
                    int value = cells.at(x, y, z);
                    if (empty.test(value)) {
                        continue;
                    }
                    if (tier == Tile.SKIN && !skinned(rawSize, x, y, z)) {
                        continue;
                    }
                    if (tier == Tile.EXPOSED && !exposed(rawSize, cells, empty, x, y, z)) {
                        continue;
                    }
                    int count = counts.merge(value, 1, Integer::sum);
                    if (count > bestCount || (count == bestCount && (best < 0 || order(value, best, rank) < 0))) {
                        best = value;
                        bestCount = count;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Which of two values is drawn on a tie: what they stand for first, and their own numbers only to separate two
     * values the rank calls equal - two states of the same block, where the difference is a property and never which
     * block a player sees.
     *
     * <p>Both halves are needed. Without the rank the tie is decided by a numbering, which is how a live view and a
     * capture came out 120 cells apart; without the numbers the two callers that hold the same block twice would
     * disagree about which of its states to draw, depending on the order the cells are met in.
     */
    private static int order(int left, int right, Rank rank) {
        int byRank = rank.compare(left, right);
        return byRank != 0 ? byRank : Integer.compare(left, right);
    }

    /**
     * {@code values} renumbered into {@code rank}'s own order, with the table that reads them back.
     *
     * <p>For a caller that has to hand its cells to something which compares numbers and nothing else: the
     * half-resolution rule ({@link PreviewLod}) breaks a tie by the lower value it is given, so a caller that renumbers
     * its cells by {@code rank} first is handed back the block a {@link #representative} vote would have chosen. Zero
     * stays zero, which is what every numbering here calls "nothing".
     */
    public static Numbering numbering(int[] values, Rank rank) {
        int[] distinct = new int[values.length];
        int found = 0;
        for (int value : values) {
            if (value != 0 && placeOf(distinct, found, value) == 0) {
                distinct[found++] = value;
            }
        }
        // Sorted by the comparison a vote itself makes, so the order this grid is numbered in and the order a tie is
        // decided by are one order and not two that happen to agree.
        for (int i = 1; i < found; i++) {
            int value = distinct[i];
            int at = i - 1;
            while (at >= 0 && order(value, distinct[at], rank) < 0) {
                distinct[at + 1] = distinct[at];
                at--;
            }
            distinct[at + 1] = value;
        }
        int[] cells = new int[values.length];
        for (int i = 0; i < values.length; i++) {
            cells[i] = placeOf(distinct, found, values[i]);
        }
        return new Numbering(cells, java.util.Arrays.copyOf(distinct, found));
    }

    /**
     * A grid in {@link #numbering}'s numbering: {@code cells} holds the places, and {@code values} turns a place back
     * into the value it stood for.
     */
    public record Numbering(int[] cells, int[] values) {

        /** The value behind a place, or zero - what every numbering here calls "nothing" - for place zero. */
        public int value(int place) {
            return place <= 0 || place > this.values.length ? 0 : this.values[place - 1];
        }
    }

    /** The place of {@code value} among the first {@code count} of a ranked grid, one-based, or zero for none. */
    private static int placeOf(int[] values, int count, int value) {
        for (int i = 0; i < count; i++) {
            if (values[i] == value) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * Marks every raw cell of the given preview cells as sent, in the sample the next diff is compared against.
     *
     * <p>Needed because a packet now names <b>preview</b> cells while the diff is over the structure's own cells: a
     * preview cell that was sent has said everything there is to say about all {@code scale}³ of its cells, and leaving
     * any of them unclaimed would report the same preview cell as changed on every pass for ever - which would also keep
     * re-arming the every-tick sampling window.
     *
     * @param now      the sample just read, in the structure's own numbering
     * @param previous the sample remembered as sent, updated in place
     * @param previews the preview cells a packet carried, in the preview's own numbering
     */
    public static void claim(int[] now, int[] previous, int[] previews, Vec3i rawSize, Vec3i previewSize, int scale) {
        int width = Math.max(1, previewSize.getX());
        int depth = Math.max(1, previewSize.getZ());
        for (int preview : previews) {
            int px = preview % width;
            int pz = preview / width % depth;
            int py = preview / (width * depth);
            int fromX = px * scale;
            int fromY = py * scale;
            int fromZ = pz * scale;
            int toX = Math.min(fromX + scale, rawSize.getX());
            int toY = Math.min(fromY + scale, rawSize.getY());
            int toZ = Math.min(fromZ + scale, rawSize.getZ());
            for (int y = fromY; y < toY; y++) {
                for (int z = fromZ; z < toZ; z++) {
                    for (int x = fromX; x < toX; x++) {
                        int at = StructureLiveView.index(x, y, z, rawSize);
                        previous[at] = now[at];
                    }
                }
            }
        }
    }
}
