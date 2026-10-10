package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <b>The host-level removal notice, on the client side.</b>
 *
 * <p>服务器忘了 host、掉落了物品，却没有告诉客户端这个 host 是"被销毁"还是"放下"；缺席被当成"放下"，客户端于是
 * 保留 landed 记录继续画一个已经不在的元件。所以新增 host 级通知: 一串被丢弃的局部坐标。契约: 被点名的 host 从索引
 * 消失且不变成 landed；空通知什么都不改。纯逻辑，{@link CarrierIndex} 不认识 level 和渲染器。
 */
class CarrierHostRemovalTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000d1"));
    private static final CarrierId SECOND = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000d2"));

    /** Far from the origin, so "the origin is not where it is drawn" is never an accident. */
    private static final BlockPos ORIGIN = new BlockPos(2400, 70, -1300);

    private final CarrierIndex carriers = new CarrierIndex();

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    private static Slot dust(int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return slot;
    }

    private static CarrierIndex.CarriedView view(CarrierId carrier, BlockPos local) {
        return new CarrierIndex.CarriedView(carrier, local, ORIGIN, ORIGIN, dust(15));
    }

    private static CarrierPose pose(double x, double y, double z) {
        return CarrierPose.of(new Vector3d(x, y, z), new Quaterniond());
    }

    /** A client that has been told about one host of {@link #CARRIER}, with a pose for it. */
    private CarrierIndex.CarriedView armed(BlockPos local) {
        CarrierIndex.CarriedView host = view(CARRIER, local);
        carriers.applySnapshot(OVERWORLD, List.of(host), Map.of(CARRIER, pose(100.0, 64.0, 100.0)));
        return host;
    }

    // ------------------------------------------------------- the ghost itself --

    @Test
    @DisplayName("a host named by the removal notice leaves the index and does not become landed")
    void aRemovedHostLeavesTheIndexAndIsNotLanded() {
        BlockPos local = pos(1, 0, 2);
        armed(local);
        assertEquals(1, carriers.size());

        int dropped = carriers.applyRemovals(CARRIER, List.of(local));

        assertEquals(1, dropped, "the removal notice must name the host the client was holding");
        assertEquals(0, carriers.size(), "the host must be gone from the carried index");
        assertNull(carriers.atLocal(CARRIER, local), "and must not answer at its local coordinate");
        // Nothing is left to draw: a host kept under the "landed" identity would be drawn where its block was.
        LandedHosts landed = new LandedHosts();
        assertEquals(0, landed.size(), "a destroyed host is not a put-down host");
        assertTrue(carriers.drawablesNear(OVERWORLD, ORIGIN, 8, landed, List.of()).stream()
                        .noneMatch(drawable -> drawable.view().localPos().equals(local)),
                "the removed host must not be drawn by the carried path at all");
    }

    @Test
    @DisplayName("the notice is what separates a destroyed host from a put-down one")
    void theNoticeSeparatesDestroyedFromPutDown() {
        BlockPos destroyed = pos(1, 0, 2);
        BlockPos putDown = pos(1, 0, 3);
        CarrierIndex.CarriedView first = view(CARRIER, destroyed);
        CarrierIndex.CarriedView second = view(CARRIER, putDown);
        carriers.applySnapshot(OVERWORLD, List.of(first, second), Map.of(CARRIER, pose(0.0, 0.0, 0.0)));

        // Both leave the carrier's list in the new message; only one is named as removed.
        LandedHosts.Forget forget = LandedHosts.forget(List.of(first, second), List.of(destroyed), CARRIER);

        assertEquals(List.of(first), forget.forgotten());
        assertEquals(List.of(second), forget.kept());
        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(destroyed)));
        assertEquals(1, carriers.size(), "the put-down host is still the client's business");
        assertNotNull(carriers.atLocal(CARRIER, putDown));
        // ...and the put-down half is what a landed record is made of, so a component put down over air
        // is still drawn. A client that dropped everything absent from the list would lose that one.
        LandedHosts landed = new LandedHosts();
        List<CarrierIndex.CarriedView> stowed =
                LandedHosts.departed(forget.kept(), List.of(), false);
        assertEquals(1, stowed.size());
        assertEquals(1, landed.stow(stowed));
    }

    @Test
    @DisplayName("a landed record of a destroyed host is cleared rather than left drawing")
    void anExistingLandedRecordOfARemovedHostIsCleared() {
        BlockPos local = pos(4, 1, 0);
        LandedHosts landed = new LandedHosts();
        landed.stow(List.of(view(CARRIER, local), view(SECOND, local)));
        assertEquals(2, landed.size());

        int dropped = landed.forgetHosts(CARRIER, List.of(local));

        assertEquals(1, dropped);
        assertEquals(1, landed.size(), "the other structure's record is untouched");
        // Which one survived is decided by the carrier, not by the coordinate: two structures can put a
        // host down on the same local coordinate of their own, and only one of them was destroyed.
        assertTrue(landed.all().stream().anyMatch(view -> view.carrier().equals(SECOND)));
        assertTrue(landed.all().stream().noneMatch(view -> view.carrier().equals(CARRIER)));
        assertEquals(0, landed.forgetHosts(CARRIER, List.of(local)),
                "the same notice twice must be a no-op, not a double removal");
    }

    // ---------------------------------------------------------------- scoping --

    @Test
    @DisplayName("a notice for one structure leaves every other structure's hosts alone")
    void aNoticeIsScopedToOneCarrier() {
        BlockPos local = pos(2, 0, 2);
        carriers.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, local), view(SECOND, local)),
                Map.of(CARRIER, pose(0.0, 0.0, 0.0), SECOND, pose(50.0, 0.0, 50.0)));

        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(local)));

        assertNull(carriers.atLocal(CARRIER, local));
        assertNotNull(carriers.atLocal(SECOND, local),
                "the same local coordinate in another structure is a different host");
        assertEquals(1, carriers.size());
    }

    @Test
    @DisplayName("only the coordinates the notice names are removed")
    void onlyTheNamedCoordinatesGo() {
        BlockPos gone = pos(1, 0, 2);
        BlockPos kept = pos(1, 0, 3);
        carriers.applySnapshot(OVERWORLD, List.of(view(CARRIER, gone), view(CARRIER, kept)),
                Map.of(CARRIER, pose(0.0, 0.0, 0.0)));

        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(gone)));

        assertEquals(0, carriers.applyRemovals(CARRIER, List.of(gone)),
                "a repeat of the same notice removes nothing, because there is nothing left");
        assertEquals(1, carriers.size());
        assertNotNull(carriers.atLocal(CARRIER, kept));
    }

    @Test
    @DisplayName("an unknown coordinate, an unknown structure or an empty notice are all no-ops")
    void anUnmatchedNoticeChangesNothing() {
        BlockPos local = pos(1, 0, 2);
        armed(local);

        assertEquals(0, carriers.applyRemovals(CARRIER, List.of()));
        assertEquals(0, carriers.applyRemovals(CARRIER, List.of(pos(9, 9, 9))));
        assertEquals(0, carriers.applyRemovals(SECOND, List.of(local)),
                "a structure the client has never heard of cannot remove anything");
        assertEquals(1, carriers.size());
        assertNotNull(carriers.atLocal(CARRIER, local));
    }

    @Test
    @DisplayName("the pure rule refuses to make a landed record of anything it was told was destroyed")
    void theForgottenSetWinsOverTheAbsentHostList() {
        BlockPos local = pos(1, 0, 2);
        CarrierIndex.CarriedView host = view(CARRIER, local);

        // A contradictory message - the host is both named as removed and still listed as riding. It cannot
        // happen on the wire, and the safe answer wins: "this component is gone" is never softened.
        LandedHosts.Forget forget = LandedHosts.forget(List.of(host), List.of(local), CARRIER);

        assertTrue(forget.isForgotten(host));
        assertEquals(List.of(host), forget.forgotten());
        assertTrue(forget.kept().isEmpty());
    }

    @Test
    @DisplayName("a message with no removal list at all is the ordinary case, not a removal of everything")
    void anAbsentRemovalListForgetsNothing() {
        BlockPos local = pos(1, 0, 2);
        CarrierIndex.CarriedView host = view(CARRIER, local);

        LandedHosts.Forget forget = LandedHosts.forget(List.of(host), null, CARRIER);

        assertFalse(forget.isForgotten(host));
        assertEquals(List.of(host), forget.kept(),
                "null/empty means 'nothing was destroyed', which is what every ordinary message says");
        assertTrue(LandedHosts.forget(List.of(host), List.of(), CARRIER).kept().contains(host));
    }

    // --------------------------------------------------- the index's own reads --

    @Test
    @DisplayName("a host can be found by its local coordinate, which is the frame a physicalised block is named in")
    void aHostIsFoundByItsLocalCoordinate() {
        BlockPos local = pos(20481032, 127, 20524040);
        armed(local);

        assertNotNull(carriers.atLocal(CARRIER, local));
        assertEquals(1, carriers.atLocalCoordinate(local).size());
        assertNull(carriers.atLocal(CARRIER, pos(0, 0, 0)));
        assertTrue(carriers.atLocalCoordinate(pos(0, 0, 0)).isEmpty());
        // The read-only dimension check must never adopt a dimension as a side effect: a Jade overlay
        // asking about the wrong level must not be the thing that decides which level this index is about.
        CarrierIndex fresh = new CarrierIndex();
        assertFalse(fresh.acceptsDimension(OVERWORLD));
        assertNull(fresh.dimension());
    }

    @Test
    @DisplayName("two structures at one local coordinate can both be found, and one's removal leaves the other")
    void bothStructuresAreFindableAtOneCoordinate() {
        BlockPos local = pos(7, 0, 7);
        carriers.applySnapshot(OVERWORLD,
                List.of(view(CARRIER, local), view(SECOND, local)),
                Map.of(CARRIER, pose(0.0, 0.0, 0.0), SECOND, pose(10.0, 0.0, 10.0)));

        assertEquals(2, carriers.atLocalCoordinate(local).size());
        carriers.applyRemovals(CARRIER, List.of(local));
        assertEquals(1, carriers.atLocalCoordinate(local).size());
        assertEquals(SECOND, carriers.atLocalCoordinate(local).get(0).carrier());
    }

    @Test
    @DisplayName("removing the last host of a structure empties its section bucket rather than leaving a hole")
    void removingTheLastHostEmptiesItsBucket() {
        BlockPos local = pos(1, 0, 2);
        armed(local);
        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(local)));
        assertEquals(0, carriers.all().size());
        assertTrue(carriers.drawablesNear(OVERWORLD, ORIGIN, 8, new LandedHosts(), List.of()).isEmpty());
    }
}
