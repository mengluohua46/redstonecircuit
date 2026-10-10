package com.jiangyuefengyu.redstonecircuit.client;

/**
 * How many vertices actually reached each of the mod's buffer sources - not how many we meant to write.
 *
 * <p>Counting in the drawing loops counts <em>intent</em>: vertices written into a source whose batch was
 * already flushed leave every counter reading perfectly. The numbers therefore have to be taken where the
 * vertices are written: {@link #writtenQuads} since the last flush, {@link #flushedQuads} for what the
 * source really held, and {@link #postFlushWrites} for writes after the flush, which correct code never
 * produces.
 *
 * <p>Nothing here names a render type: {@code RenderType}'s class initialiser reaches the vanilla item and
 * registry classes, so the drawing paths cannot be unit-tested. The accounting is plain arithmetic.
 */
final class HostBufferTally {

    /**
     * The buffer sources vertices are written into, <b>and who submits each of them</b>: a source we do not
     * submit cannot be held to "we flushed nothing", so it is not in the same ledger as the ones we do.
     */
    enum Sink {

        /**
         * The world path's source, submitted by us at {@code AFTER_ENTITIES}: the world <b>contents</b>
         * (and, under a shader pack, the block interiors). Every quad here is ours to submit.
         */
        WORLD("world", HostRenderPass.WORLD_STAGE_NAME, true),

        /**
         * The carried path's source, submitted by us at {@code AFTER_TRANSLUCENT_BLOCKS}: the carried shell
         * and its contents, ours to submit like the world one.
         */
        CARRIED("carried", HostRenderPass.CARRIED_STAGE_NAME, true),

        /**
         * The level renderer's own buffer source, which the world shell is handed to and never submitted by
         * us: the shell keeps vanilla's translucent render type identity, because a shader pack selects its
         * program from that identity, so its vertices are submitted by {@code LevelRenderer}'s translucent
         * section layer later in the same frame. A zero flush here would mean nothing, so
         * {@link #handedOverQuads} - how much we gave the renderer - is reported and never alarmed on.
         */
        RENDERER("renderer", "LevelRenderer translucent section layer", false);

        private final String label;
        private final String stage;
        private final boolean submittedByUs;

        Sink(String label, String stage, boolean submittedByUs) {
            this.label = label;
            this.stage = stage;
            this.submittedByUs = submittedByUs;
        }

        /** {@code world}, {@code carried} or {@code renderer}, for a log line. */
        String label() {
            return label;
        }

        /** The stage this sink is submitted at; see {@link HostRenderPass#flushStageLine()}. */
        String stage() {
            return stage;
        }

        /** Whether a zero flush on this sink is a defect (ours) or simply not our business. */
        boolean submittedByUs() {
            return submittedByUs;
        }
    }

    /** Which half of the overlay a vertex belongs to, so a zero can name what is missing. */
    enum Kind {

        /** The frosted shell. */
        SHELL("shell"),

        /** The component inside the block. */
        CONTENTS("content"),

        /** The block's own inside-out model, which only the shader path draws: a shader pack needs the
         * block's surface to come from somewhere once the block has left the chunk mesh. */
        INTERIOR("interior");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        /** {@code shell}, {@code contents} or {@code interior}, for a log line. */
        String label() {
            return label;
        }
    }

    private static final int SINKS = Sink.values().length;
    private static final int KINDS = Kind.values().length;

    /** Vertices each (sink, kind) has taken since that sink's last flush. */
    private static final int[] WRITTEN = new int[SINKS * KINDS];

    /** Vertices each (sink, kind) had taken when that sink was last flushed; what the summary prints. */
    private static final int[] FLUSHED = new int[SINKS * KINDS];

    /** Vertices written into each sink <b>after</b> its last flush: the trap detector. */
    private static final int[] POST_FLUSH = new int[SINKS];

    private HostBufferTally() {
    }

    private static int index(Sink sink, Kind kind) {
        return sink.ordinal() * KINDS + kind.ordinal();
    }

    /**
     * Records {@code vertices} handed to the consumer for one sink and kind. Plain {@code int}s rather than
     * atomics: only the render thread and a test touch these, and the call is per vertex.
     */
    static void written(Sink sink, Kind kind, int vertices) {
        if (vertices <= 0) {
            return;
        }
        WRITTEN[index(sink, kind)] += vertices;
        POST_FLUSH[sink.ordinal()] += vertices;
    }

    /**
     * Records that our writes to a source are done for this frame: the pending window is snapshotted and
     * reset, or last frame's legitimate writes would answer the "written after the flush" question. Only
     * for sources we submit; a source we hand on has no flush of ours to record - see {@link #handOver}.
     */
    static void flush(Sink sink) {
        if (!sink.submittedByUs()) {
            throw new IllegalArgumentException("flush(" + sink + ") is a lie: this build does not submit "
                    + "the " + sink.label() + " source - " + sink.stage() + " does. Use handOver() for a "
                    + "source we merely hand on.");
        }
        snapshot(sink);
    }

