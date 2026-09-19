package com.jiangyuefengyu.redstonecircuit;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Decides which blocks may host inner redstone, and which items are redstone components.
 *
 * <p>Rules (from the design document):
 * <ul>
 *   <li>rejected: transparent blocks (glass, leaves) and non-full-cube blocks
 *       (beds, chests, brewing stands) - detected via {@link BlockState#isSolidRender} and
 *       {@link BlockState#isCollisionShapeFullBlock};</li>
 *   <li>rejected: workstations (crafting table, furnaces, ...) - explicit deny list, because they
 *       are full opaque cubes but must not be hollowed out;</li>
 *   <li>rejected: full-cube redstone components (pistons, dispensers, ...) cannot be placed
 *       *inside* another block.</li>
 * </ul>
 */
public final class HostRules {

    /** Blocks that are full opaque cubes but must stay solid (工作方块). */
    private static final java.util.Set<Block> WORKSTATION_DENY = java.util.Set.of(
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.ANVIL,
            Blocks.CHIPPED_ANVIL,
            Blocks.DAMAGED_ANVIL,
            Blocks.CARTOGRAPHY_TABLE,
            Blocks.FLETCHING_TABLE,
            Blocks.SMITHING_TABLE,
            Blocks.LOOM,
            Blocks.STONECUTTER,
            Blocks.GRINDSTONE,
            Blocks.BREWING_STAND,
            Blocks.ENCHANTING_TABLE,
            Blocks.BEACON,
            Blocks.CONDUIT,
            Blocks.RESPAWN_ANCHOR);

    private HostRules() {
    }

    /**
     * Whether redstone may be stored inside the block at {@code pos}.
     *
     * <p>Placement uses the cheap overload; this one additionally resolves the state, so it is
     * only used from commands and diagnostics.
     */
    public static boolean isValidHost(BlockGetter level, BlockPos pos) {
        if (level == null) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        return isValidHostState(state);
    }

    public static boolean isValidHost(BlockState state) {
        return isValidHostState(state);
    }

    private static boolean isValidHostState(BlockState state) {
        if (state.isAir()) {
            return false;
        }
        if (WORKSTATION_DENY.contains(state.getBlock())) {
            return false;
        }
        // Transparency is a pure block-state property (glass, leaves, ice, ...), so check the
        // cheap one first. A dummy getter suffices because no accepted vanilla block consults the
        // position here.
        if (!state.isSolidRender(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        // Non-full-cube blocks (beds, chests, brewing stands, slabs, stairs, ...). Checking the
        // collision shape last avoids computing a VoxelShape for blocks we already rejected.
        if (!state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
            return false;
        }
        return true;
    }

    /**
     * Full-cube shapes with no block-specific state, so a dummy getter is enough.
     *
     * <p>Only used for state-independent checks; block positions are deliberately
     * {@link BlockPos#ZERO} because none of the vanilla block classes we accept read the position
     * for these two properties.
     */
    private enum EmptyBlockGetter implements BlockGetter {
        INSTANCE;

        @Override
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(BlockPos pos) {
            return null;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return Blocks.AIR.defaultBlockState();
        }

        @Override
        public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) {
            return net.minecraft.world.level.material.Fluids.EMPTY.defaultFluidState();
        }

        @Override
        public int getHeight() {
            return 0;
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }
    }

    /**
     * Maps the held item to the component it would become, or {@code null} if it is not a
     * redstone component.
     *
     * <p>Pistons, dispensers, droppers and observers are intentionally absent: they are full-cube
     * blocks and the design forbids putting them inside another block.
     */
    @Nullable
    public static ComponentType componentFor(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Item item = stack.getItem();
        if (item == Items.REDSTONE) {
            return ComponentType.DUST;
        }
        if (item == Items.REPEATER) {
            return ComponentType.REPEATER;
        }
        if (item == Items.COMPARATOR) {
            return ComponentType.COMPARATOR;
        }
        if (item == Items.REDSTONE_TORCH) {
            return ComponentType.TORCH;
        }
        if (item == Items.LEVER) {
            return ComponentType.LEVER;
        }
        if (item == Items.STONE_BUTTON
                || item == Items.OAK_BUTTON
                || item == Items.POLISHED_BLACKSTONE_BUTTON) {
            return ComponentType.BUTTON;
        }
        if (item == Items.LIGHT_WEIGHTED_PRESSURE_PLATE
                || item == Items.HEAVY_WEIGHTED_PRESSURE_PLATE) {
            return ComponentType.PRESSURE_PLATE;
        }
        return null;
    }

    /** The item a component turns back into when it is taken out of a host block. */
    public static ItemStack itemFor(ComponentType type) {
        return switch (type) {
            case DUST -> new ItemStack(Items.REDSTONE);
            case REPEATER -> new ItemStack(Items.REPEATER);
            case COMPARATOR -> new ItemStack(Items.COMPARATOR);
            case TORCH -> new ItemStack(Items.REDSTONE_TORCH);
            case LEVER -> new ItemStack(Items.LEVER);
            case BUTTON -> new ItemStack(Items.STONE_BUTTON);
            case PRESSURE_PLATE -> new ItemStack(Items.LIGHT_WEIGHTED_PRESSURE_PLATE);
        };
    }

    /**
     * Which face of the host the component is attached to.
     *
     * <p>Uses the clicked face so that clicking the top of a block behaves like vanilla, while
     * clicking a side or the bottom mounts the component on that vertical/bottom face (R5).
     */
    public static Direction slotFor(BlockPos clickedPos, Direction clickedFace) {
        return clickedFace;
    }
}
