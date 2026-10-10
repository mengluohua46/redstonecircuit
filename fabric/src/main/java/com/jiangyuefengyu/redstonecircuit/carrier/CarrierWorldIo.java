package com.jiangyuefengyu.redstonecircuit.carrier;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import org.jetbrains.annotations.Nullable;

/**
 * One structure's open side to the world: given a local coordinate and a local side, read what the world is
 * signalling at the place that side currently points.
 *
 * <p>The local coordinate and side are turned into a world position and direction by the carrier's own pose - the
 * exact forward transform, not a snapped one - and the strength comes from {@link CarrierWorldSignals}, the mod's one
 * world-signal read, so this is what a host block on the ground would see. A read from inside a structure is a read
 * <em>by a carrier</em>: the bridge names its reader ({@link #reader()}) and refuses exactly that carrier's own
 * projected hosts, whose raw emission would cancel the hop charge the inner-neighbour relationship already applied
 * and make a run of carried dust stop fading, while another structure's projection is still read. Nothing here touches
 * the world; only the direction is approximated, because the block grid has six of them
 * ({@link CarrierDirections#nearest}).
 */
public final class CarrierWorldIo {

    private final CarrierPose pose;
    private final CarrierWorldSignals signals;

    /**
     * The carrier doing the reading, or {@code null} when this bridge does not name one. Used for one thing: telling
     * the shared world read whose own projected hosts must not answer, so a component the reader's own solve is
     * looking at cannot arrive a second time as an unattenuated external supply.
     */
    @Nullable
    private final CarrierId reader;

    /**
     * The level the readings are made in, or {@code null} when this bridge has none. Needed only for the owner-aware
     * form of the read ({@link #signalIntoOwnFrame}), which takes the level the projection table is indexed by.
     */
    @Nullable
    private final ServerLevel level;

    /**
     * The carrier index this bridge reads its reader out of, resolved on first use and reused for the life of the
     * bridge, which is one carrier's pass; not a constructor parameter, because the level this bridge holds is a
     * lookup away from it.
     */
    @Nullable
    private CarriedHostIndex index;
    private boolean indexResolved;

    /**
     * How many reads have gone through a bridge that could not refuse its reader's own projection: the owner-aware
     * read needs both the reader's identity and the level the projection table is indexed by, and a bridge missing
     * either cannot filter anything - an unfiltered read is the fault itself, not a smaller version of the fix. So the
     * degradation is <b>counted and named</b> rather than silent. Production cannot reach it: the only builder on that
     * side takes both from the carrier in hand and the tick context.
     */
    private static volatile int unfilteredReads = 0;
    private static volatile boolean unfilteredWarned = false;

    /** How many reads have been made without the owner filter; read by the test, and by nothing else. */
    public static int unfilteredReadCount() {
        return unfilteredReads;
    }

    /** A bridge that names no reader: every projection is read, and it filters nothing. */
    public CarrierWorldIo(CarrierPose pose, CarrierWorldSignals signals) {
        this(pose, signals, null, null);
    }

    /**
     * {@code reader} is the carrier whose own projected hosts must not be taken as an external supply, or {@code null}
     * to read every projection; {@code level} is where the readings happen, or {@code null} when no owner-aware read is
     * possible, in which case every read is counted and, with a reader named, warned about once.
     */
    public CarrierWorldIo(CarrierPose pose, CarrierWorldSignals signals, @Nullable CarrierId reader,
                          @Nullable ServerLevel level) {
        this.pose = pose;
        this.signals = signals;
        this.reader = reader;
        this.level = level;
    }

    /** The pose this bridge was built from. */
    public CarrierPose pose() {
        return pose;
    }

    /** The carrier whose own projection this bridge refuses, or {@code null} when nothing is refused. */
    @Nullable
    public CarrierId reader() {
        return reader;
    }

    /** Where a local block currently is in the world. */
    public BlockPos worldPosOf(BlockPos localPos) {
        return CarrierProjection.project(pose, localPos);
    }

    /**
     * <b>The analogue output of the world block a local neighbour projects onto.</b> A comparator's other half, and the
     * only reason a carried host's inner comparator can read a cake standing in the world beside where its host block
     * is drawn: a vanilla analogue output is not a redstone signal, so {@link #signalIntoOwnFrame} can never carry it.
     * It goes through the same read the world host uses, direction included, because a block may answer this question
     * differently depending on which side is asked (a helm does); the direction is the local side turned into the world
     * by the pose, which is what a comparator standing next to the drawn block would pass.
     */
    public int analogOutputIntoOwnFrame(BlockPos localPos, Direction localDirection) {
        if (level == null) {
            return 0;
        }
        return InnerRedstoneNetwork.readAnalogOutput(level,
                worldPosOf(localPos.relative(localDirection)),
                CarrierDirections.worldDirection(pose, localDirection));
    }

    /**
     * The same for the block at a <b>local</b> coordinate that is also a coordinate in the level: true for a Sable
     * sub-level, whose plot coordinates are real level coordinates, and not for a Create contraption, whose local
     * coordinates are relative to its anchor - the caller gates it on that fact. Its direction is the <b>local</b> one,
     * because what is read is a block of the structure's own space.
     */
    public int analogOutputAtLocal(BlockPos localPos, Direction localDirection) {
        return level == null ? 0 : InnerRedstoneNetwork.readAnalogOutput(level, localPos, localDirection);
    }

