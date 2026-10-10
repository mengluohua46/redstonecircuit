package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the client's memory of the hosts a structure has put down.
 *
 * <p>A carried component that comes home is written into the server's world store and announced to the client,
 * but the carried record the client was drawing from goes out with the carrier and the block at the landing
 * position need not exist yet, so the world path refuses to draw a shell over air. A landed record is keyed by
 * the landing position, is idempotent per host, and yields only while the world path cannot draw. Reads are
 * snapshots.
 */
class LandedHostsTest {

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final CarrierId SECOND = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c2"));

    private final LandedHosts landed = new LandedHosts();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /**
     * One host picked up at {@code origin} and put down at {@code at}; the landed record is keyed by {@code at}.
     */
    private static CarrierIndex.CarriedView view(CarrierId carrier, int lx, int ly, int lz,
                                                 BlockPos origin, BlockPos at) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = 15;
        return new CarrierIndex.CarriedView(carrier, pos(lx, ly, lz), origin, at, slot);
    }

    @Test
    @DisplayName("a carrier that goes away leaves its hosts behind, keyed by where they were put down")
    void stowKeepsTheHostsAtTheirLandingPosition() {
        CarrierIndex.CarriedView host =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));

        assertEquals(1, landed.stow(List.of(host)));

        assertEquals(1, landed.size());
        assertEquals(1, landed.carrierCount());
        assertTrue(landed.contains(pos(100, 64, 100)));
        assertFalse(landed.contains(pos(100, 64, 101)), "and only at that position");
        assertFalse(landed.isEmpty());
    }

    @Test
    @DisplayName("a landing position that is not the pick-up position is the one that is remembered")
    void stowKeysByLandingNotByOrigin() {
        // Keyed by where the release put it: the origin is a position the server no longer answers for.
        CarrierIndex.CarriedView host =
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(140, 64, 100));

        landed.stow(List.of(host));

        assertTrue(landed.contains(pos(140, 64, 100)));
        assertFalse(landed.contains(pos(100, 64, 100)));
        assertEquals(pos(140, 64, 100), landed.all().get(0).at());
    }

    @Test
    @DisplayName("the same carrier announced gone twice is one entry, not two")
    void stowIsIdempotentPerHost() {
        CarrierIndex.CarriedView first =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView again =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));

        assertEquals(1, landed.stow(List.of(first)));
        assertEquals(0, landed.stow(List.of(again)), "a repeated removal notice adds nothing");

        assertEquals(1, landed.size());
        assertEquals(1, landed.carrierCount());
        assertEquals(1, landed.all().size());
    }

    @Test
    @DisplayName("a repeated notice updates the contents in place rather than duplicating the host")
    void stowReplacesTheSlotOnARepeat() {
        CarrierIndex.CarriedView stale =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView fresh =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));
        fresh.slot().power = 4;

        landed.stow(List.of(stale));
        assertEquals(0, landed.stow(List.of(fresh)));

        assertEquals(1, landed.size());
        assertEquals(4, landed.all().get(0).slot().power,
                "the newer view wins - the in-structure solve may have changed the value in flight");
    }

    @Test
    @DisplayName("two carriers that put a host on the same block both keep their record")
    void onePositionCanHoldTwoCarriers() {
        CarrierIndex.CarriedView mine =
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView theirs =
                view(SECOND, 0, 0, 0, pos(101, 64, 100), pos(100, 64, 100));

        assertEquals(2, landed.stow(List.of(mine, theirs)));

        assertEquals(2, landed.size());
        assertEquals(2, landed.carrierCount());
    }

    @Test
    @DisplayName("the world sync taking a position over forgets the landed record there")
    void forgetAtDropsTheCandidatesAtOnePosition() {
        CarrierIndex.CarriedView here =
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView there =
                view(SECOND, 0, 0, 0, pos(200, 64, 200), pos(200, 64, 200));
        landed.stow(List.of(here, there));

        assertEquals(1, landed.forgetAt(pos(100, 64, 100)));

        assertFalse(landed.contains(pos(100, 64, 100)));
        assertTrue(landed.contains(pos(200, 64, 200)), "the other position is untouched");
        assertEquals(1, landed.size());
        assertEquals(1, landed.carrierCount());
    }

    @Test
    @DisplayName("forgetting another carrier's position, or an empty one, changes nothing")
    void forgetAtIsSafeWhenThereIsNothingToForget() {
        landed.stow(List.of(view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100))));

        assertEquals(0, landed.forgetAt(pos(999, 64, 999)));
        assertEquals(0, landed.forgetAt(null));
        assertEquals(1, landed.size());
    }

    @Test
    @DisplayName("a carrier heard from again loses every landed record it had")
    void forgetCarrierDropsOnlyThatCarrier() {
        landed.stow(List.of(
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100)),
                view(CARRIER, 1, 0, 0, pos(101, 64, 100), pos(101, 64, 100)),
                view(SECOND, 0, 0, 0, pos(200, 64, 200), pos(200, 64, 200))));

        assertEquals(2, landed.forgetCarrier(CARRIER));

        assertFalse(landed.contains(pos(100, 64, 100)));
        assertFalse(landed.contains(pos(101, 64, 100)));
        assertTrue(landed.contains(pos(200, 64, 200)));
        assertEquals(1, landed.size());
        assertEquals(1, landed.carrierCount());
    }

    @Test
    @DisplayName("the section walk finds landed hosts by where they landed, not by where they came from")
    void forEachNearUsesTheLandingPosition() {
        // Landing next to the camera, origin far away: a walk that used the origin would find nothing.
        landed.stow(List.of(
                view(CARRIER, 0, 0, 0, pos(900, 64, 900), pos(2, 64, 2)),
                view(SECOND, 0, 0, 0, pos(1, 64, 1), pos(900, 64, 900))));

        List<CarrierId> near = new ArrayList<>();
        landed.forEachNear(pos(0, 64, 0), 4, host -> near.add(host.carrier()));

        assertEquals(List.of(CARRIER), near);
    }

    @Test
    @DisplayName("a walk against a snapshot does not see a later edit")
    void readsAreAgainstASnapshot() {
        landed.stow(List.of(view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100))));

        List<CarrierIndex.CarriedView> snapshot = landed.all();
        landed.stow(List.of(view(SECOND, 0, 0, 0, pos(300, 64, 300), pos(300, 64, 300))));

        assertEquals(1, snapshot.size(),
                "the copy handed out before the write describes the state it was taken in");
        assertEquals(2, landed.all().size());
    }

    @Test
    @DisplayName("clearing drops everything, as leaving the world must")
    void clearEmptiesTheMap() {
        landed.stow(List.of(view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100))));

        landed.clear();

        assertTrue(landed.isEmpty());
        assertEquals(0, landed.size());
        assertEquals(0, landed.carrierCount());
        assertFalse(landed.contains(pos(100, 64, 100)));
    }

    @Test
    @DisplayName("null and empty input is a no-op rather than a throw")
    void nullAndEmptyAreSafe() {
        assertEquals(0, landed.stow(null));
        assertEquals(0, landed.stow(List.of()));
        assertEquals(0, landed.forgetCarrier(null));
        assertTrue(landed.isEmpty());
        assertTrue(landed.describe().contains("landed=0"), landed.describe());
    }

    @Test
    @DisplayName("the summary says how many hosts are held and from how many carriers")
    void describeReportsBothCounts() {
        landed.stow(List.of(
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100)),
                view(SECOND, 0, 0, 0, pos(200, 64, 200), pos(200, 64, 200))));

        String described = landed.describe();
        assertTrue(described.contains("landed=2"), described);
        assertTrue(described.contains("2 carrier(s)"), described);
    }

    // ------------------------------------- which path owns a position (the yield rule) --

    @Test
    @DisplayName("the world path draws only with a host AND a block - neither fact alone is enough")
    void worldPathDrawsNeedsBothFacts() {
        assertTrue(LandedHosts.worldPathDraws(true, true),
                "a host the sync knows about over a real block: the world path draws it");
        assertFalse(LandedHosts.worldPathDraws(true, false),
                "the sync has announced it but the block has not come back yet: the world path refuses");
        assertFalse(LandedHosts.worldPathDraws(false, true),
                "a block with no component of ours in it is not a host at all");
        assertFalse(LandedHosts.worldPathDraws(false, false), "nothing to draw there either way");
    }

    @Test
    @DisplayName("a landed record yields exactly when the world path draws, and never otherwise")
    void aLandedRecordDrawsItselfWheneverTheWorldPathCannot() {
        // The truth table: "the sync knows the position" and "the block is there" arrive at different
        // moments, so a record that yielded on the sync alone would be drawn by nothing.
        assertTrue(LandedHosts.fallbackDraws(false, false), "no world host, no block: it draws itself");
        assertTrue(LandedHosts.fallbackDraws(true, false),
                "the sync knows it but the block is still air: the record has to draw it");
        assertTrue(LandedHosts.fallbackDraws(false, true),
                "a block and no world host: the component is still only in the record");
        assertFalse(LandedHosts.fallbackDraws(true, true),
                "host and block: the world path draws, and yielding avoids a second shell");
    }

    @Test
    @DisplayName("the two rulers are complementary: every combination is owned by exactly one path")
    void exactlyOnePathOwnsEveryPosition() {
        for (boolean worldHost : new boolean[] {true, false}) {
            for (boolean blockPresent : new boolean[] {true, false}) {
                boolean world = LandedHosts.worldPathDraws(worldHost, blockPresent);
                boolean fallback = LandedHosts.fallbackDraws(worldHost, blockPresent);
                assertNotEquals(world, fallback, "host=" + worldHost + " block=" + blockPresent);
                assertTrue(world || fallback, "and one of them always draws: host=" + worldHost
                        + " block=" + blockPresent);
                assertFalse(world && fallback, "and never both: host=" + worldHost
                        + " block=" + blockPresent);
            }
        }
    }

    @Test
    @DisplayName("a record the world path cannot draw survives the world sync naming its position")
    void aRecordSurvivesASyncTheWorldPathCannotDrawYet() {
        // The order the client sees: the carrier is announced gone, the ordinary host sync names the
        // landing position at once, and the block only comes back a moment later.
        CarrierIndex.CarriedView host =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));
        landed.stow(List.of(host));

        assertTrue(landed.contains(pos(100, 64, 100)), "the record is held from the removal notice");
        // The sync has arrived (worldHost = true) and the block has not (blockPresent = false).
        assertFalse(LandedHosts.worldPathDraws(true, false));
        assertTrue(LandedHosts.fallbackDraws(true, false));
        assertTrue(landed.contains(pos(100, 64, 100)),
                "and nothing has dropped the record, because the world path cannot draw there yet");

        // A moment later the block is back: now - and only now - the record may go.
        assertTrue(LandedHosts.worldPathDraws(true, true));
        assertEquals(1, landed.forgetAt(pos(100, 64, 100)));
        assertFalse(landed.contains(pos(100, 64, 100)));
    }

    // ---------------------------------- multi-block structures: the coordinate is the key --

    @Test
    @DisplayName("two hosts at different local coordinates keep separate records at separate landings")
    void aMultiBlockStructureKeepsOneRecordPerLocalCoordinate() {
        // Two components inside one structure, at different local coordinates, put down on different
        // blocks: folding happens per carrier plus local coordinate, not per carrier.
        CarrierIndex.CarriedView first =
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView second =
                view(CARRIER, 1, 0, 2, pos(101, 64, 100), pos(103, 64, 100));

        assertEquals(2, landed.stow(List.of(first, second)));

        assertEquals(2, landed.size());
        assertEquals(1, landed.carrierCount(), "one structure put both of them down");
        assertTrue(landed.contains(pos(100, 64, 100)));
        assertTrue(landed.contains(pos(103, 64, 100)));
        assertFalse(landed.contains(pos(101, 64, 100)),
                "the origin of the second host is not where it was put down");
    }

    @Test
    @DisplayName("the same local coordinate on two structures is two records, not one")
    void theSameLocalCoordinateOnTwoStructuresIsNotFolded() {
        // Why the key is carrier plus local coordinate: two machines parked next to each other both have
        // a host at their own (1,0,2).
        CarrierIndex.CarriedView mine =
                view(CARRIER, 1, 0, 2, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView theirs =
                view(SECOND, 1, 0, 2, pos(200, 64, 200), pos(200, 64, 200));

        assertEquals(2, landed.stow(List.of(mine, theirs)));

        assertEquals(2, landed.size());
        assertEquals(2, landed.carrierCount());
        assertEquals(1, landed.forgetCarrier(CARRIER));
        assertEquals(1, landed.size(), "the other structure's host at the same local coordinate stays");
    }

    // ------------------------------------- which hosts a carrier message says have been put down --

    @Test
    @DisplayName("a carrier that is gone has put everything it held down")
    void aGoneCarrierPutsEverythingDown() {
        List<CarrierIndex.CarriedView> held = List.of(
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100)),
                view(CARRIER, 1, 0, 0, pos(101, 64, 100), pos(101, 64, 100)));

        assertEquals(held, LandedHosts.departed(held, List.of(), true));
    }

    @Test
    @DisplayName("a host missing from a still-flying carrier's list is the one that was put down")
    void aHostMissingFromTheListWasPutDown() {
        // The per-host put-down path: the carrier still has a pose and its list is one entry shorter; that
        // entry is the block that just came home, and it is not a world host the client can draw.
        CarrierIndex.CarriedView stays =
                view(CARRIER, 0, 0, 0, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView leaves =
                view(CARRIER, 1, 0, 0, pos(101, 64, 100), pos(101, 64, 100));

        assertEquals(List.of(leaves), LandedHosts.departed(List.of(stays, leaves), List.of(stays), false));
        assertTrue(LandedHosts.departed(List.of(stays), List.of(stays), false).isEmpty(),
                "a host that is still listed is still riding");
    }

    @Test
    @DisplayName("another carrier's host at the same local coordinate is not mistaken for this one")
    void identityIncludesTheCarrier() {
        CarrierIndex.CarriedView mine =
                view(CARRIER, 1, 0, 0, pos(100, 64, 100), pos(100, 64, 100));
        CarrierIndex.CarriedView theirs =
                view(SECOND, 1, 0, 0, pos(200, 64, 200), pos(200, 64, 200));

        assertEquals(List.of(mine), LandedHosts.departed(List.of(mine), List.of(theirs), false));
    }

    @Test
    @DisplayName("nothing held means nothing to put down, and null is safe")
    void departedHandlesNothing() {
        assertTrue(LandedHosts.departed(List.of(), List.of(), true).isEmpty());
        assertTrue(LandedHosts.departed(null, null, false).isEmpty());
    }
}
