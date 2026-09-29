package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方向转向杆 - what the rotation rod does to the component inside a block.
 *
 * <h2>The gesture</h2>
 * Pick a block with redstone in it and click one of its faces:
 *
 * <pre>
 *   click the face the component should read from   -&gt; input side = that face
 *   click the same face again                       -&gt; output side = that face
 *   click again                                     -&gt; back to the input side
 * </pre>
 *
 * <p>Two states, then it repeats. Both are needed because a repeater cannot be pointed at a face
 * without also deciding which way round it sits: "read from here" and "drive here" are the two
 * orientations a clicked face can mean, and they are each other's opposite.
 *
 * <p>The state is derived from where the component already points rather than remembered per player,
 * so a click always means the same thing no matter who makes it or when - and nothing has to be saved.
 *
 * <p>Wire has no orientation at all - dust reads and emits on every side - so the rod refuses it rather
 * than pretending. That is also why {@link #hasDirection} is on {@link ComponentType}: it is the one
 * question the rod asks about a type.
 */
public final class RotationRod {

    /** What one click did, for the message the player gets. */
    public enum Result {
        /** The component now reads from the clicked face. */
        TURNED_TO_INPUT,
        /** The component now drives the clicked face. */
        TURNED_TO_OUTPUT,
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
     * Turns the component in the block at {@code pos} towards {@code face}, and tells the world.
     *
     * @param face the face that was clicked, which is never {@code null} for a block click
     */
    public static Result turn(ServerLevel level, BlockPos pos, Direction face) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        Slot slot = store.slotAt(pos);
        if (slot == null) {
            return Result.NOTHING_THERE;
        }
        if (!hasDirection(slot.type)) {
            return Result.NO_DIRECTION;
        }

        Direction before = slot.facing;
        boolean toInput = before != face;
        slot.facing = toInput ? face : face.getOpposite();

        // A diode was strongly powering the block it used to point at. That block is a conductor, so
        // everything around it was being powered through it; moving the output has to wake it up, or the
        // old front would keep reporting the charge it is no longer given. This is the same pair of
        // notifications vanilla's DiodeBlock.updateNeighborsInFront makes, for the old side.
        releaseOldFront(level, pos, slot.type, before);

        store.markDirty();
        // The routing changed, so the network around it has to be settled again - and, because no block
        // state changed, both the world and the clients have to be told by hand.
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, slot);

        return toInput ? Result.TURNED_TO_INPUT : Result.TURNED_TO_OUTPUT;
    }

    /** Stops the block this diode used to drive from keeping the strong power it no longer receives. */
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
