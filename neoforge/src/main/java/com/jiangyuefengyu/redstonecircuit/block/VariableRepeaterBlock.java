package com.jiangyuefengyu.redstonecircuit.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * A repeater in one of the two shapes the mod adds: a longer delay, or a kept strength.
 *
 * <h2>Why this extends vanilla's repeater</h2>
 * Everything a repeater does that is not its timing or its output strength is inherited rather than
 * rewritten: the two-in-a-row placement, the delay property and its right-click cycling, the lock a
 * neighbouring diode puts on it, the shape, and the whole diode tick machinery. Two overrides are all
 * that is left, which is also the best proof that the two new components really are repeaters.
 *
 * <ul>
 *   <li>{@link #getDelay} - the 双倍 (double) family stretches every setting: 4/8/12/16 ticks instead
 *       of 2/4/6/8. The setting itself still reads 1-4, so the way a player sets the delay is unchanged.</li>
 *   <li>{@link #getOutputSignal} - the 维持 (retaining) family hands on the strength it was given
 *       instead of restoring fifteen. That is the one behavioural difference of the family.</li>
 * </ul>
 *
 * <h2>The one thing that needs care</h2>
 * A vanilla repeater's output is always fifteen, so vanilla only ever has to notice the change from
 * "nothing" to "something" - which its block tick does. A retaining repeater's output <em>follows</em>
 * the input strength, so seven becoming three is a change that has to be announced again, and the
 * block tick would never fire for it. {@link #neighborChanged} therefore re-announces the output when
 * the side the repeater reads from changes while it is on.
 */
public class VariableRepeaterBlock extends RepeaterBlock {

    /** 1 for a repeater's own timing, 2 for the 双倍 family. */
    private final int delayMultiplier;
    /** False for the 双倍 (double) family, true for the 维持 (retaining) family. */
    private final boolean preservesStrength;

    private final MapCodec<RepeaterBlock> codec;

    public VariableRepeaterBlock(BlockBehaviour.Properties properties, int delayMultiplier,
                                 boolean preservesStrength) {
        super(properties);
        this.delayMultiplier = delayMultiplier;
        this.preservesStrength = preservesStrength;
        // RepeaterBlock declares its codec as MapCodec<RepeaterBlock>, and a generic type argument
        // cannot be narrowed by an override, so the factory has to be typed as the parent even though
        // it builds this class. Nothing but the block-codec path ever asks for it.
        @SuppressWarnings("unchecked")
        MapCodec<RepeaterBlock> own = (MapCodec<RepeaterBlock>) (MapCodec<?>) simpleCodec(
                p -> new VariableRepeaterBlock(p, delayMultiplier, preservesStrength));
        this.codec = own;
    }

    @Override
    public MapCodec<RepeaterBlock> codec() {
        return codec;
    }

    /** One delay setting's worth of ticks: vanilla's two, doubled for the 双倍 family. */
    @Override
    protected int getDelay(BlockState state) {
        return state.getValue(DELAY) * 2 * delayMultiplier;
    }

    /**
     * The same number as a public question, for anything outside the block that has to report it.
     *
     * <p>{@link #getDelay} is protected because vanilla keeps it to itself, but "how long is this
     * repeater set to" is exactly what an overlay like Jade has to be able to ask.
     */
    public int ticksFor(BlockState state) {
        return getDelay(state);
    }

    /**
     * What this repeater drives its output side with.
     *
     * <p>Vanilla answers fifteen, which is what a repeater is for. A retaining repeater answers with the
     * strength arriving at its back instead, read live rather than remembered: an input that fades from
     * seven to three while it is on has to be visible as three, and there is nowhere to remember it that
     * would not also have to be saved, synced and migrated.
     */
    @Override
    protected int getOutputSignal(BlockGetter level, BlockPos pos, BlockState state) {
        if (!preservesStrength || !(level instanceof Level realLevel)) {
            return super.getOutputSignal(level, pos, state);
        }
        int incoming = getInputSignal(realLevel, pos, state);
        return Math.max(0, Math.min(15, incoming));
    }

    /**
     * Re-announces the output when the strength reaching the back changes while we are on.
     *
     * <p>Nothing else would: {@code checkTickOnNeighbor} only schedules a tick when the on/off answer
     * changes, and for a retaining repeater the on/off answer stays "on" while the strength underneath
     * it moves. Only a change on the side we read from can do that, which is exactly what is checked
     * here - so this cannot loop back on itself through the block it drives.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block block,
                                   BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (preservesStrength && state.getValue(POWERED) && fromPos.equals(pos.relative(state.getValue(FACING)))) {
            updateNeighborsInFront(level, pos, state);
        }
    }
}
