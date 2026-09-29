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
 * One structure's open side to the world: given a local coordinate and a local side, read what the
 * world is signalling at the place that side currently points.
 *
 * <h2>What one read has to do, in order</h2>
 * <ol>
 *   <li><b>Where.</b> The local coordinate of the neighbour is turned into a world position by the
 *       carrier's own pose - the exact forward transform, not a snapped one, so a machine turned 9.5
 *       degrees reads the block it is really up against.</li>
 *   <li><b>Which way.</b> The local side is turned into a world direction through the pose's
 *       quaternion ({@link CarrierDirections#worldDirection}), because vanilla's query needs a
 *       direction in the world's frame - the same red line the other half of the bridge obeys.</li>
 *   <li><b>How strong.</b> Delegated to {@link CarrierWorldSignals}, which is the mod's one
 *       implementation of "read a world signal" and therefore identical to what a host block standing
 *       on the ground would see on that side.</li>
 * </ol>
 *
 * <h2>P1.41: the reader's identity, and the one projection it must not take</h2>
 * A read made from inside a structure is a read made <em>by a carrier</em>, and since P1.38 the shared
 * world read also consults the projection table. That table holds every carrier's hosts - including the
 * reader's own - and the projected answer is a raw emission with no hop charge. For a host of
 * <b>another</b> structure that is what makes two independently physicalised blocks able to read each
 * other at all (P1.38); for the reader's <b>own</b> host it is the same component the inner-neighbour
 * relationship already delivered, charged, and taking the uncharged copy as well makes a run of carried
 * dust stop fading. So this bridge can say who it is ({@link #reader()}), and the owner-aware read
 * refuses exactly that carrier's projection and nobody else's - see
 * {@link InnerRedstoneNetwork#projectionReadableBy}.
 *
 * <p>With no reader named - {@code reader == null} - nothing is filtered, which is the behaviour every
 * reader had before this round and what the world-side solve still asks for.
 *
 * <h2>What it deliberately does not do</h2>
 * It never touches the world. No block is read through a {@code BlockGetter} it owns, no state is
 * written, no notification is sent: it is a pure translation from the structure's own frame into the
 * world's, and the value it returns is used by the in-structure solver exactly as a lever next to a
 * ground host block would be.
 *
 * <p>One consequence worth writing down, because it is a real limitation rather than an oversight:
 * the world's block grid can only describe six directions, so a side that points between two axes
 * snaps to the nearer one ({@link CarrierDirections#nearest}). The <em>position</em> read is never
 * approximated.
 */
public final class CarrierWorldIo {

    private final CarrierPose pose;
    private final CarrierWorldSignals signals;

    /**
     * The carrier doing the reading, or {@code null} when this bridge does not name one.
     *
     * <p>P1.41. It is used for one thing only: telling the shared world read whose own projected hosts
     * must not answer, so that a component the reader's own solve is looking at cannot arrive a second
     * time as an unattenuated external supply. See the class doc.
     */
    @Nullable
    private final CarrierId reader;

    /**
     * The level the readings are made in, or {@code null} when this bridge has none.
     *
     * <p>Needed only for the owner-aware form of the read ({@link #signalIntoOwnFrame}), because the
     * merged read is the one that takes the level the projection table is indexed by. It is the same
     * {@code ServerLevel} the bridge's {@link CarrierWorldSignals} closes over, held here because a
     * lambda cannot be asked what it captured - and production has it in hand where the bridge is built
     * ({@code CarrierNetworks#worldIoOf}).
     */
    @Nullable
    private final ServerLevel level;

    /**
     * P1.45: the carrier index this bridge reads its reader out of, resolved on first use.
     *
     * <p>Not a constructor parameter: the bridge is built by {@code CarrierNetworks#worldIoOf}, whose
     * call is pinned by three rounds' worth of wiring assertions, and the index is a lookup away from
     * the level this bridge already holds. Resolved once and reused for the life of the bridge, which is
     * one carrier's pass - see {@link #hostIndex()}.
     */
    @Nullable
    private CarriedHostIndex index;
    private boolean indexResolved;

    /**
     * <b>P1.42: how many reads have gone through a bridge that could not refuse its reader's own
     * projection.</b>
     *
     * <p>The owner-aware read needs two things this bridge must have been given: the reader's identity
     * and the level the projection table is indexed by. A bridge missing either cannot filter anything,
     * and an unfiltered read is not a smaller version of the fix - it is the defect (a run of carried
     * dust stops fading, through the projection the inner relationship already delivered). So the
     * degradation is <b>counted and named</b> rather than silent, and a test pins it: "the parameter is
     * missing, so fall back to no filtering" is exactly the design that hides a regression until a
     * player notices the symptom again.
     *
     * <p>Production cannot reach it: {@code CarrierNetworks#worldIoOf} is the only builder on that side
     * and takes both from the carrier in hand and the tick context, whose level
     * {@code CarrierStore#contextOf} fills from its own non-null {@code ServerLevel} parameter.
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
     * @param reader the carrier whose own projected hosts this bridge must not take as an external
     *     supply, or {@code null} to read every projection - the unfiltered behaviour, which is what a
     *     fixture with no carrier identity and the world-side path both get
     * @param level the level the readings happen in, or {@code null} when this bridge cannot make an
     *     owner-aware read - in which case every read it makes is counted and, when a reader was named,
     *     warned about once; see {@link #unfilteredReadCount()}
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
     * <b>The analogue output of the world block a local neighbour projects onto.</b>
     *
     * <p>The comparator's other half, and the only reason a carried host's inner comparator can read a
     * cake standing in the world beside where its host block is drawn: a vanilha analogue output is not a
     * redstone signal, so {@link #signalIntoOwnFrame} can never carry it.
     */
    public int analogOutputIntoOwnFrame(BlockPos localPos, Direction localDirection) {
        if (level == null) {
            return 0;
        }
        return InnerRedstoneNetwork.readAnalogOutput(level,
                worldPosOf(localPos.relative(localDirection)));
    }

    /**
     * The same for the block at a <b>local</b> coordinate that is also a coordinate in the level.
     *
     * <p>True for a Sable sub-level, whose plot coordinates are real level coordinates; not true for a
     * Create contraption, whose local coordinates are relative to its anchor. The caller gates it on that
     * fact rather than this method guessing - see {@code CarrierSpace#structureAnalogOutput}.
     */
    public int analogOutputAtLocal(BlockPos localPos) {
        return level == null ? 0 : InnerRedstoneNetwork.readAnalogOutput(level, localPos);
    }

    /**
     * The world signal reaching local block {@code localPos} across its local side
     * {@code localDirection}, through this bridge's own world-signal source.
     *
     * <p>The raw form: it is the reader's {@link CarrierWorldSignals} as handed in, with no view of who
     * is asking, so it cannot refuse the reader's own projected hosts (P1.41). The solve therefore reads
     * through {@link #signalIntoOwnFrame}; this form is what is left for a caller that has no owner to
     * name.
     *
     * <p>The mirror image of a lever sitting against the host block on that side: the world position is
     * the neighbour's, and the direction asked about points from the host <em>towards</em> that
     * neighbour - which is vanilla's convention for a signal query, since it is called as
     * {@code getSignal(neighbourPos, hostToNeighbour)}.
     */
    public int signalInto(BlockPos localPos, Direction localDirection) {
        return signalInto(localPos, localDirection, signals);
    }

    /**
     * <b>P1.41: the same read with this bridge's reader named - the form the solve uses.</b>
     *
     * <p>{@link #reader()} says which carrier is asking, and the read is then the mod's one
     * implementation of the merged world read with that carrier's <b>own</b> projected hosts refused:
     * {@code InnerRedstoneNetwork#worldSignalAt(level, worldPos, direction, reader)}. A reader's own host
     * is a node of the running solve - it already arrives through the inner-neighbour relationship, which
     * charges the hop - so taking the projection's raw emission as well would cancel that charge and a run
     * of carried dust would stop fading. A host <b>another</b> carrier projects there is still read, which
     * is the seam P1.38 opened and must not be lost.
     *
     * <p>Three things this deliberately keeps: the vanilla half is read exactly as before (only the
     * projection contribution of the named carrier is filtered), a bridge with no reader filters nothing,
     * and a frozen bridge ({@code io == null} at the caller) never reaches here at all. A bridge with no
     * level - a fixture - cannot filter either, and that is <b>counted and warned about</b> rather than
     * passed off as a read: see {@link #unfilteredReadCount()}.
     */
    public int signalIntoOwnFrame(BlockPos localPos, Direction localDirection) {
        if (reader == null || level == null) {
            // NO SILENT DEGRADATION (P1.42). Note which of the two makes this unfiltered, because they
            // are different facts: a bridge with no reader at all is a caller that never had a carrier
            // identity to give (a fixture), while a reader with no level is the one production could
            // lose if the level stopped being threaded through - and that one is worth a warning.
            CarrierId named = reader;
            unfilteredReads++;
            if (named != null && !unfilteredWarned) {
                synchronized (CarrierWorldIo.class) {
                    if (!unfilteredWarned) {
                        unfilteredWarned = true;
                        RCConfig.LOGGER.warn("[redstonecircuit] carrier bridge for {} was built without "
                                + "a level, so its world read cannot refuse that carrier's own projected "
                                + "hosts (P1.42) - the uncharged copy of an inner neighbour is back for "
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
     * <b>P1.45: the component doing this read, looked up in the carrier index at its <em>own local</em>
     * coordinate.</b>
     *
     * <h2>Why the local coordinate and not "one step back in the world"</h2>
     * A hop is charged to the receiver, and a projection-sourced neighbour has to be told who that is.
     * The generic way to find an asker is to step back one block along the query direction
     * ({@code InnerRedstoneNetwork#seamReaderAt}), which is exact for a world query - but this bridge
     * turns a <em>local</em> side into a world direction through the pose, and
     * {@code CarrierDirections#nearest} snaps that to an axis. A machine that has been turned by an angle
     * therefore has a world direction whose reverse does not point back at the reading block, and the
     * charge would silently go missing on exactly the machines this mod exists for. The index has the
     * reader's local coordinate outright, so the charge is exact and free of that approximation.
     *
     * <p>A landed host is read from the world instead, because that is where a landed host's live slot
     * lives ({@code CarrierSpace#readSlot} makes the same two-step read). A reader that cannot be
     * resolved answers {@code null} - the pre-P1.45 read: uncharged, and never refused. A lookup that
     * fails must not take a solve down, so it answers the same.
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
     * The carrier index, resolved <b>once per bridge</b> rather than once per read.
     *
     * <p>{@code CarrierStore#indexOf} goes through the level's data storage, and a bridge is built once
     * per carrier per pass ({@code CarrierNetworks#worldIoOf}) while its reads are per component per
     * side - so asking it here would put a data-storage lookup on the solve's hot path. The index a pass
     * runs against is one object, so caching it for the life of this bridge is the same answer for less.
     * A failure is remembered as "no index" for the same reason every other read in this class degrades
     * to the pre-P1.45 answer: a bookkeeping failure must not take a solve down.
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
     * The same read, against an explicitly supplied world-signal source.
     *
     * <p>Private, because the source is an implementation detail of the two forms above: production
     * always reads through this bridge's own {@code signals} (unfiltered) or through the merged read with
     * the reader named (filtered).
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
     * The strongest world signal reaching {@code localPos} from any of its six sides.
     *
     * <p>What {@link com.jiangyuefengyu.redstonecircuit.logic.PowerEnvironment#externalSignal()} means:
     * the omnidirectional supply a piece of inner dust draws, which has no input side. Both this and the
     * world implementation take the maximum over the same six sides, so a wire inside a machine behaves
     * like a wire on the ground.
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
