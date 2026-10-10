package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 红石结构方块 - the controller block.
 *
 * <p>Right-clicking it is <b>one operation</b>: the first one shows the region, the second one does it. The
 * meanings are not decided here - {@code StructurePreviews.press} owns them, so the click, the commands and
 * the self-test cannot disagree about what a second click does.
 *
 * <p>The press is <b>server-side only</b>: the client's own prediction calls this method too, so the client
 * is answered with {@code SUCCESS} (the hand swings) and acts on nothing.
 */
public class RedstoneStructureBlock extends BaseEntityBlock {

    public RedstoneStructureBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(RedstoneStructureBlock::new);
    }

    /**
     * <b>The controller draws its own cube.</b> {@code BaseEntityBlock} answers {@link RenderShape#INVISIBLE},
     * which leaves a block undrawn while it still occludes its neighbours - a hole a player can see through.
     * A product block keeps {@code INVISIBLE} on purpose: the mini structure is what should be seen there.
     */
    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new RedstoneStructureBlockEntity(pos, state);
    }

    /**
     * <b>Right-click opens the editor, and that is the whole of the mapping</b>, sneaking or not: every step
     * is a button on that screen, and the screen and the commands call the same {@code StructurePreviews}.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        if (!(level instanceof ServerLevel server) || !(player instanceof ServerPlayer serverPlayer)) {
            // The client's prediction: it has to answer yes so the hand swings, and it must not act.
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        // The server answers "open your screen" - it does not open anything itself, it cannot - and the screen's own
        // edits are validated when they arrive, one packet per button. The two paths travel with it so the screen can
        // say where a structure file lives (see the payload's own comment).
        StructureWorldStore store = StructureWorldStore.of(server.getServer(),
                RCConfig.structurePayloadLimits());
        ServerPlayNetworking.send(serverPlayer, new RCNetwork.OpenStructureScreenPayload(pos,
                store.structuresDirectory().toString(), store.exportsDirectory().toString()));
        return InteractionResult.sidedSuccess(true);
    }

    /**
     * <b>Putting a structure INTO the controller</b>: the <em>description</em> moves (id, name, size,
     * settings), never the blocks, which stay in the save's own structure file. A creative stack is not consumed.
     */
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                              Player player, InteractionHand hand, BlockHitResult hit) {
        StructureProductData data = RCRegistry.structureOf(stack);
        if (data == null) {
            // Not one of ours: this is vanilla's click, and it must stay vanilla's click.
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!(level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity controller)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (level.isClientSide) {
            return ItemInteractionResult.sidedSuccess(true);
        }
        if (controller.pending() != null) {
            player.displayClientMessage(Component.translatable(
                    "message.redstonecircuit.structure.slot.full", controller.pending().name()), true);
            return ItemInteractionResult.sidedSuccess(false);
        }
        controller.setPending(data);
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.hasInfiniteMaterials()) {
            stack.shrink(1);
        }
        player.displayClientMessage(Component.translatable(
                "message.redstonecircuit.structure.slot.loaded", data.name()), true);
        return ItemInteractionResult.sidedSuccess(false);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        com.jiangyuefengyu.redstonecircuit.RcTooltips.append(tooltip, flag,
                "block.redstonecircuit.redstone_structure_block.tip",
                com.jiangyuefengyu.redstonecircuit.RcTooltips.detail(
                        "block.redstonecircuit.redstone_structure_block.tip.detail"),
                com.jiangyuefengyu.redstonecircuit.RcTooltips.detail(
                        "block.redstonecircuit.redstone_structure_block.tip.hold"));
    }
}
