package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the client's view of a moving structure: which components it believes are riding what, and
 * where it thinks the structure is.
 *
 * <p>The two things worth pinning down are the ones that decide whether the picture is right:
 * a host whose carrier the client has not been told about is skipped rather than drawn at the
 * position the feature exists to move it away from, and a carrier that leaves takes its hosts with it
 * rather than leaving them behind at a stale position.
 */
class CarrierIndexTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c1"));
    private static final CarrierId SECOND = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000c2"));

    private final CarrierIndex index = new CarrierIndex();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static CarrierIndex.CarriedView view(CarrierId carrier, int lx, int ly, int lz,
                                                 int ox, int oy, int oz, int power) {
        return new CarrierIndex.CarriedView(carrier, pos(lx, ly, lz), pos(ox, oy, oz), pos(ox, oy, oz),
                dust(power));
    }

    private static CarrierPose pose(double x, double y, double z) {
        return CarrierPose.of(new Vector3d(x, y, z), new Quaterniond());
    }

    @Test
    @DisplayName("P1.55: a frame draws a moving carrier between the last two poses the server sent")
    void aFrameInterpolatesBetweenTheLastTwoPoses() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 7)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));
        index.applyPose(OVERWORLD, CARRIER, pose(11.0, 64.0, 10.0));

        // Every non-render caller - a click, a solve, a test - still gets the newest whole pose.
        assertEquals(11.0, index.poseOf(CARRIER).position().x(), 1.0E-9);
        assertEquals(11.0, index.poseOf(CARRIER, 1.0).position().x(), 1.0E-9);
        // And a frame in between gets the instant Sable draws the machine at.
        assertEquals(10.5, index.poseOf(CARRIER, 0.5).position().x(), 1.0E-9);
        assertEquals(10.0, index.poseOf(CARRIER, 0.0).position().x(), 1.0E-9);
    }

    @Test
    @DisplayName("P1.55: the drawables the carried pass reads carry that interpolated pose")
    void drawablesCarryTheInterpolatedPose() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 7)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));
        index.applyPose(OVERWORLD, CARRIER, pose(11.0, 64.0, 10.0));

        List<CarrierIndex.Drawable> interpolated =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 8, null, List.of(), 0.5);
        assertEquals(1, interpolated.size());
        assertEquals(10.5, interpolated.get(0).pose().position().x(), 1.0E-9,
                "the render path is the one caller that passes a partial tick");

        List<CarrierIndex.Drawable> whole =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 8, null, List.of());
        assertEquals(11.0, whole.get(0).pose().position().x(), 1.0E-9,
                "the overload without a partial tick is the old behaviour, to the bit");
    }

    @Test
    @DisplayName("P1.55: a jump - a snapshot, a landing, a drop - is not a tick and does not interpolate")
    void aJumpDoesNotInterpolate() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 7)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));
        index.applyPose(OVERWORLD, CARRIER, pose(11.0, 64.0, 10.0));
        assertEquals(10.5, index.poseOf(CARRIER, 0.5).position().x(), 1.0E-9, "precondition");

        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 300, 64, 300, 7)),
                Map.of(CARRIER, pose(300.0, 64.0, 300.0)));

        assertEquals(300.0, index.poseOf(CARRIER, 0.5).position().x(), 1.0E-9,
                "after a snapshot there is no previous pose to interpolate from, so nothing flies");
    }

    @Test
    @DisplayName("a snapshot brings hosts and poses together")
    void snapshotCarriesHostsAndPoses() {
        index.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, 1, 0, 2, 100, 64, 100, 15)),
                Map.of(CARRIER, pose(200.0, 64.0, 200.0)));

        assertEquals(1, index.size());
        assertNotNull(index.poseOf(CARRIER));
        assertEquals(200.0, index.poseOf(CARRIER).position().x(), 1.0E-9);
        assertTrue(index.knows(CARRIER));
    }

    @Test
    @DisplayName("the pose is what changes while a structure moves, and it can be replaced on its own")
    void poseUpdatesOnTheirOwn() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 7)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        index.applyPose(OVERWORLD, CARRIER, pose(40.0, 70.0, -12.0));

        assertEquals(40.0, index.poseOf(CARRIER).position().x(), 1.0E-9);
        assertEquals(1, index.size(), "and the host list is untouched by a pose update");
    }

    @Test
    @DisplayName("a carrier that leaves takes its hosts with it rather than stranding them")
    void removedCarrierDropsItsHosts() {
        index.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 7),
                        view(SECOND, 0, 0, 0, 30, 64, 30, 3)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0), SECOND, pose(30.0, 64.0, 30.0)));

        index.applyPose(OVERWORLD, CARRIER, null);

        assertNull(index.poseOf(CARRIER), "the pose is gone");
        assertEquals(1, index.size(), "and so is everything that was riding it");
        assertEquals(SECOND, index.all().get(0).carrier());
    }

    @Test
    @DisplayName("a host that is put down stops being drawn inside the structure")
    void hostCanBeRemovedIndividually() {
        CarrierIndex.CarriedView host = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        index.removeHost(host);

        assertEquals(0, index.size());
        assertNotNull(index.poseOf(CARRIER), "the structure is still there, just empty");
    }

    @Test
    @DisplayName("updating a host replaces it rather than adding a second copy")
    void hostUpdateReplaces() {
        CarrierIndex.CarriedView first = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(first), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        CarrierIndex.CarriedView updated = view(CARRIER, 1, 0, 0, 10, 64, 10, 12);
        index.applyHost(OVERWORLD, updated);

        assertEquals(1, index.size(), "one block, one component");
        assertEquals(12, index.all().get(0).slot().power);
    }

    @Test
    @DisplayName("another dimension is ignored until its own snapshot arrives")
    void otherDimensionsAreIgnored() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 10, 64, 10, 5)),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        index.applyHost(NETHER, view(CARRIER, 0, 0, 0, 0, 64, 0, 5));
        index.applyPose(NETHER, SECOND, pose(0.0, 0.0, 0.0));

        assertEquals(1, index.size());
        assertFalse(index.knows(SECOND));

        index.applySnapshot(NETHER, List.of(view(SECOND, 0, 0, 0, 0, 64, 0, 5)),
                Map.of(SECOND, pose(0.0, 64.0, 0.0)));
        assertEquals(1, index.size(), "switching dimension drops the old set");
        assertTrue(index.knows(SECOND));
    }

    @Test
    @DisplayName("only hosts whose origin is near the camera are visited")
    void forEachNearFiltersBySection() {
        index.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5),
                        view(SECOND, 0, 0, 0, 900, 64, 900, 5)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0), SECOND, pose(900.0, 64.0, 900.0)));

        List<CarrierId> visited = new ArrayList<>();
        index.forEachNear(OVERWORLD, pos(0, 64, 0), 6, host -> visited.add(host.carrier()));

        assertEquals(List.of(CARRIER), visited, "the far carrier is not even looked at");
    }

    @Test
    @DisplayName("clear drops the dimension as well as the data")
    void clearForgetsEverything() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0)));
        index.clear();

        assertEquals(0, index.size());
        assertEquals(0, index.carrierCount());
        assertNull(index.poseOf(CARRIER));
    }

    // ------------------------------------------------ staleness (the self-heal) --

    @Test
    @DisplayName("a pose is exactly as old as the ticks since it was applied")
    void poseAgeCountsTicks() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0)));

        index.tick();
        index.tick();
        index.tick();

        assertEquals(3L, index.poseAge(CARRIER));
        assertFalse(index.isPoseStale(CARRIER, 3L), "three ticks old is not yet stale at a 3-tick limit");
        assertTrue(index.isPoseStale(CARRIER, 2L), "one tick past the limit is stale");
    }

    @Test
    @DisplayName("a pose that arrives again resets its age, so a heartbeat keeps a parked machine alive")
    void freshPoseResetsAge() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0)));
        for (int i = 0; i < 50; i++) {
            index.tick();
        }

        index.applyPose(OVERWORLD, CARRIER, pose(1.0, 64.0, 1.0));

        assertEquals(0L, index.poseAge(CARRIER));
        assertFalse(index.isPoseStale(CARRIER, 20L));
    }

    @Test
    @DisplayName("a carrier with no pose at all is stale, not ageless")
    void missingPoseIsStale() {
        index.applyHost(OVERWORLD, view(CARRIER, 0, 0, 0, 1, 64, 1, 5));

        assertEquals(-1L, index.poseAge(CARRIER));
        assertTrue(index.isPoseStale(CARRIER, 20L));
    }

    @Test
    @DisplayName("expiring a stale carrier drops it and reports which one went")
    void expireStaleReportsWhatItDropped() {
        index.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5),
                        view(SECOND, 0, 0, 0, 30, 64, 30, 3)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0), SECOND, pose(30.0, 64.0, 30.0)));
        // Only the first carrier is kept alive.
        for (int i = 0; i < 5; i++) {
            index.tick();
            index.applyPose(OVERWORLD, SECOND, pose(30.0, 64.0, 30.0));
        }

        List<CarrierId> expired = index.expireStale(2L);

        assertEquals(List.of(CARRIER), expired);
        assertNull(index.poseOf(CARRIER));
        assertEquals(1, index.size(), "the host riding the expired carrier is gone with it");
        assertNotNull(index.poseOf(SECOND));
    }

    @Test
    @DisplayName("nothing is expired while every pose is fresh")
    void expiringFreshPosesIsANoOp() {
        index.applySnapshot(OVERWORLD, List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0)));

        index.tick();

        assertTrue(index.expireStale(20L).isEmpty());
        assertEquals(1, index.carrierCount());
        assertEquals(1, index.size());
    }

    @Test
    @DisplayName("staleCarriers names exactly the ones past the threshold")
    void staleCarriersNamesThem() {
        index.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, 0, 0, 0, 1, 64, 1, 5), view(SECOND, 0, 0, 0, 30, 64, 30, 3)),
                Map.of(CARRIER, pose(1.0, 64.0, 1.0)));
        for (int i = 0; i < 4; i++) {
            index.tick();
        }
        index.applyPose(OVERWORLD, SECOND, pose(30.0, 64.0, 30.0));

        assertEquals(List.of(CARRIER), index.staleCarriers(3L));
    }

    @Test
    @DisplayName("hosts waiting for a pose are counted, and a provisional carrier is not one of them")
    void hostsWithoutPoseExcludesProvisional() {
        CarrierId provisional = CarrierId.contraption(CarrierIdentity.provisional(1L, 2, 3, 4, 5));
        index.applyHost(OVERWORLD, view(CARRIER, 0, 0, 0, 1, 64, 1, 5));
        index.applyHost(OVERWORLD, view(provisional, 0, 0, 0, 2, 64, 2, 5));

        assertEquals(1, index.hostsWithoutPose(), "the ordinary carrier is the one that cannot be drawn");
        assertEquals(List.of(CARRIER), index.carriersWithoutPose());
    }

    // ------------------------------------------------- the world-path fallback --

    @Test
    @DisplayName("everything the carried path may draw comes from one walk, and a fallback sits at home")
    void drawablesMergePosesAndFallbacks() {
        CarrierIndex.CarriedView carried = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        CarrierIndex.CarriedView fallen = view(SECOND, 2, 0, 0, 12, 64, 12, 5);
        index.applySnapshot(OVERWORLD, List.of(carried, fallen),
                Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 4, List.of(fallen));

        assertEquals(2, drawables.size());
        for (CarrierIndex.Drawable drawable : drawables) {
            if (drawable.view().carrier().equals(CARRIER)) {
                assertFalse(drawable.isFallback());
                assertNotNull(drawable.pose());
            } else {
                assertTrue(drawable.isFallback(), "the carrier that was let go of is drawn at home");
                assertNull(drawable.pose());
            }
        }
    }

    @Test
    @DisplayName("a host that is both carried and falling back is drawn exactly once")
    void drawablesDoNotDuplicate() {
        CarrierIndex.CarriedView host = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 4, List.of(host));

        assertEquals(1, drawables.size());
        assertFalse(drawables.get(0).isFallback(), "the fresh pose wins over the fallback");
    }

    @Test
    @DisplayName("a fallback far from the camera is not visited at all")
    void fallbacksRespectTheSectionRadius() {
        CarrierIndex.CarriedView fallen = view(SECOND, 0, 0, 0, 900, 64, 900, 5);
        index.applySnapshot(OVERWORLD, List.of(), Map.of());

        assertTrue(index.drawablesNear(OVERWORLD, pos(0, 64, 0), 4, List.of(fallen)).isEmpty());
        assertEquals(1, index.drawablesNear(OVERWORLD, pos(900, 64, 900), 4, List.of(fallen)).size());
    }

    @Test
    @DisplayName("a carrier the client is told about again stops being a fallback")
    void applyingAPoseClearsTheFallback() {
        CarrierIndex.CarriedView host = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));
        for (int i = 0; i < 5; i++) {
            index.tick();
        }
        index.expireStale(2L);
        // The host went with the carrier, which is what the caller puts back on the world path; the
        // index itself must not still answer for it.
        assertNull(index.poseOf(CARRIER));
        assertEquals(0, index.size());
    }

    // ------------------------------------------------ landed hosts (put down, carrier gone) --

    /** One host put down at {@code at}, having been picked up at {@code origin}. */
    private static CarrierIndex.CarriedView landed(CarrierId carrier, int lx, int ly, int lz,
                                                   BlockPos origin, BlockPos at) {
        return new CarrierIndex.CarriedView(carrier, pos(lx, ly, lz), origin, at, dust(15));
    }

    @Test
    @DisplayName("a landed host is drawn at the position it was put down at, not where it came from")
    void landedHostFallsBackToItsLandingPosition() {
        // The host came from (10,64,10) and the structure put it down at (12,64,12): a structure that
        // drifted. Drawing it at its origin here would put the component on a block the server does
        // not answer for, which is what made it invisible in the first place.
        LandedHosts landedHosts = new LandedHosts();
        landedHosts.stow(List.of(landed(CARRIER, 1, 0, 0, pos(10, 64, 10), pos(12, 64, 12))));
        index.applySnapshot(OVERWORLD, List.of(), Map.of());

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(12, 64, 12), 4, landedHosts, List.of());

        assertEquals(1, drawables.size());
        CarrierIndex.Drawable only = drawables.get(0);
        assertTrue(only.isFallback(), "no pose, so it is drawn at a world block");
        assertTrue(only.landed(), "and the block is the one it landed at");
        assertEquals(pos(12, 64, 12), only.fallbackPos());
    }

    @Test
    @DisplayName("a host whose carrier went stale mid-flight falls back to where it came from")
    void expiredHostFallsBackToItsOrigin() {
        // The opposite promise: nothing put this host down, the client simply stopped trusting where
        // the structure was. "Return to where you started" is the only honest place left.
        CarrierIndex.CarriedView riding = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(), Map.of());

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 4, new LandedHosts(),
                        List.of(riding));

        assertEquals(1, drawables.size());
        assertTrue(drawables.get(0).isFallback());
        assertFalse(drawables.get(0).landed(), "not a put-down record: a let-go carrier");
        assertEquals(pos(10, 64, 10), drawables.get(0).fallbackPos());
    }

    @Test
    @DisplayName("a carried host wins over a landed record of the same host")
    void carriedBeatsLanded() {
        CarrierIndex.CarriedView host = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));
        LandedHosts landedHosts = new LandedHosts();
        landedHosts.stow(List.of(landed(CARRIER, 1, 0, 0, pos(10, 64, 10), pos(10, 64, 10))));

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 4, landedHosts, List.of());

        assertEquals(1, drawables.size(), "one host, one draw");
        assertFalse(drawables.get(0).isFallback(), "a fresh pose is the newer truth");
        assertNotNull(drawables.get(0).pose());
    }

    @Test
    @DisplayName("a landed host far from the camera is not visited at all")
    void landedHostsRespectTheSectionRadius() {
        LandedHosts landedHosts = new LandedHosts();
        landedHosts.stow(List.of(landed(CARRIER, 0, 0, 0, pos(900, 64, 900), pos(900, 64, 900))));
        index.applySnapshot(OVERWORLD, List.of(), Map.of());

        assertTrue(index.drawablesNear(OVERWORLD, pos(0, 64, 0), 4, landedHosts, List.of()).isEmpty());
        assertEquals(1, index.drawablesNear(OVERWORLD, pos(900, 64, 900), 4, landedHosts,
                List.of()).size());
    }

    @Test
    @DisplayName("no landed set at all still folds carried and expired hosts, as before")
    void drawablesWorkWithoutALandedSet() {
        CarrierIndex.CarriedView carried = view(CARRIER, 1, 0, 0, 10, 64, 10, 5);
        CarrierIndex.CarriedView fallen = view(SECOND, 2, 0, 0, 12, 64, 12, 5);
        index.applySnapshot(OVERWORLD, List.of(carried), Map.of(CARRIER, pose(10.0, 64.0, 10.0)));

        List<CarrierIndex.Drawable> drawables =
                index.drawablesNear(OVERWORLD, pos(10, 64, 10), 4, List.of(fallen));

        assertEquals(2, drawables.size());
        for (CarrierIndex.Drawable drawable : drawables) {
            assertFalse(drawable.landed(), "nothing was put down in this world");
        }
    }
}
