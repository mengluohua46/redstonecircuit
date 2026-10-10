package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The world side of the two host transfers: a landing has to tell the client the component is a world host again, and
 * a pick-up has to tell it the component is not one any more.
 *
 * <p>Announcing only the carrier half leaves the client's own record of where a component lives stale, so the component
 * is never drawn again. The predicate and the order are pure functions of the server's own store, which is what makes
 * them decidable offline.
 */
class WorldHostTransferTest {

    /** A plain store, so the predicate can be asked without a level. */
    private final Map<Long, Slot> store = new HashMap<>();

    private final CarriedHostIndex.SlotWorld world = new CarriedHostIndex.SlotWorld() {
        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return store.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                store.remove(pos.asLong());
            } else {
                store.put(pos.asLong(), slot);
            }
        }
    };

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private void put(BlockPos pos) {
        store.put(pos.asLong(), new Slot(ComponentType.TORCH));
    }

    // ------------------------------------------------------------- the predicate --

    @Test
    @DisplayName("a landing announces a world host exactly where the store really holds one")
    void restoresHostOnlyWhereTheComponentIs() {
        put(pos(10, 64, 10));

        assertTrue(WorldHostTransfer.restoresHost(world, pos(10, 64, 10)));
        assertFalse(WorldHostTransfer.restoresHost(world, pos(11, 64, 10)),
                "a position the release did not land on is not announced");
    }

    @Test
    @DisplayName("a release that ended HELD announces nothing, because nothing was written")
    void heldReleaseAnnouncesNothing() {
        // HELD means neither the landing position nor the origin was free, so the data is still riding.
        // Announcing a world host here would be a lie the next carrier message would have to undo.
        assertFalse(WorldHostTransfer.restoresHost(world, pos(10, 64, 10)));
        assertTrue(WorldHostTransfer.broadcastsFor(pos(10, 64, 10), pos(20, 64, 20), world)
                .stream().noneMatch(step -> step == WorldHostTransfer.Broadcast.RESTORE_WORLD_HOST));
    }

    @Test
    @DisplayName("a pick-up suppresses a world host exactly where the store holds nothing any more")
    void suppressesWorldHostOnlyWhereTheComponentIsGone() {
        assertTrue(WorldHostTransfer.suppressesWorldHost(world, pos(10, 64, 10)),
                "a claim moved the data out, so the position is no longer a world host");
        put(pos(10, 64, 10));
        assertFalse(WorldHostTransfer.suppressesWorldHost(world, pos(10, 64, 10)),
                "somebody re-stored a component here: that is a world host and must not be hidden");
    }

    @Test
    @DisplayName("the two predicates are exact opposites, so a position is never both")
    void theTwoPredicatesCannotBothHold() {
        for (int x = 0; x < 4; x++) {
            if (x % 2 == 0) {
                put(pos(x, 64, 0));
            }
        }

        for (int x = 0; x < 4; x++) {
            BlockPos at = pos(x, 64, 0);
            assertTrue(WorldHostTransfer.restoresHost(world, at)
                    ^ WorldHostTransfer.suppressesWorldHost(world, at));
        }
    }

    @Test
    @DisplayName("a null position or a null store is never announced, rather than throwing")
    void nullsAreSafe() {
        assertFalse(WorldHostTransfer.restoresHost(null, pos(0, 64, 0)));
        assertFalse(WorldHostTransfer.restoresHost(world, null));
        assertFalse(WorldHostTransfer.suppressesWorldHost(null, pos(0, 64, 0)));
        assertFalse(WorldHostTransfer.suppressesWorldHost(world, null));
        assertEquals(0, WorldHostTransfer.restorableCount(null, List.of(pos(0, 64, 0))));
    }

    // --------------------------------------------------------------- the sequence --

    @Test
    @DisplayName("a landing puts the world host on the wire before the carrier message")
    void worldHostIsAnnouncedBeforeTheCarrier() {
        BlockPos here = pos(10, 64, 10);
        put(here);

        List<WorldHostTransfer.Broadcast> plan =
                WorldHostTransfer.broadcastsFor(here, here, world);

        assertEquals(List.of(WorldHostTransfer.Broadcast.RESTORE_WORLD_HOST,
                WorldHostTransfer.Broadcast.CARRIER), plan,
                "the client must know the component is a world host before it is told the carrier is "
                        + "gone - a client that hears the removal first falls back to the world path, "
                        + "which draws the component only over a real block");
    }

    @Test
    @DisplayName("a structure that drifted also drops the position the client still thinks is a host")
    void driftingLandingAlsoDropsTheOrigin() {
        BlockPos cameFrom = pos(10, 64, 10);
        BlockPos landedAt = pos(14, 64, 10);
        put(landedAt);

        List<WorldHostTransfer.Broadcast> plan =
                WorldHostTransfer.broadcastsFor(landedAt, cameFrom, world);

        assertEquals(List.of(WorldHostTransfer.Broadcast.RESTORE_WORLD_HOST,
                WorldHostTransfer.Broadcast.DROP_OLD_WORLD_HOST,
                WorldHostTransfer.Broadcast.CARRIER), plan);
    }

    @Test
    @DisplayName("landing back where it came from does not drop the entry it has just made")
    void landingAtTheOriginDoesNotRemoveItAgain() {
        BlockPos home = pos(10, 64, 10);
        put(home);

        List<WorldHostTransfer.Broadcast> plan = WorldHostTransfer.broadcastsFor(home, home, world);

        assertFalse(plan.contains(WorldHostTransfer.Broadcast.DROP_OLD_WORLD_HOST),
                "the origin and the landing position are the same block: dropping it would undo the "
                        + "announcement immediately before it");
    }

    @Test
    @DisplayName("the carrier message is always sent, even when the world half has nothing to say")
    void theCarrierMessageIsAlwaysSent() {
        List<WorldHostTransfer.Broadcast> plan =
                WorldHostTransfer.broadcastsFor(null, null, world);

        assertEquals(List.of(WorldHostTransfer.Broadcast.CARRIER), plan,
                "the carrier payload is what carries the removal; a landing that wrote nothing still "
                        + "has to stop the client drawing the host inside the structure");
    }

    @Test
    @DisplayName("calling the sequence twice for one landing describes the identical messages")
    void theSequenceIsIdempotentInItsContent() {
        BlockPos cameFrom = pos(10, 64, 10);
        BlockPos landedAt = pos(14, 64, 10);
        put(landedAt);

        // The announcement may be reached twice - two hosts released onto one block, a repeated callback. The
        // messages must be the same ones, not a second write: the plan is a pure function of the store.
        assertEquals(WorldHostTransfer.broadcastsFor(landedAt, cameFrom, world),
                WorldHostTransfer.broadcastsFor(landedAt, cameFrom, world));
    }

    @Test
    @DisplayName("the wholesale path uses the same rule, one host at a time")
    void restorableCountCountsWhatTheStoreReallyHolds() {
        put(pos(1, 64, 1));
        put(pos(3, 64, 1));

        assertEquals(2, WorldHostTransfer.restorableCount(world,
                List.of(pos(1, 64, 1), pos(2, 64, 1), pos(3, 64, 1))));
        assertEquals(0, WorldHostTransfer.restorableCount(world, List.of()));
    }

    // ------------------------------------- and the two store operations underneath --

    @Test
    @DisplayName("a real claim moves the data out, which is what makes the suppression correct")
    void aClaimLeavesTheStoreEmptyAtTheOrigin() {
        // The end-to-end shape of the pick-up half, against the real index: the claim moves the component into
        // the carrier and out of the world, so "the store holds nothing here" is a consequence, not an assumption.
        CarriedHostIndex index = new CarriedHostIndex();
        BlockPos origin = pos(10, 64, 10);
        put(origin);

        CarriedHostIndex.ClaimOutcome outcome = index.claim(
                CarrierId.contraption(UUID.randomUUID()), pos(0, 0, 0), origin, world);

        assertTrue(outcome.performed());
        assertTrue(WorldHostTransfer.suppressesWorldHost(world, origin),
                "so the client is told to stop drawing a world host where the component no longer is");
        assertFalse(WorldHostTransfer.restoresHost(world, origin));
    }

    @Test
    @DisplayName("a real release writes the data back, which is what makes the announcement correct")
    void aReleaseLeavesTheStoreHoldingTheComponentAtTheLanding() {
        CarriedHostIndex index = new CarriedHostIndex();
        CarrierId carrier = CarrierId.contraption(UUID.randomUUID());
        BlockPos origin = pos(10, 64, 10);
        BlockPos landedAt = pos(14, 64, 10);
        put(origin);
        index.claim(carrier, pos(0, 0, 0), origin, world);

        CarriedHostIndex.ReleaseOutcome released =
                index.release(carrier, pos(0, 0, 0), local -> landedAt, world);

        assertTrue(released.wrote());
        assertEquals(landedAt, released.landing());
        assertTrue(WorldHostTransfer.restoresHost(world, landedAt),
                "so the client is told the component is a world host at the position it came home to");
        assertFalse(WorldHostTransfer.suppressesWorldHost(world, landedAt));
    }
}
