package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

/**
 * <b>The low-frequency record of "which structure read which position, and what came back" (P1.38).</b>
 *
 * <h2>The question this exists to answer</h2>
 * "Two physicalised blocks do not pass signals to each other" has three completely different causes,
 * and until this class existed the log could not tell them apart:
 *
 * <ol>
 *   <li>the <b>read</b> never happened - the asking structure's solver never asked about the other
 *       block at all, so nothing was there to answer;</li>
 *   <li>the read happened and came back <b>0</b> while the other structure's block was really there
 *       holding a value - a source was reachable and hidden;</li>
 *   <li>the read happened and was answered correctly, and the problem is elsewhere (the notification
 *       half, or the world grid: the two blocks are not actually adjacent).</li>
 * </ol>
 *
 * <p>The counters separate them: {@link #describe()} distinguishes "no read was ever attempted"
 * ({@code reads=0}) from "reads were attempted and the world store answered" from "reads were
 * attempted and a carrier answered", while {@link #suppressionBlocked} counts the queries the
 * suppression flag would have hidden. The per-tick line names the asker, the position asked about, the
 * local coordinate it means inside the answerer, and the value.
 *
 * <h2>Why it cannot flood the log</h2>
 * <b>At most one line per level tick</b>, whatever the solver does: {@link #record} keeps only the
 * first read of the tick and {@link #closeTick} writes that one line and clears it. A machine with two
 * hundred components asking two hundred times per tick therefore costs one line per tick, and the
 * counters carry the rest. {@code debugLog=false} turns the whole thing off, line and counting alike;
 * with it off, {@link #record} returns after one boolean test.
 *
 * <h2>Cost</h2>
 * Per read: one boolean test, and on the first read of a tick one {@link BlockPos#immutable()} plus one
 * field store. No collection, no queue, no growth: the state is five longs and one small record, and
 * {@link #reset} drops even those on a level unload. Nothing here reads a level, an entity or a
 * sub-level container, so it is safe on the shutdown path.
 */
public final class SignalReadTrace {

    private SignalReadTrace() {
    }

    /** Reads answered from the world projection table - a carrier's host at its world position. */
    private static long projectedReads;
    /** Reads answered from a structure's own coordinate - P1.36's inside-the-machine read. */
    private static long structureReads;
    /** Reads refused because the coordinate belongs to another live sub-level - P1.37's guard. */
    private static long declines;
    /** Queries where the suppression flag would have hidden a carrier source - P1.38's symptom. */
    private static long suppressionBlocked;
    /** Reads answered from the ordinary world store. */
    private static long worldStoreReads;

    /** The first read of the current tick, held so the line can name it; cleared every tick. */
    @Nullable
    private static Read lastRead;

    /**
     * One read, as the line needs it: who asked, what about, what each source said, and the answer.
     *
     * @param asker the structure whose solve asked, or {@code null} when the asker was the world
     * @param position the position that was asked about, in the asker's own frame
     * @param crossStructure true when the position belongs to a <b>different</b> sub-level than the
     *     asker - the seam this round is about
     * @param worldProjection what the world projection source said at that position, as text: the
     *     host's local coordinate when it holds one, or {@code none-hit} / {@code none-indexed}
     * @param structureFrame what the structure frame did with it: {@code answered}, {@code declined}
     *     (P1.37's ownership guard refused it) or {@code none}
     * @param finalSource which source answered in the end, named for the line
     * @param answer the value the query was answered with, 0-15
     */
    public record Read(@Nullable CarrierId asker, BlockPos position, boolean crossStructure,
                       String worldProjection, String structureFrame, String finalSource, int answer) {

        /** The line, with the words a reader greps for: {@code cross-structure read attempt}. */
        public String describe() {
            return "cross-structure read attempt: asker="
                    + (asker == null ? "the world" : asker.shortId())
                    + " asked=" + position.toShortString()
                    + (crossStructure ? " (another sub-level's coordinate)" : " (own structure)")
                    + " worldProjection=" + worldProjection
                    + " structureFrame=" + structureFrame
                    + " finalAnswer=" + finalSource + "=" + answer;
        }
    }

