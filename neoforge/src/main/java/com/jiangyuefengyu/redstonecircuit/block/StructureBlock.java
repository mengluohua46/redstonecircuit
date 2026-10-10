package com.jiangyuefengyu.redstonecircuit.block;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.mojang.serialization.MapCodec;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketMaterializer;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketSpace;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 结构 - the product block: a view onto a structure that is really running somewhere else.
 *
 * <p>Placing one puts the structure's blocks into a pocket plot (see {@link PocketMaterializer}), which is
 * where vanilla runs their redstone; breaking one hands the structure back as an item and gives the plot
 * away. The two halves are in different places: {@code playerDestroy} is the player's path and
 * knows the block entity is still there (so it can hand the item back with its id), while
 * {@code onRemove} catches every other way the block can leave - an explosion, a piston, another mod's
 * structure edit - so a plot cannot be left ticking for a block that no longer exists.
 */
public class StructureBlock extends BaseEntityBlock {

    /**
     * How bright this structure is glowing - the visible half of "what is happening inside", and an
     * <b>indicator</b> rather than an output: light is visible from outside and cannot drive anything - it does
     * not power dust, fire an observer, or make a neighbour update that redstone could hear. It is a blockstate
     * property rather than a synced field because light is what vanilla propagates: the server sets it and the
     * light engine tells every client.
     */
    public static final IntegerProperty LIGHT = IntegerProperty.create("light", 0, 15);

