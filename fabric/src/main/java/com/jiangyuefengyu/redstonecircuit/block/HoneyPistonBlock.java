package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RcTooltips;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/**
 * 蜂蜜活塞 - a piston that pushes like vanilla's, and drags one block back when it reached into air.
 *
 * <p>Both retractions leave the same shape behind them, so which one is about to happen cannot be read off the world at
 * retraction time: it is recorded when the <b>extension</b> happens, in {@link #GRABBING}. That is the whole difference
 * from a vanilla piston - the push, the drag, the carried blocks, the sounds and the twelve-block limit all stay in
 * {@code PistonBaseBlock#moveBlocks}, which is also why this class is built {@code sticky=false}: the drag is decided
 * per retraction (see {@code HoneyPistonMixin}), not per block, and vanilla gets the retraction that keeps everything.
 */
public class HoneyPistonBlock extends PistonBaseBlock {

    /** 一条说明: 推的时候和活塞一样, 区别只在收杆那一下. 细节放在 [Shift] 后面, 与其余器件同一个形状. */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        RcTooltips.append(tooltip, flag, "block.redstonecircuit.honey_piston.tip",
                RcTooltips.detail("block.redstonecircuit.honey_piston.tip.drag"));
    }

    /** Whether the extension this piston is in reached into air, so that the next retraction drags a block back. */
    public static final BooleanProperty GRABBING = BooleanProperty.create("grabbing");

    /**
     * The state {@code triggerEvent} is deciding for, while it decides.
     *
     * <p>Vanilla asks {@code this.isSticky} in the middle of that method, and that private field cannot see a block
     * state - so the state has to be within reach of the mixin without the call changing shape. It is set and restored
     * here rather than by a second injection, so nobody can leave it set.
     *
     * <p>What is restored is the <b>previous</b> frame, not nothing: {@code triggerEvent} can be re-entered while it
     * runs - a client executes block events as they arrive ({@code Level#blockEvent}, which {@code ClientLevel} does not
     * defer), so one piston's {@code triggerEvent} can sit inside another's. Clearing the slot on the way out would take
     * the outer frame with it, and that frame's drag question would then answer "not mine" - indistinguishable from
     * vanilla's {@code isSticky=false}, so the outer piston would quietly retract without dragging its block.
     */
    private static final ThreadLocal<BlockState> DECIDING = new ThreadLocal<>();

    public HoneyPistonBlock(BlockBehaviour.Properties properties) {
        super(false, properties);
        this.registerDefaultState(this.stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(EXTENDED, false)
                .setValue(GRABBING, false));
    }

    /** Whether this piston reached into air, and therefore whether the retraction it is in drags a block back. */
    public boolean grabs(BlockState state) {
        return state.getValue(GRABBING);
    }

    /**
     * The drag question {@code triggerEvent} is deciding right now, or {@code null} when it is not being decided for
     * this piston at all - which is what the mixin passes on to vanilla's own {@code isSticky}.
     *
     * <p>Answered from the state as it was handed in, not from the world: at retraction time the base has already been
     * replaced by a moving piston, so the world no longer holds the bit.
     */
    public static Boolean drag(Block piston) {
        BlockState deciding = DECIDING.get();
        return deciding != null && deciding.getBlock() == piston ? deciding.getValue(GRABBING) : null;
    }

    @Override
    protected boolean triggerEvent(BlockState state, Level level, BlockPos pos, int id, int param) {
        BlockState deciding = state;
        if (id == TRIGGER_EXTEND) {
            // "This one reached into air" is knowable only now: what was in front is gone by the time it retracts, and
            // vanilla then goes on to set EXTENDED, publish the state and build the head from the very state handed in.
            deciding = state.setValue(GRABBING, level.getBlockState(pos.relative(state.getValue(FACING))).isAir());
        }
        BlockState previous = DECIDING.get();
        DECIDING.set(deciding);
        try {
            return super.triggerEvent(deciding, level, pos, id, param);
        } finally {
            // Put back what was here before, not nothing: this call may itself be a frame inside another piston's
            // triggerEvent, and that outer frame is still owed its own drag answer (see DECIDING).
            if (previous == null) {
                DECIDING.remove();
            } else {
                DECIDING.set(previous);
            }
        }
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(GRABBING);
    }
}
