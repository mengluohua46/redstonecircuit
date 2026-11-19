package com.jiangyuefengyu.redstonecircuit.block;

import java.util.function.Supplier;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 维持 / 双倍 / 双倍维持 中继器（贴面）: this mod's three repeaters, hung on any of the six faces of their cell.
 *
 * <p>The face machinery, the shapes and the diode itself are {@link SurfaceRepeaterBlock}'s, which is vanilla's
 * repeater moved into a plane. What the three add is exactly what {@code VariableRepeaterBlock} adds to vanilla's own:
 * one of them multiplies every delay setting, one hands on the strength it was given instead of restoring fifteen, and
 * one does both. Keeping the two rules in one class with two flags - rather than three classes that differ by two
 * lines - is how the floor versions are written too.
 *
 * <p><b>A retaining plate has to re-announce its output.</b> Vanilla's repeater tick only schedules when its on/off
 * answer changes, so a plate whose input moves from seven to three while it is already on would keep driving seven:
 * the answer never changed, so nothing ever runs. {@link #neighborChanged} therefore re-announces when the side it
 * reads from moves - the one direction that cannot come back to it through the block it drives.
 */
public class SurfaceVariableRepeaterBlock extends SurfaceRepeaterBlock {

    /** 1 for a repeater's own timing, 2 for the 双倍 family. */
    private final int delayMultiplier;
    /** False for the 双倍 (double) family, true for the 维持 (retaining) family. */
    private final boolean preservesStrength;
    /** What one sheet of this plate gives back: the item of the repeater it is. */
    private final Supplier<Item> drop;

    private final MapCodec<SurfaceRepeaterBlock> codec;

    public SurfaceVariableRepeaterBlock(BlockBehaviour.Properties properties, int delayMultiplier,
                                        boolean preservesStrength, Supplier<Item> drop) {
        super(properties);
        this.delayMultiplier = delayMultiplier;
        this.preservesStrength = preservesStrength;
        this.drop = drop;
        // SurfaceRepeaterBlock declares its codec as MapCodec<SurfaceRepeaterBlock>, and a generic type argument cannot
        // be narrowed by an override, so this has to be typed as the parent even though it builds this class.
        @SuppressWarnings("unchecked")
        MapCodec<SurfaceRepeaterBlock> own = (MapCodec<SurfaceRepeaterBlock>) (MapCodec<?>) simpleCodec(
                p -> new SurfaceVariableRepeaterBlock(p, delayMultiplier, preservesStrength, drop));
        this.codec = own;
    }

    @Override
    protected MapCodec<SurfaceRepeaterBlock> codec() {
        return codec;
    }

    /** One delay setting's worth of ticks: vanilla's two, doubled for the 双倍 family. */
    @Override
    protected int getDelay(BlockState state) {
        return state.getValue(DELAY) * 2 * delayMultiplier;
    }

    /** The same number as a public question, for anything outside the block that has to report it. */
    public int ticksFor(BlockState state) {
        return getDelay(state);
    }

    /**
     * What this plate drives its output side with: vanilla's fifteen, or for a retaining one the strength arriving at
     * its back, read live - an input fading from seven to three while it is on has to be visible as three.
     */
    @Override
    protected int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state) {
        if (!preservesStrength || !(level instanceof Level realLevel)) {
            return super.getOutputSignal(level, pos, state);
        }
        return Math.max(0, Math.min(15, getInputSignal(realLevel, pos, state)));
    }

    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block block, BlockPos fromPos,
                                   boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (preservesStrength && state.getValue(POWERED) && fromPos.equals(pos.relative(state.getValue(FACING)))) {
            updateNeighborsInFront(level, pos, state);
        }
    }

    @Override
    public ItemStack cloneAs() {
        return new ItemStack(drop.get());
    }
}