    public StructureBlock(Properties properties) {
        super(properties);
        registerDefaultState(this.stateDefinition.any().setValue(LIGHT, 0));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIGHT);
    }

    /** The light this structure emits: exactly the level the indicator holds, and nothing more. */
    @Override
    public int getLightEmission(BlockState state, BlockGetter level, BlockPos pos) {
        return state.getValue(LIGHT);
    }

    /**
     * This block is a redstone source, and vanilla has to be told so: a dust answers a signal query only on a
     * side it is <em>connected</em> on, and {@code shouldConnectTo} counts a plain block as connectable only
     * when {@code isSignalSource()} says so - so a block that emits but denies being a source leaves every wire
     * beside it holding the strength and unable to pass it on.
     *
     * <p>A constant, because the question has no position in it: whether this structure's table says anything
     * lives on its block entity. A wire beside a placed structure shows its stub towards it, as beside a
     * redstone block - which is what this block is.
     */
    @Override
    protected boolean isSignalSource(BlockState state) {
        return true;
    }

    /**
     * What this structure answers with, on the face a neighbour is asking from: nothing is emitted unless the
     * output table says so, and an empty table answers 0 on every face.
     *
     * <p><b>{@code side} runs backwards, which is vanilla's own convention</b> - the direction a block is asked
     * with points <em>from the asker towards itself</em> - so the face that is answering is
     * {@code side.getOpposite()}. A reader on the east side is the one that asks with {@code WEST}, so answering
     * on {@code side} directly leaves the lamp and the wire beside the block dark while a direct query reads the
     * row's strength.
     *
     * <p>Both vanilla questions are answered the same way, and no state is set and no update sent.
     */
    @Override
    protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return StructureBlockEntity.outputTowards(level, pos, side.getOpposite());
    }

    @Override
    protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction side) {
        return StructureBlockEntity.outputTowards(level, pos, side.getOpposite());
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(StructureBlock::new);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new StructureBlockEntity(pos, state);
    }

    /**
     * Right-clicking a placed structure opens its own screen: the keys are this structure's own copy, so the
     * screen belongs to the block that holds <em>its</em> panel rather than to the controller.
     *
     * <p>The server decides and the client draws: this method runs on both sides, and the payload it sends is
     * what makes a client open a screen.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                               BlockHitResult hit) {
        // A structure driven by its activation faces has no keys, so it has no screen either: the click simply
        // does nothing, without a message.
        if (level.getBlockEntity(pos) instanceof StructureBlockEntity product && product.ioMode() != IoMode.MANUAL) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        if (level instanceof ServerLevel server && player instanceof ServerPlayer serverPlayer) {
            // The client decides which of the two screens this block's state calls for (see StructureScreens#open).
            StructureWorldStore store = StructureWorldStore.of(server.getServer(),
                    RCConfig.structurePayloadLimits());
            PacketDistributor.sendToPlayer(serverPlayer, new RCNetwork.OpenStructureScreenPayload(pos,
                    store.structuresDirectory().toString(), store.exportsDirectory().toString()));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    /**
     * The product ticks, because mode A is a <b>reading</b> job: every tick the block asks the world what
     * each of its six faces is receiving, and pushes the changes into its plot. Only the server ticks it -
     * what the client shows is a later phase's business, and a client has no pocket to inject into.
     */
    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
                                                                 BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(type, RCRegistry.STRUCTURE_BLOCK_ENTITY.get(),
                        StructureBlockEntity::serverTick);
    }

    /**
     * Drops a structure item that carries this structure's id, instead of the plain item.
     *
     * <p>Not calling super is the point: the default implementation drops the loot table, which is a
     * structure item with <b>no</b> component - an item that describes nothing, which is the placeholder
     * case. The loot table stays in place as the fallback for removals that are not a player's (see
     * {@code onRemove}), where there is no player to be handed an item anyway.
     */
    @Override
    public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,
                              @Nullable BlockEntity blockEntity, ItemStack tool) {
        if (level instanceof ServerLevel server && blockEntity instanceof StructureBlockEntity product) {
            if (!product.isMissing()) {
                Block.popResource(level, pos, RCRegistry.structureStack(product.asProduct()));
            }
            releasePlot(server, product, true);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean movedByPiston) {
        // Not a state change within the same block (a waterlogged flag, say) but a real removal.
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel server
                && level.getBlockEntity(pos) instanceof StructureBlockEntity product) {
            releasePlot(server, product, false);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    /**
     * Gives the plot back, once - unless the block left the world with something rather than with a player.
     *
     * <p>A player break runs both {@code playerDestroy} and {@code onRemove}, so the index is cleared before the
     * release and the second caller sees -1; releasing a plot also clears its blocks, which is not work to do
     * twice. A removal no player asked for is never a break, and the index stays on the block: a carrier takes a
     * block out of the world by removing it, a moved block looks exactly like a destroyed one at that instant,
     * and the moved one must keep its index for its next tick to reown the plot - losing it costs a structure
     * outright. The lease and the sweep remain the backstop for a block destroyed by an explosion, a command or
     * another machine's leftovers.
     */
    private static void releasePlot(ServerLevel server, StructureBlockEntity product, boolean byPlayer) {
        int plot = product.plotIndex();
        if (plot < 0) {
            return;
        }
        BlockPos pos = product.getBlockPos();
        if (!byPlayer) {
            if (PocketSpace.awayFromHome(server, plot) || CarrierStore.isCarried(server, pos)) {
                RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} was taken out of the world by a "
                                + "moving structure, so plot {} travels with it instead of being given back",
                        pos.toShortString(), plot);
                return;
            }
            // Nothing can be asked of the carrier layer at this instant: Sable clears the world side and only
            // then moves the blocks into the plot, so a lookup answers "none" for a block already on its way.
            ServerLevel pocket = PocketDimension.of(server.getServer());
            boolean marked = pocket != null
                    && PocketSpace.of(pocket).noteTravelling(server.dimension(), pos, server.getGameTime());
            RCConfig.LOGGER.info("[redstonecircuit] pocket: the structure at {} was removed by something that is "
                            + "not a player, so plot {} waits for the block to turn up ({})",
                    pos.toShortString(), plot, marked ? "stamped travelling" : "no record left to stamp");
            return;
        }
        // A player's break is the one removal that ends the claim, and clearing the index first makes the second
        // caller (onRemove always follows playerDestroy) see -1.
        product.setPlotIndex(-1);
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] pocket: the player broke the structure at {}, so plot {} is "
                            + "given back", pos.toShortString(), plot);
        }
        PocketMaterializer.release(server, plot);
    }

    @Override
    public void appendHoverText(ItemStack stack, Item.TooltipContext context, List<Component> tooltip,
                                TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        // One line by default, the how-to behind [Shift], like Create's wrench (RcTooltips).
        com.jiangyuefengyu.redstonecircuit.RcTooltips.append(tooltip, flag,
                "block.redstonecircuit.structure.tip",
                com.jiangyuefengyu.redstonecircuit.RcTooltips.detail("block.redstonecircuit.structure.tip.detail"));
    }
}
