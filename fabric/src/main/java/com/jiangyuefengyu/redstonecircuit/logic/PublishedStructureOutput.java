package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * What a <em>drawn</em> structure answers with, at the position it is drawn at.
 *
 * <p>A physicalised structure's product block stands inside the carrier's own storage - for a Sable sub-level that
 * is a reserved plot coordinate - so the six strengths it answers with were published at that coordinate and
 * nowhere else: the blocks that travelled <em>with</em> the machine (which really do live beside it there) read
 * them, and every device in the world beside the machine read air.
 *
 * <p>The product publishes the strengths it is answering with, <b>indexed by world face</b>, at the position the
 * carrier draws it at ({@code ProductFrame#worldPosOf}), every tick it is ticked; a query about that world position
 * - which is air, and would otherwise answer nothing at all - is answered from here ({@code BlockStateSignalMixin}).
 *
 * <p>It expires, so a structure that stops being carried, is destroyed, or simply stops being ticked cannot leave a
 * phantom signal behind in the world; and it is keyed by dimension and position rather than by a level object, so
 * the whole rule is arithmetic a test can drive without a server (see {@code PublishedStructureOutputTest}).
 */
public final class PublishedStructureOutput {

    /** How long one published answer stays good without being republished, in game ticks. */
    public static final int PUBLISH_TICKS = 4;

    /** The most entries kept at once; a machine that moves republishes rather than accumulating. */
    private static final int LIMIT = 512;

    private record Key(ResourceKey<Level> dimension, long pos) {
    }

    private record Entry(int[] byWorldFace, long gameTime) {
    }

    private static final Map<Key, Entry> PUBLISHED = new HashMap<>();

    private PublishedStructureOutput() {
    }

    /**
     * Says what the structure drawn at {@code pos} answers with, until {@link #PUBLISH_TICKS} ticks have passed.
     *
     * <p>{@code byWorldFace} holds one strength per {@link Direction}, indexed by the <b>world</b> face the answer
     * belongs to: the carrier's frame has already turned the structure's own faces into world directions.
     */
    public static void publish(ResourceKey<Level> dimension, BlockPos pos, int[] byWorldFace, long gameTime) {
        if (dimension == null || pos == null || byWorldFace == null) {
            return;
        }
        if (PUBLISHED.size() >= LIMIT) {
            forgetStale(gameTime);
        }
        PUBLISHED.put(new Key(dimension, pos.asLong()), new Entry(byWorldFace.clone(), gameTime));
    }

    /**
     * What the structure drawn at {@code pos} emits towards {@code worldDirection}, or {@code 0} when nothing is
     * published there (or the publication has gone stale).
     *
     * <p>{@code worldDirection} is <b>the face the signal leaves through</b>, i.e. the face pointing at whoever is
     * asking - the same thing a strength in the product's table names. Vanilla's own query parameter is the opposite
     * (it names the way the signal travels from the asker to this block), which is why the caller converts it; see
     * {@code BlockStateSignalMixin} and {@code StructureBlock#getSignal}, which make the same conversion for a product
     * that is standing on the ground.
     */
    public static int answer(ResourceKey<Level> dimension, BlockPos pos, @Nullable Direction worldDirection,
                             long gameTime) {
        if (dimension == null || pos == null || worldDirection == null) {
            return 0;
        }
        Entry entry = PUBLISHED.get(new Key(dimension, pos.asLong()));
        if (entry == null || gameTime - entry.gameTime() > PUBLISH_TICKS) {
            return 0;
        }
        int[] strengths = entry.byWorldFace();
        int face = worldDirection.ordinal();
        return face >= 0 && face < strengths.length ? strengths[face] : 0;
    }

    /** Drops everything published longer ago than {@link #PUBLISH_TICKS} - the sweep behind the cap. */
    public static void forgetStale(long gameTime) {
        Iterator<Map.Entry<Key, Entry>> it = PUBLISHED.entrySet().iterator();
        while (it.hasNext()) {
            if (gameTime - it.next().getValue().gameTime() > PUBLISH_TICKS) {
                it.remove();
            }
        }
    }

    /**
     * <b>Withdraws the answer at one position the moment the structure has left it.</b>
     *
     * <p>The publication outlives the machine by {@link #PUBLISH_TICKS} ticks, and a device woken in that window
     * reads the answer the machine no longer gives and keeps it for ever - nothing pokes it again. Whoever moves a
     * structure away withdraws the old position <em>before</em> waking the world there.
     */
    public static void forget(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return;
        }
        PUBLISHED.remove(new Key(dimension, pos.asLong()));
    }

    /** How many answers are published right now. Tests only, so a leak is a failing assertion rather than a guess. */
    public static int sizeForTests() {
        return PUBLISHED.size();
    }

    /** Forgets every published answer. Tests only. */
    public static void forgetForTests() {
        PUBLISHED.clear();
    }
}
