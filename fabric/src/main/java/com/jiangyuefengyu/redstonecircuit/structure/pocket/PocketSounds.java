package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.Optional;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.structure.PlotAllocator;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * <b>声音从结构里出来：口袋里没有人，所以那边的音效一个都发不出去。</b>
 *
 * <p>A structure's blocks keep being ordinary blocks in a real level ({@link PocketDimension}), so vanilla plays their
 * sounds the ordinary way - to the players of <em>that</em> level, of whom there are never any. A note block, a lever,
 * a button or a repeater inside a captured structure therefore runs correctly and is silent.
 *
 * <p>This re-issues such a sound on the level the plot's owner stands in, at the cell that block is <b>drawn</b> in:
 * the structure's own cell, or wherever a physicalized structure has carried it - {@link ProductFrame}, the same funnel
 * the block's signals go through, so a sound can never be heard somewhere the machine is not. One point source per
 * structure, attenuated by the ordinary distance rule.
 *
 * <p>Only sounds <b>at a position</b> are bridged; a sound attached to an entity belongs to that entity, which is not
 * ours to move. NeoForge states that as an event ({@code PlayLevelSoundEvent.AtPosition}); this loader has no such
 * event, so {@code ServerLevelSoundMixin} stands in for it at the one call every position-shaped sound funnels through.
 */
public final class PocketSounds {

    /** How far a chain of structures inside structures is followed before the answer is called a loop. */
    private static final int MAX_NESTING = 16;

    /** The world level a pocket sound belongs to, and the cell it is heard from. */
    public record Source(ResourceKey<Level> dimension, BlockPos pos) {
    }

    /**
     * Re-issues a sound about to be played at {@code position} in the pocket on the level its owner stands in, and
     * answers whether the pocket's own copy is now to be dropped.
     *
     * <p>The original is dropped by the caller at the <em>entry</em> of the call that would have played it, so the
     * pocket never speaks while the world speaks once; leaving it standing would send the same sound twice the day
     * anything does listen in there.
     */
    public static boolean replayAtOwner(ServerLevel pocket, Vec3 position, Holder<SoundEvent> sound,
            SoundSource source, float volume, float pitch) {
        if (!RCConfig.structurePocketSounds()) {
            return false;
        }
        // One dimension comparison for every sound in the game: everything played outside the pocket is not ours.
        if (!pocket.dimension().equals(PocketDimension.STRUCTURE_SPACE)) {
            return false;
        }
        Source heard = worldSource(pocket, position).orElse(null);
        if (heard == null) {
            return false;
        }
        ServerLevel owner = pocket.getServer() == null ? null : pocket.getServer().getLevel(heard.dimension());
        if (owner == null) {
            return false;
        }
        // The cell, not the sound's own coordinates: the distance rule is the level's, measured from where the world
        // hears it, so the pocket's coordinate space may not leak into the replay - nor may the volume, which is why
        // it is handed on unchanged.
        BlockPos at = heard.pos();
        owner.playSeededSound(null, at.getX() + 0.5, at.getY() + 0.5, at.getZ() + 0.5,
                sound, source, volume, pitch, owner.getRandom().nextLong());
        return true;
    }

    /**
     * Where a sound played at {@code position} in this level is heard: the product block owning the plot that holds
     * this position, or the world block at the end of a chain of structures inside structures.
     *
     * <p>Empty for a position outside every plot, and for a plot whose owner block is nowhere at all - a contraption
     * that keeps its blocks in a map of its own leaves a product riding it with no level position left to look up (the
     * carrier frame hands its blocks in; a plot record has no carrier to name). A block a <b>Sable</b> sub-level
     * carries stays in the level, so that one is answered: see {@link #drawnPosition}.
     */
    public static Optional<Source> worldSource(ServerLevel level, Vec3 position) {
        ServerLevel current = level;
        Vec3 at = position;
        for (int step = 0; step < MAX_NESTING; step++) {
            Source source = ownerOf(current, at).orElse(null);
            if (source == null) {
                return Optional.empty();
            }
            ServerLevel owner = current.getServer() == null ? null : current.getServer().getLevel(source.dimension());
            if (owner == null) {
                return Optional.empty();
            }
            if (!owner.dimension().equals(PocketDimension.STRUCTURE_SPACE)) {
                return Optional.of(new Source(source.dimension(), drawnPosition(owner, source.pos())));
            }
            // A product standing inside another structure: the sound belongs to the outer one, so ask again from the
            // inner block's own cell.
            current = owner;
            at = Vec3.atCenterOf(source.pos());
        }
        return Optional.empty();
    }

    /**
     * Where that block is <b>drawn</b> right now: its own cell while it stands, or the place its carrier has taken it
     * to when a physicalized structure is moving it - the pose through the block centre, the one implementation of
     * "where is this drawn" this mod has.
     */
    private static BlockPos drawnPosition(ServerLevel owner, BlockPos pos) {
        ProductFrame frame = ProductFrame.carriedAt(owner, pos);
        return frame.carried() ? frame.worldPosOf(pos) : pos;
    }

    /** The block that owns the plot holding this position, if there is one and it is still standing in its world. */
    private static Optional<Source> ownerOf(ServerLevel level, Vec3 position) {
        int plot = PlotAllocator.indexAt(Mth.floor(position.x), Mth.floor(position.z));
        if (plot < 0) {
            return Optional.empty();
        }
        PocketPlots.Entry entry = PocketPlots.get(level).entry(plot);
        if (entry == null) {
            return Optional.empty();
        }
        ServerLevel owner = level.getServer() == null ? null : level.getServer().getLevel(entry.ownerDimension());
        if (owner == null || !owner.isLoaded(entry.ownerPos())
                || !(owner.getBlockEntity(entry.ownerPos()) instanceof StructureBlockEntity)) {
            return Optional.empty();
        }
        return Optional.of(new Source(entry.ownerDimension(), entry.ownerPos()));
    }

    private PocketSounds() {
    }
}
