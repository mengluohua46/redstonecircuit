package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * The switches hidden inside blocks: levers and buttons.
 *
 * <p>Separate from the interaction handler because working a switch is not a player-specific act -
 * the player click, the {@code /rc toggle} command and the game tests all have to go through exactly
 * the same state change, and the only difference between them is the sound the player hears.
 *
 * <p>A lever stays where it is put. A button is the same thing with a release scheduled for one
 * second later - "vanilla's stone button" - which is why both live here.
 */
public final class InnerSwitches {

    /** How long a button inside a block stays pressed: vanilla's stone button, one second. */
    public static final int BUTTON_PRESS_TICKS = 20;

    /** What working a switch did. */
    public enum Result {
        /** The block holds no switch, so nothing happened and vanilla keeps the click. */
        NONE,
        /** Something is now on: a lever flicked up, or a button pressed. */
        ENGAGED,
        /** Something is now off. */
        RELEASED
    }

    private InnerSwitches() {
    }

    /**
     * Flips the lever, or presses the button, stored inside the block at {@code pos}.
     *
     * <p>Pressing a button that is already held down is a no-op rather than an early release, which
     * matches vanilla.
     */
    public static Result toggle(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            return Result.NONE;
        }
        Slot slot = node.slot();
        if (!slot.type.isManual()) {
            return Result.NONE;
        }
        if (slot.type == ComponentType.BUTTON && slot.powered) {
            return Result.ENGAGED;
        }

        slot.powered = !slot.powered;
        slot.power = slot.powered ? PowerSolver.MAX_POWER : 0;
        if (slot.powered && slot.type == ComponentType.BUTTON) {
            InnerRedstoneNetwork.scheduleRelease(level, pos, BUTTON_PRESS_TICKS);
        }

        store.markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, slot);

        boolean lever = slot.type == ComponentType.LEVER;
        SoundEvent sound = lever
                ? SoundEvents.LEVER_CLICK
                : (slot.powered ? SoundEvents.STONE_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_OFF);
        level.playSound(null, pos, sound, SoundSource.BLOCKS, lever ? 0.3F : 0.5F,
                slot.powered ? 0.6F : 0.5F);

        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] {} {} at {}", slot.powered ? "engaged" : "released",
                    slot.type, pos.toShortString());
        }
        return slot.powered ? Result.ENGAGED : Result.RELEASED;
    }
}
