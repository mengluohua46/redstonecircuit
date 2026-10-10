package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The entry trace's format, its bounds and its throttle.</b>
 *
 * <p>The three outcomes are written verbatim, so a grep finds MATCH, MISS or REFUSED; the throttle is one
 * line per shape per window and never throttles a MATCH; the candidate list is bounded to K carriers by M
 * hosts and prints the CURRENT projection rather than the record's origin.
 */
class CarrierEntryTraceTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000004001"));

    /** The machine's position, with a rotation so the pose is not the identity. */
    private static final Vector3d AT = new Vector3d(2775.0, 77.0, -1395.0);

    @BeforeEach
    void reset() {
        CarrierEntryTrace.reset();
    }

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust() {
        return new Slot(ComponentType.DUST);
    }

    private static CarrierPose pose(double degrees) {
        return CarrierPose.of(AT, new Quaterniond().rotateY(Math.toRadians(degrees)));
    }

    /** A host carried at {@code local}, picked up at a two-million-block plot coordinate. */
    private static CarriedHost host(BlockPos local) {
        return CarriedHost.captured(new BlockPos(20481032, 127, 20524040), local, dust());
    }

    // ------------------------------------------------------------------- the three outcomes --

    @Test
    @DisplayName("the three outcomes are written verbatim, so a grep for MATCH/MISS/REFUSED finds them")
    void theThreeOutcomesAreWrittenVerbatim() {
        assertEquals("MATCH", CarrierEntryTrace.Outcome.MATCH.name());
        assertEquals("MISS", CarrierEntryTrace.Outcome.MISS.name());
        assertEquals("REFUSED", CarrierEntryTrace.Outcome.REFUSED.name());
        for (CarrierEntryTrace.Outcome outcome : CarrierEntryTrace.Outcome.values()) {
            String line = CarrierEntryTrace.entryLine("RIGHT_CLICK_BLOCK", "作者", "1, 2, 3", "up",
                    "MAIN_HAND", "minecraft:redstone x1", "component", outcome, "detail");
            assertTrue(line.contains("-> " + outcome.name() + " "), "the arrow and the outcome: " + line);
        }
        // A MISS whose reason mentions MATCH must not be mistaken for a hit.
        String miss = CarrierEntryTrace.entryLine("E", "p", "1, 2, 3", "up", "MAIN_HAND", "empty",
                "bare hand", CarrierEntryTrace.Outcome.MISS, "no host");
        assertFalse(miss.contains("-> MATCH"));
        assertFalse(miss.contains("-> REFUSED"));
    }

    @Test
    @DisplayName("the entry line carries every field an acceptance run reads")
    void theEntryLineCarriesEveryField() {
        String line = CarrierEntryTrace.entryLine("CONTRAPTION_PACKET", "作者", "20481031, 128, 20524040",
                "east", "OFF_HAND", "minecraft:redstone_torch x3", "component",
                CarrierEntryTrace.Outcome.MISS, "(looked up with CarrierStore.hitAt)");

        assertTrue(line.startsWith("[redstonecircuit] carrier entry trace: "), line);
        assertTrue(line.contains("entry=CONTRAPTION_PACKET"), "the entry point's name: " + line);
        assertTrue(line.contains("by=作者"), "who: " + line);
        assertTrue(line.contains("raw=20481031, 128, 20524040"), "the raw coordinate: " + line);
        assertTrue(line.contains("face=east"), "the face: " + line);
        assertTrue(line.contains("hand=OFF_HAND"), "the hand: " + line);
        assertTrue(line.contains("item=minecraft:redstone_torch x3"), "the held item: " + line);
        assertTrue(line.contains("tool=component"), "what the item means: " + line);
        assertTrue(line.contains("-> MISS "), "the outcome: " + line);
        assertTrue(line.contains("looked up with"), "what it was looked up with: " + line);
        // One line, whatever happens: no embedded newline can turn one event into two log lines.
        assertFalse(line.contains("\n"), "a trace is exactly one line");
    }

    @Test
    @DisplayName("a MATCH line names the carrier, the structure's local coordinate and the projection")
    void aMatchLineNamesThePairThatMatters() {
        BlockPos local = pos(20481031, 128, 20524040);
        BlockPos world = pos(2775, 77, -1393);
        CarriedBlockRemoval.Hit hit = new CarriedBlockRemoval.Hit(MINE, local, world, pose(0.0), true);

        String tail = CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MATCH, hit, local, null);
        assertTrue(tail.contains("carrier=" + MINE.shortId()), tail);
        assertTrue(tail.contains("local=20481031, 128, 20524040"), tail);
        assertTrue(tail.contains("world=2775, 77, -1393"), tail);
        assertTrue(tail.contains("via=projection"), "which frame answered: " + tail);
        assertTrue(tail.contains("rawIsLocal=true"),
                "the raw coordinate and the local coordinate are the same point here: " + tail);
    }

    @Test
    @DisplayName("the tail names the frame, and a caller-described match says it has no Hit record")
    void theMatchTailNamesTheFrame() {
        BlockPos local = pos(1, 2, 3);
        BlockPos world = pos(10, 20, 30);

        String structureFrame = CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MATCH,
                new CarriedBlockRemoval.Hit(MINE, local, world, null, false), pos(9, 9, 9), null);
        assertTrue(structureFrame.contains("via=structure-block"), structureFrame);
        assertTrue(structureFrame.contains("rawIsLocal=false"),
                "the click arrived somewhere else, and that fact is the useful one: " + structureFrame);

        // The break path answers a destruction rather than a hit: the line must say so instead of having a
        // frame invented for it.
        String described = CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MATCH, null, local,
                "carrier=c:1 local=1, 2, 3");
        assertTrue(described.contains("no Hit record"), described);
        assertTrue(described.endsWith("carrier=c:1 local=1, 2, 3"), described);

        // A miss carries its reason and nothing else.
        assertEquals("no host", CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MISS, null,
                local, "no host"));
        assertEquals("not a carried host",
                CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MISS, null, local, null));

        // The detail is appended after the pair, which is where the reason belongs.
        String withNote = CarrierEntryTrace.matchDetail(CarrierEntryTrace.Outcome.MATCH,
                new CarriedBlockRemoval.Hit(MINE, local, world, pose(0.0), true), local, "turned to input");
        assertTrue(withNote.endsWith("turned to input"), withNote);
    }

    // ------------------------------------------------------------------------ the throttle --

    @Test
    @DisplayName("the same miss shape prints once per twenty ticks, and a different coordinate does not")
    void theSameMissShapePrintsOncePerWindow() {
        CarrierEntryTrace.Throttle throttle = new CarrierEntryTrace.Throttle(20, 32);
        String shape = CarrierEntryTrace.shape("RIGHT_CLICK_BLOCK", CarrierEntryTrace.Outcome.MISS,
                pos(1, 2, 3), true);

        assertTrue(throttle.allow(shape, 0L), "the first one is printed");
        for (long tick = 1; tick < 20; tick++) {
            assertFalse(throttle.allow(shape, tick), "tick " + tick + " is inside the window");
        }
        assertTrue(throttle.allow(shape, 20L), "twenty ticks later it is printed again");

        String other = CarrierEntryTrace.shape("RIGHT_CLICK_BLOCK", CarrierEntryTrace.Outcome.MISS,
                pos(4, 5, 6), true);
        assertNotEquals(shape, other, "a different coordinate is a different shape while carriers exist");
        assertTrue(throttle.allow(other, 20L), "so a different position is never swallowed");
    }

    @Test
    @DisplayName("with no carrier at all, every miss shares one shape whatever its coordinate")
    void withNoCarrierPositionIsNotInformation() {
        String one = CarrierEntryTrace.shape("RIGHT_CLICK_BLOCK", CarrierEntryTrace.Outcome.MISS,
                pos(1, 2, 3), false);
        String two = CarrierEntryTrace.shape("RIGHT_CLICK_BLOCK", CarrierEntryTrace.Outcome.MISS,
                pos(900, 64, -900), false);
        assertEquals(one, two, "an unrelated click cannot fill the log: there is nothing to distinguish");
        assertTrue(one.endsWith("|any"), one);

        // A different entry point is still a different shape: a break and a click are separate facts.
        assertNotEquals(one, CarrierEntryTrace.shape("BREAK_EVENT", CarrierEntryTrace.Outcome.MISS,
                pos(1, 2, 3), false));
        // ...and so is a refusal, which is a different answer about the same click.
        assertNotEquals(one, CarrierEntryTrace.shape("RIGHT_CLICK_BLOCK", CarrierEntryTrace.Outcome.REFUSED,
                pos(1, 2, 3), false));
    }

    @Test
    @DisplayName("a MATCH never enters the throttle; a MISS and a REFUSAL do")
    void aMatchIsNeverThrottled() {
        BlockPos raw = pos(3, 4, 5);
        CarrierEntryTrace.entry(null, "BREAK_EVENT", null, raw, null, null, null,
                CarrierEntryTrace.Outcome.MATCH, null, "first");
        assertEquals(0, CarrierEntryTrace.throttle().size(),
                "a hit is a real action: it must not be recorded by the throttle at all");

        CarrierEntryTrace.entry(null, "BREAK_EVENT", null, raw, null, null, null,
                CarrierEntryTrace.Outcome.MISS, null, "no host");
        assertEquals(1, CarrierEntryTrace.throttle().size(), "a miss is recorded");
        CarrierEntryTrace.entry(null, "BREAK_EVENT", null, raw, null, null, null,
                CarrierEntryTrace.Outcome.MISS, null, "no host");
        assertEquals(1, CarrierEntryTrace.throttle().size(),
                "the identical miss was throttled, so it was not recorded a second time");
    }

    @Test
    @DisplayName("the throttle's map is bounded, so one entry point cannot make the others repeat")
    void theThrottleMapIsBounded() {
        CarrierEntryTrace.Throttle throttle = new CarrierEntryTrace.Throttle(20, 4);
        for (int i = 0; i < 50; i++) {
            assertTrue(throttle.allow("shape-" + i, i));
        }
        assertTrue(throttle.size() <= 4, "the ceiling holds: " + throttle.size());
    }

    @Test
    @DisplayName("a tick that goes backwards (a reloaded world) never silences a line")
    void aBackwardsTickDoesNotSilence() {
        CarrierEntryTrace.Throttle throttle = new CarrierEntryTrace.Throttle(20, 32);
        assertTrue(throttle.allow("s", 1000L));
        assertFalse(throttle.allow("s", 1005L));
        assertTrue(throttle.allow("s", 3L), "a clock that reset is not the same shape any more");
    }

    @Test
    @DisplayName("the candidate list has its own, coarser window")
    void theCandidateListHasItsOwnWindow() {
        CarrierEntryTrace.Throttle throttle = new CarrierEntryTrace.Throttle(20, 32);
        assertTrue(throttle.allowCandidates(0L));
        for (long tick = 1; tick < CarrierEntryTrace.CANDIDATE_WINDOW_TICKS; tick++) {
            assertFalse(throttle.allowCandidates(tick), "tick " + tick + " is inside the window");
        }
        assertTrue(throttle.allowCandidates(CarrierEntryTrace.CANDIDATE_WINDOW_TICKS));
        assertEquals(0, throttle.size(), "the candidate window is not one of the shapes");
    }

    // --------------------------------------------------------------- the candidate list --

    @Test
    @DisplayName("K and M are the small constants the report says they are")
    void theLimitsAreSmallConstants() {
        assertEquals(3, CarrierEntryTrace.CANDIDATE_CARRIER_LIMIT);
        assertEquals(6, CarrierEntryTrace.CANDIDATE_HOST_LIMIT);
        assertTrue(CarrierEntryTrace.CANDIDATE_CARRIER_LIMIT * CarrierEntryTrace.CANDIDATE_HOST_LIMIT <= 18,
                "the whole list is at most a handful of coordinate pairs");
    }

    @Test
    @DisplayName("the host pairs are bounded by M and the remainder is a count, never more lines")
    void theHostPairsAreBounded() {
        List<CarriedHost> hosts = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            hosts.add(host(pos(i, 64, 0)));
        }
        String pairs = CarrierEntryTrace.hostPairs(pose(37.5), hosts, CarrierEntryTrace.CANDIDATE_HOST_LIMIT);

        int written = pairs.split("local=\\(", -1).length - 1;
        assertEquals(CarrierEntryTrace.CANDIDATE_HOST_LIMIT, written,
                "exactly M pairs, whatever the machine's size: " + pairs);
        assertTrue(pairs.endsWith("...+" + (40 - CarrierEntryTrace.CANDIDATE_HOST_LIMIT)),
                "and one truncation marker: " + pairs);
        assertFalse(pairs.contains("\n"), "still one line");
    }

    @Test
    @DisplayName("a host pair is the CURRENT projection, never the record's origin")
    void aPairIsTheCurrentProjectionAndNotTheOrigin() {
        CarriedHost carried = host(pos(1, 2, 3));
        CarrierPose moved = pose(90.0);

        String pairs = CarrierEntryTrace.hostPairs(moved, List.of(carried), 6);
        BlockPos expected = CarrierProjection.project(moved, carried.localPos());

        assertEquals("local=(1, 2, 3) world=(" + expected.toShortString() + ")", pairs);
        assertNotEquals(carried.at(), expected,
                "the setup must actually differ: the origin is the plot coordinate it was picked up at");
        assertFalse(pairs.contains(carried.at().toShortString()),
                "printing the origin instead of the projection is exactly the confusion this list exists "
                        + "to expose");
    }

    @Test
    @DisplayName("no hosts, no pose, or a zero limit each answer without a guess")
    void thePairsDegradeHonestly() {
        assertEquals("none", CarrierEntryTrace.hostPairs(pose(0.0), List.of(), 6));
        assertEquals("none", CarrierEntryTrace.hostPairs(pose(0.0), null, 6));
        assertEquals("none", CarrierEntryTrace.hostPairs(pose(0.0), List.of(host(pos(0, 0, 0))), 0));
        assertEquals("local=(0, 0, 0) world=(?)",
                CarrierEntryTrace.hostPairs(null, List.of(host(pos(0, 0, 0))), 6),
                "a structure with no readable pose names no world position rather than inventing one");
    }

    @Test
    @DisplayName("a candidate line has the shape the acceptance procedure reads")
    void aCandidateLineHasTheDocumentedShape() {
        String line = CarrierEntryTrace.candidateLine("s:6c60aeef", pose(0.0).describe(),
                CarrierEntryTrace.hostPairs(pose(0.0), List.of(host(pos(1, 2, 3))), 6));
        assertTrue(line.contains("carrier s:6c60aeef pose="), line);
        assertTrue(line.contains("hosts=[local=(1, 2, 3) world=("), line);
        assertTrue(line.endsWith("]"), line);

        String header = CarrierEntryTrace.candidateHeader("RIGHT_CLICK_BLOCK", "1, 2, 3",
                "minecraft:overworld", 2, 5);
        assertTrue(header.contains("2 live carrier(s) in minecraft:overworld"), header);
        assertTrue(header.contains("entry=RIGHT_CLICK_BLOCK missed at 1, 2, 3"), header);
        assertTrue(header.contains("K=" + CarrierEntryTrace.CANDIDATE_CARRIER_LIMIT), header);
        assertTrue(header.contains("...+" + 5 + " more carrier(s)"), header);
    }

    // ------------------------------------------------------------------ the field helpers --

    @Test
    @DisplayName("a field with nothing behind it says so, and never throws")
    void theFieldHelpersHandleNothing() {
        assertEquals("n/a", CarrierEntryTrace.faceText(null));
        assertEquals("up", CarrierEntryTrace.faceText(Direction.UP));
        assertEquals("n/a", CarrierEntryTrace.handText(null));
        assertEquals("OFF_HAND", CarrierEntryTrace.handText(InteractionHand.OFF_HAND));
        assertEquals("?", CarrierEntryTrace.posText(null));
        assertEquals("-", CarrierEntryTrace.whoOf(null));
        // An ItemStack is never built here: reading its registry entry needs the game's bootstrap, which a
        // trace helper asserted offline does not have.
        assertEquals("n/a", CarrierEntryTrace.itemText(null), "no hand at all is not an empty hand");
        assertEquals("n/a", CarrierEntryTrace.toolText(null));
    }

    @Test
    @DisplayName("a miss says what it was looked up with, and says when there is no index")
    void aMissSaysWhatItWasLookedUpWith() {
        String detail = CarrierEntryTrace.lookupAt(null, "CarrierStore.hitAt(level, rawPos)", pos(1, 2, 3));
        assertTrue(detail.contains("looked up with CarrierStore.hitAt(level, rawPos)"), detail);
        assertTrue(detail.contains("at 1, 2, 3"), detail);
        assertTrue(detail.contains("no carrier index for this level"), detail);
        assertTrue(detail.startsWith("(") && detail.endsWith(")"), detail);
    }

    @Test
    @DisplayName("a resolved destruction is described with the drop position beside the world position")
    void aDestroyedHostIsDescribedWithItsDropPosition() {
        BlockPos local = pos(1, 0, 0);
        BlockPos world = pos(2776, 77, -1395);
        CarriedBlockRemoval.Destroyed destroyed = new CarriedBlockRemoval.Destroyed(MINE, local, world,
                world, dust());

        String detail = CarrierEntryTrace.destroyDetail(destroyed);
        assertTrue(detail.contains("resolvedCarrier=c:"), detail);
        assertTrue(detail.contains("resolvedLocal=1, 0, 0"), detail);
        assertTrue(detail.contains("resolvedWorld=2776, 77, -1395"), detail);
        assertTrue(detail.contains("dropPos=2776, 77, -1395"), detail);
        assertTrue(detail.contains("type=DUST"), detail);
        // The names differ from the hit tail's: one break line carries both records, and the two name
        // different positions in the structure-frame case.
        assertFalse(detail.contains(" local="), detail);
        assertFalse(detail.contains(" world="), detail);
        assertEquals("resolved to nothing", CarrierEntryTrace.destroyDetail(null));
    }

    // ------------------------------------------------------------ the "did a break arrive" ledger --

    @Test
    @DisplayName("the break ledger remembers a host, and a different host is not the same host")
    void theBreakLedgerRemembersOneHost() {
        BlockPos local = pos(1, 0, 0);
        CarrierEntryTrace.noteBreakEvent(MINE, local, pos(2776, 77, -1395));

        assertTrue(CarrierEntryTrace.breakEventSeenForHost(MINE, local),
                "a break event at this host's coordinate was seen");
        assertFalse(CarrierEntryTrace.breakEventSeenForHost(MINE, pos(2, 0, 0)),
                "a neighbouring coordinate is a different question");
        assertFalse(CarrierEntryTrace.breakEventSeenForHost(
                        CarrierId.subLevel(MINE.uuid()), local),
                "and so is the same coordinate under another structure's identity");
        assertFalse(CarrierEntryTrace.breakEventSeenForHost(null, local));
        assertFalse(CarrierEntryTrace.breakEventSeenForHost(MINE, null));
    }

    @Test
    @DisplayName("the break ledger is bounded: it keeps the most recent breaks and nothing more")
    void theBreakLedgerIsBounded() {
        int cap = CarrierEntryTrace.MAX_REMEMBERED_BREAKS;
        for (int i = 0; i < cap * 2; i++) {
            CarrierEntryTrace.noteBreakEvent(MINE, pos(i, 0, 0), pos(i, 0, 0));
        }
        assertEquals(cap, CarrierEntryTrace.rememberedBreaks(), "the ceiling holds");
        assertFalse(CarrierEntryTrace.breakEventSeenForHost(MINE, pos(0, 0, 0)),
                "the oldest break was evicted");
        assertTrue(CarrierEntryTrace.breakEventSeenForHost(MINE, pos(cap * 2 - 1, 0, 0)),
                "the newest break is still remembered");
    }

    @Test
    @DisplayName("reset forgets the throttle and the ledger, so one test cannot leak into the next")
    void resetForgetsEverything() {
        CarrierEntryTrace.entry(null, "E", null, pos(1, 2, 3), null, null, null,
                CarrierEntryTrace.Outcome.MISS, null, "no host");
        CarrierEntryTrace.noteBreakEvent(MINE, pos(1, 0, 0), pos(1, 0, 0));
        assertTrue(CarrierEntryTrace.throttle().size() > 0);
        assertTrue(CarrierEntryTrace.rememberedBreaks() > 0);

        CarrierEntryTrace.reset();
        assertEquals(0, CarrierEntryTrace.throttle().size());
        assertEquals(0, CarrierEntryTrace.rememberedBreaks());
    }
}
