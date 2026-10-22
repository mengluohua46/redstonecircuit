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
import net.minecraft.world.level.block.state.BlockState;

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
 *
 * <p>Two things guard the assembly itself. The cell is read back immediately before the call, because what Sable moves
 * into the new plot is that cell's block: a machine pointing at a cell that reads as air would otherwise ask for a
 * structure with nothing in it, and a sub-level's body is weighed from its plot. And the structure that comes back is
 * registered rather than judged, because Sable fills the plot, its bounds and the mass tracker after this call
 * returns - the judgement belongs to a later carrier walk, and it is the judgement that switches assembly off.
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
            // The structure an earlier activation made is judged before this one is made, so a machine cannot
            // assemble twice into a state that has already been shown to be unusable.
            SableAssemblyWatch.resolvePending(level);
            if (!PlacerAssemblyGate.assemblyAllowed()) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] block placer: the block at {} stays an ordinary block in "
                            + "the world: assembling structures is switched off for this server run ({})",
                            drawnTarget.toShortString(), PlacerAssemblyGate.switchedOffReason());
                }
                return true;
            }

            // The block Sable moves into the new plot is this cell's block, read exactly this way. So the cell is
            // read back before the assembly: a cell that answers air makes a structure with nothing in its plot, and
            // a plot with nothing in it is a physics body that is never given a mass.
            BlockState source = level.getBlockState(drawnTarget);
            if (source.isAir()) {
                RCConfig.LOGGER.warn("[redstonecircuit] block placer: nothing was assembled at {}: the cell reads as "
                        + "air after the placement, so there is no block for Sable to carry into a new structure",
                        drawnTarget.toShortString());
                return true;
            }

            BoundingBox3i box = new BoundingBox3i(drawnTarget, drawnTarget);
            ServerSubLevel made = SubLevelAssemblyHelper.assembleBlocks(level, drawnTarget, List.of(drawnTarget), box);
            if (made == null) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] block placer: Sable would not assemble the block at {}, so "
                            + "it stays an ordinary block in the world", drawnTarget.toShortString());
                }
                return true;
            }
            // Registered, not judged: the plot's bounds and the structure's mass are written after this call returns,
            // so a body read here would call a sound structure empty.
            PlacerAssemblyWatch.watch(made.getUniqueId(), level.dimension(), drawnTarget, level.getGameTime());
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
