package com.jiangyuefengyu.redstonecircuit.client;

/**
 * <b>How many vertices actually reached each of the mod's buffer sources - not how many we meant to
 * write.</b>
 *
 * <h2>The defect this exists for</h2>
 * The frame summary's {@code carriedShellQuads=30 carriedContentQuads=12} were counted in the drawing
 * loops: every time a quad was handed to a {@code VertexConsumer}, an {@code int} went up. That is a
 * count of <em>intent</em>, and the author's log carried it - 431 frames of it - while the screen showed
 * a contraption with a frame and no components. The cause was one line: the two carried draw points
 * asked for their consumer from the <b>world</b> buffer source, whose batch is ended at
 * {@code AFTER_ENTITIES}, and wrote into it from the carried stage two stages later. The vertices went
 * into a source that had already been flushed, and every counter said the frame was perfect.
 *
 * <p>So the number that has to exist is "vertices this source actually took", measured where they are
 * written rather than where they are decided. Three facts come out of it, and each one names a different
 * failure:
 *
 * <ul>
 *   <li>{@link #writtenQuads} - what the source took since its last flush.</li>
 *   <li>{@link #flushedQuads} - what it had taken when that flush happened. <b>This is the number the
 *       frame summary prints</b>, because it is the only one that cannot be true of a batch that was
 *       never submitted.</li>
 *   <li>{@link #postFlushWrites} - vertices written into a source <em>after</em> its flush. Correct code
 *       never produces this: it means a draw point is aiming at a batch that is already gone, which is
 *       exactly the defect above, caught in one number instead of in a screenshot.</li>
 * </ul>
 *
 * <h2>Why this class has no Minecraft in it</h2>
 * {@code RenderType}'s class initialiser reaches {@code ItemRenderer}, {@code Items} and the vanilla
 * registries, so nothing that mentions a render type can be loaded on a bare JVM - which is why the
 * drawing paths themselves cannot be unit-tested. This is the part of the answer that can be: the
 * accounting, the trap detector and the alarms are plain arithmetic, and {@code HostBufferTallyTest}
 * drives all of them.
 */
final class HostBufferTally {

    /**
     * The buffer sources vertices are written into, <b>and who submits each of them</b>.
     *
     * <p>The distinction is the fix for a false alarm that fired 305 times in one session: a source we do
     * not submit cannot be held to "we flushed nothing", so it must not be in the same ledger as the ones
     * we do.
     */
    enum Sink {

        /**
         * The world path's source, submitted by us at {@code AFTER_ENTITIES}.
         *
         * <p>Holds the world <b>contents</b> (and, under a shader pack, the block interiors). Every quad
         * written here is ours to submit, so "emitted &gt; 0 and flushed == 0" is a real defect.
         */
        WORLD("world", HostRenderPass.WORLD_STAGE_NAME, true),

        /**
         * The carried path's source, submitted by us at {@code AFTER_TRANSLUCENT_BLOCKS}.
         *
         * <p>Holds the carried shell and the carried contents. Ours to submit, same rule.
         */
        CARRIED("carried", HostRenderPass.CARRIED_STAGE_NAME, true),

        /**
         * <b>The level renderer's own buffer source, which the world shell is handed to and never
         * submitted by us.</b>
         *
         * <p>The world shell keeps vanilla's translucent render type identity on purpose - a shader pack
         * selects its program from that identity - so its vertices go into the renderer's own source and
         * are submitted by {@code LevelRenderer}'s translucent section layer later in the same frame. We
         * cannot observe that submission and must not pretend to: a zero here would mean nothing, which is
         * exactly why the old single ledger produced a false "WORLD STAGE WROTE NOTHING" on every frame in
         * which only the world shell was drawn.
         *
         * <p>{@link #handedOverQuads} is the honest reading for this sink: how much we <em>gave</em> the
         * renderer. It is reported, never alarmed on.
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

        /**
         * The block's own inside-out model, which only the shader path draws.
         *
         * <p>Counted apart because it belongs to no config gate the others have: it exists only because a
         * shader pack needs the block's surface to come from somewhere once the block has left the chunk
         * mesh.
         */
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
     * Records {@code vertices} vertices handed to the consumer for one sink and kind.
     *
     * <p>Called per vertex, from the consumer wrapper, on the render thread. Plain {@code int}s rather
     * than atomics: nothing but the render thread and a test ever touches these, and a per-vertex
     * synchronised block would be a real cost on a hot path.
     */
    static void written(Sink sink, Kind kind, int vertices) {
        if (vertices <= 0) {
            return;
        }
        WRITTEN[index(sink, kind)] += vertices;
        POST_FLUSH[sink.ordinal()] += vertices;
    }

