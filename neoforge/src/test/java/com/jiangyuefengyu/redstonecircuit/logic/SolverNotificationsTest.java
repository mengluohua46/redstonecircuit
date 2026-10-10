package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The spin: a solve that is fed by its own neighbour notifications, and the tick-boundary flush that stops it.
 *
 * <p>The mechanism is re-entrancy, and it is vanilla's own shape: {@code RedStoneWireBlock#neighborChanged} calls
 * {@code updatePowerStrength} synchronously, so a notification sent from inside the solve runs the neighbour's
 * whole re-derivation on the current stack and comes straight back in - two halves of one circuit passing a value
 * back and forth inside one tick, which spends the whole round budget every tick.
 *
 * <p>The test drives production's shape - a dirty set, a recompute per queued position, a notification per changed
 * position - with {@link PowerSolver}'s own rule over a real {@link PowerEnvironment} and the real
 * {@link SolverNotifications} for the notification path, modelling only the neighbour on the other side: a vanilla
 * wire re-derived synchronously, exactly as {@code RedStoneWireBlock} does. With the switch off the loop is fed by
 * its own notifications; with it on the flush tells the world and the next tick finds nothing to do.
 */
class SolverNotificationsTest {

    /** Where the host block is, in the structure's own frame, one hop from the wire. */
    private static final BlockPos HOST = new BlockPos(0, 0, 0);
    private static final Direction TOWARDS_WIRE = Direction.NORTH;

    /**
     * The host block's inside, plus the one vanilla wire beside it that reads it and is read by it: a real
     * {@link PowerEnvironment} over real {@link Slot}s, so the arithmetic deciding the host's power is
     * {@link PowerSolver}'s and not this test's. The wire is one integer, which is all the environment needs:
     * {@code externalSignal(north)} is what the wire offers on that side, already attenuated by the one-hop rule
     * the world reader applies to a {@code minecraft:redstone_wire} neighbour.
     */
    private static final class HostAndWire implements PowerEnvironment {

        final Slot host = new Slot(ComponentType.DUST);
        int wire;

        /** Notifications the solve made, in the order they were sent. */
        final List<Integer> notified = new ArrayList<>();

        /** True while a notification is being delivered - the re-entrancy this test is about. */
        boolean insideNotification;

        /** The synchronous neighbour re-derivation, i.e. what vanilla's wire does on the same stack. */
        @Nullable
        Runnable reentrant;

        HostAndWire(int hostPower, int wirePower) {
            host.power = hostPower;
            wire = wirePower;
        }

        @Override
        @Nullable
        public Node nodeAt(int x, int y, int z) {
            return new BlockPos(x, y, z).equals(HOST) ? node() : null;
        }

        Node node() {
            return new Node() {
                @Override
                public ComponentType type() {
                    return host.type;
                }

                @Override
                public int power() {
                    return host.power;
                }

                @Override
                public void setPower(int power) {
                    host.power = PowerSolver.clamp(power);
                }

                @Override
                public Direction facing() {
                    return host.facing;
                }

                @Override
                public ComparatorMode mode() {
                    return host.mode;
                }

                @Override
                public boolean open(Direction direction) {
                    return false;
                }

                @Override
                public boolean explicitlyCut(Direction direction) {
                    return false;
                }

                @Override
                public boolean routingLocked() {
                    return false;
                }
            };
        }

        @Override
        public int externalSignal() {
            return externalSignal(TOWARDS_WIRE);
        }

        /**
         * What the world offers this host on one side, already through the one-hop rule. The decrement lives here
         * rather than in {@link #supply()} because the environment is the thing that knows it is reading a vanilla
         * wire, which is where production applies the rule too; applying it twice would make the model's arithmetic
         * the test's rather than {@link PowerSolver}'s.
         */
        @Override
        public int externalSignal(Direction direction) {
            return direction == TOWARDS_WIRE
                    ? InnerRedstoneNetwork.neighbourSignal(wire, true) : 0;
        }

        /** The {@code supply} the solve passes to {@link PowerSolver#wireTarget} for this host. */
        int supply() {
            return externalSignal();
        }

        @Override
        public boolean isRedstoneConductor(int x, int y, int z) {
            return false;
        }

