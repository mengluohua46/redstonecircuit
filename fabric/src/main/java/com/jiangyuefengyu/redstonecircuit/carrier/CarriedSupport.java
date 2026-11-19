package com.jiangyuefengyu.redstonecircuit.carrier;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * <b>Whether one face of a block is solid enough to hold something against it - read where the block is drawn and where
 * it is stored.</b>
 *
 * <p>Every 贴面 family asks this question before it is placed and again whenever a neighbour changes: is the block behind
 * this face sturdy? The world answers air for a block a machine is carrying (its blocks live in the carrier's own
 * storage, in the carrier's own coordinates), so a face on a machine looked unsupported and the click was refused - while
 * the <b>same cell</b> accepted that face a moment later if another face of it was already standing on solid ground,
 * because "is this sheet supported" only needs <b>one</b> face ({@code SurfaceWireBlock#canStay}). That is the asymmetry
 * 作者 reported (2026-11-16): at a wall/floor junction across two structures - or across a structure and the world -
 * the floor has to be placed first, and only then the wall.
 *
 * <p>One question, one place: the world's own answer first (it is the cheap one and never overridden), then the carrier's
 * storage at that coordinate, turned back through the pose exactly as every other carried read in this mod is
 * ({@link CarrierProjection#localBlockOf} for the position, {@link CarrierDirections#localDirection} for the side).
 * A jar without the physics half, a structure that has gone, a pose that cannot be read and a coordinate no structure
 * contains all answer "not sturdy", which is what they answered before.
 */
public final class CarriedSupport {

    private CarriedSupport() {
    }

    /**
     * Whether {@code support}'s {@code facing} face can hold something, asked of the block as it is drawn and of the
     * block as the carrier stores it.
     *
     * @param facing the side of the support block that the held block sits against - vanilla's own convention, i.e. the
     *               side facing <em>away</em> from the block being held
     */
    public static boolean sturdy(BlockGetter level, BlockPos support, Direction facing, SupportType type) {
        if (level.getBlockState(support).isFaceSturdy(level, support, facing, type)) {
            return true;
        }
        CarriedBlocks.CarriedLookup where = CarriedBlocks.lookUp(level, support);
        if (where == null) {
            CarriedBlocks.note(level, " support " + support.toShortString() + " (" + facing
                    + ") refused: " + CarriedBlocks.describe(level, support));
            return false;
        }
        BlockState state = where.storage().getBlockState(where.local());
        Direction localFace = CarrierDirections.localDirection(where.pose(), facing);
        boolean holds = state.isFaceSturdy(where.storage(), where.local(),
                localFace == null ? facing : localFace, type);
        if (!holds) {
            CarriedBlocks.note(level, " support " + support.toShortString() + " (" + facing
                    + ") refused inside the carrier: " + CarriedBlocks.describe(level, support));
        }
        return holds;
    }
}