    /**
     * Records that our writes to a source are done for this frame: the pending window is snapshotted.
     *
     * <p>The reset is the whole point. Without it the "was anything written after the flush" question
     * would be answered by last frame's legitimate writes.
     *
     * <p>Only called for sources we submit - {@link Sink#WORLD} and {@link Sink#CARRIED}. A source we
     * hand to somebody else has no flush of ours to record; see {@link #handOver}.
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
     * Records that we have handed a source to its real submitter, for the renderer's own buffer source.
     *
     * <p>The same bookkeeping as {@link #flush} and a different name, because the fact is different: we
     * gave {@code LevelRenderer} the world shell and it will submit that source later in the frame. The
     * snapshot answers "how much did we hand over", which is all this build can honestly know.
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
            // The pending window restarts with the snapshot, so that the next frame's numbers describe
            // the next frame. A running total would make "carriedShellQuads=30" true forever after the
            // first good frame, which is the same kind of lie the intent counters told.
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
     * <b>The alarm the author's log needed.</b> A source we submit, into which geometry was written and
     * none of which we flushed.
     *
     * <p>Scoped to sources this build submits, <b>and to a named pair of numbers</b>. Both matter:
     * <ul>
     *   <li>a source it merely hands to the level renderer cannot be held to this rule - see
     *       {@link Sink#RENDERER};</li>
     *   <li>and the flushed count has to be the one that corresponds to the emitted count. Summing every
     *       kind of a sink lets one kind's flush cover another's loss - under a shader pack the world
     *       shell shares the world source with the contents, so a shell that flushed 30 would hide
     *       twelve contents that reached nothing. The caller passes both numbers; the two are then
     *       comparable by construction.</li>
     * </ul>
     *
     * @param sink the stage's own source; {@code null} is returned for one this build does not submit
     * @param emittedQuads quads the drawing loops handed out <b>for that source</b> - the count that used
     *     to be reported as if it were a flush, and that on its own cannot tell a perfect frame from a
     *     lost one
     * @param flushedQuads quads of the <em>same kinds</em> that the source held when we submitted it
     * @return the sentence for the log, or {@code null} when the stage either emitted nothing or really
     *     did flush something
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
     * The second half of the same alarm: where the lost vertices <em>did</em> go.
     *
     * <p>Stated as its own fact because it names the fix. A zero flush on one sink plus writes after the
     * other sink's flush is the exact signature the parent defect had, and it is stored independently
     * rather than inferred, so it cannot be a coincidence.
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
     * One compact line for the frame summary: what each source really took, and what leaked.
     *
     * <p>The renderer's sink is reported as <b>handed over</b> rather than flushed, because that is the
     * only thing this build can know about it. Mixing the two readings is what produced the false alarm.
     *
     * <p>Every name carries {@code Buf}, for "<b>the buffer's own ledger</b>", so that it cannot be
     * confused with the frame summary's own counts printed beside it: {@code worldShellQuads} there is
     * what the drawing loops emitted, {@code worldBufShellQuads} here is what the source actually held.
     * Two readings of the same thing are only useful when a reader can tell which is which.
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

    /**
     * Forgets everything, for a test.
     *
     * <p>Not called by the renderer: the tally is a running measure of the session, and a frame that
     * reset it could not answer "has this source ever flushed anything".
     */
    static void resetForTest() {
        java.util.Arrays.fill(WRITTEN, 0);
        java.util.Arrays.fill(FLUSHED, 0);
        java.util.Arrays.fill(POST_FLUSH, 0);
    }
}
