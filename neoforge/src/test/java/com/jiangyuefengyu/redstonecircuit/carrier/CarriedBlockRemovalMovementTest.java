package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.CarriedTool;

import net.minecraft.core.BlockPos;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Breaking a carried block that a structure has <em>moved</em> must behave exactly as breaking one that has
 * not moved: the break path must not read a coordinate that stopped being true when the structure moved.
 *
 * <p>{@link CarriedBlockRemoval#resolve} takes the host's <b>current</b> world position as an argument rather
 * than deriving one from the clicked position, so the drop is the current projection - not the {@code origin}
 * the record was captured with, and not the position an earlier pose would name. The record is consumed and
 * discarded rather than released, so nothing is written back into a block that has just been destroyed.
 */
class CarriedBlockRemovalMovementTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000005001"));

    /** Where the block stood when the structure picked it up. */
    private static final BlockPos ORIGIN = new BlockPos(10, 64, 10);

    /** The host's coordinate inside the structure. */
    private static final BlockPos LOCAL = new BlockPos(1, 0, 2);

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    /** The world store, as a map - the same stand-in the other index tests use. */
    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    private static CarrierPose poseAt(double x, double y, double z) {
        return CarrierPose.at(new Vector3d(x, y, z));
    }

    /** The record the sweep is looking at, by the carrier and coordinate it is keyed by. */
    private CarriedHost record() {
        return index.at(MINE, LOCAL);
    }

    /**
     * The pose the structure has <b>before</b> it moves: it sits where the host was picked up, so the
     * host projects back onto its own origin and the two disagree only once it has moved.
     */
    private static CarrierPose still() {
        return poseAt(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ());
    }

    /** The pose after "move to a machine bay far away and turn thirty degrees". */
    private static CarrierPose moved() {
        return CarrierPose.of(new Vector3d(2775.0, 77.0, -1395.0),
                new Quaterniond().rotateY(Math.toRadians(30.0)));
    }

    /** Picks a component up into the structure, the way the assembly callback does. */
    private void carry(ComponentType type) {
        assertNull(world.get(ORIGIN), "one host per setup: " + ORIGIN);
        world.put(ORIGIN.asLong(), new Slot(type));
        index.claim(MINE, LOCAL, ORIGIN, new MapWorld(world));
        assertNotNull(index.at(MINE, LOCAL), "the host has to be in the index to be about anything");
    }

    /** The hit a break event at a world position produces, with the pose it was resolved with. */
    private static CarriedBlockRemoval.Hit projectionHit(CarrierPose pose, BlockPos local) {
        return new CarriedBlockRemoval.Hit(MINE, local, CarrierProjection.project(pose, local), pose, true);
    }

    /** The hit the structure's own block storage produces: it only knows the structure's coordinate. */
    private static CarriedBlockRemoval.Hit structureFrameHit(BlockPos local, BlockPos clicked) {
        return new CarriedBlockRemoval.Hit(MINE, local, clicked, null, false);
    }

    // ------------------------------------------ 1. the hit still resolves when moved --

    @Test
    @DisplayName("a moved structure's host still resolves, and the record is still the live one")
    void aMovedHostStillResolves() {
        carry(ComponentType.TORCH);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);
        BlockPos whereItWas = CarrierProjection.project(still(), LOCAL);

        assertNotEquals(ORIGIN, now,
                "the setup is only meaningful if moving really moved the host: " + now);
        assertNotEquals(whereItWas, now,
                "and the pose from before the move names a different block");
        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.resolve(index, null,
                projectionHit(after, LOCAL), now, CarriedBlockRemoval.StructureViewState.PRESENT,
                CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF);

        assertNotNull(destroyed, "a live host at a coordinate the break named is a destruction");
        assertEquals(MINE, destroyed.carrier());
        assertEquals(LOCAL, destroyed.localPos());
        assertEquals(ComponentType.TORCH, destroyed.slot().type);
    }

    // -------------------------------------- 2. the drop is the CURRENT projection --

    @Test
    @DisplayName("the drop is the current projection, not the record's origin and not the old position")
    void theDropIsTheCurrentProjection() {
        carry(ComponentType.REPEATER);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);
        BlockPos before = CarrierProjection.project(poseAt(ORIGIN.getX(), ORIGIN.getY(), ORIGIN.getZ()),
                LOCAL);

        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.resolve(index, null,
                projectionHit(after, LOCAL), now, CarriedBlockRemoval.StructureViewState.PRESENT,
                CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF);

        assertNotNull(destroyed);
        assertEquals(now, destroyed.dropPos(),
                "the item lands where the block IS, not at the origin");
        assertNotEquals(ORIGIN, destroyed.dropPos(),
                "and the origin is a different block after the structure has moved");
        assertNotEquals(before, destroyed.dropPos(),
                "and neither is the position the pose from before the move names");
        assertEquals(now, destroyed.worldPos(),
                "the world position the client is told about is the same one");
    }

    @Test
    @DisplayName("a structure-frame hit drops at the projection too, not at the storage coordinate")
    void aStructureFrameHitDropsAtTheProjection() {
        carry(ComponentType.COMPARATOR);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);
        // A Sable sub-level's own coordinates are two-million-magnitude numbers, and a drop there is a
        // drop a player can never reach - which reads exactly like "it did not drop at all".
        BlockPos storageCoordinate = new BlockPos(20481032, 127, 20524040);

        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.resolve(index, null,
                structureFrameHit(LOCAL, storageCoordinate), now,
                CarriedBlockRemoval.StructureViewState.GONE,
                CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE);

        assertNotNull(destroyed, "the structure's own view says the block is gone, which is this "
                + "caller's evidence");
        assertEquals(now, destroyed.dropPos(),
                "the drop is derived from the pose and the local coordinate, never from the position the "
                        + "click arrived at");
        assertNotEquals(storageCoordinate, destroyed.dropPos(),
                "a component dropped at a plot coordinate is a component nobody can pick up");
    }

    // ------------------------------- 3. a stale or origin-based drop must fail loudly --

    @Test
    @DisplayName("regression guard: the old rule's two positions are both wrong for a moved structure")
    void theOldAnswersAreWrongAfterMovement() {
        carry(ComponentType.DUST);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        // Stated as arithmetic: if the drop were the record's own position - which does not move while
        // riding - or the clicked position, it would not be `now`.
        CarriedHost record = index.at(MINE, LOCAL);
        assertEquals(ORIGIN, record.at(),
                "a riding record's own position is where it was picked up, not where the host is");
        assertNotEquals(now, record.at(),
                "so after movement the record and the host name different blocks");
    }

    // ------------------------------------------------- 4. the record is consumed --

    @Test
    @DisplayName("the moved host's record is discarded rather than released, exactly once")
    void theMovedHostIsConsumedExactlyOnce() {
        carry(ComponentType.TORCH);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.resolve(index, null,
                projectionHit(after, LOCAL), now, CarriedBlockRemoval.StructureViewState.PRESENT,
                CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF);
        assertNotNull(destroyed);

        // The one write-back the two triggers share: `discard` removes the record and writes nothing.
        CarriedHost removed = index.discard(destroyed.carrier(), destroyed.localPos());
        assertNotNull(removed, "the record is the one a break consumes");
        assertEquals(ComponentType.TORCH, removed.slot().type);
        assertNull(index.discard(destroyed.carrier(), destroyed.localPos()),
                "a second trigger for one block finds nothing: that, and not a guard, is 'exactly once'");
        assertFalse(world.containsKey(destroyed.dropPos().asLong()),
                "and nothing was written back into the world: a destruction is not a release, and the "
                        + "block it would be written into has just been destroyed");
        assertFalse(world.containsKey(now.asLong()));
        assertTrue(index.isEmpty(), "the structure no longer answers for anything");
    }

    // ------------------------------------- 5. the two refusals are still refusals --

    @Test
    @DisplayName("a position the world store holds at the moved host's place keeps the world's ownership")
    void theWorldStoreStillWinsAtTheCurrentPosition() {
        carry(ComponentType.DUST);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        // Somebody stored a component at the block the structure has just arrived on. The world's own
        // break path owns that position, and dropping here as well would be two items for one block.
        Slot someoneElses = new Slot(ComponentType.LEVER);

        assertNull(CarriedBlockRemoval.resolve(index, someoneElses, projectionHit(after, LOCAL), now,
                        CarriedBlockRemoval.StructureViewState.PRESENT,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "the world store is consulted at the host's CURRENT position, and it wins there");
        assertNotNull(index.at(MINE, LOCAL), "and the record is left alone for the store's own path");
    }

    @Test
    @DisplayName("a moved host whose pose cannot name a position is left alone rather than guessed at")
    void noPositionMeansNoDrop() {
        carry(ComponentType.DUST);
        CarrierPose after = moved();

        assertNull(CarriedBlockRemoval.resolve(index, null, projectionHit(after, LOCAL), null,
                        CarriedBlockRemoval.StructureViewState.PRESENT,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "no world position is not a reason to drop somewhere plausible");
        assertNull(CarriedBlockRemoval.resolve(null, null, projectionHit(after, LOCAL),
                        CarrierProjection.project(after, LOCAL),
                        CarriedBlockRemoval.StructureViewState.PRESENT,
                        CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF),
                "and no index at all is not either");
        assertNotNull(index.at(MINE, LOCAL), "the record survives both refusals");
    }

    @Test
    @DisplayName("a structure-frame hit still needs the view to say the block is gone")
    void theStructureFrameStillNeedsTheView() {
        carry(ComponentType.DUST);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        assertNull(CarriedBlockRemoval.resolve(index, null, structureFrameHit(LOCAL, now), now,
                        CarriedBlockRemoval.StructureViewState.PRESENT,
                        CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE),
                "a structure that is merely moving keeps reporting the block, and that must never be "
                        + "read as a destruction");
        assertNull(CarriedBlockRemoval.resolve(index, null, structureFrameHit(LOCAL, now), now,
                        CarriedBlockRemoval.StructureViewState.UNREADABLE,
                        CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE),
                "'cannot tell' is never 'gone'");
    }

    @Test
    @DisplayName("a projected hit is accepted with no view at all, and a searched one never is")
    void aMissingViewIsNotEvidenceAgainstTheProjectionTable() {
        // A structure whose own reader cannot be resolved has no view to offer. That is not evidence AGAINST
        // the projection table, which computed this coordinate from the carrier's own pose and the host's own
        // local coordinate, so a break there is still a destruction - and it is certainly not evidence FOR a
        // hit that had to search the structure's storage.
        assertTrue(CarriedBlockRemoval.accepts(null,
                CarriedBlockRemoval.ViewRequirement.PROJECTION_IS_PROOF));
        assertFalse(CarriedBlockRemoval.accepts(null,
                CarriedBlockRemoval.ViewRequirement.VIEW_MUST_SAY_GONE));
        assertFalse(CarriedBlockRemoval.accepts(CarriedBlockRemoval.StructureViewState.GONE, null),
                "and a missing requirement is not an answer either");
    }

    // ------------------------------------------- 6. the sweep converges when moved --

    @Test
    @DisplayName("the self-healing sweep finds a moved host gone and drops it at its CURRENT position")
    void theSelfHealSweepConvergesAfterMovement() {
        carry(ComponentType.TORCH);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        // The sweep's evidence: the structure's own view now says air at the host's own local coordinate
        // (the block was removed by something that is not a break event - a drill, /setblock), and the
        // world store holds nothing at the host's CURRENT position.
        CarriedBlockRemoval.Destroyed destroyed = CarriedBlockRemoval.selfHealCandidate(index, MINE,
                record(), CarriedBlockRemoval.StructureViewState.GONE, now, null);

        assertNotNull(destroyed,
                "both safeties must fire for a moved structure: the break path and the sweep share this "
                        + "decision");
        assertEquals(now, destroyed.dropPos(),
                "and it drops where the host IS, which is the block the player just watched disappear");
        assertNotEquals(ORIGIN, destroyed.dropPos());
    }

    @Test
    @DisplayName("the sweep still refuses what it must: a block that is present, and a world-held position")
    void theSelfHealSweepStillRefusesWhatItMust() {
        carry(ComponentType.TORCH);
        CarrierPose after = moved();
        BlockPos now = CarrierProjection.project(after, LOCAL);

        assertNull(CarriedBlockRemoval.selfHealCandidate(index, MINE, record(),
                        CarriedBlockRemoval.StructureViewState.PRESENT, now, null),
                "a structure that is merely moving keeps reporting the block: never a destruction");
        assertNull(CarriedBlockRemoval.selfHealCandidate(index, MINE, record(),
                        CarriedBlockRemoval.StructureViewState.UNREADABLE, now, null),
                "'cannot tell' is never 'gone'");
        assertNotNull(CarriedBlockRemoval.selfHealCandidate(index, MINE, record(),
                        CarriedBlockRemoval.StructureViewState.GONE, now, null),
                "the control: with the view saying gone and an empty world store, it does act");
        assertNull(CarriedBlockRemoval.selfHealCandidate(index, MINE, record(),
                        CarriedBlockRemoval.StructureViewState.GONE, now,
                        new Slot(ComponentType.LEVER)),
                "and a component the WORLD holds at the host's current position belongs to the world's "
                        + "own path - the check has to use that position, not the origin, once the "
                        + "structure has moved");
        assertNull(CarriedBlockRemoval.selfHealCandidate(index, MINE, record(),
                        CarriedBlockRemoval.StructureViewState.GONE, null, null),
                "no current position means no drop, rather than a guess");
    }

    @Test
    @DisplayName("an emptied host is still carried, and its slot can be written back as empty")
    void anEmptiedHostIsStillAHost() {
        carry(ComponentType.TORCH);
        assertTrue(index.removeSlot(MINE, LOCAL),
                "a component put into a carried block has to be removable again");
        assertNotNull(index.at(MINE, LOCAL), "the host is still the structure's");
        assertNull(index.at(MINE, LOCAL).slot());

        // And the solve's own write-back path must not resurrect the component: it reads the record.
        assertNull(CarriedTool.entryOf(index, MINE, LOCAL));
        assertTrue(index.removeSlot(MINE, LOCAL), "emptying an empty host is idempotent");
    }
}
