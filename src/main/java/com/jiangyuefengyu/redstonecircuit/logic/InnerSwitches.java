package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.network.HostSync;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Working a component inside a block by hand: switches, and the two settings a diode has.
 *
 * <p>Separate from the interaction handler because none of this is player-specific - the player's bare
 * hand, the {@code /rc toggle} command and the game tests all have to go through exactly the same state
 * change, and the only difference between them is what the player hears and reads.
 *
 * <pre>
 *   lever       flips, and stays where it is put
 *   button      the same, with a release scheduled one second later (vanilla's stone button)
 *   repeater    steps its delay 1 -> 2 -> 3 -> 4 -> 1, as a right-click does in vanilla
 *   comparator  swaps compare and subtract mode, as a right-click does in vanilla
 * </pre>
 *
 * <p>The two diodes are here rather than in {@code InnerRedstoneInteraction} for the usual reason:
 * "the delay is now three" has to mean one thing, and it is the same thing whether it came from a click,
 * a command or a test.
 */
public final class InnerSwitches {

    /** How long a button inside a block stays pressed: vanilla's stone button, one second. */
    public static final int BUTTON_PRESS_TICKS = 20;

    /** What working a component did. */
    public enum Result {
        /** The block holds nothing that can be worked, so vanilla keeps the click. */
        NONE,
        /** Something is now on: a lever flicked up, or a button pressed. */
        ENGAGED,
        /** Something is now off. */
        RELEASED,
        /** A setting changed: a repeater's delay, or a comparator's mode. */
        ADJUSTED
    }

    private InnerSwitches() {
    }

    /**
     * Works whatever is inside the block at {@code pos}.
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
        return switch (slot.type) {
            case LEVER, BUTTON -> flip(level, pos, slot);
            case REPEATER -> cycleDelay(level, pos, slot);
            case COMPARATOR -> toggleMode(level, pos, slot);
            default -> Result.NONE;
        };
    }

    /** True when a bare hand can do something with this component. */
    public static boolean isWorkable(ComponentType type) {
        return type == ComponentType.LEVER || type == ComponentType.BUTTON
                || type == ComponentType.REPEATER || type == ComponentType.COMPARATOR;
    }

    /**
     * The new state, as one line of text.
     *
     * <p>Exists because an inner component has no visible state of its own: the host block looks
     * identical before and after a click, so the only feedback a player gets without the goggles is
     * what they are told and what they hear.
     */
    public static MutableComponent describe(Slot slot) {
        return switch (slot.type) {
            case REPEATER -> Component.translatable("message.redstonecircuit.switch.repeater",
                    Component.literal(Integer.toString(slot.delay)),
                    Component.literal(Integer.toString(slot.type.delayTicks(slot.delay))));
            case COMPARATOR -> Component.translatable(slot.mode == ComparatorMode.SUBTRACT
                    ? "message.redstonecircuit.switch.comparator.subtract"
                    : "message.redstonecircuit.switch.comparator.compare");
            case LEVER, BUTTON -> Component.translatable(slot.powered
                    ? "message.redstonecircuit.switch.on"
                    : "message.redstonecircuit.switch.off");
            default -> Component.literal(slot.type.name());
        };
    }

    // -------------------------------------------------------------- switches --

    private static Result flip(ServerLevel level, BlockPos pos, Slot slot) {
        if (slot.type == ComponentType.BUTTON && slot.powered) {
            return Result.ENGAGED;
        }

        slot.powered = !slot.powered;
        slot.power = slot.powered ? PowerSolver.MAX_POWER : 0;
        if (slot.powered && slot.type == ComponentType.BUTTON) {
            InnerRedstoneNetwork.scheduleRelease(level, pos, BUTTON_PRESS_TICKS);
        }

        boolean lever = slot.type == ComponentType.LEVER;
        SoundEvent sound = lever
                ? SoundEvents.LEVER_CLICK
                : (slot.powered ? SoundEvents.STONE_BUTTON_CLICK_ON : SoundEvents.STONE_BUTTON_CLICK_OFF);
        changed(level, pos, slot, sound, lever ? 0.3F : 0.5F, slot.powered ? 0.6F : 0.5F);

        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] {} {} at {}", slot.powered ? "engaged" : "released",
                    slot.type, pos.toShortString());
        }
        return slot.powered ? Result.ENGAGED : Result.RELEASED;
    }

    /**
     * Steps a repeater's delay, exactly as right-clicking one on the ground does.
     *
     * <p>The output is deliberately left alone: the new delay applies to the next re-evaluation, which
     * is what vanilla does too - changing the setting does not cut an in-flight pulse short.
     */
    private static Result cycleDelay(ServerLevel level, BlockPos pos, Slot slot) {
        slot.delay = slot.delay >= 4 ? 1 : slot.delay + 1;
        // Vanilla's repeater is silent here, but an inner component shows nothing on the outside of its
        // block, so without a click the player has no way to tell the click even landed.
        changed(level, pos, slot, SoundEvents.COMPARATOR_CLICK, 0.3F, 0.5F);

        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] REPEATER at {} delay -> {} ({} ticks)",
                    pos.toShortString(), slot.delay, slot.type.delayTicks(slot.delay));
        }
        return Result.ADJUSTED;
    }

    /** Swaps a comparator between compare and subtract, as right-clicking one on the ground does. */
    private static Result toggleMode(ServerLevel level, BlockPos pos, Slot slot) {
        boolean subtract = slot.mode == ComparatorMode.SUBTRACT;
        slot.mode = subtract ? ComparatorMode.COMPARE : ComparatorMode.SUBTRACT;
        changed(level, pos, slot, SoundEvents.COMPARATOR_CLICK, 0.3F, subtract ? 0.5F : 0.55F);

        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] COMPARATOR at {} mode -> {}",
                    pos.toShortString(), slot.mode);
        }
        return Result.ADJUSTED;
    }

    // ---------------------------------------------------------- bookkeeping --

    /**
     * Everything a change of one component's own state needs.
     *
     * <p>No block state changed, so the world has to be told explicitly, the network has to be
     * re-derived, and clients have to be told because the drawing shows the delay pips and the mode
     * mark. The sound is played from here so it cannot be forgotten at one of the three callers.
     */
    private static void changed(ServerLevel level, BlockPos pos, Slot slot, SoundEvent sound,
                                float volume, float pitch) {
        InnerRedstoneStore.get(level).markDirty();
        InnerRedstoneNetwork.markDirtyWithNeighbours(level, pos);
        InnerRedstoneNetwork.notifyOutputChanged(level, pos);
        HostSync.broadcast(level, pos, slot);
        level.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
    }
}
