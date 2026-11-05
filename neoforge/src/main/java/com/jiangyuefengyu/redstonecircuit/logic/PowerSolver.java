package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.ticks.TickPriority;

/**
 * The rules for everything that can live inside a host block: each component's output strength, and the
 * directions it leaves by.
 *
 * <p>Dust is <em>solved</em> (a function of the whole network), a torch, repeater or comparator is
 * <em>driven</em> (it follows its input after a delay), and a lever, button or plate is <em>manual</em>.
 * Directions follow vanilla's diodes: a repeater or comparator reads {@code facing} and emits on
 * {@code facing.getOpposite()}, a torch does not signal into the block it hangs on, and dust reads and
 * emits on every side. A hop between two wires costs one, every other hop costs nothing.
 *
 * <p>The wrench overrides all of it: a locked side is open, a cut side is dead, and once any side is
 * locked the routing is explicit - every side not locked is dead too, so a lock says "this is the line"
 * and redstone laid against another face cannot sneak in. See {@link #Locks}.
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
     * <p>Three booleans per side rather than one state, because "the wrench cut this" and "this was left out
     * of a lock" have to be told apart: an explicit cut stops a component reading that side, while being left
     * out of a lock only stops it talking to neighbours - a diode must still read its own input, or locking
     * anything on it would switch the component off.
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
     * Whether the component's output also counts as <b>strong</b> power towards the side a query came from -
     * vanilla's {@code getDirectSignal}. Weak power is only ever seen by the block it is asked about, while
     * strong power is carried onwards by a solid block, which is what stops a host block from behaving like a
     * redstone block. A lever, button or plate gives none, dust gives none in practice, and only a repeater or
     * comparator charges the block in front of it.
     *
     * <p>{@code direction} is where the signal would travel: from this component towards the neighbour asking,
     * the same frame {@link #emitsToward} uses. Vanilla's {@code getDirectSignal} is handed the opposite of
     * this and converts before calling in, so every rule in this class speaks one frame.
     */
    public static boolean directSignalToward(ComponentType type, Direction facing, Direction direction,
                                             Locks locks) {
        if (locks.closed()) {
            return false;
        }
        if (type.isDiode()) {
            // A diode strongly powers the block in front - FACING points at the input, so that is the opposite
            // side - and the 维持 repeaters are diodes here too, differing only in the strength they hand on.
            return direction == facing.getOpposite();
        }
        // Everything else is a weak source, as in vanilla: a lever never charges a block and dust only powers
        // what it touches, which keeps a host block from charging every solid block around it.
        return false;
    }

    /** Convenience for the mixin and for tests: the same rule with no overrides at all. */
    public static boolean directSignalToward(ComponentType type, Direction facing, Direction direction,
                                             boolean explicitlyCut) {
        return directSignalToward(type, facing, direction, new Locks(false, explicitlyCut, false));
    }

    /**
     * What a stored component reports to a vanilla signal query, or {@code 0} when it does not answer: the
     * weak query is answered on the directions the component emits towards, the strong query only by the diode
     * in front of which it sits, and neither by a component the wrench has closed.
     *
     * <p>The direction is in the component's own frame: a host riding a turned structure has one, and its
     * caller has to turn the world query back through the pose before calling in - which is the reason this is
     * a public method with the rule spelled out rather than two lines inside the mixin.
     */
    public static int signalAnswer(Slot slot, Direction emitted, boolean strong,
                                   boolean neighbourHoldsComponent, boolean hostConductsStrongPower) {
        if (slot == null || emitted == null) {
            return 0;
        }
        if (strong) {
            if (!directSignalToward(slot.type, slot.facing, emitted, Locks.of(slot, emitted))) {
                return 0;
            }
            if (!hostConductsStrongPower && neighbourHoldsComponent) {
                return 0;
            }
        } else if (!emitsToward(slot, emitted)) {
            return 0;
        }
        return clamp(slot.power);
    }

    /**
     * Whether this component's output leaves it towards {@code direction}.
     *
     * <p>Note what a lock does <em>not</em> do here: it stops the signal leaving along unpinned sides without
     * changing where the component would have emitted anyway. Forcing a side open is the other half, and is
     * what makes "route a repeater sideways" possible.
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
            case SYNC_TORCH:
                // A torch does not signal into the block it is attached to - that is what lets it sit
                // on its own input without latching itself on. 同步火把 is that same shape with the polarity
                // reversed, so its five output directions are literally the torch's.
                return direction != facing;
            case REPEATER:
            case RETAINING_REPEATER:
            case DOUBLE_REPEATER:
            case DOUBLE_RETAINING_REPEATER:
            case COMPARATOR:
                // Diodes are one-way by definition: everything goes out the far side.
                return direction == facing.getOpposite();
            case CONTROLLER:
            case TRANSFORMER:
            case CLOCK:
            case EXTENDER:
            case TRIGGER:
                // One-way like a diode - it reads 正面 and answers on 背面 - but not a diode: none of these pushes
                // strong power into the block in front. All five must answer on one face only, since the plate (or,
                // for 红石触发器, the block standing in the world) and the component inside a block are one device.
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
        if (node.type() == ComponentType.SYNC_TORCH) {
            return SyncTorchRule.emitsOn(node.syncInputs(), node.facing(), direction);
        }
        return emitsToward(node.type(), node.facing(), direction, Locks.of(node, direction));
    }

    /** True when this component's output leaves it towards {@code direction}, overrides included. */
    public static boolean emitsToward(Slot slot, Direction direction) {
        if (slot.type == ComponentType.SYNC_TORCH) {
            // 逐面决定: 有输入的那一面不输出 (SyncTorchRule#emitsOn)。这一条不能走下面那个纯函数, 因为掩码是"此刻"的
            // 状态, 而这件器件不自锁的全部机制就在它上面。
            return SyncTorchRule.emitsOn(slot.syncInputs, slot.facing, direction);
        }
        return emitsToward(slot.type, slot.facing, direction, Locks.of(slot, direction));
    }

    /**
     * Whether this component takes its input from {@code direction}.
     *
     * <p>A lock does not stop a driven component reading its own input side, while an explicit cut does - which
     * is what makes the cut a way to disable one input of a comparator.
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
            case RETAINING_REPEATER:
            case DOUBLE_REPEATER:
            case DOUBLE_RETAINING_REPEATER:
            case COMPARATOR:
            case CONTROLLER:
            case EXTENDER:
            case SYNC_TORCH:
            case TRIGGER:
                // 同步火把 reads the one face it is stuck to, like the torch: what it does with that reading is the
                // group's business (SyncGroups), never this solver's. 红石触发器 reads its 正面 the same way - one face
                // and no other, which is what makes the input side a side of its own rather than a routed line.
                return direction == facing;
            default:
                // Dust reads every side, and the manual sources are never driven - claiming they read everything
                // keeps this branch honest for a future type that is neither driven nor manual.
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
        return type.hasInputSide() && direction == facing;
    }

    // ------------------------------------------------------------ signal lock --

    /**
     * The sides a diode can be held from: the two sides across its axis.
     *
     * <p>Vanilla uses {@code FACING.getClockWise()} and {@code getCounterClockWise()}, and both throw for a
     * vertical facing - while a component inside a host block can be mounted on the floor or the ceiling, which
     * vanilla's repeaters cannot. Those read all four horizontal faces instead, the same fallback
     * {@link #sideInput} makes for a comparator mounted the same way.
     */
    public static Iterable<Direction> lockSides(Direction facing) {
        if (facing.getAxis() == Direction.Axis.Y) {
            return Direction.Plane.HORIZONTAL;
        }
        return List.of(facing.getClockWise(), facing.getCounterClockWise());
    }

    /**
     * The pure half of the lock rule, over a stored neighbour: is the component on {@code side} a diode that
     * pushes signal towards the side it stands on?
     *
     * <p>Three conditions, all of them vanilla's: a diode ({@code DiodeBlock#isDiode}, and the gate is the whole
     * point - a piece of dust, a torch and a solid block charged from somewhere else all push signal and none of
     * them locks anything), emitting towards the caller ({@code FACING} is the diode's input, so its output
     * leaves on the opposite side) and outputting something.
     *
     * <p>Slot-shaped and public because the client asks the same question with nothing but a neighbour's
     * component in hand: a held repeater draws vanilla's lock bar, and a second half-copy of this rule in the
     * drawing code is exactly how the two answers would drift apart.
     */
    public static boolean holdsToward(@Nullable Slot neighbour, Direction side) {
        return neighbour != null && neighbour.type.isDiode()
                && emitsToward(neighbour, side.getOpposite())
                && clamp(neighbour.power) > 0;
    }

    /** The same rule over a solver node, for the two solvers. */
    public static boolean holdsToward(@Nullable PowerEnvironment.Node neighbour, Direction side) {
        return neighbour != null && neighbour.type().isDiode()
                && emitsToward(neighbour, side.getOpposite())
                && clamp(neighbour.power()) > 0;
    }

    /**
     * The same three conditions over a vanilla diode standing in the world instead of a stored component.
     *
     * <p>Split out because the two halves of the answer arrive from different places on the client: a
     * neighbour inside a host block is a component the client has been sent, while a repeater set in the
     * ground is a block state only the client's own copy of the world knows - and a lock it applies is felt
     * inside the host block all the same.
     *
     * <p>{@code facing} is compared here as well as the signal, although the signal alone already says it
     * on a server: {@code DiodeBlock#getSignal} only answers from a diode whose {@code FACING} is the side
     * the signal comes from. Saying it here is what lets a caller that read the two halves separately - the
     * drawing, from a {@code BlockState} - reach the identical verdict.
     */
    public static boolean holdsToward(@Nullable PowerEnvironment.VanillaDiode diode, Direction side) {
        return diode != null && diode.facing() == side && clamp(diode.signalToward()) > 0;
    }

    /**
     * Whether the neighbour on {@code side} of {@code pos} holds this component.
     *
     * <p>An inner neighbour answers through {@link #holdsToward}. A block of the vanilla world only when it is a
     * diode, which {@link PowerEnvironment#vanillaDiodeOn} has already established - asking a solid block for its
     * strong power instead is the one mistake that would make the rule wider than vanilla's.
     */
    public static boolean lockedBySide(PowerEnvironment env, BlockPos pos, Direction side) {
        PowerEnvironment.Node other = nodeAt(env, pos.relative(side));
        if (other != null) {
            return holdsToward(other, side);
        }
        return holdsToward(env.vanillaDiodeOn(side), side);
    }

    /**
     * Whether this component's output is held still by a diode beside it, the way vanilla's locked repeater is.
     *
     * <p>Only the repeater family can be held: vanilla's {@code DiodeBlock#isLocked} answers false and only
     * {@code RepeaterBlock} overrides it, so a comparator is never held - while a comparator is a perfectly good
     * lock <em>source</em>, because {@code isDiode} includes it. A torch has no lock at all.
     *
     * <p>Derived from the neighbours and never stored: the answer cannot go stale when a component is carried,
     * saved or reloaded, which a stored flag would.
     */
    public static boolean isSignalLocked(PowerEnvironment env, BlockPos pos, PowerEnvironment.Node self) {
        if (!self.type().isRepeater()) {
            return false;
        }
        for (Direction side : lockSides(self.facing())) {
            if (lockedBySide(env, pos, side)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ tick priority --

    /**
     * Vanilla's {@code DiodeBlock#shouldPrioritize}: there is a diode on my output side, and it faces anywhere
     * but my output direction.
     *
     * <p>Read straight off vanilla, inequality included: the ordinary chain is the case this covers, and the
     * mutual pair - each diode on the other's output side - is the one vanilla leaves at the plain priority. It
     * only ever orders work inside a single tick, so one diode on its own behaves identically either way.
     */
    public static boolean prioritizes(PowerEnvironment env, BlockPos pos, Direction facing) {
        Direction output = facing.getOpposite();
        PowerEnvironment.Node other = nodeAt(env, pos.relative(output));
        if (other != null) {
            return other.type().isDiode() && other.facing() != output;
        }
        PowerEnvironment.VanillaDiode diode = env.vanillaDiodeOn(output);
        return diode != null && diode.facing() != output;
    }

    /**
     * The priority a stale component's queue entry gets, exactly as vanilla's {@code checkTickOnNeighbor} picks
     * it: {@code HIGH} normally, {@code EXTREMELY_HIGH} when {@link #prioritizes} says so, {@code VERY_HIGH} when
     * the component is already on and about to turn off.
     */
    public static TickPriority tickPriority(PowerEnvironment env, BlockPos pos, PowerEnvironment.Node self) {
        if (prioritizes(env, pos, self.facing())) {
            return TickPriority.EXTREMELY_HIGH;
        }
        return self.power() > 0 ? TickPriority.VERY_HIGH : TickPriority.HIGH;
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
     * What the inner network delivers to {@code self} at {@code pos} from {@code direction}: 0 when the
     * component does not read that side, otherwise what arrives after the hop.
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
     * What survives the hop between two components: vanilla charges it to the wire that <em>receives</em>, and
     * modelling it as the receiver's cost is what makes a superconducting wire possible - its hop costs
     * nothing, so a run of it carries the strength it was given all the way. A hop into or out of anything that
     * is not a wire costs nothing, which is why a repeater reads a wire at full strength.
     */
    private static int afterHop(@Nullable PowerEnvironment.Node receiver,
                                PowerEnvironment.Node sender, int value) {
        if (receiver == null || sender == null) {
            return value;
        }
        return afterHop(receiver.type(), sender.type(), value);
    }

    /**
     * The same rule stated on types, for a caller that is not a node of a solve: a hop is charged by the
     * component that <em>receives</em> it. Vanilla's wire scan only accepts a neighbour whose block state is
     * itself wire, so a component hidden inside a host block reads as a source there and its hop went
     * uncharged; naming the reader as an ordinary wire is what charges it. A {@code null} reader answers the
     * value unchanged, the honest degradation for a caller with no identity to give.
     */
    public static int afterHop(@Nullable ComponentType receiver, @Nullable ComponentType sender,
                               int value) {
        if (receiver == null || sender == null || !receiver.isWire() || !sender.isWire() || value == 0) {
            return value;
        }
        // The receiver's cost, which is the solve's own rule. The read at a block boundary - where the other
        // side of the hop is a vanilla block rather than one of our nodes - is charged elsewhere.
        return Math.max(0, value - receiver.hopCost());
    }

    /**
     * Whether a read across a physicalisation seam is <b>lossless</b>: two wires that both charge nothing for
     * the hop between them.
     *
     * <p>Inside one solve that is simply one wire, and the solve's least fixed point is reached from zero. A
     * seam is not inside one solve: the two halves are solved one after the other, each restarting its wires
     * from zero and each taking the other's <em>last answer</em> as a supply - and a supply is copied at the
     * receiver's own cost. Two wires that both charge nothing therefore read each other at gain one, so the
     * seam's two equations are {@code A = B} and {@code B = A}: every value is a fixed point of the pair, and
     * a strength nobody supplies any more stays where it is. A seam that pays anything cannot do that, because
     * {@link #afterHop} then makes the pair's equations contradictory, which is what a fading line already is.
     *
     * <p>{@code reader} is the component doing the reading, {@code sender} the one whose strength is read.
     */
    public static boolean losslessSeam(@Nullable ComponentType reader, @Nullable ComponentType sender) {
        return reader != null && sender != null
                && reader.isWire() && sender.isWire()
                && reader.hopCost() == 0 && sender.hopCost() == 0;
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
     *
     * <p><b>Why the world half is read uncharged here and charged everywhere else:</b> vanilla's comparator
     * answers its side faces through {@code RedStoneWireBlock#getSignal}, which reports the wire's own
     * strength - full strength, with none of the decrement the wire charges the block it feeds. That is the
     * same rule {@link #neighbourValue} states for the inner half above, and it has to hold on both halves or a
     * comparator's sides read one step low: a subtract would then come out one too high, and a feedback loop
     * vanilla brings down to zero would stop at one. The back input and every other component keep the charged
     * read, because a wire there really does charge the hop.
     */
    public static int sideInputFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                    PowerEnvironment.Node self) {
        if (self != null && self.explicitlyCut(direction)) {
            return 0;
        }
        return Math.max(neighbourValue(env, pos, direction), clamp(env.externalSignalUncharged(direction)));
    }

    /**
     * The output a driven component should hold right now, given what reaches it.
     *
     * <p>Called twice per change: once when the network settles, to notice that the output is stale and schedule
     * a re-evaluation, and once when that scheduled tick arrives, to decide what the output becomes. Vanilla does
     * the same thing - a repeater schedules a tick and then asks {@code shouldTurnOn} at that later moment - and
     * the pulse a shorter input leaves behind is decided by the caller of the second call, not here: vanilla's
     * {@code DiodeBlock#tick} turns a component that was <em>off</em> on regardless of what it reads by then, and
     * schedules one more delay to turn it off again. See {@code InnerRedstoneNetwork#applyDueFlips}.
     *
     * <p>A held component answers what it already holds: vanilla skips the whole tick of a locked repeater, so
     * its output cannot follow anything until the diode beside it lets go.
     */
    public static int desiredOutput(PowerEnvironment env, BlockPos pos, PowerEnvironment.Node self) {
        if (isSignalLocked(env, pos, self)) {
            return clamp(self.power());
        }
        Direction input = self.facing();
        int signal = inputFrom(env, pos, input, self);
        // The number is recorded here because a screen cannot re-derive it: a component inside a machine is
        // read through the structure's own view, so a screen that asked the level instead would show 0 while
        // the device worked. Recorded where it is read, so "what the screen shows" and "what the circuit used"
        // are the same number by construction.
        self.noteInput(signal);
        switch (self.type()) {
            case TORCH:
                // Lit by default; anything reaching its attachment side puts it out.
                return signal == 0 ? MAX_POWER : 0;
            case REPEATER:
            case DOUBLE_REPEATER:
                // Any input at all comes out at full strength; that is what a repeater is for.
                return signal > 0 ? MAX_POWER : 0;
            case RETAINING_REPEATER:
            case DOUBLE_RETAINING_REPEATER:
                // The same relay, except that it hands on what it was given: seven in, seven out. The delay is
                // unchanged, so a pulse shorter than it still disappears.
                return clamp(signal);
            case COMPARATOR:
                // The back input has two halves and vanilla reads both: a redstone signal, and the neighbouring
                // block's own analogue output - the only way a cake, a cauldron, a chest or a hopper is read at
                // all, since vanilla asks the analogue output first and adds the wire reading after it.
                int back = Math.max(signal, clamp(env.analogOutput(input)));
                int side = sideInput(env, pos, input, self);
                if (self.mode() == ComparatorMode.SUBTRACT) {
                    return Math.max(0, back - side);
                }
                // Compare: pass the back signal through, but only while it is at least the sides.
                return back >= side ? back : 0;
            case CONTROLLER:
                // The controller's rule over the strength standing at its 正面 - the face it was mounted on,
                // since the clicked face is what is stored. Combinational: no delay of its own, like the plate.
                return self.controller().answer(signal);
            case TRANSFORMER:
                // The transformer's rule over the strength standing at its 正面, with no input meaning 0:
                // combinational like the controller, and it reads its own face for the same reason.
                return TransformerRule.answer(signal, self.transformer());
            case TRIGGER:
                // 红石触发器 answers the <b>edge</b> of its input, not its level: the switch it already holds is the
                // answer, and the only thing allowed to step it is the pass that owns the level
                // (InnerRedstoneNetwork#tickTriggers). Re-deriving it from the reading here would toggle it on every
                // notification - a wire that stays powered would switch it over and over.
                return clamp(self.power());
            case EXTENDER:
                // 信号延长器 hands its 正面 straight through - the strength it was given, nothing shaped, nothing
                // delayed. With nothing arriving it keeps answering what it was already answering, because that is
                // the hold: the deadline belongs to the tick pass that owns the level (only it knows when the hold
                // runs out, and it writes the 0 itself), so re-deriving an answer here would cut the hold short.
                return signal > 0 ? clamp(signal) : clamp(self.power());
            default:
                return clamp(self.power());
        }
    }

    /**
     * The strongest of a comparator's side inputs.
     *
     * <p>A comparator on the ground reads the two sides either side of its back input. A comparator
     * <em>inside</em> a block can also be mounted on the top or bottom face, and then its input axis is
     * vertical - where there is no clock-wise side at all and asking for one throws. A vertical comparator
     * reads all four horizontal sides instead: the same idea, with the plane it is missing filled in.
     */
    public static int sideInput(PowerEnvironment env, BlockPos pos, Direction facing,
                                PowerEnvironment.Node self) {
        if (facing.getAxis() == Direction.Axis.Y) {
            int best = 0;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                best = Math.max(best, sideInputFrom(env, pos, direction, self));
            }
            return best;
        }
        return Math.max(
                sideInputFrom(env, pos, facing.getClockWise(), self),
                sideInputFrom(env, pos, facing.getCounterClockWise(), self));
    }

    /** Vanilla keeps signals in 0-15; clamp defensively so corrupt data cannot break propagation. */
    public static int clamp(int power) {
        if (power < 0) {
            return 0;
        }
        return Math.min(power, MAX_POWER);
    }
}
