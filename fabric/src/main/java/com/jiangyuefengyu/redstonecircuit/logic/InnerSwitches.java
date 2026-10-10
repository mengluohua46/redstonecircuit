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
 * <p>A lever and a button are their own state, so a bare hand may work them without the goggles; a repeater's
 * delay and a comparator's mode only mean something once they can be read. A click, {@code /rc toggle} and the
 * game tests all make the same state change here, and differ only in what the player hears and reads.
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
     * What a bare hand can do to a component - the one place that decides it.
     *
     * <p>The rule is about the <em>gesture</em>, not about the component: a lever and a button <em>are</em> their
     * state, so a player who cannot see inside still knows what flipping one does, while a repeater's delay or a
     * comparator's mode is a value that only means something once it can be read. {@link #toggle} switches on this
     * same classification, so the whitelist and the actions cannot drift apart.
     */
    public enum Gesture {
        /** Nothing a bare hand can do: a wire, a torch, a plate. */
        NONE,
        /** A person flips it: a lever, a button. Worked without the goggles. */
        SWITCH,
        /** A person tunes it: a repeater's delay, a comparator's mode, a controller's four settings. Needs the goggles. */
        SETTING
    }

    /** Which of the three gestures this component has. */
    public static Gesture gestureOf(ComponentType type) {
        return switch (type) {
            case LEVER, BUTTON -> Gesture.SWITCH;
            case REPEATER, RETAINING_REPEATER, DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER,
                 COMPARATOR, CONTROLLER, TRANSFORMER, CLOCK, EXTENDER -> Gesture.SETTING;
            default -> Gesture.NONE;
        };
    }

    /**
     * Works whatever is inside the block at {@code pos}, by the component's gesture. Pressing a button that is
     * already held down is a no-op rather than an early release, which matches vanilla.
     */
    public static Result toggle(ServerLevel level, BlockPos pos) {
        InnerRedstoneStore store = InnerRedstoneStore.get(level);
        InnerRedstoneNode node = store.get(pos);
        if (node == null || node.isEmpty()) {
            return Result.NONE;
        }
        Slot slot = node.slot();
        return switch (gestureOf(slot.type)) {
            case SWITCH -> flip(level, pos, slot);
            case SETTING -> switch (slot.type) {
                case COMPARATOR -> toggleMode(level, pos, slot);
                // 控制器's SETTING gesture is its screen, not a step - a bare hand opens it
                // (InnerRedstoneInteraction#tryToggle). The same holds for 红石变压器, 时钟源 and 信号延长器.
                case CONTROLLER -> Result.NONE;
                case TRANSFORMER -> Result.NONE;
                case CLOCK -> Result.NONE;
                case EXTENDER -> Result.NONE;
                default -> cycleDelay(level, pos, slot);
            };
            case NONE -> Result.NONE;
        };
    }

    /** True when a bare hand can do something with this component. */
    public static boolean isWorkable(ComponentType type) {
        return gestureOf(type) != Gesture.NONE;
    }

    /**
     * Whether working this component is "a person flips it" rather than "a person tunes it".
     *
     * <p>The switch kinds are worked without the goggles and the setting kinds are not;
     * {@code logic.InnerAccess} is where that becomes a decision.
     */
    public static boolean isHandSwitch(ComponentType type) {
        return gestureOf(type) == Gesture.SWITCH;
    }

    /**
     * The new state, as one line of text: an inner component has no visible state of its own, so without the
     * goggles the only feedback a player gets is what they are told and what they hear.
     */
    public static MutableComponent describe(Slot slot) {
        return switch (slot.type) {
            // All four repeaters step through the same four settings; the reported ticks are the type's own
            // (a double repeater reads 4/8/12/16, a plain one 2/4/6/8).
            case REPEATER, RETAINING_REPEATER, DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER ->
                    Component.translatable("message.redstonecircuit.switch.repeater",
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
     * Steps a repeater's delay, exactly as right-clicking one on the ground does. The output is left alone: the
     * new delay applies to the next re-evaluation, as in vanilla - changing the setting does not cut an
     * in-flight pulse short.
     */
    private static Result cycleDelay(ServerLevel level, BlockPos pos, Slot slot) {
        slot.delay = slot.delay >= 4 ? 1 : slot.delay + 1;
        // Vanilla's repeater is silent here, but an inner component shows nothing on its block's outside,
        // so without a click the player cannot tell the click landed.
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
     * Everything a change of one component's own state needs: no block state changed, so the world has to be told
     * explicitly, the network re-derived, and clients told, because the drawing shows the delay pips and the mode
     * mark. The sound plays from here so it cannot be forgotten at one of the three callers.
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
