package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * <b>Specification S2: the carried set is the UNION of the origin walk and the pose walk.</b>
 *
 * <h2>The defect, and the regression that followed the first attempt</h2>
 * The author measured the defect: "once the physical structure is far enough from where it was placed, the
 * internal components and the frame stop being drawn; the seventh chunk away from the chunk the component
 * was placed in and the rendering is gone." Six sections of radius plus one - the radius of the render
 * path's walk, keyed on each host's <b>origin</b>: a fixed point in the world that a moving machine leaves
 * behind.
 *
 * <p>The first fix made the pose-anchored walk the <b>only</b> source and the author's next report was
 * "now it simply does not render at all". That is the shape these tests exist to forbid, and it is not a
 * detail: an empty anchor (a carrier the client holds no pose for), or a pose whose position is not in the
 * camera's frame, stops being a missed optimisation and becomes a total loss. So S2 is a union:
 *
 * <ol>
 *   <li>{@code forEachNear} - the origin walk - runs <b>always</b>, unconditionally: whatever it found
 *       before this round, it still finds ({@code theOriginWalkStillFinds..., theOriginRadiusIsAlways...},
 *       {@code anEmptyAnchorCostsNothing});</li>
 *   <li>{@code forEachCarrierNearPose} - the pose walk - is <b>added</b>, and can only add: it is what
 *       finds a machine that has travelled beyond the origin radius
 *       ({@code thePoseWalkAddsAStructureThatHasMovedAway}), and it is folded in with {@code putIfAbsent}
 *       ({@code theUnionOfBothWalksIsContainedInTheResult}, {@code oneHostFoundByBothWalks...}).</li>
 * </ol>
 */
class CarrierIndexPoseAnchorTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000e1"));
    private static final CarrierId SECOND = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000e2"));

    /** Where the component was <b>placed</b>. Eighteen sections from {@link #CAMERA}: three times the
     * render path's own radius, so nothing here can pass by accident. */
    private static final BlockPos PLACED_AT = new BlockPos(1, 64, 1);

    /** Where the player is standing, which is also where the structure now is. */
    private static final BlockPos CAMERA = new BlockPos(300, 64, 1);

    /** Far enough that no walk of either kind can reach it (twenty-plus sections). */
    private static final BlockPos FAR_AWAY = new BlockPos(9000, 64, 9000);

    private final CarrierIndex index = new CarrierIndex();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static CarrierIndex.CarriedView host(CarrierId carrier, BlockPos local, BlockPos origin) {
        return new CarrierIndex.CarriedView(carrier, local, origin, origin, dust(15));
    }

    private static CarrierPose pose(double x, double y, double z) {
        return CarrierPose.of(new Vector3d(x, y, z), new Quaterniond());
    }

    /** The pose pivot for a block position: the block's centre, which is what the wire carries. */
    private static CarrierPose poseAt(BlockPos block) {
        return pose(block.getX() + 0.5, block.getY() + 0.5, block.getZ() + 0.5);
    }

    /** The identity a host is folded by, written the same way {@code CarrierIndex} writes it. */
    private static String identity(CarrierIndex.CarriedView view) {
        return view.carrier().shortId() + "@" + view.localPos().asLong();
    }

    /** The identities {@link CarrierIndex#drawablesNear} handed over. */
    private static Set<String> drawnIdentities(List<CarrierIndex.Drawable> drawn) {
        Set<String> out = new LinkedHashSet<>();
        for (CarrierIndex.Drawable drawable : drawn) {
            out.add(identity(drawable.view()));
        }
        return out;
    }

    private List<CarrierIndex.Drawable> drawnAt(BlockPos center) {
        return index.drawablesNear(OVERWORLD, center, 6, new LandedHosts(), List.of());
    }

    // ------------------------------------ S2 rule 1: the origin walk never goes away --

    @Test
    @DisplayName("S2 rule 1: the origin walk still finds a host whose pose is nowhere near the camera")
    void theOriginWalkStillFindsWhatItAlwaysDid() {
        // The pose is twenty-plus sections away, so the pose walk finds nothing at all here. Only the
        // origin walk can answer - which is exactly the case that made the first attempt draw nothing.
        CarrierIndex.CarriedView host = host(CARRIER, pos(1, 0, 0), CAMERA);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, poseAt(FAR_AWAY)));

        List<CarrierIndex.Drawable> drawn = drawnAt(CAMERA);

        assertEquals(1, drawn.size(),
                "the host came from here: the origin walk must still hand it over, whatever the pose says");
        assertEquals(host.localPos(), drawn.get(0).view().localPos());
        assertNotNull(drawn.get(0).pose(), "and it is still drawn inside its structure, from that pose");
    }

    @Test
    @DisplayName("S2 rule 1: an empty pose anchor costs nothing - the origin walk answers regardless")
    void anEmptyAnchorCostsNothing() {
        // No pose for the carrier at all: `poses` empty, `anchorsByPose` empty. Under a pose-only entry
        // point this is "nothing is drawn anywhere", which is the author's second report.
        CarrierIndex.CarriedView host = host(CARRIER, pos(1, 0, 0), CAMERA);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of());

        assertEquals(0, index.anchoredCarrierCount(), "the anchor is legitimately empty");
        assertEquals(1, drawnAt(CAMERA).size(), "and the host is still drawn, at the block it came from");
    }

    @Test
    @DisplayName("S2 rule 1: every host inside the origin radius is found, with no dependence on any pose")
    void theOriginRadiusIsAlwaysAnsweredFor() {
        // Three carriers, each with a pose twenty-plus sections away, each with a host placed here. This is
        // the assertion that goes red if anyone ever swaps the origin walk out for the pose walk.
        List<CarrierIndex.CarriedView> hosts = List.of(
                host(CARRIER, pos(1, 0, 0), CAMERA),
                host(SECOND, pos(2, 0, 0), CAMERA.offset(8, 0, 0)));
        index.applySnapshot(OVERWORLD, hosts,
                Map.of(CARRIER, poseAt(FAR_AWAY), SECOND, poseAt(FAR_AWAY)));

        Set<String> expected = new LinkedHashSet<>();
        for (CarrierIndex.CarriedView host : hosts) {
            expected.add(identity(host));
        }
        assertEquals(expected, drawnIdentities(drawnAt(CAMERA)),
                "everything whose origin is inside the radius must be in the answer, in full");
    }

    // ------------------------------------------- S2 rule 2: the pose walk only adds --

    @Test
    @DisplayName("S2 rule 2: the pose walk adds a structure that has moved away from where it was placed")
    void thePoseWalkAddsAStructureThatHasMovedAway() {
        CarrierIndex.CarriedView host = host(CARRIER, pos(1, 0, 0), PLACED_AT);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, poseAt(CAMERA)));

        List<CarrierIndex.Drawable> drawn = drawnAt(CAMERA);

        assertEquals(1, drawn.size(),
                "the origin is eighteen sections away, so ONLY the pose walk can find this: without it the "
                        + "machine stops drawing its components once it has driven off");
        assertEquals(host.localPos(), drawn.get(0).view().localPos());
        assertNotNull(drawn.get(0).pose());
        assertEquals(1, index.anchoredCarrierCount(), "filed under its current pose's section");
    }

    @Test
    @DisplayName("S2 rule 2: POSE_ANCHOR_RADIUS is seven sections, the renderer's own reach")
    void thePoseRadiusMatchesTheRenderersReach() {
        assertEquals(7, CarrierIndex.POSE_ANCHOR_RADIUS,
                "seven sections is 112 blocks, the same reach HostRenderPass#DRAW_DISTANCE uses - if that "
                        + "constant changes, this one changes with it");
    }

    @Test
    @DisplayName("S2 rule 2: the pose walk is additive - it cannot overwrite what the origin walk produced")
    void theUnionOfBothWalksIsContainedInTheResult() {
        // Both walks are exercised on one query: the origin walk finds the two hosts placed here, the pose
        // walk finds the host riding a carrier that is now beside the player.
        CarrierIndex.CarriedView here = host(CARRIER, pos(1, 0, 0), CAMERA);
        CarrierIndex.CarriedView away = host(SECOND, pos(2, 0, 0), PLACED_AT);
        CarrierIndex.CarriedView alsoHere = host(SECOND, pos(3, 0, 0), CAMERA.offset(16, 0, 0));
        index.applySnapshot(OVERWORLD, List.of(here, away, alsoHere),
                Map.of(CARRIER, poseAt(FAR_AWAY), SECOND, poseAt(CAMERA)));

        // What each walk answers, enumerated exactly as the entry point calls them:
        Set<String> fromOrigin = new LinkedHashSet<>();
        index.forEachNear(OVERWORLD, CAMERA, 6, view -> fromOrigin.add(identity(view)));
        Set<String> fromPose = new LinkedHashSet<>();
        index.forEachCarrierNearPose(OVERWORLD, CAMERA, CarrierIndex.POSE_ANCHOR_RADIUS,
                view -> fromPose.add(identity(view)));
        assertFalse(fromOrigin.isEmpty(), "the origin walk has something to answer for");
        assertFalse(fromPose.isEmpty(), "and so does the pose walk");

        Set<String> union = new LinkedHashSet<>(fromOrigin);
        union.addAll(fromPose);
        Set<String> drawn = drawnIdentities(drawnAt(CAMERA));

        assertTrue(drawn.containsAll(union),
                "the union of the two walks must be contained in what the entry point hands over; a "
                        + "replacement would drop one of the two sets, and dropping the origin one is the "
                        + "'nothing is drawn at all' regression");
        assertEquals(union, drawn, "and the entry point hands over nothing that neither walk found");
    }

    @Test
    @DisplayName("S2 rule 2: a host both walks reach is still exactly one drawable")
    void oneHostFoundByBothWalksIsOneDrawable() {
        // The host was placed where the carrier now is, so both walks reach it. The fold is what keeps it
        // one component; two would be two shells on one block.
        CarrierIndex.CarriedView host = host(CARRIER, pos(2, 0, 0), CAMERA);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, poseAt(CAMERA)));

        assertEquals(1, drawnAt(CAMERA).size());
        assertEquals(1, index.anchoredCarrierCount());
    }

    @Test
    @DisplayName("S2 rule 2: a carrier with everything far away is still culled")
    void aCarrierFarAwayIsStillCulled() {
        // Both its origin and its pose are twenty-plus sections away: the union must not become "draw
        // everything".
        index.applySnapshot(OVERWORLD, List.of(host(CARRIER, pos(1, 0, 0), FAR_AWAY)),
                Map.of(CARRIER, poseAt(FAR_AWAY)));

        assertTrue(drawnAt(CAMERA).isEmpty());
        assertEquals(1, drawnAt(FAR_AWAY).size(), "and it is found where it really is");
    }

    // ------------------------------------------------------- the anchor's bookkeeping --

    @Test
    @DisplayName("S2 rule 2: the anchor is filled by every path that holds a pose, and only then")
    void theAnchorIsFilledByEveryPathThatHoldsAPose() {
        // The invariant the extra walk relies on: a carrier is filed exactly when a pose is held for it.
        assertEquals(0, index.anchoredCarrierCount(), "nothing is known yet");
        assertFalse(index.knows(CARRIER));

        index.applySnapshot(OVERWORLD, List.of(host(CARRIER, pos(1, 0, 0), CAMERA)),
                Map.of(CARRIER, poseAt(CAMERA)));
        assertEquals(1, index.anchoredCarrierCount(), "a snapshot with a pose files it (login/respawn)");
        assertTrue(index.knows(CARRIER));

        index.applyPose(OVERWORLD, CARRIER, poseAt(CAMERA.offset(32, 0, 0)));
        assertEquals(1, index.anchoredCarrierCount(), "a pose packet refiles it, and does not add a second");

        index.applyHost(OVERWORLD, host(CARRIER, pos(4, 0, 0), CAMERA));
        assertEquals(1, index.anchoredCarrierCount(), "a host arriving later keeps it in step");

        index.drop(CARRIER);
        assertEquals(0, index.anchoredCarrierCount(),
                "a carrier that is let go of leaves no anchor behind - a stale entry would keep the extra "
                        + "walk visiting a structure that no longer exists");
        assertFalse(index.knows(CARRIER));
    }

    @Test
    @DisplayName("S2 rule 2: a moving carrier is re-anchored on every pose, not accumulated")
    void aMovingCarrierIsRebucketed() {
        CarrierIndex.CarriedView host = host(CARRIER, pos(1, 0, 0), PLACED_AT);
        index.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, poseAt(CAMERA)));
        assertEquals(1, index.anchoredCarrierCount());

        for (int section = 1; section <= 20; section++) {
            index.applyPose(OVERWORLD, CARRIER, poseAt(CAMERA.offset(section * 16, 0, 0)));
        }
        assertEquals(1, index.anchoredCarrierCount(),
                "a carrier re-filed twenty times is still one entry - an accumulating anchor would be a leak "
                        + "that grows with every tick of every machine");

        BlockPos whereItIsNow = CAMERA.offset(20 * 16, 0, 0);
        List<CarrierIndex.Drawable> drawn = drawnAt(whereItIsNow);
        assertEquals(1, drawn.size(), "and the pose walk finds it where it is now");
        assertEquals(host.localPos(), drawn.get(0).view().localPos());
    }

    @Test
    @DisplayName("S2 rule 2: a snapshot files both halves, whichever order they were folded in")
    void aSnapshotAnchorDoesNotDependOnHostOrder() {
        index.applySnapshot(OVERWORLD,
                List.of(host(CARRIER, pos(1, 0, 0), PLACED_AT), host(CARRIER, pos(3, 0, 0), PLACED_AT)),
                Map.of(CARRIER, poseAt(CAMERA)));

        assertEquals(1, index.anchoredCarrierCount());
        assertEquals(2, drawnAt(CAMERA).size(), "both hosts come with their structure's one anchor");
    }

    // -------------------------------------------------- the other two sources, unchanged --

    @Test
    @DisplayName("S2: a landed host and a swept carrier are still answered for exactly as before")
    void landedAndExpiredHostsAreUnchanged() {
        CarrierIndex.CarriedView landed = host(CARRIER, pos(1, 0, 0), CAMERA);
        index.applySnapshot(OVERWORLD, List.of(), Map.of());
        LandedHosts landedHosts = new LandedHosts();
        landedHosts.stow(List.of(landed));

        List<CarrierIndex.Drawable> drawn =
                index.drawablesNear(OVERWORLD, CAMERA, 6, landedHosts, List.of());
        assertEquals(1, drawn.size());
        assertTrue(drawn.get(0).landed(), "a put-down record is drawn where it was put down");
        assertEquals(0, index.anchoredCarrierCount());

        // And the caller's own fallback list, which is the third source, at the origin.
        CarrierIndex.CarriedView expired = host(CARRIER, pos(2, 0, 0), CAMERA);
        List<CarrierIndex.Drawable> fromTheList =
                index.drawablesNear(OVERWORLD, CAMERA, 6, new LandedHosts(), List.of(expired));
        assertEquals(1, fromTheList.size(), "a carrier the caller has let go of is drawn at its origin");
        assertTrue(fromTheList.get(0).isFallback());
        assertFalse(fromTheList.get(0).landed());
        assertNull(index.poseOf(CARRIER), "and the index itself holds no pose for it");
    }
}
