package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>What block stands in a cell, read where it is drawn and where a machine stores it.</b>
 *
 * <p>A carried block's world cell holds air: a machine keeps its blocks in its own storage, in its own coordinates, and
 * the pose is what puts them where the player sees them. Every question the 贴面 families ask about a neighbour -
 * "is that face sturdy", "is that a wire of ours", "does that block connect", "is it a conductor" - used to be
 * {@code level.getBlockState(cell)} and therefore saw air across a structure boundary. Two consequences, both reported by
 * 作者 (2026-11-16): a 贴面 could not be placed on the face of a carried block at all, and at a wall/floor junction the
 * floor sheet did not light the wall sheet beside it.
 *
 * <p>One chain, written once: {@code carrierAt} finds the carrier standing at that world position,
 * {@code poseOf} its pose, {@link CarrierProjection#localBlockOf} turns the world coordinate back into the structure's own
 * coordinate, and {@code storageInLevel} decides which view holds the block (a Sable sub-level's blocks <em>are</em>
 * level blocks at plot coordinates; a Create contraption's are in its own world). A world that answers a real block is
 * never overridden - the carried read is the fallback, exactly as {@link CarriedSupport} needs it.
 */
public final class CarriedBlocks {

    /** One diagnostic line per this many ticks: these reads are on the redstone hot path. */
    private static final int FRAME_LOG_TICKS = 20;
    private static long lastFrameLog;
    /** The sheet probe keeps its own slot: sharing the frame counter made "never recomputed" indistinguishable. */
    private static long lastWireLog;

    private CarriedBlocks() {
    }

    /**
     * The block in this cell: the world's own answer when there is one, otherwise the block a carrier is drawing there,
     * otherwise air. Never {@code null}, so a caller can ask it where it used to ask {@code getBlockState}.
     */
    public static BlockState stateAt(BlockGetter level, BlockPos cell) {
        BlockState world = level.getBlockState(cell);
        if (world != null && !world.isAir()) {
            return world;
        }
        BlockState carried = carriedStateAt(level, cell);
        if (carried != null) {
            note(level, " neighbour " + cell.toShortString() + " is drawn as " + carried
                    + " (" + describe(level, cell) + ")");
            return carried;
        }
        return Blocks.AIR.defaultBlockState();
    }

    /**
     * <b>Every link of the chain, for a diagnostic log.</b> A world that answers air for a cell a machine holds is the
     * shape of three separate defects now (support, connection, placement), and each one lives in a different link, so
     * the line names all of them instead of the caller guessing.
     */
    public static String describe(BlockGetter level, BlockPos cell) {
        if (!(level instanceof ServerLevel server)) {
            return "no server level";
        }
        try {
            // The SAME lookup the read made, so the line cannot describe one structure while the read used another.
            CarriedLookup resolved = lookUp(level, cell);
            if (resolved != null) {
                return "resolved=" + resolved.local().toShortString() + " block="
                        + resolved.storage().getBlockState(resolved.local());
            }
            CarrierId carrier = CarrierStore.carrierAt(server, cell);
            if (carrier == null) {
                return "carrier=none";
            }
            CarrierPose pose = CarrierStore.poseOf(server, carrier);
            if (pose == null) {
                return "carrier=" + carrier + " pose=none";
            }
            BlockPos local = CarrierProjection.localBlockOf(pose, cell);
            if (local == null) {
                return "carrier=" + carrier + " pose=" + pose.describe() + " local=none";
            }
            BlockGetter storage = CarrierStore.storageInLevel(carrier) ? server : CarrierStore.blockViewOf(carrier);
            if (storage == null) {
                return "carrier=" + carrier + " local=" + local.toShortString() + " view=none";
            }
            return "carrier=" + carrier + " local=" + local.toShortString()
                    + " in=" + (CarrierStore.storageInLevel(carrier) ? "level" : "carrier-view")
                    + " block=" + storage.getBlockState(local);
        } catch (RuntimeException | LinkageError failed) {
            return "lookup failed: " + failed;
        }
    }

    /** The sheet probe's own slot, so its silence means silence. */
    public static void noteWire(BlockGetter level, String message) {
        if (!(level instanceof ServerLevel server) || !com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            return;
        }
        long now = server.getGameTime();
        if (now - lastWireLog < FRAME_LOG_TICKS) {
            return;
        }
        lastWireLog = now;
        com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] wire{}", message);
    }

    /** One line, at most once per {@link #FRAME_LOG_TICKS} ticks, and only when the detailed log is on. */
    public static void note(BlockGetter level, String message) {
        if (!(level instanceof ServerLevel server) || !net.minecraft.world.level.Level.class.isInstance(server)) {
            return;
        }
        if (!com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            return;
        }
        long now = server.getGameTime();
        if (now - lastFrameLog < FRAME_LOG_TICKS) {
            return;
        }
        lastFrameLog = now;
        com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info("[redstonecircuit] frame{}", message);
    }

    /**
     * <b>The block one step along {@code towards} from the block at {@code self} - read in the structure's own frame and
     * in the frame it is drawn in.</b>
     *
     * <p>A 贴面 that rides a machine keeps its state at a coordinate of that structure, so "the wire beside me" is the
     * cell next door <em>in the structure</em>; the wire the player built against it, however, stands in the world beside
     * the place the machine is <b>drawn</b>. Both are answers to the same question and the redstone has to take
     * whichever one holds a block: two structures side by side keep their blocks in two plots that are nowhere near each
     * other, and a structure beside ordinary ground is a plot cell next to a world cell - in both cases the neighbour the
     * player sees is one frame away from the neighbour the block state knows about (作者 2026-11-17：两个结构之间能放了，
     * 但通电互相点不亮).
     */
    public static BlockState neighbourOf(BlockGetter level, BlockPos self, Direction towards) {
        BlockState own = stateAt(level, self.relative(towards));
        if (!own.isAir()) {
            return own;
        }
        if (!(level instanceof ServerLevel server)) {
            return own;
        }
        try {
            ProductFrame frame = ProductFrame.carriedAt(server, self);
            if (!frame.carried()) {
                return own;
            }
            BlockState drawn = stateAt(level, frame.worldPosOf(self.relative(towards)));
            return drawn.isAir() ? own : drawn;
        } catch (RuntimeException | LinkageError unreadable) {
            return own;
        }
    }

    /**
     * The block a carrier stores at this world position, or {@code null} when no carrier holds it.
     *
     * <p>This is the structural read, named so a caller can tell "the machine says air" from "no machine": the first is a
     * real answer about a carried cell, the second is a cell nobody carries.
     */
    @Nullable
    public static BlockState carriedStateAt(BlockGetter level, BlockPos cell) {
        CarriedLookup where = lookUp(level, cell);
        if (where == null) {
            return null;
        }
        return where.storage.getBlockState(where.local);
    }

    /** Where a world position lives inside its carrier, when one holds it. */
    @Nullable
    static CarriedLookup lookUp(BlockGetter level, BlockPos cell) {
        if (!(level instanceof ServerLevel server)) {
            return null;
        }
        try {
            // MORE THAN ONE STRUCTURE CAN COVER THE SAME WORLD POSITION, and the first one the container happens to keep
            // is not the one the player is looking at: the same cell answered oak_planks through one sub-level and air
            // through the other, one call apart (作者 2026-11-17 的日志). The block that is really drawn there is the one
            // whose own coordinate holds a block, so every candidate is asked and an empty answer is only used when all of
            // them are empty.
            CarriedLookup fallback = null;
            for (CarrierId carrier : CarrierStore.carriersAt(server, cell)) {
                CarrierPose pose = CarrierStore.poseOf(server, carrier);
                if (pose == null) {
                    continue;
                }
                BlockPos local = CarrierProjection.localBlockOf(pose, cell);
                if (local == null) {
                    continue;
                }
                BlockGetter storage = CarrierStore.storageInLevel(carrier) ? server : CarrierStore.blockViewOf(carrier);
                if (storage == null) {
                    continue;
                }
                CarriedLookup candidate = new CarriedLookup(pose, storage, local);
                if (!storage.getBlockState(local).isAir()) {
                    return candidate;
                }
                if (fallback == null) {
                    fallback = candidate;
                }
            }
            return fallback;
        } catch (RuntimeException | LinkageError unreadable) {
            // A failure in the bridge has to look like a jar with no machines in it.
            return null;
        }
    }

    /** One carried coordinate: the pose that put it there, the view that holds it, and where it is in that view. */
    record CarriedLookup(CarrierPose pose, BlockGetter storage, BlockPos local) {
    }
}
