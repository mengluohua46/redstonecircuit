package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;
import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * A repeater in one of the two shapes the mod adds: a longer delay, or a kept strength.
 *
 * <p>Everything a repeater does that is not its timing or its output strength is inherited rather than rewritten: the
 * two-in-a-row placement, the delay property and its right-click cycling, the lock a neighbouring diode puts on it,
 * the shape, and the whole diode tick machinery. Two overrides are left - {@link #getDelay} stretches every setting
 * for the 双倍 (double) family (4/8/12/16 ticks instead of 2/4/6/8, while the setting itself still reads 1-4), and
 * {@link #getOutputSignal} makes the 维持 (retaining) family hand on the strength it was given instead of restoring
 * fifteen.
 *
 * <p>A vanilla repeater's output is always fifteen, so vanilla only has to notice the change from "nothing" to
 * "something", which its block tick does; a retaining repeater's output <em>follows</em> the input strength, so seven
 * becoming three is a change that has to be announced again and the block tick would never fire for it.
 * {@link #neighborChanged} therefore re-announces the output when the side the repeater reads from changes while it
 * is on.
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
        // RepeaterBlock declares its codec as MapCodec<RepeaterBlock>, and a generic type argument cannot be narrowed
        // by an override, so this has to be typed as the parent even though it builds this class.
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
     * The same number as a public question, for anything outside the block that has to report it: {@link #getDelay} is
     * protected because vanilla keeps it to itself, but an overlay like Jade has to be able to ask.
     */
    public int ticksFor(BlockState state) {
        return getDelay(state);
    }

    /**
     * What this repeater drives its output side with: vanilla's fifteen, or for a retaining repeater the strength
     * arriving at its back, read live rather than remembered - an input fading from seven to three while it is on has
     * to be visible as three, and anywhere to remember it would also have to be saved, synced and migrated.
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
     * Re-announces the output when the strength reaching the back changes while we are on. Nothing else would:
     * {@code checkTickOnNeighbor} only schedules a tick when the on/off answer changes, and for a retaining repeater
     * that answer stays "on" while the strength underneath it moves. Only a change on the side we read from can do
     * that, which is what is checked here, so this cannot loop back on itself through the block it drives.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, net.minecraft.world.level.block.Block block,
                                   BlockPos fromPos, boolean isMoving) {
        super.neighborChanged(state, level, pos, block, fromPos, isMoving);
        if (preservesStrength && state.getValue(POWERED) && fromPos.equals(pos.relative(state.getValue(FACING)))) {
            updateNeighborsInFront(level, pos, state);
        }
    }

    /**
     * The tooltip of whichever of the three repeaters this instance is - the two flags in the constructor are exactly
     * what tells them apart, so the shape is asked here rather than passed in a second time.
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        if (delayMultiplier == 1) {
            RcTooltips.append(tooltip, flag, "block.redstonecircuit.retaining_repeater.tip",
                    RcTooltips.detail("block.redstonecircuit.retaining_repeater.tip.detail"));
        } else if (preservesStrength) {
            RcTooltips.append(tooltip, flag, "block.redstonecircuit.double_retaining_repeater.tip",
                    RcTooltips.detail("block.redstonecircuit.double_retaining_repeater.tip.detail"));
        } else {
            RcTooltips.append(tooltip, flag, "block.redstonecircuit.double_repeater.tip",
                    RcTooltips.detail("block.redstonecircuit.double_repeater.tip.detail"));
        }
    }
}
