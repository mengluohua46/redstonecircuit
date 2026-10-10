package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.core.Vec3i;

/**
 * The live view of a structure: which cells changed, and how they are numbered.
 *
 * <p>The miniature on the placed block must not be a snapshot taken at capture time, or a lamp that lit up inside the
 * pocket would change nothing visible from outside. So the structure's cells are sampled, compared with what was last
 * sent, and only the differences travel: a structure is a few hundred to a few thousand cells, and a redstone circuit
 * changes a handful of them per tick at most.
 *
 * <p>The cell order is the one every other part of the mod uses for flat arrays - {@code x} fastest, then {@code z},
 * then {@code y} - written down here in {@link #index}, because a payload whose two ends disagree about ordering shows
 * up as "the lamp lights up in the wrong place". Pure: no level, no blocks, no packets.
 */
public final class StructureLiveView {

    /** How many cell changes one packet may carry before the rest wait for the next one. */
    public static final int MAX_CHANGES_PER_PACKET = 512;

    /** How many moving cells one move packet may carry - a piston or two, never a whole machine of them. */
    public static final int MAX_MOVES_PER_PACKET = 64;

    /**
     * How long one of a piston's own moves takes: the same two ticks vanilla slides it over. A view that only carries
     * <em>states</em> has exactly two frames of it - the arm and the block it pushes jump a whole cell, twice - so the
     * move itself has to be sent (see {@code StructureBlockEntity#movingRecord}).
     */
    public static final int MOVE_TICKS = 2;

    /** How far along a move that started at {@code startTick} is, clamped to 0..1. */
    public static float slideProgress(long startTick, long nowTick, float partialTick) {
        float done = (nowTick - startTick + partialTick) / MOVE_TICKS;
        return Math.min(1.0F, Math.max(0.0F, done));
    }

    /**
     * The cell a move came from, as three small offsets, packed into one int: each is -1, 0 or 1.
     *
     * <p>Packed because it travels on every move of every structure, and three numbers in the range -1..1 need six
     * bits between them.
     */
    public static int packFrom(int dx, int dy, int dz) {
        return (dx + 1) | (dy + 1) << 2 | (dz + 1) << 4;
    }

    public static int fromX(int packed) {
        return (packed & 3) - 1;
    }

    public static int fromY(int packed) {
        return (packed >> 2 & 3) - 1;
    }

    public static int fromZ(int packed) {
        return (packed >> 4 & 3) - 1;
    }

    /** The flat index of a structure-local cell, {@code x} fastest, then {@code z}, then {@code y}. */
    public static int index(int x, int y, int z, Vec3i size) {
        return x + size.getX() * (z + size.getZ() * y);
    }

    /** How many cells a structure of this size has. */
    public static int count(Vec3i size) {
        return size.getX() * size.getY() * size.getZ();
    }

    /**
     * The cells whose value differs, as flat indices into {@link #index}'s ordering. Two arrays of different lengths are
     * a programming error rather than a state to tolerate: they can only mean that a structure was resized between the
     * two samples, and an {@link IllegalArgumentException} is read immediately while a wrong lamp is not.
     */
    public static int[] changed(int[] before, int[] after) {
        if (before.length != after.length) {
            throw new IllegalArgumentException("samples of different sizes: " + before.length + " and " + after.length);
        }
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                indices.add(i);
            }
        }
        int[] result = new int[indices.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = indices.get(i);
        }
        return result;
    }

    /**
     * The first {@link #MAX_CHANGES_PER_PACKET} of {@code indices}, and how many were left over.
     *
     * <p>A structure that changes more than that in one interval is not a redstone circuit having a busy tick - it is
     * something else entirely (a release landing in the plot, a machine being assembled) - and the honest answer is to
     * send what fits and let the next tick carry the rest, rather than to grow a packet without a bound.
     */
    public static int[] firstBatch(int[] indices) {
        if (indices.length <= MAX_CHANGES_PER_PACKET) {
            return indices;
        }
        int[] batch = new int[MAX_CHANGES_PER_PACKET];
        System.arraycopy(indices, 0, batch, 0, MAX_CHANGES_PER_PACKET);
        return batch;
    }

    /** How many changes {@link #firstBatch} could not carry. */
    public static int remaining(int[] indices) {
        return Math.max(0, indices.length - MAX_CHANGES_PER_PACKET);
    }

    /**
     * A whole structure, in as many packets as the cap takes.
     *
     * <p>A resend that sent the first 512 cells of a thousand and then recorded the whole sample as sent left the
     * remaining cells never sent at all - and because the client's resend handler <em>empties</em> its view before
     * filling it, every resend also threw away what those cells had learned from a diff in the meantime. So a resend is
     * a stream with a cursor rather than one packet that pretends to be the whole picture: every cell is sent exactly
     * once per pass, the client is told when a pass begins, and the pass finishes over the next few ticks.
     */
    public static final class Resend {

        private final int total;
        private int cursor;

        public Resend(int total) {
            this.total = total;
        }

        /** Whether this is the first batch of the pass, which is what lets the client start from an empty view. */
        public boolean first() {
            return this.cursor == 0;
        }

        /** Whether every cell has been sent. */
        public boolean done() {
            return this.cursor >= this.total;
        }

        /** The next cell indices, at most {@link #MAX_CHANGES_PER_PACKET} of them; empty once the pass is done. */
        public int[] next() {
            int to = Math.min(this.total, this.cursor + MAX_CHANGES_PER_PACKET);
            int[] batch = new int[Math.max(0, to - this.cursor)];
            for (int i = 0; i < batch.length; i++) {
                batch[i] = this.cursor + i;
            }
            this.cursor = to;
            return batch;
        }
    }

    /**
     * The sample to report, with a moving-piston placeholder standing in for what it replaced. A {@code moving_piston}
     * renders as nothing - it is a cell that is mid-move, not a block - so it is <b>not news about a cell</b>: what the
     * cell holds is whatever the move took out of it or is about to put into it, and that is what is reported. Sending
     * the placeholder itself puts a hole in the miniature exactly where the piston (or the block it is pushing) is, and
     * a piston's base cell is a placeholder for a tick on every single retraction.
     *
     * <p>The stand-in cannot outlive anything: it applies only while the world's own cell holds a placeholder, and the
     * moment the world leaves that state the diff fires, so the real value is sent on the next sample. {@code previous}
     * is what was reported last time, or null when there is no last time - then the world is all there is to report.
     */
    public static int[] believe(int[] sampled, int placeholderId, int[] previous) {
        int[] believed = new int[sampled.length];
        for (int i = 0; i < sampled.length; i++) {
            believed[i] = sampled[i] == placeholderId && previous != null ? previous[i] : sampled[i];
        }
        return believed;
    }

    private StructureLiveView() {
    }
}