    /**
     * The same bookkeeping as {@link #flush} under a different name, because the fact is different: the
     * source was handed to {@code LevelRenderer}, which submits it later in the same frame.
     */
    static void handOver(Sink sink) {
        if (sink.submittedByUs()) {
            throw new IllegalArgumentException("handOver(" + sink + ") is wrong: this build submits the "
                    + sink.label() + " source itself at " + sink.stage() + ". Use flush().");
        }
        snapshot(sink);
    }

    /** Moves pending into the snapshot and restarts the window. */
    private static void snapshot(Sink sink) {
        int base = sink.ordinal() * KINDS;
        for (int kind = 0; kind < KINDS; kind++) {
            FLUSHED[base + kind] = WRITTEN[base + kind];
            // The pending window restarts with the snapshot, or a running total would be true for ever after one good frame.
            WRITTEN[base + kind] = 0;
        }
        POST_FLUSH[sink.ordinal()] = 0;
    }

    /** Vertices this sink has taken since its last flush. */
    static int writtenVertices(Sink sink, Kind kind) {
        return WRITTEN[index(sink, kind)];
    }

    /** Vertices this sink had taken at its last flush: the honest count. */
    static int flushedVertices(Sink sink, Kind kind) {
        return FLUSHED[index(sink, kind)];
    }

    /** Whole quads this sink had taken at its last flush. */
    static int flushedQuads(Sink sink, Kind kind) {
        return FLUSHED[index(sink, kind)] / 4;
    }

    /** Whole quads this sink had taken when we last handed it over; see {@link #handOver}. */
    static int handedOverQuads(Sink sink, Kind kind) {
        return FLUSHED[index(sink, kind)] / 4;
    }

    /** Vertices written into this sink since its last flush: {@code 0} unless something is misaimed. */
    static int postFlushWrites(Sink sink) {
        return POST_FLUSH[sink.ordinal()];
    }

    /**
     * The alarm: a source we submit took geometry and none of it was flushed. Scoped to sources we submit and
     * to one pair of kinds, so one kind's flush cannot cover another's loss - {@code emittedQuads} is what the
     * loops handed out, {@code flushedQuads} what that source of those kinds really held. Null otherwise.
     */
    static String nothingFlushedWhileEmitting(Sink sink, int emittedQuads, int flushedQuads) {
        if (!sink.submittedByUs()) {
            return null;
        }
        if (emittedQuads <= 0 || flushedQuads > 0) {
            return null;
        }
        return "*** " + sink.label().toUpperCase(java.util.Locale.ROOT) + " STAGE WROTE NOTHING INTO ITS "
                + "OWN BUFFER - " + emittedQuads + " quad(s) were emitted and none reached the "
                + sink.label() + " source, which this build submits at " + sink.stage() + ". They went to "
                + "a source that was already ended, so they will never be submitted: this is a "
                + "WRONG-BUFFER defect, not a depth, matrix or configuration one ***"
                + postFlushSentence();
    }

    /**
     * The second half of the same alarm: where the lost vertices <em>did</em> go. Stored rather than
     * inferred - a zero flush on one sink plus writes after another's flush is the signature of a draw
     * point aiming at a batch that is already finished.
     */
    static String postFlushSentence() {
        StringBuilder sb = new StringBuilder();
        for (Sink sink : Sink.values()) {
            int stray = POST_FLUSH[sink.ordinal()];
            if (stray > 0) {
                sb.append(" (").append(stray).append(" vertex/vertices were written into the ")
                        .append(sink.label()).append(" source AFTER we were done with it at ")
                        .append(sink.stage()).append("; a draw point is aiming at a finished batch)");
            }
        }
        return sb.toString();
    }

    /**
     * One compact line for the frame summary: what each source really took, and what leaked. Every name
     * carries {@code Buf}, so {@code worldBufShellQuads} here cannot be confused with the frame summary's
     * own {@code worldShellQuads} printed beside it - emitted there, really held here.
     */
    static String describe() {
        StringBuilder sb = new StringBuilder();
        for (Sink sink : Sink.values()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(sink.label()).append("Buf")
                    .append(sink.submittedByUs() ? "FlushedQuads=" : "HandedOverQuads=");
            int total = 0;
            for (int kind = 0; kind < KINDS; kind++) {
                total += FLUSHED[sink.ordinal() * KINDS + kind];
            }
            sb.append(total / 4).append(" (");
            for (Kind kind : Kind.values()) {
                if (kind != Kind.values()[0]) {
                    sb.append(' ');
                }
                sb.append(sink.label()).append("Buf").append(capitalise(kind.label())).append("Quads=")
                        .append(flushedQuads(sink, kind));
            }
            sb.append(')');
            int stray = POST_FLUSH[sink.ordinal()];
            if (stray > 0) {
                sb.append(" *** ").append(sink.label()).append("BufPostFlushVertices=").append(stray)
                        .append(" ***");
            }
        }
        return sb.toString();
    }

    private static String capitalise(String text) {
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    /** Forgets everything, for a test: a frame that reset the running tally could not answer "has this source ever flushed". */
    static void resetForTest() {
        java.util.Arrays.fill(WRITTEN, 0);
        java.util.Arrays.fill(FLUSHED, 0);
        java.util.Arrays.fill(POST_FLUSH, 0);
    }
}
