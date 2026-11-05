package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.physics.config.block_properties.PhysicsBlockPropertyHelper;
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
 * <p>A placement the world refuses is offered a second time, into the structure that draws the cell holding its
 * support - a block asks vanilla whether the cell below it will hold it up, and a physicalised structure's blocks
 * are not in the world, so the very placement a player can see is standing on solid ground is refused there. The
 * fallback writes into a structure that already exists, so it assembles nothing; see
 * {@code placeIntoSupportStructure}.
 *
 * <p>Two things guard the assembly itself. The cell is read back immediately before the call, because what Sable moves
 * into the new plot is that cell's block: a machine pointing at a cell that reads as air would otherwise ask for a
 * structure with nothing in it, and a sub-level's body is weighed from its plot. And the structure that comes back is
 * registered rather than judged, because Sable fills the plot, its bounds and the mass tracker after this call
 * returns - the judgement belongs to a later carrier walk, and it is the judgement that switches assembly off.
 */
final class SablePlacerPlacement {

    /**
     * The cells a refused placement's support is looked for in: down first, because that is the cell vanilla asks
     * about when it decides whether a sapling, a seed or a torch may stand here, then the four sides, then up.
     *
     * <p>A fixed six-entry table rather than a walk over every direction the enum holds: one activation may look at
     * six cells and write at most one plot, and the order has to be the same on every activation so that the same
     * machine keeps choosing the same structure.
     */
    private static final Direction[] SUPPORT_PROBES = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP,
    };

    private SablePlacerPlacement() {
    }

    /**
     * Places one block where the machine points, and - if the machine is physical and Sable allows it - has Sable
     * assemble that block into a structure of its own. Answers true when the block was placed, i.e. when the caller must
     * write the spent stack back.
     */
    static boolean place(ServerLevel level, BlockPos drawnTarget, Direction out, ItemStack stack) {
        if (!RCConfig.placerCreatesStructures()) {
            // Switched off (RCConfig#PLACER_CREATES_STRUCTURES): nothing is placed and nothing is spent, which is
            // what a world with a damaged saved sub-level needs.
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: creating a standalone structure is switched off "
                        + "(placerCreatesStructures=false); nothing was placed and nothing was spent");
            }
            return false;
        }

        InteractionResult placed = BlockPlacerBlock.placeInto(level, drawnTarget, out, stack);
        if (!placed.consumesAction()) {
            // The world said no, which is the only answer a physicalised support block gives: it is not in the
            // world. So the block is offered to a structure that really holds a block beside it, and the answer
            // there is this activation's answer. Wrapped exactly like the assembly below, because this reaches
            // Sable's own storage: nothing has been placed and nothing has been spent, so a structure that throws
            // here is a line in the log rather than a crash in the tick.
            try {
                return placeIntoSupportStructure(level, drawnTarget, out, stack);
            } catch (RuntimeException | LinkageError failed) {
                RCConfig.LOGGER.warn("[redstonecircuit] block placer: the placement of {} at {} could not be "
                        + "written into the structure drawing its support ({}); nothing was placed and nothing "
                        + "was spent", stack.getHoverName(), drawnTarget.toShortString(), failed.toString());
                return false;
            }
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

            // What Sable weighs a structure with is the sum over its plot's solid blocks, and a block with no
            // collision shape - a crop, a torch, a line of dust - weighs exactly zero however it is tagged. Such a
            // cell is not assembled: the structure would be the massless body the judgement below exists to keep out
            // of the physics step, and asking here is the only moment the answer is free. The block stays an ordinary
            // block in the world and the item stays spent, exactly as when Sable itself refuses.
            double mass = PhysicsBlockPropertyHelper.getMass(level, drawnTarget, source);
            if (PlacerAssemblyGate.weighsNothing(mass)) {
                if (RCConfig.debugLog()) {
                    RCConfig.LOGGER.info("[redstonecircuit] block placer: the block at {} stays an ordinary block in "
                            + "the world: Sable weighs that cell at mass {}, so a structure made of it would have no "
                            + "body to give the physics step", drawnTarget.toShortString(), mass);
                }
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

    /**
     * A placement the world refused, offered to the <b>plot</b> of a structure that really holds a block beside
     * the target cell.
     *
     * <p>Vanilla decides whether a block may stand in a cell by asking the level about the cell below it: a
     * sapling, a seed, a torch all read {@code pos.below()}. The blocks of a physicalised structure are not in the
     * world - they live at their plot coordinates - so the world cell the machine draws the support at reads as
     * air, and vanilla refuses a placement the player can see is standing on solid ground. Inside the plot that
     * same cell below really is the support block, so the very same vanilla placement answers yes there.
     *
     * <p>A probe cell is believed only once the structure's own storage proves it. {@code CarrierSource#carrierAt}
     * answers from a structure's world bounding box, and a turned structure's box overhangs the blocks it holds, so
     * a cell inside some structure's box is not necessarily a block of that structure: the probe cell is converted
     * back and the block stored at that coordinate must not be air. Both coordinates come from
     * {@link CarrierProjection#localBlockOf}, this mod's one answer to "which structure cell is this world cell",
     * so nothing is rounded a second time here.
     *
     * <p>The structure that proves it may be the one the machine itself rides, and that is intended: a machine
     * mounted on a structure plants onto the physicalised block under the cell it points at, which belongs to the
     * machine's own structure.
     *
     * <p>A direction where the structure holds nothing, a pose that cannot name the target cell and a plot cell
     * that refuses the block are all merely "not this direction", so the remaining probes are still tried; only a
     * placement that went down answers true, and the item is spent only then. Nothing is assembled here - the block
     * belongs to a structure that already exists, and a second structure carrying a copy of it would stand the same
     * block in the world twice.
     */
    private static boolean placeIntoSupportStructure(ServerLevel level, BlockPos drawnTarget, Direction out,
                                                     ItemStack stack) {
        CarrierStore.CarrierSource source = CarrierStore.source();
        if (source == null) {
            return false;
        }
        for (Direction probe : SUPPORT_PROBES) {
            BlockPos probeCell = drawnTarget.relative(probe);
            CarrierId support = source.carrierAt(level, probeCell);
            if (support == null) {
                continue;
            }
            CarrierPose pose = CarrierStore.poseOf(level, support);
            // The box that named this structure overhangs it once the structure is turned, so the probe is
            // believed only when the structure's own plot holds a block there.
            BlockPos supportCell = CarrierProjection.localBlockOf(pose, probeCell);
            if (supportCell == null || level.getBlockState(supportCell).isAir()) {
                continue;
            }
            // "I cannot say which plot cell this world cell is" must never become a guessed coordinate that gets
            // written to, and it says nothing about the five cells still to be tried.
            BlockPos plotCell = CarrierProjection.localBlockOf(pose, drawnTarget);
            if (plotCell == null) {
                continue;
            }
            // The plot cell is a level position of this same level, and Sable's own chunk mixin is what tells a
            // structure that one of its cells changed - so this is the ordinary setBlock path, through the plot
            // path's placement, and none of Sable's bookkeeping is touched here.
            InteractionResult inPlot = BlockPlacerBlock.placeInto(level, plotCell, out, stack);
            if (!inPlot.consumesAction()) {
                continue;
            }
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer: the world refused {} at {}, so it went into "
                        + "plot cell {} of the structure {} that really holds a block there", stack.getHoverName(),
                        drawnTarget.toShortString(), plotCell.toShortString(), support.shortId());
            }
            return true;
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] block placer: nothing would go down at {} ({} face {}), and none "
                    + "of the six cells around it is a block of a structure", drawnTarget.toShortString(),
                    stack.getHoverName(), out);
        }
        return false;
    }
}