        /**
         * One notification, delivered the way the world delivers it: synchronously. The wire re-derives from what
         * the host now emits - vanilla's own {@code max(neighbours) - 1} for a wire, with the host's power as the
         * source - and if that is a change it tells its neighbours, which is the host: that marks the host dirty
         * again, inside the notification. A -1 return means nothing changed and nothing is notified back.
         */
        int deliver(BlockPos pos, Set<BlockPos> dirty) {
            if (insideNotification) {
                // Vanilla cannot recurse here either: this flag keeps the model honest about whose stack
                // a delivery is on. Re-entering would be an infinite loop rather than a spin.
                return -1;
            }
            int target = PowerSolver.clamp(Math.max(0, host.power - 1));
            if (target == wire) {
                return -1;
            }
            wire = target;
            notified.add(target);
            reentrant = () -> dirty.add(pos);
            insideNotification = true;
            try {
                reentrant.run();
            } finally {
                insideNotification = false;
            }
            return target;
        }
    }

    /**
     * One tick of the solver: drain the queue, recompute each position, notify what changed. Production's shape -
     * the notification goes through the real {@link SolverNotifications}, so which of the two policies is in force
     * is decided by the production code and not by this loop.
     *
     * <p>With the switch on a real session is opened, so {@code notifyOutputChanged} collects and the caller's
     * flush tells the world after the loop returns: the wire's synchronous answer lands in the next tick's queue,
     * which is the "one tick of lag, no spin" the fix buys. With the switch off there is no session, so the
     * notification is delivered inline and its answer re-enters this very loop.
     */
    private static int tick(HostAndWire world, Set<BlockPos> dirty,
                            @Nullable SolverNotifications.Session session) {
        int rounds = 0;
        while (!dirty.isEmpty()) {
            rounds++;
            if (rounds > 64) {
                // The round budget stands in for MAX_ROUNDS_PER_TICK, so a runaway fails rather than hangs.
                break;
            }
            List<BlockPos> positions = new ArrayList<>(dirty);
            dirty.clear();
            for (BlockPos pos : positions) {
                int before = world.host.power;
                world.setQueryPosition(pos);
                int target = PowerSolver.wireTarget(world, pos, world.supply(), world.node());
                world.host.power = target;
                if (target == before) {
                    continue;
                }
                if (session != null) {
                    // The coalesced form: collected, and sent by the flush the caller performs.
                    session.notify(pos);
                } else {
                    // The old form: delivered here and now, on this stack - which is the re-entrancy.
                    world.deliver(pos, dirty);
                }
            }
        }
        return rounds;
    }

