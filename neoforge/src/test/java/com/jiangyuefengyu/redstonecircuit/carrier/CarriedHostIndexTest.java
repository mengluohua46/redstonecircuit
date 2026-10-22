package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Tests the bookkeeping of taking a host's data out of the world, keeping it while a structure carries
 * it, and putting it back exactly once - the promise that it follows the machine, comes back, and
 * nothing is lost.
 *
 * <p>What is worth pinning is what would otherwise look like a mysterious in-game bug: the original
 * position being remembered at all, a second callback not writing twice, an occupied landing position
 * not being written over, and the data still being readable at the position the client was last told
 * about. Deliberately free of any Minecraft level: the index reaches the store only through
 * {@link CarriedHostIndex.SlotWorld}, which a test implements with a hash map.
 */
class CarriedHostIndexTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));
    private static final CarrierId OTHER =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000beef"));
    private static final CarrierId SUB_LEVEL =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000ab00"));

    /** A stand-in for the world store: a map of positions to components. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }

        int size() {
            return slots.size();
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    /**
     * A provisional identity, as the compat half makes one.
     *
     * <p>Written out here rather than through {@code CarrierIdentity.provisional} so the re-key tests
     * describe a real version-8 UUID - which is the property {@code CarrierIdentity} guarantees - and do
     * not accidentally pass because a helper changed shape.
     */
    private static final CarrierId PROVISIONAL = CarrierId.contraption(
            UUID.fromString("00000000-0000-8000-8000-00000000a001"));

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static Slot repeater(Direction facing) {
        Slot slot = new Slot(ComponentType.REPEATER);
        slot.facing = facing;
        return slot;
    }

    /**
     * The landing function of a contraption whose anchor is at {@code anchor}, then moved by
     * {@code (dx, dy, dz)}.
     *
     * <p>This mirrors Create, whose local coordinates are measured from the anchor and whose pose then
     * carries that anchor somewhere else: {@code world = anchor + localPos + motion}. Spelling it out
     * is what makes these tests about the index rather than about arithmetic nobody can check by eye.
     */
    private static CarriedHostIndex.Landing moved(BlockPos anchor, int dx, int dy, int dz) {
        return local -> pos(anchor.getX() + local.getX() + dx,
                anchor.getY() + local.getY() + dy,
                anchor.getZ() + local.getZ() + dz);
    }

    // ------------------------------------------------------------- pick-up --

    @Test
    @DisplayName("picking a host up moves its data out of the world and keeps a snapshot")
    void pickupMovesTheDataOut() {
        Slot component = dust(11);
        world.set(pos(10, 64, 10), component);

        CarriedHostIndex.ClaimOutcome outcome =
                index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        assertTrue(outcome.performed(), "the claim did the work");
        assertEquals(pos(10, 64, 10), outcome.origin(), "and remembers where the block was");
        assertEquals(pos(1, 0, 2), outcome.localPos());
        assertNull(world.get(pos(10, 64, 10)), "the world entry really is gone, not copied");
        assertEquals(1, index.carriedCount());
        assertTrue(index.isCarried(pos(10, 64, 10)));
    }

    @Test
    @DisplayName("a block with nothing inside it is not an error, just a no-op")
    void emptyBlocksAreCheapNoOps() {
        // This is the common case: the hook is registered per block *type*, so every plain stone in a
        // contraption calls in here.
        CarriedHostIndex.ClaimOutcome outcome =
                index.claim(CONTRAPTION, pos(4, 0, 0), pos(20, 64, 20), world);

        assertFalse(outcome.claimed());
        assertFalse(outcome.performed());
        assertTrue(index.isEmpty());
        assertEquals(0, world.size());
    }

    @Test
    @DisplayName("claiming the same block twice does not overwrite the snapshot")
    void claimIsIdempotent() {
        world.set(pos(10, 64, 10), dust(11));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);

        // The world entry is gone now, so a naive second claim would find "nothing to carry" and lose
        // the data; the index has to answer "already carried" instead.
        CarriedHostIndex.ClaimOutcome again =
                index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);

        assertTrue(again.claimed());
        assertTrue(again.alreadyCarried());
        assertFalse(again.performed(), "no second move was logged");
        assertEquals(11, again.slot().power, "and the snapshot is still the good one");
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("the data is still readable at the position it was taken from")
    void carriedDataIsStillReadableAtItsOrigin() {
        world.set(pos(10, 64, 10), dust(11));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);

        // The world store has nothing, but "what is inside this block" must not change its answer
        // merely because the block is away - the renderer, the wrench and the tests all ask this.
        assertNotNull(index.slotAt(pos(10, 64, 10), world));
        assertEquals(11, index.slotAt(pos(10, 64, 10), world).power);
        assertNull(index.slotAt(pos(11, 64, 10), world), "a position with nothing in it stays empty");
    }

    @Test
    @DisplayName("two carriers never see each other's hosts")
    void carriersAreIsolated() {
        world.set(pos(10, 64, 10), dust(15));
        world.set(pos(20, 64, 20), dust(3));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);
        index.claim(OTHER, pos(0, 0, 0), pos(20, 64, 20), world);

        assertEquals(1, index.liveHostsOf(CONTRAPTION).size());
        assertEquals(1, index.liveHostsOf(OTHER).size());
        assertEquals(15, index.liveHostsOf(CONTRAPTION).get(0).slot().power);
        assertEquals(3, index.liveHostsOf(OTHER).get(0).slot().power);
        assertEquals(CONTRAPTION, index.carrierHolding(pos(10, 64, 10)));
        assertEquals(OTHER, index.carrierHolding(pos(20, 64, 20)));
    }

    // -------------------------------------------------------------- put down --

    @Test
    @DisplayName("putting a host down writes it back where the structure says, and remembers the origin")
    void releaseWritesBackAtTheLandingPosition() {
        // The contraption's anchor sits at 9,64,8, so the block at local (1,0,2) is the one at world
        // 10,64,10 - the anchor plus the local coordinate, exactly as Create computes it.
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), repeater(Direction.EAST));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        // The machine has moved 40 blocks along x, 5 up and 2 along z by the time it is set down.
        CarriedHostIndex.ReleaseOutcome outcome =
                index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 40, 5, 2), world);

        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, outcome.status());
        assertEquals(1, outcome.moved());
        assertEquals(0, outcome.conflicts());
        assertEquals(pos(50, 69, 12), outcome.landing());
        Slot landed = world.get(pos(50, 69, 12));
        assertNotNull(landed, "the component is in the world again");
        assertEquals(ComponentType.REPEATER, landed.type);
        assertEquals(Direction.EAST, landed.facing, "with its settings intact");

        CarriedHost host = index.at(CONTRAPTION, pos(1, 0, 2));
        assertTrue(host.landed());
        assertEquals(pos(10, 64, 10), host.origin(), "the original position is still known");
        assertEquals(pos(50, 69, 12), host.at(), "and the host now answers at the landing position");
        assertEquals(0, index.carriedCount());
        assertFalse(index.isCarried(pos(10, 64, 10)));
    }

    @Test
    @DisplayName("a repeated stopMoving call does not write a second time")
    void releaseIsIdempotent() {
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        CarriedHostIndex.ReleaseOutcome first =
                index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 5, 0, 0), world);
        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, first.status());
        assertEquals(pos(15, 64, 10), first.landing());

        // Create fires this four to five times for one motion, with identical arguments.
        for (int i = 0; i < 4; i++) {
            CarriedHostIndex.ReleaseOutcome repeat =
                    index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 5, 0, 0), world);
            assertEquals(CarriedHostIndex.ReleaseStatus.ALREADY_RELEASED, repeat.status(),
                    "call " + (i + 2) + " must be recognised as a repeat");
            assertFalse(repeat.wrote(), "and must not write anything");
        }
        assertEquals(9, world.get(pos(15, 64, 10)).power);
        assertEquals(1, world.size(), "one component, one place");
    }

    @Test
    @DisplayName("a landing position somebody else has taken is never written over")
    void conflictFallsBackToTheOrigin() {
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        // While the machine is away, somebody builds something where it is going to land: anchor plus
        // local (1,0,2) plus 30 along x is 40,64,10.
        world.set(pos(40, 64, 10), dust(15));

        CarriedHostIndex.ReleaseOutcome outcome =
                index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 30, 0, 0), world);

        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED_AT_ORIGIN, outcome.status());
        assertEquals(1, outcome.conflicts());
        assertEquals(pos(40, 64, 10), outcome.landing(), "where it wanted to go");
        assertEquals(15, world.get(pos(40, 64, 10)).power, "the stranger's component is untouched");
        assertEquals(9, world.get(pos(10, 64, 10)).power, "and ours is back where it came from");
        assertTrue(index.at(CONTRAPTION, pos(1, 0, 2)).conflict(),
                "the fallback is recorded, so the log can say so");
        assertEquals(pos(10, 64, 10), index.at(CONTRAPTION, pos(1, 0, 2)).at(),
                "and the host answers at the fallback position");
    }

    @Test
    @DisplayName("when neither position is free the data stays carried instead of being lost")
    void heldWhenThereIsNowhereToGo() {
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        // Both the landing position and the block's own position are taken now.
        world.set(pos(40, 64, 10), dust(1));
        world.set(pos(10, 64, 10), dust(2));

        CarriedHostIndex.ReleaseOutcome outcome =
                index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 30, 0, 0), world);

        assertEquals(CarriedHostIndex.ReleaseStatus.HELD, outcome.status());
        assertEquals(1, outcome.held());
        assertEquals(0, outcome.moved());
        assertFalse(index.at(CONTRAPTION, pos(1, 0, 2)).landed(),
                "the entry stays live, so a later release can still complete");
        assertEquals(9, index.at(CONTRAPTION, pos(1, 0, 2)).slot().power,
                "and the component is still held, not dropped");
        assertEquals(2, world.get(pos(10, 64, 10)).power, "nothing was overwritten either");
    }

    @Test
    @DisplayName("putting everything down at once returns every host to its own origin")
    void releaseAllUsesOrigins() {
        world.set(pos(1, 64, 1), dust(1));
        world.set(pos(2, 64, 1), dust(2));
        world.set(pos(3, 64, 1), dust(3));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(1, 64, 1), world);
        index.claim(CONTRAPTION, pos(1, 0, 0), pos(2, 64, 1), world);
        index.claim(CONTRAPTION, pos(2, 0, 0), pos(3, 64, 1), world);

        CarriedHostIndex.ReleaseOutcome outcome = index.releaseAll(CONTRAPTION, null, world);

        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, outcome.status());
        assertEquals(3, outcome.moved());
        assertEquals(1, world.get(pos(1, 64, 1)).power);
        assertEquals(2, world.get(pos(2, 64, 1)).power);
        assertEquals(3, world.get(pos(3, 64, 1)).power);
        assertEquals(0, index.carriedCount());

        CarriedHostIndex.ReleaseOutcome again = index.releaseAll(CONTRAPTION, null, world);
        assertEquals(CarriedHostIndex.ReleaseStatus.ALREADY_RELEASED, again.status());
        assertEquals(0, again.moved());
    }

    @Test
    @DisplayName("an unknown carrier and an unknown local coordinate are reported, not guessed at")
    void unknownThingsAreReported() {
        CarriedHostIndex.ReleaseOutcome noCarrier =
                index.release(CONTRAPTION, pos(0, 0, 0), null, world);
        assertEquals(CarriedHostIndex.ReleaseStatus.UNKNOWN_CARRIER, noCarrier.status());

        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);
        CarriedHostIndex.ReleaseOutcome noHost =
                index.release(CONTRAPTION, pos(9, 9, 9), null, world);
        assertEquals(CarriedHostIndex.ReleaseStatus.NOT_CARRIED, noHost.status());
    }

    // ------------------------------------------------------ the reverse map --

    @Test
    @DisplayName("a position stays readable after the release, until the record is dropped")
    void releasedPositionsStayReadableForTheClient() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);
        index.release(CONTRAPTION, pos(0, 0, 0), null, world);
        // The world entry is taken away again, so what the store answers can only come from the
        // carrier record: that is exactly the window in which a client that has not been told yet
        // still needs the right answer.
        world.set(pos(10, 64, 10), null);

        assertTrue(index.isDisplaced(pos(10, 64, 10)));
        assertNotNull(index.slotAt(pos(10, 64, 10), world), "the record answers for it");
        assertEquals(9, index.slotAt(pos(10, 64, 10), world).power);

        assertEquals(List.of(CONTRAPTION), index.forgetReleased());
        assertFalse(index.isDisplaced(pos(10, 64, 10)),
                "and once the carrier is forgotten, the position is ordinary again");
        assertNull(index.slotAt(pos(10, 64, 10), world),
                "with nothing in the world and no record left, it really is empty");
    }

    @Test
    @DisplayName("forgetReleased never drops a carrier that is still holding something")
    void forgettingIsSafe() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);

        assertTrue(index.forgetReleased().isEmpty(), "nothing was released, so nothing was dropped");

        assertEquals(1, index.carriedCount(), "live data is never forgotten");
        assertTrue(index.isCarried(pos(10, 64, 10)));
    }

    @Test
    @DisplayName("forgetReleased reports each dropped carrier exactly once")
    void forgettingReportsOncePerCarrier() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);
        index.release(CONTRAPTION, pos(0, 0, 0), null, world);

        assertEquals(List.of(CONTRAPTION), index.forgetReleased(),
                "the caller has to hear about the removal so it can announce it");
        assertTrue(index.forgetReleased().isEmpty(), "and a second pass has nothing left to report");
    }

    @Test
    @DisplayName("clear drops everything")
    void clearForgetsEverything() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(0, 0, 0), pos(10, 64, 10), world);
        index.clear();

        assertTrue(index.isEmpty());
        assertEquals(0, index.carrierCount());
        assertFalse(index.isCarried(pos(10, 64, 10)));
    }

    // ------------------------------------------------------------------ NBT --

    @Test
    @DisplayName("a save taken in mid-flight still knows what is riding what")
    void saveRoundTripKeepsCarriedHosts() {
        world.set(pos(10, 64, 10), repeater(Direction.WEST));
        index.claim(SUB_LEVEL, pos(2, 3, 4), pos(10, 64, 10), world);

        CompoundTag tag = new CompoundTag();
        index.saveInto(tag);
        CarriedHostIndex loaded = CarriedHostIndex.load(tag);

        assertEquals(1, loaded.carriedCount());
        assertTrue(loaded.isCarried(pos(10, 64, 10)));
        CarriedHost host = loaded.at(SUB_LEVEL, pos(2, 3, 4));
        assertNotNull(host);
        assertEquals(pos(10, 64, 10), host.origin());
        assertEquals(ComponentType.REPEATER, host.slot().type);
        assertEquals(Direction.WEST, host.slot().facing);
        assertEquals(SUB_LEVEL.kind(), loaded.carrierHolding(pos(10, 64, 10)).kind(),
                "and the carrier is still known to be a sub-level rather than a contraption");
    }

    @Test
    @DisplayName("a loaded index can still put its hosts down properly")
    void loadedIndexCanRelease() {
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), dust(6));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        CompoundTag tag = new CompoundTag();
        index.saveInto(tag);

        CarriedHostIndex loaded = CarriedHostIndex.load(tag);
        CarriedHostIndex.ReleaseOutcome outcome =
                loaded.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 50, 0, 0), world);

        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, outcome.status());
        assertEquals(6, world.get(pos(60, 64, 10)).power);
    }

    @Test
    @DisplayName("an empty or absent tag loads as an empty index, not as a failure")
    void emptyTagLoadsEmpty() {
        assertTrue(CarriedHostIndex.load(null).isEmpty());
        assertTrue(CarriedHostIndex.load(new CompoundTag()).isEmpty());
    }

    @Test
    @DisplayName("a repeated callback after a reload is still recognised as a repeat")
    void repeatedCallbackSurvivesAReload() {
        BlockPos anchor = pos(9, 64, 8);
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        index.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 5, 0, 0), world);
        assertEquals(9, world.get(pos(15, 64, 10)).power);

        CompoundTag tag = new CompoundTag();
        index.saveInto(tag);
        CarriedHostIndex loaded = CarriedHostIndex.load(tag);

        CarriedHostIndex.ReleaseOutcome repeat =
                loaded.release(CONTRAPTION, pos(1, 0, 2), moved(anchor, 5, 0, 0), world);
        assertEquals(CarriedHostIndex.ReleaseStatus.ALREADY_RELEASED, repeat.status());
        assertEquals(9, world.get(pos(15, 64, 10)).power);
        assertEquals(1, world.size());
    }

    // ----------------------------------------------------------- the summary --

    @Test
    @DisplayName("the summary names the local coordinates an operator can check in game")
    void summaryListsLocalCoordinates() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(CONTRAPTION, pos(1, 2, 3), pos(10, 64, 10), world);

        String summary = index.summary(CONTRAPTION);

        assertTrue(summary.contains("hosts=1"), summary);
        assertTrue(summary.contains("riding=1"), summary);
        assertTrue(summary.contains(pos(1, 2, 3).toShortString()), summary);
    }

    // --------------------------------------------------- the solve additions --

    @Test
    @DisplayName("a solved value can be written back without moving or releasing anything")
    void updateSlotKeepsEverythingElse() {
        world.set(pos(10, 64, 10), dust(4));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        // The solver mutates the slot the record already holds - that is exactly what happens in game,
        // where the node the environment hands out is that slot.
        CarriedHost before = index.at(CONTRAPTION, pos(1, 0, 2));
        before.slot().power = 13;
        CarriedHost updated = index.updateSlot(CONTRAPTION, pos(1, 0, 2), before.slot());

        assertNotNull(updated);
        assertEquals(13, updated.slot().power);
        assertEquals(pos(10, 64, 10), updated.origin(), "the position it came from is untouched");
        assertEquals(pos(1, 0, 2), updated.localPos());
        assertFalse(updated.landed(), "and it is still riding, not released");
        assertEquals(13, index.slotAt(pos(10, 64, 10), world).power,
                "the reverse map was re-pointed, so a world-position read sees the new value too");
        assertEquals(0, world.size(), "nothing was written into the world");
    }

    @Test
    @DisplayName("updateSlot does nothing at all for a coordinate this carrier does not hold")
    void updateSlotIgnoresUnknownCoordinates() {
        world.set(pos(10, 64, 10), dust(4));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        assertNull(index.updateSlot(CONTRAPTION, pos(9, 9, 9), dust(15)));
        assertNull(index.updateSlot(OTHER, pos(1, 0, 2), dust(15)));
        assertEquals(4, index.at(CONTRAPTION, pos(1, 0, 2)).slot().power);
    }

    @Test
    @DisplayName("a host can be emptied: the record stays, the component goes, and it is exactly once")
    void aHostCanBeEmptied() {
        world.set(pos(10, 64, 10), dust(4));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);

        assertTrue(index.removeSlot(CONTRAPTION, pos(1, 0, 2)),
                "the host is still carried and now holds nothing - the state a retrieval leaves");
        CarriedHost emptied = index.at(CONTRAPTION, pos(1, 0, 2));
        assertNotNull(emptied, "the HOST is still the structure's: taking a component out is not a release");
        assertNull(emptied.slot());
        assertFalse(emptied.landed());
        assertEquals(pos(10, 64, 10), emptied.origin(), "and nothing else about the record moved");
        assertNull(index.slotAt(pos(10, 64, 10), world),
                "the reverse map was re-pointed, so the world-position read sees the empty host too");
        assertTrue(index.removeSlot(CONTRAPTION, pos(1, 0, 2)),
                "emptying an already-empty host is not an error: it is the same state again");
        assertFalse(index.removeSlot(CONTRAPTION, pos(9, 9, 9)),
                "but a coordinate this carrier does not hold is not");
        assertEquals(0, world.size(), "and nothing was written into the world either way");
    }

    @Test
    @DisplayName("forgetting one carrier drops exactly its own hosts")
    void forgettingOneCarrierIsExact() {
        world.set(pos(10, 64, 10), dust(9));
        world.set(pos(20, 64, 20), dust(3));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        index.claim(OTHER, pos(1, 0, 2), pos(20, 64, 20), world);

        assertEquals(1, index.forgetCarrier(CONTRAPTION));

        assertFalse(index.isCarried(pos(10, 64, 10)), "the forgotten carrier's host is gone");
        assertFalse(index.isDisplaced(pos(10, 64, 10)), "and it answers at no position any more");
        assertTrue(index.isCarried(pos(20, 64, 20)), "the other carrier is untouched");
        assertEquals(1, index.carrierCount());
        assertEquals(0, index.forgetCarrier(CONTRAPTION), "forgetting twice is harmless");
    }

    // ------------------------------------------- re-labelling (two-phase identity) --

    @Test
    @DisplayName("re-keying moves a claim onto the entity's name and keeps every fact about it")
    void rekeyMovesTheClaimVerbatim() {
        world.set(pos(10, 64, 10), dust(9));
        world.set(pos(12, 64, 10), repeater(Direction.NORTH));
        index.claim(PROVISIONAL, pos(1, 0, 2), pos(10, 64, 10), world);
        index.claim(PROVISIONAL, pos(3, 0, 2), pos(12, 64, 10), world);
        CarriedHost before = index.at(PROVISIONAL, pos(1, 0, 2));

        assertEquals(2, index.rekey(PROVISIONAL, CONTRAPTION));

        assertNull(index.at(PROVISIONAL, pos(1, 0, 2)), "the provisional name no longer answers");
        assertEquals(0, index.hostsOf(PROVISIONAL).size());
        CarriedHost after = index.at(CONTRAPTION, pos(1, 0, 2));
        assertNotNull(after);
        assertEquals(before.origin(), after.origin(), "a re-key must not move the host");
        assertEquals(before.localPos(), after.localPos());
        assertEquals(before.slot().power, after.slot().power);
        assertFalse(after.landed(), "it is still riding");
        assertTrue(index.isCarried(pos(10, 64, 10)), "the reverse map followed the record");
        assertTrue(index.isCarried(pos(12, 64, 10)));
        assertEquals(1, index.carrierCount(), "one carrier, under its real name");
        assertEquals(2, index.carriedCount());
    }

    @Test
    @DisplayName("re-keying nothing is harmless and answers zero")
    void rekeyOfAnUnknownCarrierIsHarmless() {
        assertEquals(0, index.rekey(PROVISIONAL, CONTRAPTION));
        assertEquals(0, index.rekey(CONTRAPTION, CONTRAPTION), "a self re-key is a no-op");
        assertEquals(0, index.rekey(null, CONTRAPTION));
        assertEquals(0, index.rekey(CONTRAPTION, null));
        assertTrue(index.isEmpty());
    }

    @Test
    @DisplayName("after a re-key, a repeated release under the entity's name is ALREADY_RELEASED")
    void releaseAfterRekeyIsIdempotent() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(PROVISIONAL, pos(1, 0, 2), pos(10, 64, 10), world);
        index.rekey(PROVISIONAL, CONTRAPTION);

        CarriedHostIndex.ReleaseOutcome first =
                index.release(CONTRAPTION, pos(1, 0, 2), null, world);
        assertEquals(CarriedHostIndex.ReleaseStatus.LANDED, first.status());
        assertEquals(1, first.moved());

        // The second, third and fourth stopMoving callbacks of one motion.
        for (int repeat = 0; repeat < 3; repeat++) {
            CarriedHostIndex.ReleaseOutcome again =
                    index.release(CONTRAPTION, pos(1, 0, 2), null, world);
            assertEquals(CarriedHostIndex.ReleaseStatus.ALREADY_RELEASED, again.status(),
                    "a repeat must not write the component back a second time");
            assertEquals(0, again.moved());
            assertEquals(1, again.alreadyReleased());
        }
        assertEquals(1, world.size(), "exactly one copy of the component is in the world");
    }

    @Test
    @DisplayName("re-keying carries already-put-down records across, so they still answer where they landed")
    void rekeyKeepsLandedRecords() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(PROVISIONAL, pos(1, 0, 2), pos(10, 64, 10), world);
        index.release(PROVISIONAL, pos(1, 0, 2), null, world);

        assertEquals(1, index.rekey(PROVISIONAL, CONTRAPTION));

        CarriedHost host = index.at(CONTRAPTION, pos(1, 0, 2));
        assertNotNull(host);
        assertTrue(host.landed());
        assertEquals(pos(10, 64, 10), host.at());
        assertFalse(index.isCarried(pos(10, 64, 10)), "put down is not carried");
        assertTrue(index.isDisplaced(pos(10, 64, 10)));
        assertEquals(9, index.slotAt(pos(10, 64, 10), world).power,
                "the world store answers at the landing position");
    }

    @Test
    @DisplayName("a live claim under the real name wins over a stale put-down record at the same coordinate")
    void rekeyPrefersTheLiveClaim() {
        // A carrier already under its real name, with an old motion that has been put down.
        world.set(pos(10, 64, 10), dust(4));
        index.claim(CONTRAPTION, pos(1, 0, 2), pos(10, 64, 10), world);
        index.release(CONTRAPTION, pos(1, 0, 2), null, world);

        // The same contraption object reused for a new machine, claimed before its entity exists and so
        // filed under a provisional name. Same carrier object, same local coordinate - the collision the
        // re-key has to resolve.
        world.set(pos(30, 64, 30), dust(15));
        index.claim(PROVISIONAL, pos(1, 0, 2), pos(30, 64, 30), world);
        assertEquals(1, index.rekey(PROVISIONAL, CONTRAPTION), "the live claim is the one that moves");

        CarriedHost host = index.at(CONTRAPTION, pos(1, 0, 2));
        assertNotNull(host);
        assertFalse(host.landed(), "the live claim survives and is the one the coordinate answers with");
        assertEquals(pos(30, 64, 30), host.origin());
        assertEquals(15, host.slot().power);
        assertEquals(1, index.hostsOf(CONTRAPTION).size(), "the stale record is gone, not shadowed");
    }

    @Test
    @DisplayName("the reverse map is consistent after a re-key, so no position answers with a stale record")
    void rekeyLeavesNoStaleReverseEntry() {
        world.set(pos(10, 64, 10), dust(9));
        index.claim(PROVISIONAL, pos(1, 0, 2), pos(10, 64, 10), world);

        assertEquals(1, index.rekey(PROVISIONAL, CONTRAPTION));

        // Every position the index answers for has a record behind it, and that record belongs to the
        // carrier under its real name - which is what makes a world-position read agree with a
        // carrier-local read after the rename.
        assertEquals(1, index.displacedWorldPositions().size());
        assertEquals(pos(10, 64, 10), index.displacedWorldPositions().get(0));
        assertNotNull(index.slotAt(pos(10, 64, 10), world));
        assertEquals(CONTRAPTION, index.carrierHolding(pos(10, 64, 10)),
                "the carrier holding the origin is the re-labelled one");
        assertNotNull(index.at(CONTRAPTION, pos(1, 0, 2)));
    }

    // ------------------------------------------------- one host, one carrier --

    /** The UUID a physics-assembled structure shares between its contraption entity and its sub-level. */
    private static final UUID SHARED = UUID.fromString("6c60aeef-6583-4fca-8d65-71457e69b75a");

    @Test
    @DisplayName("a shared UUID stays a sub-level: the Create half cannot re-file it as a contraption")
    void theKindOfASharedUuidStaysSubLevel() {
        // A physics-assembled structure has ONE UUID shared by a Create contraption entity and a Sable
        // sub-level, so both halves reach the index through the same key: whatever the Create half files,
        // that key must keep answering as the sub-level it really is.
        CarrierId create = CarrierId.contraption(SHARED);
        CarrierId sable = CarrierId.subLevel(SHARED);

        world.set(pos(10, 64, 10), dust(12));
        assertTrue(index.claim(sable, pos(20_481_032, 127, 20_524_040), pos(10, 64, 10), world)
                .performed());
        assertEquals(sable, index.carriers().get(0));

        // The Create half claims the same host. Whatever it files, the UUID must keep answering as the
        // sub-level it really is.
        world.set(pos(10, 64, 10), dust(12));
        index.claim(create, pos(20_481_032, 127, 20_524_040), pos(10, 64, 10), world);

        assertEquals(List.of(sable), index.carriers(),
                "the structure is a Sable sub-level and must still be named as one: the c: id is the name "
                        + "the rest of the mod asks about it by");
        assertEquals(sable, index.carrierHolding(pos(10, 64, 10)));
        assertEquals(1, index.carriedCount(), "and exactly one host is riding it");
    }

    @Test
    @DisplayName("a wholesale release never takes a host another carrier is holding at the same position")
    void releaseAllRefusesAHostAnotherCarrierHolds() {
        // A host whose world position is answered by another carrier's live record must not be written
        // back into the world by this one; the entry it refuses is counted as a conflict so the log can
        // show it.
        CarrierId mine = CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000f00d"));
        CarrierId theirs = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000000abc"));

        // Two carriers, two local coordinates, one world position: the same component seen from two
        // structures that both claim it is inside them.
        world.set(pos(10, 64, 10), dust(12));
        assertTrue(index.claim(mine, pos(1, 0, 2), pos(10, 64, 10), world).performed());
        world.set(pos(10, 64, 10), dust(12));
        assertTrue(index.claim(theirs, pos(9, 0, 9), pos(10, 64, 10), world).performed());

        assertEquals(theirs, index.carrierHolding(pos(10, 64, 10)),
                "the later claim is the one the position answers with");

        CarriedHostIndex.ReleaseOutcome outcome = index.releaseAll(mine, null, world);

        assertEquals(0, outcome.moved(),
                "nothing may be written back while another carrier holds that position");
        assertEquals(0, world.size(), "and the world store must stay empty");
        assertTrue(outcome.conflicts() > 0, "the refusal is a fact the log has to be able to see");
        assertFalse(outcome.wrote());
        assertTrue(index.isCarried(pos(10, 64, 10)));
        assertEquals(1, index.liveHostsOf(theirs).size());
    }

    @Test
    @DisplayName("forgetting one carrier leaves another carrier's host at the same position drawable")
    void forgettingOneCarrierDoesNotStrandTheOther() {
        CarrierId mine = CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000f00d"));
        CarrierId theirs = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-000000000abc"));

        world.set(pos(10, 64, 10), dust(12));
        index.claim(mine, pos(1, 0, 2), pos(10, 64, 10), world);
        world.set(pos(10, 64, 10), dust(12));
        index.claim(theirs, pos(9, 0, 9), pos(10, 64, 10), world);

        index.forgetCarrier(mine);

        // The other carrier's record is the one answering at the world position, so the reverse map must
        // still hold it: a forget that removed the entry blindly would leave the client with nothing to
        // draw and no counter to explain it.
        assertEquals(1, index.displacedWorldPositions().size());
        assertNotNull(index.slotAt(pos(10, 64, 10), world));
        assertTrue(index.isCarried(pos(10, 64, 10)));
        assertEquals(theirs, index.carrierHolding(pos(10, 64, 10)));
    }

    // ------------------------------------- the world block a carried host answers at --

    /**
     * A carried host answers at <b>the block its structure is drawn in</b>, and this is the functional half of
     * that promise rather than the drawing half: {@code slotAt}, {@code isCarried} and every reader built on
     * them (the world-side signal query, the interaction that decides what it is looking at, the palette
     * overlay) ask the reverse map by a world block. If the record is filed at a different block, the
     * component is judged to belong to a neighbouring cell - it is interacted with there, it supplies signal
     * there, and only one of the two blocks ever shows it, because there was only ever one record.
     *
     * <p>The two answers disagree the moment the structure is turned: a pose transforms block <b>corners</b>,
     * so rounding a corner puts the host one block away on the axes the rotation carried the half block onto.
     */
    @Test
    @DisplayName("a carried host answers at the block its structure is drawn in, not the block beside it")
    void aCarriedHostAnswersWhereItIsDrawn() {
        CarrierPose pose = CarrierPose.of(new Vector3d(100.4, 64.0, 200.6),
                new Quaterniond().rotationY(Math.PI));
        BlockPos local = pos(3, 0, 5);

        // The origin the filing path records for that cell.
        BlockPos origin = WorldComponentAdoption.originFor(pose, local);
        assertNotNull(origin);

        world.set(origin, dust(0));
        assertTrue(index.claim(CONTRAPTION, local, origin, world).claimed());

        BlockPos drawn = CarrierProjection.project(pose, local);
        assertNotNull(index.slotAt(drawn, world),
                "the block the structure is drawn in is the block that must answer for its component: "
                        + "origin=" + origin.toShortString() + " drawn=" + drawn.toShortString());
        assertTrue(index.isCarried(drawn), "and the host is displaced there, not beside it");
        assertNull(index.slotAt(CarrierProjection.project(pose, local.east()), world),
                "the cell next to it must not answer for this host");
    }
}
