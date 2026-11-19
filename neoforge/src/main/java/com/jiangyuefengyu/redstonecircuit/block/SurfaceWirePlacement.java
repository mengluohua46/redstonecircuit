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
 * Places {@link SurfaceWireBlock} from vanilla's own redstone item, and only where vanilla cannot place its wire.
 *
 * <p>One line wide, the same rule the two diode twins use: <b>clicking a top face is vanilla's click, untouched</b>.
 * Vanilla's wire needs a floor under it, so every other face is a click its own code cannot answer - those are the
 * ones this takes over. Nothing about a wire on the floor moves: same item, same block, same code.
 *
 * <p>Clicking a face of a cell that already carries a sheet <b>adds that face</b> instead of placing a new block: one
 * cell holds one wire, on as many of its six faces as the player asked for.
 */
public final class SurfaceWirePlacement {

    private SurfaceWirePlacement() {
    }

    /** The placement for this click, or {@code null} when the click is vanilla's to handle. */
    @Nullable
    public static InteractionResult tryPlace(UseOnContext context) {
        ItemStack stack = context.getItemInHand();
        if (!(stack.getItem() instanceof BlockItem item) || item.getBlock() != Blocks.REDSTONE_WIRE) {
            return null;
        }
        Direction clicked = context.getClickedFace();
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos().relative(clicked);
        Direction face = clicked.getOpposite();
        SurfaceWireBlock block = RCRegistry.SURFACE_WIRE.get();
        BlockState target = level.getBlockState(pos);
        if (com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info(
                    "[redstonecircuit] placement asked: clicked={} face={} cell={} target={} | {}",
                    context.getClickedPos().toShortString(), clicked, pos.toShortString(), target,
                    com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.describe(level,
                            context.getClickedPos()));
        }
        if (target.getBlock() instanceof SurfaceWireBlock) {
            // Any face, UP included: a cell that already carries a sheet takes one more layer.
            return SurfaceWireBlock.hasFace(target, face) ? InteractionResult.sidedSuccess(level.isClientSide)
                    : addFace(level, pos, target, face, stack, context);
        }
        if (clicked == Direction.UP && !insideCarrier(level, context.getClickedPos())) {
            // The one face vanilla's wire is happy with: an empty cell there is vanilla's click. <b>Except over a block a
            // machine keeps in its own storage</b> - the click arrives already in the structure's own coordinates (作者
            // 2026-11-17 的日志：raw=20481032, 127, 20513799 face=up), and vanilla's placement writes nothing a player
            // ever sees there, which is why a floor sheet could not be placed first at a junction across a structure
            // (作者 2026-11-16). Over a carried block this sheet is put down by us, on the same floor face vanilla uses.
            return null;
        }
        if (target.is(Blocks.REDSTONE_WIRE)) {
            // 拐角那一格上已经有一根原版粉（点地面时是原版放的）：它占的就是「地面那一面」，
            // 现在再点墙面，就是给同一格加第二个面 —— 原版粉换成孪生粉，强度照抄。
            return convert(level, pos, target, face, stack, context);
        }
        if (!target.canBeReplaced()) {
            return null;
        }
        BlockState state = SurfaceWireBlock.withFace(block.defaultBlockState(), face);
        if (!block.canStay(state, level, pos)) {
            refuse("new sheet", level, pos, face);
            return null;
        }
        level.setBlock(pos, state, 3);
        after(level, pos, state);
        consume(level, stack, context);
        playPlaceSound(level, pos, state, context);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * <b>Whether the clicked block is one a machine keeps in its own block storage.</b> Asked only to decide who owns a
     * top-face click: vanilla's placement is the world's, and a structure's own blocks are not in the world (a Sable
     * sub-level keeps them at plot coordinates, which is what the click already names).
     */
    private static boolean insideCarrier(Level level, BlockPos clickedPos) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) {
            // The client predicts vanilla's click; the server is where the real answer is, and it is the one that writes.
            return false;
        }
        return com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore.structureOwnerAt(server, clickedPos) != null;
    }

    /**
     * <b>What the sheet looks like the moment it is written.</b> A sheet that answers nothing is either not a signal
     * source (the silence counter stuck above zero) or standing where nothing will ever wake it, and from outside the two
     * are indistinguishable - so the faces, the strength, the silence counter, whether it counts as a source and the
     * carrier lookup are printed together (作者 2026-11-17：跨结构点不亮，而且那张粉连一行自己的诊断都没有).
     */
    private static void after(Level level, BlockPos pos, BlockState state) {
        if (!com.jiangyuefengyu.redstonecircuit.RCConfig.debugLog()) {
            return;
        }
        com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info(
                "[redstonecircuit] wire placed: cell={} faces={} power={} quiet={} source={} | {}",
                pos.toShortString(), SurfaceWireBlock.faces(state), state.getValue(SurfaceWireBlock.POWER),
                SurfaceWireBlock.quietDepth(), state.isSignalSource(),
                com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.describe(level, pos));
    }

    /** A vanilla wire in the cell becomes our sheet on its own floor face, plus the face just clicked. */
    private static InteractionResult convert(Level level, BlockPos pos, BlockState target, Direction face,
                                             ItemStack stack, UseOnContext context) {
        SurfaceWireBlock block = RCRegistry.SURFACE_WIRE.get();
        BlockState converted = SurfaceWireBlock.withFace(SurfaceWireBlock.withFace(block.defaultBlockState(),
                Direction.DOWN), face).setValue(SurfaceWireBlock.POWER,
                        target.getValue(net.minecraft.world.level.block.RedStoneWireBlock.POWER));
        if (!block.canStay(converted, level, pos)) {
            return InteractionResult.PASS;
        }
        level.setBlock(pos, converted, 3);
        after(level, pos, converted);
        consume(level, stack, context);
        playPlaceSound(level, pos, converted, context);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /** One more face on a sheet that is already there: same cell, same wire, one item. */
    static InteractionResult addFace(Level level, BlockPos pos, BlockState target, Direction face,
                                             ItemStack stack, UseOnContext context) {
        SurfaceWireBlock block = (SurfaceWireBlock) target.getBlock();
        BlockState merged = SurfaceWireBlock.withFace(target, face);
        if (!block.canStay(merged, level, pos)) {
            refuse("one more face", level, pos, face);
            return InteractionResult.PASS;
        }
        level.setBlock(pos, merged, 3);
        after(level, pos, merged);
        consume(level, stack, context);
        playPlaceSound(level, pos, merged, context);
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * <b>Why a click that looked placeable was refused, in one line.</b> A 贴面 is refused when the face behind it is not
     * solid, and across a structure boundary that face is a block a machine is drawing - so the line names the cell, the
     * world's own answer and every link of the carrier lookup, which is the only way to tell "no machine here" from "the
     * machine's block is not where the pose says".
     */
    private static void refuse(String what, Level level, BlockPos pos, Direction face) {
        BlockPos support = pos.relative(face);
        com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.info(
                "[redstonecircuit] placement refused ({}): cell={} face={} support={} world={} | {}",
                what, pos.toShortString(), face, support.toShortString(),
                level.getBlockState(support),
                com.jiangyuefengyu.redstonecircuit.carrier.CarriedBlocks.describe(level, support));
    }

    static void consume(Level level, ItemStack stack, UseOnContext context) {
        if (!level.isClientSide && (context.getPlayer() == null || !context.getPlayer().getAbilities().instabuild)) {
            stack.shrink(1);
        }
    }

    static void playPlaceSound(Level level, BlockPos pos, BlockState state, UseOnContext context) {
        SoundType sound = state.getSoundType();
        level.playSound(context.getPlayer(), pos, sound.getPlaceSound(), SoundSource.BLOCKS,
                (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
    }
}
