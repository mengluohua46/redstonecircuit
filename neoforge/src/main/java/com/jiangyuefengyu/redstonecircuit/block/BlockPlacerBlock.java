package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame;
import com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat;
import com.jiangyuefengyu.redstonecircuit.logic.PlacerRule;

import com.mojang.serialization.MapCodec;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;

/**
 * 方块放置器 - one activation, one block, out of the front face.
 *
 * <p>{@link #FACING} is the six-way output face, and the cell that receives the block is the one in front of
 * it. The GUI is the dispenser's, because the block entity hands out vanilla's menu. {@link #TRIGGERED} is
 * the dispenser's rising-edge latch, so a signal that stays on places one block rather than one per tick, and
 * the block goes down one tick later - acting like a piston rather than writing to the world from inside a
 * neighbour update.
 *
 * <p>The block is placed by vanilla's own item placement through {@link FacingContext}, a placement context
 * with no player in it that answers vanilla's orientation questions from the output face, so every vanilla
 * special case - waterlogging, block-entity data carried by the item, the place sound, the item being
 * consumed exactly once - is kept rather than copied. Nothing to place, or the cell in front taken, means
 * nothing happens and nothing is spent either.
 *
 * <p>When this block is being drawn by a carrier its own coordinate is a plot coordinate, so what it places
 * is a new standalone one-block structure drawn where the machine points: a world block written there would
 * appear in the wrong place and be left behind the moment the structure moved.
 */
public class BlockPlacerBlock extends BaseEntityBlock {

    public static final MapCodec<BlockPlacerBlock> CODEC = simpleCodec(BlockPlacerBlock::new);

    /** 正面: the output face. The cell in front of it is the one that receives the block. Six-way, like an observer's. */
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    /**
     * Whether the signal this block is currently being given has already been spent: vanilla's dispenser
     * property, which is what turns "there is a signal" into "a signal <b>arrived</b>", so a lever left on
     * places one block rather than sixty-four.
     */
    public static final BooleanProperty TRIGGERED = BlockStateProperties.TRIGGERED;

    /**
     * How long after the rising edge the block is put down: one tick, the way a piston acts, rather than the
     * dispenser's four. Placing straight out of {@code neighborChanged} would write to the world while the
     * world is still deciding what changed.
     */
    private static final int PLACE_DELAY_TICKS = 1;