    /**
     * One tick plus its flush, which is the production tick: open a session, drain, end it. The flush's
     * notification arrives after the drain has finished, so it is the next tick that sees it - one tick of lag,
     * and never a re-entrant round.
     */
    private static int tickAndFlush(HostAndWire world, Set<BlockPos> dirty, long tick) {
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> { });
        assertNotNull(session, "the coalescing switch is on");
        int rounds = tick(world, dirty, session);
        session.note(rounds, 1);
        int flushed = SolverNotifications.end(TEST_DIMENSION, tick);
        for (int i = 0; i < flushed; i++) {
            world.deliver(HOST, dirty);
        }
        return rounds;
    }

    private static Set<BlockPos> queue() {
        Set<BlockPos> dirty = new LinkedHashSet<>();
        dirty.add(HOST);
        return dirty;
    }

    /**
     * The coalescer's state is per dimension and process-lifetime, so one test must not see another's open session
     * or statistics: the same reset the production code does on a level unload.
     */
    @BeforeEach
    void clearCoalescer() {
        SolverNotifications.clearAll();
        SolverLog.clear();
    }

    // ------------------------------------------------------------ the spin --

    /**
     * The red half of the pair: with the switch off the solve is re-fed by its own notifications while it runs.
     * The wire re-derives synchronously and tells the host back inside the notification, so the step the solve is
     * in the middle of changes the input the next one reads. The value must not change because of it: a real spin
     * is a defect of cost.
     */
    @Test
    @DisplayName("without coalescing, a tick is fed by its own notifications while it runs")
    void withoutCoalescingTheTickIsFedByItsOwnNotifications() {
        HostAndWire world = new HostAndWire(0, 13);
        Set<BlockPos> dirty = queue();

        // The switch off: no session, so the notification is delivered inline, on the solve's own stack.
        int rounds = tick(world, dirty, null);

        assertTrue(rounds > 1,
                "a solve whose notifications are delivered while it runs must be re-fed: rounds=" + rounds);
        assertEquals(0, world.host.power,
                "and it walks the pair all the way down to the no-source fixpoint in one tick");
        assertEquals(0, world.wire, "which is exactly the churn the author's log shows");
    }

    /**
     * The fix: with the switch on one tick is one round and nothing can move under the solve. The wire's answer is
     * delivered by the flush at the end of the tick, so it cannot re-enter the solve that caused it - the circuit
     * still settles, one step per tick, which is vanilla's own pace for a wire hop.
     */
    @Test
    @DisplayName("with coalescing, every tick is one round and the value still converges")
    void withCoalescingTheTickConvergesInOneRound() {
        HostAndWire world = new HostAndWire(0, 13);
        Set<BlockPos> dirty = queue();

        // Tick one: one round, the host takes the wire's offer (13 - 1 hop = 12), and the flush tells the wire
        // once - which drops it to the host's power minus one, the change the next tick will see.
        int first = tickAndFlush(world, dirty, 1L);
        assertEquals(1, first, "a tick of the solver is one round when nothing can move under it");
        assertEquals(12, world.host.power, "the host reads the wire's offer: a wire hop costs one");
        assertEquals(11, world.wire, "and the flush told the wire, which dropped one step below the host");

        // Tick two: the wire's answer arrives through the notification it caused, one round again - the "one tick
        // of lag" the fix buys, not a lost update.
        dirty.add(HOST);
        int second = tickAndFlush(world, dirty, 2L);
        assertEquals(1, second, "and so is the next tick");
        assertEquals(10, world.host.power, "the host follows the wire down through its own notification");
        assertEquals(9, world.wire);

        // At most fifteen ticks: the pair steps down one hop per tick and stops.
        int ticks = 2;
        while (ticks < 40) {
            dirty.add(HOST);
            int rounds = tickAndFlush(world, dirty, 3L + ticks);
            assertTrue(rounds <= 1, "no tick may cost more than one round: tick " + ticks + " took "
                    + rounds);
            ticks++;
            if (world.wire == 0) {
                break;
            }
        }
        assertTrue(ticks < 40, "the pair reaches its fixpoint rather than stepping for ever");
        assertEquals(0, world.wire, "and the fixpoint is 'nobody is powering anybody'");

        // ...and one more tick with nothing queued does nothing at all: no solve, no rounds, no changes.
        List<BlockPos> quietFlush = new ArrayList<>();
        SolverNotifications.Session quiet = SolverNotifications.open(TEST_DIMENSION, quietFlush::add);
        int quietRounds = tick(world, new LinkedHashSet<>(), quiet);
        quiet.note(quietRounds, 0);
        SolverNotifications.end(TEST_DIMENSION, 100L);
        assertEquals(0, quietRounds, "an empty queue is no rounds");
        assertTrue(quietFlush.isEmpty(), "and nothing to tell the world");
    }

    /**
     * The two policies side by side: the same total work, spread over ticks instead of packed into one. A host and
     * a wire that read each other step down one hop at a time either way; what differs is how much of that happens
     * inside a single tick - unbuffered, every hop is a round of the tick that started it; coalesced, one round per
     * tick with the hop arriving as next tick's input. Nothing is skipped and nothing is invented.
     */
    @Test
    @DisplayName("the same work, spread over ticks: the coalesced run never exceeds one round per tick")
    void theSameWorkIsSpreadOverTicks() {
        HostAndWire unbuffered = new HostAndWire(0, 13);
        Set<BlockPos> rawQueue = queue();
        int unbufferedRounds = tick(unbuffered, rawQueue, null);
        assertTrue(unbufferedRounds > 1,
                "unbuffered, one tick does the whole descent: rounds=" + unbufferedRounds);

        HostAndWire coalesced = new HostAndWire(0, 13);
        Set<BlockPos> queue = queue();
        int ticks = 0;
        int worstTick = 0;
        while (ticks < 40) {
            int thisTick = tickAndFlush(coalesced, queue, ticks + 1L);
            worstTick = Math.max(worstTick, thisTick);
            ticks++;
            if (coalesced.wire == 0) {
                break;
            }
            queue.add(HOST);
        }

        assertEquals(0, coalesced.wire, "the coalesced run reaches the same fixpoint");
        assertEquals(unbuffered.host.power, coalesced.host.power,
                "and the same value the unbuffered run reached - the work is spread, not lost");
        assertEquals(1, worstTick,
                "no tick of the coalesced run ever needed more than one round, while the unbuffered one "
                        + "needed " + unbufferedRounds);
        assertTrue(ticks > 1, "the descent took several ticks rather than one: " + ticks);
    }

    /**
     * A circuit that genuinely needs several rounds still gets them: coalescing removes the re-entrancy, not the
     * propagation. Two components in a row is the shape "more rounds are the right answer" - each one has to be
     * relaxed for the value to travel - and those rounds see a frozen world, so they terminate at the circuit's own
     * length rather than at the round budget.
     */
    @Test
    @DisplayName("a circuit that needs to propagate still takes its rounds, and stops")
    void aChainStillPropagates() {
        HostAndWire world = new HostAndWire(0, 15);
        Set<BlockPos> dirty = queue();
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> { });

        int rounds = tick(world, dirty, session);
        session.note(rounds, 1);
        SolverNotifications.end(TEST_DIMENSION, 20L);

        assertTrue(rounds >= 1 && rounds <= 4,
                "the circuit's own length bounds the rounds, not the round budget: rounds=" + rounds);
        assertEquals(14, world.host.power,
                "and the value is the one-hop-reduced supply - hand-derived: max(0, 15 - 1)");
    }

    // -------------------------------------------------- the coalescer itself --

    /** @see SolverNotifications#lastStats(net.minecraft.resources.ResourceKey) */
    private static final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> TEST_DIMENSION =
            net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse("redstonecircuit:solver_notifications_test"));

    @Test
    @DisplayName("a coalesced notification goes out once, at the flush, however often it is offered")
    void aCoalescedNotificationGoesOutOnce() {
        List<BlockPos> sent = new ArrayList<>();
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, sent::add);

        session.notify(HOST);
        session.notify(HOST);
        session.notify(HOST);
        assertTrue(sent.isEmpty(), "nothing is sent while the solve is running");
        assertEquals(1, session.pendingCount(), "and the same position is one thing to tell the world about");

        session.note(1, 1);
        assertEquals(1, SolverNotifications.end(TEST_DIMENSION, 1L), "the flush sent exactly one");
        assertEquals(List.of(HOST), sent);
        assertTrue(session.coalescing());
    }

    @Test
    @DisplayName("with the switch off, a notification is sent where it is offered")
    void withoutCoalescingANotificationIsSentImmediately() {
        List<BlockPos> sent = new ArrayList<>();
        SolverNotifications.Session session = new SolverNotifications.Session(sent::add, false);

        session.notify(HOST);
        assertEquals(List.of(HOST), sent, "the old behaviour: sent here and now");
        assertEquals(0, session.pendingCount());
        session.notify(HOST.relative(Direction.UP));
        assertEquals(2, sent.size(), "and again, because that is what it was asked to do");
        SolverNotifications.end(TEST_DIMENSION, 1L);
    }

    /**
     * The flush drains before the first notification is sent, so a notification that re-enters the solver cannot
     * offer new positions to a set the flush is walking: the one place the coalescer itself could be re-entered, and
     * the reason the pending set is copied rather than iterated.
     */
    @Test
    @DisplayName("a notification that offers more positions during the flush cannot extend it")
    void theFlushIsNotExtendedByWhatItNotifies() {
        List<BlockPos> sent = new ArrayList<>();
        SolverNotifications.Session[] holder = new SolverNotifications.Session[1];
        // The session is registered under the test dimension and reached through the array: the same "the world
        // tells the solver back" shape the spin test uses.
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> {
            sent.add(pos);
            // The world's reaction is the classic synchronous re-entry: no second round may be added here.
            holder[0].notify(HOST.relative(Direction.UP));
        });
        holder[0] = session;

        session.notify(HOST);
        session.note(1, 1);
        int flushed = SolverNotifications.end(TEST_DIMENSION, 1L);

        assertEquals(1, flushed, "the flush was sized when it began");
        assertEquals(List.of(HOST), sent);
        assertEquals(1, session.pendingCount(),
                "and what the re-entry offered is left for the next solve rather than sent now");
    }

    @Test
    @DisplayName("a session reports what the solve did, once, in the window's figures")
    void statsRecordWhatTheSolveDid() {
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> { });
        session.note(3, 7);
        session.notify(HOST);
        SolverNotifications.end(TEST_DIMENSION, 1L);

        SolverNotifications.TickStats stats = SolverNotifications.lastStats(TEST_DIMENSION);
        assertEquals(3, stats.rounds());
        assertEquals(7, stats.recomputes());
        assertEquals(1, stats.flushed());
        assertEquals(0, session.pendingCount(), "and the flush emptied the set it walked");
        assertTrue(stats.ranSolve(), "a solve that recorded itself is a solve that ran");

        // An untouched dimension reports nothing at all.
        assertEquals(0, SolverNotifications.lastStats((net.minecraft.resources.ResourceKey<
                net.minecraft.world.level.Level>) null).rounds());
        assertFalse(SolverNotifications.lastStats((net.minecraft.resources.ResourceKey<
                net.minecraft.world.level.Level>) null).ranSolve());
        assertNotNull(SolverNotifications.lastStats(TEST_DIMENSION));

        SolverNotifications.clear(TEST_DIMENSION);
        assertEquals(0, SolverNotifications.lastStats(TEST_DIMENSION).rounds(),
                "and a cleared dimension forgets");
        assertNull(SolverNotifications.open(TEST_DIMENSION));
    }

    /**
     * {@code end} on a session nobody opened is a no-op, so a solve that runs outside a tick - a command,
     * a test - cannot close somebody else's session or invent statistics.
     */
    @Test
    @DisplayName("ending a session that was never opened is a no-op")
    void endingAnUnopenedSessionIsANoOp() {
        SolverNotifications.clearAll();
        assertEquals(0, SolverNotifications.end(TEST_DIMENSION, 5L));
        assertEquals(0, SolverNotifications.lastStats(TEST_DIMENSION).rounds());
        assertFalse(SolverNotifications.lastStats(TEST_DIMENSION).ranSolve());
    }

    /**
     * Every changed position is notified once, in the order the solve found them: the order matters to a reader of
     * the log though not to the world, and a duplicate is the smallest version of the storm.
     */
    @Test
    @DisplayName("every changed position is notified once, in the order the solve found them")
    void everyChangedPositionIsNotifiedOnce() {
        List<BlockPos> sent = new ArrayList<>();
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, sent::add);
        BlockPos second = HOST.relative(Direction.EAST);
        BlockPos third = HOST.relative(Direction.UP);

        session.notify(third);
        session.notify(HOST);
        session.notify(second);
        session.notify(HOST);
        SolverNotifications.end(TEST_DIMENSION, 1L);

        assertEquals(3, sent.size());
        assertEquals(new HashSet<>(List.of(HOST, second, third)), new HashSet<>(sent),
                "each changed position exactly once");
        assertEquals(List.of(third, HOST, second), sent,
                "and in the order they were first offered, which is the order the solve found them");
    }

    /** The dispatcher's two answers, with no level: "no session" means "send it now". */
    @Test
    @DisplayName("the dispatcher is a no-op for a null level and opens nothing")
    void theDispatcherToleratesNothingToDispatch() {
        SolverNotifications.clearAll();
        Consumer<BlockPos> ignored = pos -> { };
        assertNull(SolverNotifications.begin(null, ignored), "no level, nothing to coalesce");
        assertNull(SolverNotifications.begin(null), "and the production form agrees");
        SolverNotifications.offer(null, HOST);
        SolverNotifications.offer(null, null);
        assertEquals(0, SolverNotifications.end((net.minecraft.server.level.ServerLevel) null));
        assertEquals(0, SolverNotifications.lastStats(TEST_DIMENSION).rounds());
    }

    /**
     * The map of open sessions holds at most one per dimension, and ending a session twice sends nothing the second
     * time: a solve that ends a session it did not open, or ends its own twice, must not announce a change it has
     * already announced.
     */
    @Test
    @DisplayName("ending a session twice sends nothing the second time")
    void endingTwiceSendsOnce() {
        SolverNotifications.clearAll();
        List<BlockPos> sent = new ArrayList<>();
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, sent::add);
        session.notify(HOST);
        assertEquals(1, SolverNotifications.end(TEST_DIMENSION, 1L), "the first end flushes it");
        assertEquals(1, sent.size());
        assertEquals(0, SolverNotifications.end(TEST_DIMENSION, 2L),
                "and the session is closed, so there is nothing left to end");
        assertEquals(1, sent.size(), "the world was told exactly once");
        assertTrue(session.ended());
    }

    /** A full map of statistics must not grow without bound: only dimensions that solved are remembered. */
    @Test
    @DisplayName("a cleared session leaves no state behind")
    void clearingLeavesNoState() {
        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> { });
        session.note(9, 9);
        SolverNotifications.end(TEST_DIMENSION, 1L);
        assertEquals(9, SolverNotifications.lastStats(TEST_DIMENSION).rounds());

        SolverNotifications.clearAll();
        assertEquals(0, SolverNotifications.lastStats(TEST_DIMENSION).rounds());
        assertNull(SolverNotifications.open(TEST_DIMENSION));
    }

    /** The value the model is built on, so a change to it cannot silently invalidate the tests above. */
    @Test
    @DisplayName("the one-hop rule the model relies on is the production rule")
    void theHopRuleIsTheProductionRule() {
        assertEquals(13, InnerRedstoneNetwork.neighbourSignal(14, true),
                "a vanilla wire neighbour costs one hop - the author's 'externalSignal=13 from a wire'");
        assertEquals(15, InnerRedstoneNetwork.neighbourSignal(15, false),
                "and anything else passes through");
        assertEquals(0, InnerRedstoneNetwork.neighbourSignal(0, true));
    }

    /**
     * {@code begin}'s switch is the config's, and the config's default is on. Read through the production accessor
     * rather than a private field, so an accidentally flipped default fails here.
     */
    @Test
    @DisplayName("the coalescing switch is on by default, which is what begin() reads")
    void theSwitchIsOnByDefault() {
        assertTrue(com.jiangyuefengyu.redstonecircuit.RCConfig.solverCoalesceNotifications(),
                "the fix is the default; a build with it off has to be a deliberate config edit");
    }

    /** The wire's own rule, stated once: it is the model's other half. */
    @Test
    @DisplayName("the wire re-derives as max(neighbours) - 1, which is what makes the pair solvable")
    void theWireDropsOneStepBelowTheHost() {
        HostAndWire world = new HostAndWire(15, 0);
        Set<BlockPos> dirty = new LinkedHashSet<>();
        assertEquals(14, world.deliver(HOST, dirty), "a wire fed by a 15 host carries 14");
        assertEquals(14, world.wire);
        assertTrue(dirty.contains(HOST), "and tells the host back, on the same stack");
        dirty.clear();
        world.host.power = 15;
        assertEquals(-1, world.deliver(HOST, dirty), "and stops changing once it is there");
        assertTrue(dirty.isEmpty(), "with nothing to tell anybody");

        world.host.power = 0;
        assertEquals(0, world.deliver(HOST, dirty), "and goes dark when the host does");
        assertEquals(List.of(14, 0), world.notified);
    }

    /**
     * The arithmetic the model is built on, stated once with {@link PowerSolver} itself: the host's power is
     * {@code max(supply, every neighbour after its hop)}. This is what makes the two policy tests below meaningful -
     * they compare two runs of this function, not of a test's own arithmetic.
     */
    @Test
    @DisplayName("the model's value is PowerSolver's own rule")
    void theValueIsPowerSolversOwnRule() {
        HostAndWire wireAt13 = new HostAndWire(0, 13);
        wireAt13.setQueryPosition(HOST);
        assertEquals(12, PowerSolver.wireTarget(wireAt13, HOST, wireAt13.supply(),
                wireAt13.node()), "wire at 13 offers 12 (a wire hop costs one)");

        HostAndWire wireAt15 = new HostAndWire(0, 15);
        wireAt15.setQueryPosition(HOST);
        assertEquals(14, PowerSolver.wireTarget(wireAt15, HOST, wireAt15.supply(),
                wireAt15.node()), "wire at 15 offers 14");

        HostAndWire dark = new HostAndWire(0, 0);
        dark.setQueryPosition(HOST);
        assertEquals(0, PowerSolver.wireTarget(dark, HOST, dark.supply(), dark.node()),
                "and a dark world leaves the host dark - nothing is self-sustaining here");
    }

    /** A tick drains the queue completely, whatever it found. */
    /** During the solve nothing is sent; at the flush, exactly what changed is, once each. */
    @Test
    @DisplayName("nothing is sent during the solve, and what changed is sent once at the flush")
    void theDirtySetIsDrained() {
        List<BlockPos> duringSolve = new ArrayList<>();
        List<BlockPos> atFlush = new ArrayList<>();
        HostAndWire world = new HostAndWire(0, 13);
        Set<BlockPos> dirty = queue();

        SolverNotifications.Session session = SolverNotifications.open(TEST_DIMENSION, pos -> {
            duringSolve.add(pos);
            atFlush.add(pos);
        });
        int rounds = tick(world, dirty, session);
        assertTrue(duringSolve.isEmpty(), "with coalescing on, nothing is sent during the solve");
        assertTrue(dirty.isEmpty(), "and the queue is empty when the tick returns");

        session.note(rounds, 1);
        assertEquals(1, SolverNotifications.end(TEST_DIMENSION, 1L), "the flush sent one position");
        assertEquals(List.of(HOST), atFlush, "the one that changed");
        assertEquals(12, world.host.power, "and the solve still produced the right value");
    }

    // ------------------------------------------- the structure-frame pass --

    /**
     * The second half of a carried host's neighbourhood: once per coordinate per tick. The pass sends the first
     * offer of each coordinate and nothing else, and "per tick" rather than "per call" is a property of this class:
     * it is keyed by the level's own clock and the next tick replaces it, so a second driver in the same tick cannot
     * double-send while a later tick can send the same coordinate again when it really changed again.
     */
    @Test
    @DisplayName("a structure-frame pass sends a coordinate once per tick, and the next tick is news again")
    void aStructureFramePassSendsEachCoordinateOncePerTick() {
        BlockPos first = new BlockPos(20481032, 127, 20481032);
        BlockPos second = new BlockPos(20481033, 127, 20481032);
        List<BlockPos> sent = new ArrayList<>();

        SolverNotifications.StructurePass tickOne =
                SolverNotifications.structurePass(TEST_DIMENSION, 1L, sent::add, true);
        assertNotNull(tickOne, "the switch is on");
        assertEquals(1L, tickOne.tick());
        assertTrue(tickOne.notify(first), "the first offer of a coordinate is the one that sends it");
        assertFalse(tickOne.notify(first), "and a repeat in the same tick is not news");
        assertTrue(tickOne.notify(second), "another coordinate is its own notice");
        assertEquals(List.of(first, second), sent);
        assertEquals(2, tickOne.sent());

        // The same tick asks for the pass again - the shape a second driver would take - and gets the
        // pass that is already open rather than a fresh set.
        assertSame(tickOne, SolverNotifications.structurePass(TEST_DIMENSION, 1L, sent::add, true));
        assertFalse(SolverNotifications.structurePass(TEST_DIMENSION, 1L, sent::add, true).notify(first));

        SolverNotifications.StructurePass tickTwo =
                SolverNotifications.structurePass(TEST_DIMENSION, 2L, sent::add, true);
        assertNotNull(tickTwo);
        assertTrue(tickTwo.notify(first), "a later tick can announce the same coordinate again");
        assertEquals(List.of(first, second, first), sent);

        // ...and the pass goes with the level it belonged to.
        SolverNotifications.clear(TEST_DIMENSION);
        assertTrue(sent.size() == 3, "and clearing sends nothing by itself");
        SolverNotifications.StructurePass afterClear =
                SolverNotifications.structurePass(TEST_DIMENSION, 2L, sent::add, true);
        assertNotNull(afterClear);
        assertTrue(afterClear.notify(first));
    }

    /**
     * Off is off: no pass at all, so the caller offers nothing and the world keeps the previous build. The boolean
     * the production factory computes is {@code solverCoalesceNotifications() && carrierNotifyStructureNeighbours()}
     * - the coalescing switch this rule belongs to and the capability switch a player can turn off on its own - and
     * either one being false has to mean "no pass", because that is what the caller tests for.
     */
    @Test
    @DisplayName("with either switch off there is no structure-frame pass, so nothing new is sent")
    void theStructureFramePassIsOffWhenEitherSwitchIsOff() {
        List<BlockPos> sent = new ArrayList<>();
        assertNull(SolverNotifications.structurePass(TEST_DIMENSION, 1L, sent::add, false),
                "off means null - not an empty pass, which a caller could still offer to");
        assertNull(SolverNotifications.structurePass(TEST_DIMENSION, 1L, null, true),
                "and no notification to send is the same answer");
        assertTrue(sent.isEmpty(), "nothing was sent either way");

        assertTrue(com.jiangyuefengyu.redstonecircuit.RCConfig.carrierNotifyStructureNeighbours(),
                "the capability is on by default, like every other carrier feature");
        assertTrue(com.jiangyuefengyu.redstonecircuit.RCConfig.solverCoalesceNotifications(),
                "and it rides the coalescing switch, which is on by default too");
    }
}