    /**
     * Records one read of a carrier-hosted position, or one read of the ordinary world store.
     *
     * <p>Gated on {@code debugLog}: with the switch off this is one boolean test and nothing else.
     * Counting happens on every recorded read; the <b>line</b> is written by {@link #closeTick}, so a
     * busy tick still writes one line.
     *
     * @param asker the structure whose solve asked, or {@code null} for the world / a vanilla device
     * @param position the position asked about
     * @param crossStructure true when the position belongs to another live sub-level than the asker
     * @param worldProjection what the projection source said, as text
     * @param structureFrame what the structure frame did, as text
     */
    public static void record(@Nullable CarrierId asker, @Nullable BlockPos position,
                              boolean crossStructure, String worldProjection, String structureFrame,
                              BlockSignalSources.Source finalSource, int answer) {
        if (!RCConfig.debugLog() || position == null || finalSource == null) {
            return;
        }
        if (finalSource == BlockSignalSources.Source.WORLD_STORE) {
            worldStoreReads++;
        } else if (finalSource == BlockSignalSources.Source.WORLD_PROJECTION) {
            projectedReads++;
        } else if (finalSource == BlockSignalSources.Source.STRUCTURE_FRAME) {
            structureReads++;
        }
        if (lastRead == null) {
            lastRead = new Read(asker, position.immutable(), crossStructure, worldProjection,
                    structureFrame, finalSource.name().toLowerCase(Locale.ROOT), answer);
        }
    }

    /**
     * True when a read should be recorded at all: the one boolean the hot path pays for.
     *
     * <p>Exposed so a caller that has to do work to build the record - the carrier layer, which asks
     * which structure's plot holds a coordinate - can skip that work entirely when the operator has not
     * asked for lines. {@link #record} repeats the test, so a caller that ignores this is still safe.
     */
    public static boolean enabled() {
        return RCConfig.debugLog();
    }

    /**
     * Counts one query that the suppression flag hid from the carrier sources.
     *
     * <p>Called from the rule rather than from the mixin so "the world store was suppressed and nothing
     * else was reachable" is one counter instead of a condition spread over two files. Counted only
     * while {@code debugLog} is on, like every other counter here.
     */
    public static void suppressionBlocked() {
        if (RCConfig.debugLog()) {
            suppressionBlocked++;
        }
    }

    /** Counts one P1.37 decline: a coordinate refused because it belongs to another live sub-level. */
    public static void declined() {
        if (RCConfig.debugLog()) {
            declines++;
        }
    }

    /**
     * Ends one level tick: the one line for that tick, then the slot is cleared.
     *
     * <p>Returns the line rather than logging it, exactly like {@code SolverLog#tickEnd}, so the
     * once-per-tick property and the wording are asserted by an offline unit test; {@link #flush} is
     * the production caller that writes it.
     */
    public static java.util.List<String> closeTick() {
        Read read = lastRead;
        lastRead = null;
        if (read == null || !RCConfig.debugLog()) {
            return java.util.List.of();
        }
        return java.util.List.of("[redstonecircuit] " + read.describe());
    }

    /** Writes the tick's line, if there is one; called once per level tick. */
    public static void flush() {
        for (String line : closeTick()) {
            RCConfig.LOGGER.info(line);
        }
    }

    /**
     * One compact summary for the existing {@code solver:} line, so the counters reach the log without a
     * command being typed - the point of this round. Empty when nothing has been read yet, which keeps
     * the summary line unchanged in a level where nothing is physicalised.
     */
    public static String describe() {
        if (projectedReads == 0 && structureReads == 0 && worldStoreReads == 0 && declines == 0
                && suppressionBlocked == 0) {
            return "";
        }
        return "carrierReads: worldProjection=" + projectedReads
                + " via=world-projection structureFrame=" + structureReads
                + " via=structure-frame structureDeclines=" + declines
                + " worldStore=" + worldStoreReads
                + " suppressionBlocked=" + suppressionBlocked
                + " lastRead=" + lastReadMode();
    }

    /** The three-state marker the author reads: which frame the <b>last</b> structure query used. */
    public static String lastReadMode() {
        if (structureReads > 0 && declines == 0) {
            return "structure_frame";
        }
        if (declines > 0) {
            return "cross_structure_declined";
        }
        return "world_projection";
    }

    /** How many reads a carrier answered at its own world position; for the acceptance report. */
    public static long projectedReads() {
        return projectedReads;
    }

    /** How many reads the structure frame answered; P1.36's counter. */
    public static long structureReads() {
        return structureReads;
    }

    /** How many reads P1.37's ownership guard refused. */
    public static long declines() {
        return declines;
    }

    /** How many queries the suppression flag hid from the carrier sources - P1.38's evidence. */
    public static long suppressionBlockedCount() {
        return suppressionBlocked;
    }

    /** How many reads the ordinary world store answered. */
    public static long worldStoreReads() {
        return worldStoreReads;
    }

    /**
     * Drops every counter and the pending tick slot.
     *
     * <p>Called when a level unloads: the counters describe a level's structures, and the pending slot
     * holds a position of one. No level, entity or container is touched here.
     */
    public static void reset() {
        projectedReads = 0;
        structureReads = 0;
        declines = 0;
        suppressionBlocked = 0;
        worldStoreReads = 0;
        lastRead = null;
    }
}
