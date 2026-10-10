package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;

/**
 * 方块放置器's physical half, built on Sable's own assembly API.
 *
 * <p>Two calls: the block is placed <b>in the world</b> through the very helper the ground path uses, and then
 * {@code SubLevelAssemblyHelper#assembleBlocks} turns that one block into a sub-level of its own. That is what makes it
 * correct - the plot, the chunk, its contents, the storage record that points at it and the pose that draws it are all
 * Sable's business, done by Sable, in the order Sable expects. Doing any of it by hand does not survive a save/load
 * round trip, because a plot chunk made by hand is not a plot chunk Sable can serialise: the structures left behind are
 * reported on every load as {@code Failed to load sub_level}.
 *
 * <p>{@code assembleBlocks} can answer {@code null} (for instance when the blocks are not something it assembles), which
 * is not a failure the player should pay for: the block is already standing in the world, correctly placed and oriented,
 * and it simply <b>stays</b> a normal block. The item is spent either way, because either way the player got a block
 * where they pointed the machine.
 */
final class SablePlacerPlacement {

    private SablePlacerPlacement() {
    }

    /**
     * Places one block where the machine points, and - if the machine is physical and Sable allows it - has Sable
     * assemble that block into a structure of its own. Answers true when the block was placed, i.e. when the caller must
     * write the spent stack back.
     */
    static boolean place(ServerLevel level, BlockPos drawnTarget, Direction out, ItemStack stack) {
        if (!RCConfig.placerCreatesStructures()) {
            // Off by default (RCConfig#PLACER_CREATES_STRUCTURES): nothing is placed and nothing is spent while the
            // storage behaviour is unproven.
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: creating a standalone structure is switched off "
                        + "(placerCreatesStructures=false); nothing was placed and nothing was spent");
            }
            return false;
        }

        InteractionResult placed = BlockPlacerBlock.placeInto(level, drawnTarget, out, stack);
        if (!placed.consumesAction()) {
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: nothing would go down at {} ({} face {}), so there "
                        + "is nothing to assemble", drawnTarget.toShortString(), stack.getHoverName(), out);
            }
            return false;
        }

        try {
            BoundingBox3i box = new BoundingBox3i(drawnTarget, drawnTarget);
            ServerSubLevel made = SubLevelAssemblyHelper.assembleBlocks(level, drawnTarget, List.of(drawnTarget), box);
            if (made == null) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] block placer: Sable would not assemble the block at {}, so "
                            + "it stays an ordinary block in the world", drawnTarget.toShortString());
                }
                return true;
            }
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: Sable assembled the block at {} into a structure "
                        + "of its own (uuid {})", drawnTarget.toShortString(), made.getUniqueId());
            }
            return true;
        } catch (RuntimeException | LinkageError failed) {
            // The block is on the ground and correctly placed: not an error the player should see twice.
            RCConfig.LOGGER.warn("[redstonecircuit] block placer: assembling the block at {} failed ({}); it stays an "
                    + "ordinary block in the world", drawnTarget.toShortString(), failed.toString());
            return true;
        }
    }
}
