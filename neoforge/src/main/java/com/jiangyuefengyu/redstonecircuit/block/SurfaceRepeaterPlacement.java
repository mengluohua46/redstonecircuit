package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places {@link SurfaceRepeaterBlock} from vanilla's own repeater item, and only where vanilla cannot place its own.
 *
 * <p>The rule is one line wide on purpose: <b>clicking the top face is vanilla's click, untouched</b>. Every other face
 * is a face vanilla's repeater has no answer for (it needs a floor under it), so those are the ones this takes over.
 * A repeater is still placed by vanilla's code - same item, same survival rules, same behaviour - for every click that
 * vanilla could already handle; nothing about the existing mechanic moves.
 *
 * <p>Called from the {@code BlockItem#useOn} hook, which runs on both sides, so the placement below writes on the
 * client as well: the client's prediction and the server's answer end up the same block, and no ghost block is left
 * behind.
 */
public final class SurfaceRepeaterPlacement {

    private SurfaceRepeaterPlacement() {
    }

    /**
     * The placement for this click, or {@code null} when this is not a click the twin should take - in which case the
     * caller leaves everything to vanilla.
     */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        boolean repeater = item.getBlock() == Blocks.REPEATER;
        boolean comparator = item.getBlock() == Blocks.COMPARATOR;
        if (!repeater && !comparator) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        if (clicked == Direction.UP) {
            // The one face vanilla's repeater is happy with: its own placement runs, byte for byte.
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        SurfaceDiodeBlock block = repeater ? RCRegistry.SURFACE_REPEATER.get() : RCRegistry.SURFACE_COMPARATOR.get();
        Direction mount = clicked.getOpposite();
        BlockState state = block.defaultBlockState()
                .setValue(SurfaceDiodeBlock.MOUNT, mount)
                // A plate on a wall reads along the wall, and where the player looks decides which way: aiming into
                // the wall snaps to the nearest direction that mounting can honour.
                .setValue(SurfaceDiodeBlock.FACING, SurfaceDiodeBlock.placementFacing(mount,
                        directionToward(context), context.getHorizontalDirection().getOpposite()));
        if (!block.canStay(state, level, pos)) {
            // Nothing to hang on: vanilla's click decides, exactly as it would without this class.
            return null;
        }
        if (state.hasProperty(SurfaceRepeaterBlock.LOCKED)) {
            state = state.setValue(SurfaceRepeaterBlock.LOCKED, block.isLocked(level, pos, state));
        }
        level.setBlock(pos, state, 3);
        if (!level.isClientSide && (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild)) {
            stack.shrink(1);
        }
        SoundType sound = state.getSoundType();
        level.playSound(context.getPlayer(), pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * Where the plate's front should point: back at the player, which is vanilla's own rule for a diode, but read in
     * three dimensions so that looking up or down at a wall gives a plate that reads up or down.
     *
     * <p>{@code UseOnContext} does not carry a nearest-looking direction, so it is taken from the player's own look
     * vector - the negative of it is the way the front faces.
     */
    private static Direction directionToward(UseOnContext context) {
        if (context.getPlayer() == null) {
            return context.getHorizontalDirection().getOpposite();
        }
        net.minecraft.world.phys.Vec3 look = context.getPlayer().getLookAngle();
        return Direction.getNearest(-look.x, -look.y, -look.z);
    }

    /** The item this twin gives back, for callers that have only a stack in hand. */
    public static boolean isRepeaterItem(ItemStack stack) {
        return stack.is(Items.REPEATER);
    }
}