package com.jiangyuefengyu.redstonecircuit.logic;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * The rules for everything that can live inside a host block.
 *
 * <h2>One value per component</h2>
 * Every component's output strength lives in one place, and this class decides what that value should
 * be and which directions it leaves by. Dust is <em>solved</em> (its value is a function of the whole
 * network); a torch, repeater and comparator are <em>driven</em> (their value follows their input,
 * after a delay); a lever, button and plate are <em>manual</em> (the player or an entity sets them).
 *
 * <h2>Where the directions come from</h2>
 * Vanilla's diodes read from {@code FACING} and put their signal out on the opposite side; a torch
 * does not signal towards the block it hangs on. Inner components keep those rules, which is what
 * makes {@link net.minecraft.world.level.block.DiodeBlock} wiring knowledge transfer directly:
 *
 * <pre>
 *   dust                      reads every side, emits on every side
 *   torch                     reads its attachment side, emits on the other five
 *   repeater / comparator     reads {@code facing}, emits on {@code facing.getOpposite()}
 *   lever / button / plate    reads nothing, emits on every side
 * </pre>
 *
 * <h2>Attenuation</h2>
 * A wire hop costs one, a source block does not - exactly as in vanilla, where a torch lights wire to
 * 15 but wire only lights the next wire to 14. So a neighbouring {@link ComponentType#DUST} is worth
 * one less than its value, and everything else is worth its value.
 *
 * <h2>The wrench</h2>
 * The wrench overrides the rules above. A side it <b>locked</b> is open, a side it <b>cut</b> is dead,
 * and - the part that matters - once any side is locked the component's routing is <em>explicit</em>:
 * every side that was not locked is dead too. So a lock says "this is the line", not merely "these two
 * are joined", and redstone laid against any other face cannot sneak in. See {@link #Locks}.
 */
public final class PowerSolver {

    /** Maximum vanilla redstone signal strength. */
    public static final int MAX_POWER = 15;

    private PowerSolver() {
    }

    // ------------------------------------------------------- connection rules --

    /**
     * One component's connection overrides, as the rules need them.
     *
     * <p>Three booleans per side rather than one state, because "the wrench cut this" and "this was left
     * out of a lock" have to be told apart: an explicit cut stops a component reading that side, while
     * being left out of a lock only stops it talking to neighbours - a diode must still read its own
     * input, or locking anything on it would switch the component off.
     *
     * @param open the wrench locked this side: the signal goes out here whatever the neighbours say
     * @param explicitlyCut the wrench cut this side by hand: nothing goes in or out
     * @param routingLocked the component has at least one locked side, so its routing is explicit
     */
    public record Locks(boolean open, boolean explicitlyCut, boolean routingLocked) {

        /** Reads the overrides of a stored component. */
        public static Locks of(Slot slot, Direction direction) {
            return new Locks(slot.isOpen(direction), slot.isExplicitlyCut(direction),
                    slot.isRoutingLocked());
        }

        /** Reads the overrides of a component view. */
        public static Locks of(PowerEnvironment.Node node, Direction direction) {
            return new Locks(node.open(direction), node.explicitlyCut(direction),
                    node.routingLocked());
        }

        /** No overrides at all: the ordinary case, and the one the rules assume by default. */
        public static final Locks NONE = new Locks(false, false, false);

        /** True when this side carries nothing at all, whether or not the neighbours could. */
        public boolean closed() {
            return explicitlyCut || (routingLocked && !open);
        }
    }

    /**
     * Whether the component's output also counts as <b>strong</b> power towards the side a query came
     * from - vanilla's {@code getDirectSignal}.
     *
     * <p>Strong and weak power are not the same thing in vanilla, and the difference is what stops a
     * block from becoming a redstone block: <b>weak</b> power is only ever seen by the block it is
     * asked about, while <b>strong</b> power is carried onwards by a solid block to everything around
     * it. So a lever at the far side of a stone block does not light a lamp on the near side, whereas
     * a repeater pushed into that stone does - because only the repeater gives strong power.
     *
     * <p>Vanilla's rules, and the reason this matters: a lever, button or plate gives none, dust gives
     * none in practice, and only a repeater or comparator charges the block in front of it. So a lever
     * on the far side of a stone block does not light a lamp on the near side, while a repeater pushed
     * into that stone does.
     *
     * @param direction where the signal would travel: from this component towards the neighbour asking,
     *     the same frame {@link #emitsToward} uses. Vanilla's {@code getDirectSignal} is handed the
     *     opposite of this - see the note on directions in {@code BlockStateSignalMixin} - and converts
     *     before calling in, so every rule in this class speaks one frame.
     */
    public static boolean directSignalToward(ComponentType type, Direction facing, Direction direction,
                                             Locks locks) {
        if (locks.closed()) {
            return false;
        }
        if (type == ComponentType.REPEATER || type == ComponentType.COMPARATOR) {
            // A diode strongly powers the block in front of it, which is what lets it drive a wire on
            // the far side of a solid block - exactly as it does in vanilla. FACING points at the
            // input, so the block in front is the opposite side.
            return direction == facing.getOpposite();
        }
        // Everything else is a weak source, just like in vanilla: a lever never charges a block, and
        // dust only powers what it actually touches. That is what keeps a host block from behaving
        // like a redstone block and charging every solid block around it.
        return false;
    }

    /** Convenience for the mixin and for tests: the same rule with no overrides at all. */
    public static boolean directSignalToward(ComponentType type, Direction facing, Direction direction,
                                             boolean explicitlyCut) {
        return directSignalToward(type, facing, direction, new Locks(false, explicitlyCut, false));
    }

    /**
     * Whether this component's output leaves it towards {@code direction}.
     *
     * <p>Note what a lock does <em>not</em> do here: it stops the signal leaving along unpinned sides,
     * but it never changes where the component would have emitted anyway. Forcing a side open is the
     * other half, and is what makes "route a repeater sideways" possible.
     */
    public static boolean emitsToward(ComponentType type, Direction facing, Direction direction,
                                      Locks locks) {
        if (locks.closed()) {
            return false;
        }
        if (locks.open()) {
            return true;
        }
        switch (type) {
            case TORCH:
                // A torch does not signal into the block it is attached to - that is what lets it sit
                // on its own input without latching itself on.
                return direction != facing;
            case REPEATER:
            case COMPARATOR:
                // Diodes are one-way by definition: everything goes out the far side.
                return direction == facing.getOpposite();
            default:
                return true;
        }
    }

    /** True when this component's output leaves it towards {@code direction}. */
    public static boolean emitsToward(ComponentType type, Direction facing, Direction direction,
                                      boolean open, boolean explicitlyCut, boolean routingLocked) {
        return emitsToward(type, facing, direction, new Locks(open, explicitlyCut, routingLocked));
    }

    /** True when this component's output leaves it towards {@code direction}. */
    public static boolean emitsToward(PowerEnvironment.Node node, Direction direction) {
        return emitsToward(node.type(), node.facing(), direction, Locks.of(node, direction));
    }

    /** True when this component's output leaves it towards {@code direction}, overrides included. */
    public static boolean emitsToward(Slot slot, Direction direction) {
        return emitsToward(slot.type, slot.facing, direction, Locks.of(slot, direction));
    }

    /**
     * Whether this component takes its input from {@code direction}.
     *
     * <p>A lock does not stop a driven component reading its own input side: locking a repeater's
     * output sideways must not switch the repeater off. An explicit cut <em>does</em> stop it, which is
     * what makes {@code /rc connect ... off} a way to disable one input of a comparator.
     */
    public static boolean readsToward(ComponentType type, Direction facing, Direction direction,
                                      Locks locks) {
        if (locks.explicitlyCut()) {
            return false;
        }
        if (locks.open()) {
            return true;
        }
        if (locks.routingLocked() && isInputSide(type, facing, direction)) {
            // The component's own feed is its own business, not one of the lines the wrench routes.
            return true;
        }
        if (locks.closed()) {
            return false;
        }
        switch (type) {
            case TORCH:
            case REPEATER:
            case COMPARATOR:
                return direction == facing;
            default:
                // Dust reads every side. The manual sources are never driven, so their answer here is
                // never consulted - but claiming they read everything keeps the default branch honest
                // for any future type that is neither driven nor manual.
                return true;
        }
    }

    /** True when this component takes its input from {@code direction}. */
    public static boolean readsToward(ComponentType type, Direction facing, Direction direction,
                                      boolean open, boolean explicitlyCut, boolean routingLocked) {
        return readsToward(type, facing, direction, new Locks(open, explicitlyCut, routingLocked));
    }

    /** True when this component takes its input from {@code direction}. */
    public static boolean readsFrom(PowerEnvironment.Node node, Direction direction) {
        return readsToward(node.type(), node.facing(), direction, Locks.of(node, direction));
    }

    /** True when this component takes its input from {@code direction}, overrides included. */
    public static boolean readsFrom(Slot slot, Direction direction) {
        return readsToward(slot.type, slot.facing, direction, Locks.of(slot, direction));
    }

    /**
     * True when a driven component would read this side with no overrides in play.
     *
     * <p>Only the back input counts: a comparator's two sides are read as well, but they are not
     * {@code facing}, and a lock on one of them is a lock the player asked for.
     */
    private static boolean isInputSide(ComponentType type, Direction facing, Direction direction) {
        return type.isDriven() && direction == facing;
    }

    // ------------------------------------------------------------ propagation --

    /**
     * The value inner wire at {@code pos} should hold.
     *
     * <p>{@code value = max(supply, max over sides of what arrives from that side)}, where the supply
     * is the component's own draw from outside the wire network (or an injected debug value) and each
     * hop is charged according to {@link #afterHop}. Stated this way the answer depends only on the
     * supplies, which is why the caller can iterate to a fixpoint in any order.
     */
    public static int wireTarget(PowerEnvironment env, BlockPos pos, int supply,
                                 PowerEnvironment.Node self) {
        int best = clamp(supply);
        for (Direction direction : Direction.values()) {
            best = Math.max(best, innerFrom(env, pos, direction, self));
        }
        return Math.max(0, best);
    }

    /**
     * What a neighbour offers across one side, before any wire hop is charged.
     *
     * <p>Split out because a comparator has three inputs, not one: its back is gated by
     * {@link #readsFrom} but its two sides are read by design - and vanilla reads those side inputs at
     * full strength, without the decrement a wire charges.
     */
    public static int neighbourValue(PowerEnvironment env, BlockPos pos, Direction direction) {
        PowerEnvironment.Node other = nodeAt(env, pos.relative(direction));
        if (other == null || !emitsToward(other, direction.getOpposite())) {
            return 0;
        }
        return clamp(other.power());
    }

    /**
     * What the inner network delivers to {@code self} at {@code pos} from {@code direction}.
     *
     * @return 0 when the component does not read that side, otherwise what arrives after the hop
     */
    public static int innerFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                PowerEnvironment.Node self) {
        if (self != null && !readsFrom(self, direction)) {
            return 0;
        }
        PowerEnvironment.Node other = nodeAt(env, pos.relative(direction));
        if (other == null || !emitsToward(other, direction.getOpposite())) {
            return 0;
        }
        return afterHop(self, other, clamp(other.power()));
    }

    /**
     * What survives the hop between two components.
     *
     * <p>Vanilla charges this to the wire that <em>receives</em>: a wire's strength is
     * {@code max(sources around it, best neighbouring wire - 1)}, so a torch lights a wire to fifteen
     * while wire only lights the next wire to fourteen. Modelling it as the receiver's cost is what
     * makes a superconducting wire possible at all - its hop costs nothing, so a run of it carries the
     * strength it was given all the way.
     *
     * <p>A hop into or out of anything that is not a wire costs nothing, which is why a repeater reads
     * a wire at full strength, exactly as it does in vanilla.
     */
    private static int afterHop(@Nullable PowerEnvironment.Node receiver,
                                PowerEnvironment.Node sender, int value) {
        if (receiver == null || !receiver.type().isWire() || !sender.type().isWire() || value == 0) {
            return value;
        }
        return Math.max(0, value - receiver.type().hopCost());
    }

    /** The component at a position, or {@code null}; the environment lookup in one place. */
    @Nullable
    private static PowerEnvironment.Node nodeAt(PowerEnvironment env, BlockPos pos) {
        return env.nodeAt(pos.getX(), pos.getY(), pos.getZ());
    }

    /**
     * What reaches a driven component at {@code pos} from one side: its inner neighbours plus the
     * vanilla world on that same side.
     *
     * <p>The vanilla half is what lets a lever or a piece of wire touching the host block on the
     * component's input side feed it, while the same lever on any other side does not.
     */
    public static int inputFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                PowerEnvironment.Node self) {
        return Math.max(innerFrom(env, pos, direction, self), clamp(env.externalSignal(direction)));
    }

    /**
     * A comparator's side input.
     *
     * <p>Unlike the back input this is not gated by {@link #readsFrom}: a comparator reads its two
     * sides by design, which is the whole difference between compare and subtract. The wrench can still
     * cut a side outright with {@code forcedOff} - and a lock does not, because the sides are part of
     * how the comparator works rather than lines it is routing.
     */
    public static int sideInputFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                    PowerEnvironment.Node self) {
        if (self != null && self.explicitlyCut(direction)) {
            return 0;
        }
        return Math.max(neighbourValue(env, pos, direction), clamp(env.externalSignal(direction)));
    }

    /**
     * The output a driven component should hold right now, given what reaches it.
     *
     * <p>Called twice per change: once when the network settles, to notice that the output is stale
     * and schedule a re-evaluation, and once when that scheduled tick arrives, to decide what the
     * output actually becomes. Vanilla does the same thing - a repeater schedules a tick and then asks
     * {@code shouldTurnOn} at that later moment, so a pulse shorter than the delay never propagates.
     */
    public static int desiredOutput(PowerEnvironment env, BlockPos pos, PowerEnvironment.Node self) {
        Direction input = self.facing();
        int signal = inputFrom(env, pos, input, self);
        switch (self.type()) {
            case TORCH:
                // Lit by default; anything reaching its attachment side puts it out.
                return signal == 0 ? MAX_POWER : 0;
            case REPEATER:
                // Any input at all comes out at full strength, which is the whole point of a repeater.
                return signal > 0 ? MAX_POWER : 0;
            case COMPARATOR:
                int side = Math.max(
                        sideInputFrom(env, pos, input.getClockWise(), self),
                        sideInputFrom(env, pos, input.getCounterClockWise(), self));
                if (self.mode() == ComparatorMode.SUBTRACT) {
                    return Math.max(0, signal - side);
                }
                // Compare: pass the back signal through, but only while it is at least the sides.
                return signal >= side ? signal : 0;
            default:
                return clamp(self.power());
        }
    }

    /** Vanilla keeps signals in 0-15; clamp defensively so corrupt data cannot break propagation. */
    public static int clamp(int power) {
        if (power < 0) {
            return 0;
        }
        return Math.min(power, MAX_POWER);
    }
}
