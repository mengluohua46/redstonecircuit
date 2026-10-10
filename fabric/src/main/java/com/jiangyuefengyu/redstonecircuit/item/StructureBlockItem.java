package com.jiangyuefengyu.redstonecircuit.item;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketMaterializer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 结构 as an item: a structure's id in a block's shape.
 *
 * <p>Placing one writes what it carries into the block it becomes, which is the whole of "the item only
 * carries an id" - the item is the id, and the block entity it lands on is where the id is kept while the
 * structure stands in the world. Mining it hands an item back the same way (see the block's loot table and
 * the block entity's own data).
 */
public class StructureBlockItem extends BlockItem {

    public StructureBlockItem(Block block, Item.Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean updateCustomBlockEntityTag(BlockPos pos, Level level, @Nullable Player player,
                                                 ItemStack stack, BlockState state) {
        boolean handledByVanilla = super.updateCustomBlockEntityTag(pos, level, player, stack, state);
        StructureProductData data = stack.get(RCRegistry.STRUCTURE_DATA);
        if (data == null) {
            return handledByVanilla;
        }
        if (level.getBlockEntity(pos) instanceof StructureBlockEntity product) {
            product.setStructure(data);
            materialize(level, product, player);
            return true;
        }
        return handledByVanilla;
    }

    /**
     * Puts the structure's blocks into a pocket plot, so the block that was just placed is a view onto something that
     * really runs. A structure whose file is not in this save is <b>not</b> an error: the block stays a view of nothing
     * and can be fixed by importing the file, so this reports rather than throws - to the log, and to the player, since
     * the refusal is the whole answer to "为什么".
     *
     * <p>The materialising itself is {@code StructureBlockEntity#repairPlot}, which a block with no plot also calls once
     * a second on its own tick: the block keeps asking, and this line is what tells the player what it is waiting for.
     */
    private static void materialize(Level level, StructureBlockEntity product, @Nullable Player player) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        PocketMaterializer.Result result = product.repairPlot(server);
        if (result.materialized()) {
            return;
        }
        RCConfig.LOGGER.warn("[redstonecircuit] structure {} could not be given a pocket: {}",
                product.structureId(), result.refusal());
        if (player != null) {
            player.displayClientMessage(Component.literal("[redstonecircuit] this structure has no pocket: "
                    + result.refusal() + " (it asks again once a second)"), false);
        }
    }
}
