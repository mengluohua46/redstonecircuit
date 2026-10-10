package com.jiangyuefengyu.redstonecircuit.block;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.logic.PlacerRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 方块放置器's nine slots.
 *
 * <p>The GUI "和发射器相同" is taken as "is it" rather than "looks like it": a nine-slot {@code Container} whose menu
 * is vanilla's {@link DispenserMenu}, registered under {@code MenuType.GENERIC_3x3} and drawn by vanilla's
 * {@code DispenserScreen}, whose client-side constructor fills in a dummy nine-slot container of its own. A block
 * entity that is a {@code MenuProvider} handing out a {@code DispenserMenu} therefore gets the dispenser's window,
 * slot positions, fast-shift behaviour and item icons with <b>no</b> screen class, texture or menu registration of
 * this mod's own; only the title differs, and that is what the server sends.
 *
 * <p>{@code RandomizableContainerBlockEntity} rather than {@code Container} by hand: it is the class vanilla's
 * dispenser extends, so saving, loading, the "still valid" check, hopper insertion and the comparator's fill reading
 * all come from the same place vanilla's do. Hoppers may push in and may not pull out, exactly as with a dispenser
 * (only a {@code WorldlyContainer} may be pulled from).
 */
public class BlockPlacerBlockEntity extends RandomizableContainerBlockEntity {

    /** Nine, like the dispenser's - see the class doc for why that number is not a coincidence. */
    public static final int CONTAINER_SIZE = 9;

    private NonNullList<ItemStack> items = NonNullList.withSize(CONTAINER_SIZE, ItemStack.EMPTY);

    protected BlockPlacerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    public BlockPlacerBlockEntity(BlockPos pos, BlockState state) {
        this(RCRegistry.BLOCK_PLACER_ENTITY, pos, state);
    }

    @Override
    public int getContainerSize() {
        return CONTAINER_SIZE;
    }

    @Override
    protected Component getDefaultName() {
        return Component.translatable("container.redstonecircuit.block_placer");
    }

    @Override
    protected NonNullList<ItemStack> getItems() {
        return this.items;
    }

    @Override
    protected void setItems(NonNullList<ItemStack> items) {
        this.items = items;
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory playerInventory) {
        return new DispenserMenu(containerId, playerInventory, this);
    }

    /**
     * <b>Only things that can be put down as a block.</b>
     *
     * <p>A dispenser takes arrows, buckets and bones because it can use them; this machine has exactly one action, so
     * anything else in a slot is an item the player could never get back out of a working machine without wondering
     * why it never fires. Refusing it at the door - the slot, the hopper and the fast-shift all ask this one question
     * - is the difference between "put a block in" and "put a block in and watch nothing happen".
     */
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BlockItem;
    }

    /** {@link PlacerRule#firstFilled}: fill from the first slot in order, or -1 when there is nothing to place. */
    public int firstFilledSlot() {
        // Vanilla's dispenser unpacks its loot table before reading a slot, and a loot table may be what fills these.
        this.unpackLootTable(null);
        boolean[] filled = new boolean[CONTAINER_SIZE];
        for (int slot = 0; slot < filled.length; slot++) {
            filled[slot] = !this.items.get(slot).isEmpty();
        }
        return PlacerRule.firstFilled(filled);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY);
        if (!this.tryLoadLootTable(tag)) {
            ContainerHelper.loadAllItems(tag, this.items, registries);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (!this.trySaveLootTable(tag)) {
            ContainerHelper.saveAllItems(tag, this.items, registries);
        }
    }
}