    /**
     * The world signal reaching local block {@code localPos} across its local side {@code localDirection}, through this
     * bridge's own world-signal source: the raw form, with no view of who is asking, so it cannot refuse the reader's
     * own projected hosts, and the solve reads through {@link #signalIntoOwnFrame} instead.
     *
     * <p>The mirror image of a lever sitting against the host block on that side: the world position is the
     * neighbour's, and the direction asked about points from the host <em>towards</em> that neighbour - which is
     * vanilla's convention for a signal query.
     */
    public int signalInto(BlockPos localPos, Direction localDirection) {
        return signalInto(localPos, localDirection, signals);
    }

    /**
     * <b>The same read with this bridge's reader named - the form the solve uses.</b> The named carrier's <b>own</b>
     * projected hosts are refused: such a host is a node of the running solve, arriving already through the
     * inner-neighbour relationship, which charges the hop, so taking the projection's raw emission as well would
     * cancel that charge and a run of carried dust would stop fading. A host <b>another</b> carrier projects there is
     * still read, which is the seam two independently physicalised blocks need. The vanilla half is read exactly as
     * before, a bridge with no reader filters nothing, and a frozen bridge never reaches here at all.
     */
    public int signalIntoOwnFrame(BlockPos localPos, Direction localDirection) {
        if (reader == null || level == null) {
            // No silent degradation. Note which of the two makes this unfiltered, because they are different
            // facts: a bridge with no reader never had a carrier identity to give (a fixture), while a reader
            // with no level is the one production could lose if the level stopped being threaded through.
            CarrierId named = reader;
            unfilteredReads++;
            if (named != null && !unfilteredWarned) {
                synchronized (CarrierWorldIo.class) {
                    if (!unfilteredWarned) {
                        unfilteredWarned = true;
                        RCConfig.LOGGER.warn("[redstonecircuit] carrier bridge for {} was built without "
                                + "a level, so its world read cannot refuse that carrier's own projected "
                                + "hosts - the uncharged copy of an inner neighbour is back for "
                                + "this bridge, and a run of carried dust will stop fading. Production "
                                + "always has the level (CarrierStore#contextOf); logged once, every "
                                + "such read is counted.",
                                named.shortId());
                    }
                }
            }
            return signalInto(localPos, localDirection, signals);
        }
        return InnerRedstoneNetwork.seamReader(readerTypeOf(localPos), () ->
                signalInto(localPos, localDirection,
                        (worldPos, worldDirection) ->
                                InnerRedstoneNetwork.worldSignalAt(level, worldPos, worldDirection, reader)));
    }

    /**
     * <b>The component doing this read, looked up in the carrier index at its own local coordinate.</b> The generic way
     * to find an asker is to step back one block along the query direction, which is exact for a world query, but this
     * bridge turns a <em>local</em> side into a world direction through the pose and snaps that to an axis, so a
     * machine turned by an angle has a world direction whose reverse does not point back at the reading block and the
     * hop charge would silently go missing. The index has the reader's local coordinate outright, so the charge is
     * exact. A landed host is read from the world instead, because that is where its live slot lives, and a reader that
     * cannot be resolved answers {@code null} - uncharged and never refused, since a lookup failure must not take a
     * solve down.
     */
    @Nullable
    private ComponentType readerTypeOf(BlockPos localPos) {
        if (reader == null || level == null || localPos == null) {
            return null;
        }
        try {
            Slot slot = null;
            CarriedHostIndex index = hostIndex();
            CarriedHost host = index == null ? null : index.at(reader, localPos);
            if (host != null) {
                slot = host.slot();
            }
            if (slot == null) {
                slot = CarrierStore.slotAt(level, worldPosOf(localPos));
            }
            return slot == null ? null : slot.type;
        } catch (RuntimeException | LinkageError unreadable) {
            return null;
        }
    }

    /**
     * The carrier index, resolved <b>once per bridge</b> rather than once per read: the lookup goes through the level's
     * data storage, and a bridge's reads are per component per side, so asking here would put a data-storage lookup on
     * the solve's hot path. A failure is remembered as "no index", because a bookkeeping failure must not take a solve
     * down.
     */
    @Nullable
    private CarriedHostIndex hostIndex() {
        if (!indexResolved) {
            indexResolved = true;
            try {
                index = CarrierStore.indexOf(level);
            } catch (RuntimeException | LinkageError unreadable) {
                index = null;
            }
        }
        return index;
    }

    /**
     * The same read against an explicitly supplied world-signal source; private, because production always reads
     * through this bridge's own source or through the merged read with the reader named.
     */
    private int signalInto(BlockPos localPos, Direction localDirection, CarrierWorldSignals source) {
        if (localDirection == null || source == null) {
            return 0;
        }
        BlockPos worldPos = worldPosOf(localPos.relative(localDirection));
        Direction worldDirection = CarrierDirections.worldDirection(pose, localDirection);
        return PowerSolver.clamp(source.externalSignal(worldPos, worldDirection));
    }

    /**
     * The strongest world signal reaching {@code localPos} from any of its six sides - the omnidirectional supply a
     * piece of inner dust draws, which has no input side. Both this and the world implementation take the maximum over
     * the same six sides, so a wire inside a machine behaves like a wire on the ground.
     */
    public int signalAround(BlockPos localPos) {
        int best = 0;
        for (Direction direction : Direction.values()) {
            best = Math.max(best, signalInto(localPos, direction));
            if (best >= PowerSolver.MAX_POWER) {
                return PowerSolver.MAX_POWER;
            }
        }
        return best;
    }
}