    public BlockPlacerBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(FACING, Direction.NORTH)
                .setValue(TRIGGERED, false));
    }

    @Override
    protected MapCodec<BlockPlacerBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, TRIGGERED);
    }

    /** A block with a model, not one of {@code BaseEntityBlock}'s invisible ones. */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    /**
     * 正面 is the output face, and it points back at whoever placed the block - the piston's own rule
     * ({@code getNearestLookingDirection().getOpposite()}), the same one 逻辑整合器 uses.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new BlockPlacerBlockEntity(pos, state);
    }

    /** 点击进入界面 - and the interface is the dispenser's, because the menu the block entity hands out is. */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (level.isClientSide) {
            // The client's prediction: the hand swings and the screen is opened by the server's answer.
            return InteractionResult.SUCCESS;
        }
        if (level.getBlockEntity(pos) instanceof BlockPlacerBlockEntity placer) {
            player.openMenu(placer);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * 右键的那一行字：这台机器有两条玩家看不出来的规则 - 正面是输出面、槽里只收能当方块放下的东西。
     *
     * <p>写在这里而不是界面上，是因为它要在玩家<b>还没打开</b>界面时就能读到：发射器的界面本身不会告诉他这两件事。
     */
    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // One line by default, the how-to behind [Shift], like Create's wrench.
        com.jiangyuefengyu.redstonecircuit.RcTooltips.append(tooltip, flag,
                "block.redstonecircuit.block_placer.tip",
                com.jiangyuefengyu.redstonecircuit.RcTooltips.detail("block.redstonecircuit.block_placer.tip.detail"));
    }

    /**
     * The rising edge, and only the rising edge - asked in both coordinate frames.
     *
     * <p>{@code hasNeighborSignal} is vanilla's "is any of the six sides giving this block a signal". Vanilla's
     * dispenser also fires when the block <em>above</em> it is powered; that clause is not copied, because it
     * because it is a rule about this machine that reads as a bug from the outside.
     *
     * <p>A machine that is being drawn somewhere else has to be asked twice: a plot coordinate's neighbours
     * are the plot's, so a signal in the world next to where the machine is drawn never reaches it. Ask at the
     * coordinate <b>and</b> at the place it is drawn.
     */
    @Override
    protected void neighborChanged(BlockState state, Level level, BlockPos pos, Block neighbour,
                                   BlockPos fromPos, boolean movedByPiston) {
        if (level instanceof ServerLevel serverLevel) {
            latch(serverLevel, pos, state);
        }
    }

    /**
     * The same question, asked by the carrier layer's own tick walk, because nothing else ever will: a block
     * inside a plot is not told that the world next to where it is drawn has changed, since vanilla's neighbour
     * updates do not cross that gap.
     */
    public static void refreshCarried(ServerLevel level, BlockPos pos, BlockState state) {
        latch(level, pos, state);
    }

    /**
     * Is this machine being given a signal, in either frame? The cell's own six sides, and - when the machine
     * is being drawn by a carrier - the six sides of the block it is drawn as. Either one lights it: a redstone
     * block assembled <em>with</em> the machine, and one standing on the ground where the machine looks.
     */
    private static boolean powered(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.hasNeighborSignal(pos)) {
            return true;
        }
        ProductFrame frame = ProductFrame.carriedAt(level, pos);
        if (!frame.carried()) {
            return false;
        }
        BlockPos drawn = frame.worldPosOf(pos);
        return drawn != null && !drawn.equals(pos) && level.hasNeighborSignal(drawn);
    }

    /**
     * The rising edge applied to the block state, shared by the neighbour update and the carrier walk. The
     * latch lives in {@link #TRIGGERED}, so this is idempotent: the only thing that can happen is the one
     * transition that has not happened yet.
     */
    private static boolean latch(ServerLevel level, BlockPos pos, BlockState state) {
        boolean powered = powered(level, pos, state);
        if (powered == state.getValue(TRIGGERED)) {
            return false;
        }
        if (powered) {
            // The placement itself happens on the next tick: writing to the world from inside a neighbour update -
            // or from the carrier's walk - is what the delay exists to avoid.
            level.scheduleTick(pos, state.getBlock(), PLACE_DELAY_TICKS);
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer at {} armed by {}",
                        pos.toShortString(), level.hasNeighborSignal(pos)
                                ? "a neighbour of its own cell"
                                : "a neighbour of the place it is drawn at");
            }
        }
        level.setBlock(pos, state.setValue(TRIGGERED, powered), Block.UPDATE_CLIENTS);
        return powered;
    }

    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        placeFrom(level, state, pos);
    }

    /**
     * One activation: put one block down in the cell in front, using the first filled slot. Server side, and
     * public because it is the machine's whole action - the scheduled tick calls it and the game test drives it.
     */
    public static void placeFrom(ServerLevel level, BlockState state, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof BlockPlacerBlockEntity placer)) {
            return;
        }
        Direction out = state.getValue(FACING);
        int slot = placer.firstFilledSlot();
        ItemStack stack = slot < 0 ? ItemStack.EMPTY : placer.getItem(slot);
        BlockPos front = pos.relative(out);
        ProductFrame frame = ProductFrame.carriedAt(level, pos);

        if (frame.carried()) {
            if (placeStandalone(level, frame, out, slot, stack, front)) {
                placer.setItem(slot, stack);
                placer.setChanged();
            }
            return;
        }

        BlockPlaceContext context = new FacingContext(level, stack, hitAt(pos, out), out);
        PlacerRule.Verdict verdict = PlacerRule.decide(slot >= 0, context.canPlace());
        if (verdict != PlacerRule.Verdict.PLACE) {
            report(pos, verdict, out, slot, front);
            return;
        }

        InteractionResult placed = place(context, stack);
        if (placed.consumesAction()) {
            // `place` consumed the item itself (ItemStack#consume), and `stack` is the container's own stack,
            // so writing it back also stores the "that was the last one" case as an empty slot.
            placer.setItem(slot, stack);
            placer.setChanged();
        } else if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] block placer at {} could not place {} at {}",
                    pos.toShortString(), stack.getDescriptionId(), front.toShortString());
        }
    }

    /**
     * The physical half: the block is placed into a new standalone structure instead of the level.
     *
     * <p>When this block is drawn by a carrier its own coordinate is a plot coordinate, where writing a block
     * would put it in the wrong place and leave it behind the moment the structure moved. So the compat half
     * makes a new sub-level holding exactly this one block, drawn at the world cell the machine points at, and
     * the block goes into it through the same vanilla placement the world path uses.
     *
     * <p>"Is the cell in front taken" is asked once per coordinate frame: the cell inside the machine's own
     * structure, where a carried machine's "in front" literally is, and the world cell the new structure would
     * be drawn at. Either one taken means nothing happens - and nothing is spent.
     */
    private static boolean placeStandalone(ServerLevel level, ProductFrame frame, Direction out, int slot,
                                           ItemStack stack, BlockPos front) {
        BlockPos drawn = frame.worldPosOf(front);
        BlockPlaceContext probe = new FacingContext(level, stack, hitAt(front, out), out);
        boolean free = level.getBlockState(front).canBeReplaced(probe)
                && level.getBlockState(drawn).canBeReplaced(probe);

        PlacerRule.Verdict verdict = PlacerRule.decide(slot >= 0, free);
        if (verdict != PlacerRule.Verdict.PLACE) {
            report(front, verdict, out, slot, drawn);
            return false;
        }
        if (!PhysicsCompat.placeSingleBlockStructure(level, drawn, out, stack)) {
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] block placer on a structure at {} does nothing: no standalone "
                        + "structure could be made at {} (no Sable, or the block would not go down)",
                        front.toShortString(), drawn.toShortString());
            }
            return false;
        }
        return true;
    }

    /**
     * One line naming <b>why</b> an activation did nothing: the rule has three states and two of them are
     * "nothing happens", which from the outside look identical - the machine just sits there.
     */
    private static void report(BlockPos pos, PlacerRule.Verdict verdict, Direction out, int slot, BlockPos target) {
        if (!RCConfig.debugLog()) {
            return;
        }
        RCConfig.LOGGER.info("[redstonecircuit] block placer at {} does nothing: {} ({} face, slot {} -> {})",
                pos.toShortString(),
                verdict == PlacerRule.Verdict.NOTHING ? "no block in the slots" : "the cell in front is taken",
                out, slot, target.toShortString());
    }

    /**
     * Puts one item's block into a given cell of a level, as this machine would: vanilla's own placement aimed
     * at the cell <em>itself</em> rather than at the machine's face, so the plot path's placement is the world
     * path's placement rather than a second implementation of it.
     *
     * <p>It places into that cell or not at all. Vanilla's rule for a click on a cell that cannot be replaced
     * is to place beside it, which for a cell of a structure that was just made - empty by construction - could
     * only ever be a bug: the block would land one cell further along. So the cell's own replaceability is
     * asked here too, and a cell that cannot take the block answers {@code FAIL} with nothing spent.
     */
    public static InteractionResult placeInto(Level level, BlockPos cell, Direction out, ItemStack stack) {
        BlockPlaceContext context = new FacingContext(level, stack, hitAt(cell, out), out);
        return level.getBlockState(cell).canBeReplaced(context) ? place(context, stack) : InteractionResult.FAIL;
    }

    /** Vanilla's placement, with a synthetic click and this machine's orientation rules. */
    private static InteractionResult place(BlockPlaceContext context, ItemStack stack) {
        if (!(stack.getItem() instanceof BlockItem blockItem)) {
            // Unreachable through the GUI and through a hopper - canPlaceItem refuses anything else - so this
            // is the belt-and-braces half of that rule, not the rule itself.
            return InteractionResult.FAIL;
        }
        return blockItem.place(context);
    }

    /** A synthetic click on {@code pos}'s {@code face} side; see {@link #faceCentre} for the vector. */
    private static BlockHitResult hitAt(BlockPos pos, Direction face) {
        return new BlockHitResult(faceCentre(pos, face), face, pos, false);
    }

    /**
     * The centre of a face of a block - vanilla's own hit vector for a synthetic click. It matters for the
     * blocks that ask where the click landed rather than which face was clicked (a slab decides top-or-bottom
     * from it), so the synthetic click has to be a real one.
     */
    private static Vec3 faceCentre(BlockPos pos, Direction face) {
        return new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5,
                pos.getY() + 0.5 + face.getStepY() * 0.5,
                pos.getZ() + 0.5 + face.getStepZ() * 0.5);
    }

    /**
     * A placement context with nobody standing there, answered from the output face.
     *
     * <p>Vanilla asks two questions about the player and they carry two different meanings of "facing": the
     * horizontal direction, which stairs, logs and campfires read straight into their {@code FACING}, and the
     * nearest looking direction, which piston- and observer-like blocks flip so their front points back at the
     * player who put them down. Both are answered here from the output face, so a stair rises outward and a
     * piston pushes outward - the machine's convention rather than a player's, because there is no player here.
     *
     * <p>The synthetic hit is a click on <b>this</b> block's output face, so vanilla lands the placement in the
     * cell the face points at and {@code canPlace()} asks whether that cell can be replaced.
     */
    private static final class FacingContext extends BlockPlaceContext {

        private final Direction out;

        FacingContext(Level level, ItemStack stack, BlockHitResult hit, Direction out) {
            super(level, null, InteractionHand.MAIN_HAND, stack, hit);
            this.out = out;
        }

        @Override
        public Direction getHorizontalDirection() {
            return this.out;
        }

        @Override
        public float getRotation() {
            return this.out.toYRot();
        }

        @Override
        public Direction getNearestLookingDirection() {
            return this.out.getOpposite();
        }

        /**
         * The same order the two methods above imply, spelled out because there is no player for vanilla to
         * sort directions against.
         */
        @Override
        public Direction[] getNearestLookingDirections() {
            Direction[] order = new Direction[Direction.values().length];
            order[0] = this.out.getOpposite();
            order[1] = this.out;
            int next = 2;
            for (Direction candidate : Direction.values()) {
                if (candidate != this.out && candidate != this.out.getOpposite()) {
                    order[next++] = candidate;
                }
            }
            return order;
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    /**
     * Breaking the machine drops what is inside it - and it is the player's break that drops them.
     *
     * <p>The obvious implementation is the dispenser's own line, dropping the contents on removal. It is wrong
     * here because <b>a carrier takes a block out of the world by removing it</b>: that path runs every time a
     * machine is picked up by a contraption or a physics assembler, so dropping there would spill the box on
     * the ground <b>and</b> hand the very same nine slots to the plot a moment later - a duplication rather
     * than a courtesy. Vanilla's version cannot be argued with either, since all it asks is whether the block
     * changed. {@code playerDestroy} runs for a break and for nothing else, so the rule goes there: an
     * explosion that destroys the machine destroys what was in it.
     */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (level instanceof ServerLevel serverLevel && blockEntity instanceof BlockPlacerBlockEntity placer) {
            Containers.dropContents(serverLevel, pos, placer);
        }
        super.playerDestroy(level, player, pos, state, blockEntity, tool);
    }

    /** The dispenser's comparator behaviour: a full slot is a stronger signal. */
    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));
    }
}
