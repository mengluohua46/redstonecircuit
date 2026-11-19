package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方向转向杆 - what the rotation rod does to the component inside a block.
 *
 * <p>The gesture: one click, one step around a fixed six-step cycle - north, east, south, west (clockwise seen from
 * above), then up, then down, then north again. The cycle is fixed rather than relative to the clicked face, so the
 * same number of clicks always lands on the same orientation and the player can aim without watching the component.
 * Two of the steps are vertical, which is something a placed vanilla repeater cannot be made to do.
 *
 * <p>The stepped field is {@code facing}, the component's input side; the output side is its opposite, so both turn
 * together. Nothing is remembered per player: the cycle is read off the component's own facing, so a click means the
 * same thing whoever makes it and whenever.
 *
 * <p>Wire has no orientation at all - dust reads and emits on every side - so the rod refuses it rather than pretending.
 * That is also why {@link #hasDirection} is on {@link ComponentType}: it is the one question the rod asks about a type.
 */
public final class RotationRod {

    /** What one click did, for the message the player gets. */
    public enum Result {
        /** The component stepped one place around the cycle. */
        TURNED,
        /** The component has no orientation to turn. */
        NO_DIRECTION,
        /** There is no component here to turn. */
        NOTHING_THERE
    }

    private RotationRod() {
    }

    /** True when turning this kind of component means anything. */
    public static boolean hasDirection(ComponentType type) {
        return !type.isWire();
    }

    /**
     * The orientation one click moves to: clockwise around the vertical axis for the four horizontal sides, then the
     * two vertical ones, then back to the start. Written out rather than derived from {@link Direction#getClockWise()}
     * because that one only walks the horizontal ring and would have nowhere to put up and down.
     */
    public static Direction nextFacing(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.UP;
            case UP -> Direction.DOWN;
            case DOWN -> Direction.NORTH;
        };
    }

    /**
     * Turns a diode that is a <b>block</b> rather than a component in a slot: 中继器（贴面）, and vanilla's own repeater.
     *
     * <p>One click, one step. A plate on a wall steps round the four directions inside its wall (up and down
     * included); a plate on the floor or the ceiling, and a vanilla repeater, step round the four horizontal sides in
     * the order {@link #nextFacing} documents. Returns the 朝向 it now reads, or {@code null} when the block at
     * {@code pos} is neither of those, in which case the caller keeps looking for something to turn.
     */
    @Nullable
    public static Direction turnWorldDiode(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.SurfaceDiodeBlock) {
            return com.jiangyuefengyu.redstonecircuit.block.SurfaceDiodeBlock
                    .turnClockwise(level, pos, state);
        }
        if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerBlock) {
            return com.jiangyuefengyu.redstonecircuit.block.SurfaceTriggerBlock.turnClockwise(level, pos, state);
        }
        if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock) {
            // The four 薄板 carry their own 贴面, so the rod walks the ring inside that plate's plane - and the
            // device re-derives its answer from the notification, exactly as it does for any other neighbour change.
            return com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock.turnClockwise(level, pos, state);
        }
        if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.TriggerBlock) {
            // 红石触发器 walks the four horizontal sides alone: its 朝向 is vanilla's horizontal property, because it
            // only ever stands on a floor or hangs from a ceiling.
            Direction next = state.getValue(com.jiangyuefengyu.redstonecircuit.block.TriggerBlock.FACING).getClockWise();
            BlockState turned = state.setValue(com.jiangyuefengyu.redstonecircuit.block.TriggerBlock.FACING, next);
            level.setBlock(pos, turned, Block.UPDATE_ALL);
            level.neighborChanged(pos, turned.getBlock(), pos);
            return next;
        }
        if (state.getBlock() instanceof ComparatorBlock) {
            Direction next = state.getValue(ComparatorBlock.FACING).getClockWise();
            BlockState turned = state.setValue(ComparatorBlock.FACING, next);
            level.setBlock(pos, turned, Block.UPDATE_ALL);
            level.neighborChanged(pos, turned.getBlock(), pos);
            return next;
        }
        if (state.getBlock() instanceof RepeaterBlock) {
            // A vanilla repeater cannot read up or down - its property has four horizontal values - so its cycle is
            // the horizontal ring alone, not the six-step one a component walks.
            Direction next = state.getValue(RepeaterBlock.FACING).getClockWise();
            BlockState turned = state.setValue(RepeaterBlock.FACING, next);
            level.setBlock(pos, turned, Block.UPDATE_ALL);
            level.neighborChanged(pos, turned.getBlock(), pos);
            return next;
        }
        return null;
    }

    /** Steps the component in the block at {@code pos} one place around the cycle, and tells the world. */
    public static Result turn(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = store.slotAt(pos);
        if (slot == null) {
            return Result.NOTHING_THERE;
        }
        if (!hasDirection(slot.type)) {
            return Result.NO_DIRECTION;
        }

        Direction before = slot.facing;
        slot.facing = nextFacing(before);

        // The old front is a conductor and has to be woken up, or it keeps reporting a charge it no longer receives:
        // the pair of notifications vanilla's DiodeBlock.updateNeighborsInFront makes, for the old side.
        releaseOldFront(level, pos, slot.type, before);

        store.markDirty();
        // The routing changed with no block state change, so the world and the clients both have to be told by hand.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, slot);

        return Result.TURNED;
    }

    /** Stops the block the diode was driving from keeping the strong power it no longer receives. */
    private static void releaseOldFront(ServerLevel level, BlockPos pos, ComponentType type,
                                        Direction oldFacing) {
        if (!type.isDiode()) {
            return;
        }
        Direction oldOutput = oldFacing.getOpposite();
        BlockPos oldFront = pos.relative(oldOutput);
        BlockState state = level.getBlockState(pos);
        Block source = state.getBlock();
        level.neighborChanged(oldFront, source, pos);
        // Everything around that block, except back towards the diode - which was just notified.
        level.updateNeighborsAtExceptFromFacing(oldFront, source, oldOutput.getOpposite());
    }
}