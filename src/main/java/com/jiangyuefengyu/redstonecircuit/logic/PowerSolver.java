package com.jiangyuefengyu.redstonecircuit.logic;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

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
 * {@code forcedOff(direction)} cuts a direction outright and {@code forcedOn(direction)} opens it,
 * both taking precedence over the rules above. See {@code Slot#setConnection}.
 */
public final class PowerSolver {

    /** Maximum vanilla redstone signal strength. */
    public static final int MAX_POWER = 15;

    private PowerSolver() {
    }

    // ------------------------------------------------------- connection rules --

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
                                             boolean forcedOff) {
        if (forcedOff) {
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

    /**
     * Primitive overload of {@link #emitsToward(PowerEnvironment.Node, Direction)}.
     *
     * <p>Exists because the host block's own signal query runs on Minecraft's hottest path
     * ({@code BlockState#getSignal} is asked by every redstone update in the world), and it must not
     * allocate a node view for a one-line rule.
     */
    public static boolean emitsToward(ComponentType type, Direction facing, Direction direction,
                                      boolean forcedOn, boolean forcedOff) {
        if (forcedOff) {
            return false;
        }
        if (forcedOn) {
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
    public static boolean emitsToward(PowerEnvironment.Node node, Direction direction) {
        return emitsToward(node.type(), node.facing(), direction,
                node.forcedOn(direction), node.forcedOff(direction));
    }

    /**
     * Primitive overload of {@link #readsFrom(PowerEnvironment.Node, Direction)}.
     *
     * <p>Exists for the same reason as the emit one: the client draws a component's input side, and
     * asking the rules directly is the only way for the drawing and the solver to agree by
     * construction rather than by being kept in step by hand.
     */
    public static boolean readsToward(ComponentType type, Direction facing, Direction direction,
                                      boolean forcedOn, boolean forcedOff) {
        if (forcedOff) {
            return false;
        }
        if (forcedOn) {
            return true;
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
    public static boolean readsFrom(PowerEnvironment.Node node, Direction direction) {
        return readsToward(node.type(), node.facing(), direction,
                node.forcedOn(direction), node.forcedOff(direction));
    }

    // ------------------------------------------------------------ propagation --

    /**
     * The value inner dust at {@code pos} should hold.
     *
     * <p>{@code value = max(supply, max over sides of what arrives from that side)}, where the supply
     * is the component's own draw from outside the wire network (or an injected debug value) and a
     * neighbouring wire is worth one less than its own value. Stated this way the answer depends only
     * on the supplies, which is why the caller can iterate to a fixpoint in any order.
     */
    public static int dustTarget(PowerEnvironment env, BlockPos pos, int supply,
                                 PowerEnvironment.Node self) {
        int best = clamp(supply);
        for (Direction direction : Direction.values()) {
            best = Math.max(best, innerFrom(env, pos, direction, self));
        }
        return Math.max(0, best);
    }

    /**
     * What the inner network delivers across one side, ignoring what the receiver is willing to read.
     *
     * <p>Split out because a comparator has three inputs, not one: its back is gated by
     * {@link #readsFrom} but its two sides are read by design.
     */
    public static int neighbourValue(PowerEnvironment env, BlockPos pos, Direction direction) {
        BlockPos neighbour = pos.relative(direction);
        PowerEnvironment.Node other =
                env.nodeAt(neighbour.getX(), neighbour.getY(), neighbour.getZ());
        if (other == null || !emitsToward(other, direction.getOpposite())) {
            return 0;
        }
        int value = clamp(other.power());
        if (other.type() == ComponentType.DUST) {
            value--;
        }
        return Math.max(0, value);
    }

    /**
     * What the inner network delivers to {@code self} at {@code pos} from {@code direction}.
     *
     * @return 0 when the component does not read that side, the neighbour's value when it does, one
     *     less than that when the neighbour is dust
     */
    public static int innerFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                PowerEnvironment.Node self) {
        if (self != null && !readsFrom(self, direction)) {
            return 0;
        }
        return neighbourValue(env, pos, direction);
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
     * sides by design, which is the whole difference between compare and subtract. The wrench can
     * still cut a side with {@code forcedOff}.
     */
    public static int sideInputFrom(PowerEnvironment env, BlockPos pos, Direction direction,
                                    PowerEnvironment.Node self) {
        if (self != null && self.forcedOff(direction)) {
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
