package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

/**
 * <b>The low-frequency record of "which structure read which position, and what came back".</b>
 *
 * <p>"两块物理化方块之间信号传不过去"有三种原因: 读取根本没发生、读取发生但答 0 而源其实在、读取正常而问题在别处。
 * 计数器把它们分开: {@link #describe()} 区分世界投影／结构自身坐标／世界存档，{@link #suppressionBlocked} 数被抑制
 * 开关挡掉的查询。每个 level tick 最多一行，{@code debugLog=false} 时只花一次布尔判断。
 */
public final class SignalReadTrace {

    private SignalReadTrace() {
    }

    /** Reads answered from the world projection table - a carrier's host at its world position. */
    private static long projectedReads;
    /** Reads answered from a structure's own coordinate - the inside-the-machine read. */
    private static long structureReads;
    /** Reads refused because the coordinate belongs to another live sub-level - the ownership guard. */
    private static long declines;
    /** Queries where the suppression flag would have hidden a carrier source. */
    private static long suppressionBlocked;
    /** Reads answered from the ordinary world store. */
    private static long worldStoreReads;

    /** The first read of the current tick, held so the line can name it; cleared every tick. */
    @Nullable
    private static Read lastRead;

    /**
     * One read, as the line needs it: who asked ({@code null} for the world), what about, what each source said, and
     * the answer. {@code position} is in the asker's own frame; {@code crossStructure} is true when it belongs to a
     * <b>different</b> sub-level than the asker; {@code worldProjection} is the host's local coordinate or
     * {@code none-hit} / {@code none-indexed}; {@code structureFrame} is {@code answered}, {@code declined} or
     * {@code none}; {@code answer} is 0-15.
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

    /** Records one read of a carrier-hosted position, or one read of the ordinary world store. Gated on
     * {@code debugLog}: with the switch off this is one boolean test and nothing else. Counting happens on every
     * recorded read; the <b>line</b> is written by {@link #closeTick}, so a busy tick still writes one line. */
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

    /** True when a read should be recorded at all: the one boolean the hot path pays for. Exposed so a caller that has
     * to do work to build the record can skip it; {@link #record} repeats the test, so ignoring this is still safe. */
    public static boolean enabled() {
        return RCConfig.debugLog();
    }

    /** Counts one query that the suppression flag hid from the carrier sources. Counted only while {@code debugLog} is
     * on, like every other counter here. */
    public static void suppressionBlocked() {
        if (RCConfig.debugLog()) {
            suppressionBlocked++;
        }
    }

    /** Counts one decline: a coordinate refused because it belongs to another live sub-level. */
    public static void declined() {
        if (RCConfig.debugLog()) {
            declines++;
        }
    }

    /** Ends one level tick: the one line for that tick, then the slot is cleared. Returns the line rather than logging
     * it, so the once-per-tick property and the wording are asserted by an offline unit test; {@link #flush} is the
     * production caller that writes it. */
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

    /** One compact summary for the existing {@code solver:} line, so the counters reach the log without a command being
     * typed. Empty when nothing has been read yet, which keeps the summary line unchanged. */
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

    /** The three-state marker: which frame the <b>last</b> structure query used. */
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

    /** How many reads the structure frame answered. */
    public static long structureReads() {
        return structureReads;
    }

    /** How many reads the ownership guard refused. */
    public static long declines() {
        return declines;
    }

    /** How many queries the suppression flag hid from the carrier sources. */
    public static long suppressionBlockedCount() {
        return suppressionBlocked;
    }

    /** How many reads the ordinary world store answered. */
    public static long worldStoreReads() {
        return worldStoreReads;
    }

    /** Drops every counter and the pending tick slot; called when a level unloads, because the counters describe a
     * level's structures and the pending slot holds a position of one. */
    public static void reset() {
        projectedReads = 0;
        structureReads = 0;
        declines = 0;
        suppressionBlocked = 0;
        worldStoreReads = 0;
        lastRead = null;
    }
}
