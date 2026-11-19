package com.jiangyuefengyu.redstonecircuit.block;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Places {@link SurfacePressurePlateBlock} from the four vanilla plate items, on every face but the top one. The top
 * face is vanilla's own placement and is never touched.
 */
public final class SurfacePressurePlatePlacement {

    private SurfacePressurePlatePlacement() {
    }

    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item)) {
            return null;
        }
        SurfacePressurePlateBlock block = switch (item.getBlock() == Blocks.STONE_PRESSURE_PLATE ? "stone"
                : item.getBlock() == Blocks.OAK_PRESSURE_PLATE ? "oak"
                : item.getBlock() == Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE ? "light"
                : item.getBlock() == Blocks.HEAVY_WEIGHTED_PRESSURE_PLATE ? "heavy" : "") {
            case "stone" -> RCRegistry.SURFACE_STONE_PRESSURE_PLATE;
            case "oak" -> RCRegistry.SURFACE_OAK_PRESSURE_PLATE;
            case "light" -> RCRegistry.SURFACE_LIGHT_WEIGHTED_PRESSURE_PLATE;
            case "heavy" -> RCRegistry.SURFACE_HEAVY_WEIGHTED_PRESSURE_PLATE;
            default -> null;
        };
        if (block == null) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        if (clicked == Direction.UP && !(context.getLevel() instanceof net.minecraft.server.level.ServerLevel server
                && com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.structureOwnerAt(server,
                        context.getClickedPos()) != null)) {
            // On the ground a top-face plate is vanilla's (作者 2026-11-14); over a block a machine keeps in its own
            // storage it is not, because vanilla's placement writes nothing a player ever sees there.
            return null;
        }
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        if (!level.getBlockState(pos).canBeReplaced()) {
            return null;
        }
        BlockState state = block.defaultBlockState().setValue(SurfacePressurePlateBlock.MOUNT, clicked.getOpposite());
        if (!block.canStay(state, level, pos)) {
            return null;
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
}
