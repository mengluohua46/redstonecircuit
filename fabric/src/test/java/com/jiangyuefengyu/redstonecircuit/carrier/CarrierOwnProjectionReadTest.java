package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The carrier-side world read must not take the asking carrier's own projected host, through <em>either</em> half.
 *
 * <p>The refusal is a property of <em>the read</em>, not of one of its sources: the vanilla half is answered by that
 * same projection table, so refusing in one place refuses nothing. {@code CarrierStore#projectedHostAt} is the single
 * accessor both halves go through; {@link InnerRedstoneNetwork#refusingOwnProjectionAt} opens the scope.
 */
class CarrierOwnProjectionReadTest {

    /** The structure whose run of dust is being solved - the reader. */
    private static final CarrierId X =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a142"));
    /** An independently physicalised structure standing next door. */
    private static final CarrierId Y =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b142"));

    private static final BlockPos HERE = new BlockPos(6, 64, 5);
    private static final BlockPos NEXT_DOOR = new BlockPos(7, 64, 5);

    private static CarrierProjection.ProjectedHost projected(CarrierId carrier, BlockPos worldPos,
                                                             int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        return new CarrierProjection.ProjectedHost(carrier, worldPos, worldPos,
                CarrierPose.at(new Vector3d(worldPos.getX(), 64.0, worldPos.getZ())), slot, power);
    }

    // ------------------------------------------------- 1. the rule, both directions --

    @Test
    @DisplayName("the refusal is exact: one carrier, one position, and it is restored afterwards")
    void theRefusalIsScopedToOneCarrierAndOnePosition() {
        CarrierProjection.ProjectedHost own = projected(X, HERE, 15);
        CarrierProjection.ProjectedHost theirs = projected(Y, HERE, 15);

        assertTrue(InnerRedstoneNetwork.projectionMayAnswer(own, HERE),
                "outside a read nothing is refused: the world-side solve and the neighbouring seam read every "
                        + "projection there is");
        assertTrue(InnerRedstoneNetwork.projectionMayAnswer(null, HERE),
                "and air is air whoever is asking");

        int answer = InnerRedstoneNetwork.refusingOwnProjectionAt(X, HERE, () -> {
            assertFalse(InnerRedstoneNetwork.projectionMayAnswer(own, HERE),
                    "the asking carrier's own host is a node of the solve that is asking: it already "
                            + "arrives through the inner relationship, which charges the hop, so the "
                            + "projection's raw emission must not answer as well - that is how the run "
                            + "stopped fading");
            assertTrue(InnerRedstoneNetwork.projectionMayAnswer(theirs, HERE),
                    "another carrier's host at the same position is the seam between them and must keep "
                            + "answering, or two independently physicalised blocks go dark again");
            assertTrue(InnerRedstoneNetwork.projectionMayAnswer(own, NEXT_DOOR),
                    "ONLY that position: the mixin's strong-power question asks about the block doing "
                            + "the asking, and refusing that would change a different answer");
            return 7;
        });

        assertEquals(7, answer, "the read's own answer is the read's answer");
        assertTrue(InnerRedstoneNetwork.projectionMayAnswer(own, HERE),
                "and the refusal does not outlive the read it belongs to");
    }

    @Test
    @DisplayName("nesting two refusals cannot leak one out of the other")
    void theRefusalIsReEntrant() {
        CarrierProjection.ProjectedHost own = projected(X, HERE, 15);

        InnerRedstoneNetwork.refusingOwnProjectionAt(X, HERE, () -> {
            InnerRedstoneNetwork.refusingOwnProjectionAt(Y, NEXT_DOOR, () -> {
                assertTrue(InnerRedstoneNetwork.projectionMayAnswer(own, HERE),
                        "the inner read refuses a different pair, not this one");
                return 0;
            });
            assertFalse(InnerRedstoneNetwork.projectionMayAnswer(own, HERE),
                    "the outer refusal is back in force once the inner read returns");
            return 0;
        });
        assertTrue(InnerRedstoneNetwork.projectionMayAnswer(own, HERE), "and then gone");
    }

    // ------------------------------------ 2. the defect: the vanilla half is that table --

    @Test
    @DisplayName("the vanilla half of the read is the same table, so the refusal has to be read there")
    void theVanillaHalfIsTheSameTableSoItIsRefusedToo() throws Exception {
        String network = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/"
                + "InnerRedstoneNetwork.java");
        String store = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/"
                + "CarrierStore.java");
        String mixin = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/"
                + "BlockStateSignalMixin.java");
        String io = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/"
                + "CarrierWorldIo.java");
        String networks = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/"
                + "CarrierNetworks.java");

        assertTrue(mixin.contains("CarrierStore.projectedHostAt(serverLevel, pos)"),
                "the vanilla signal query - which is what the carrier half's world read is answered by, "
                        + "because a ServerLevel is a SignalGetter - takes its projection from this one "
                        + "accessor. If that ever stops being true this whole fix is bypassed again, so "
                        + "the wiring is asserted rather than assumed");
        assertTrue(store.contains("return InnerRedstoneNetwork.projectionMayAnswer(host, worldPos) "
                        + "? host : null;"),
                "and that accessor is where the refusal is read, so BOTH halves are covered by ONE rule");
        assertTrue(network.contains("return refusingOwnProjectionAt(owner, worldPos, () -> {"),
                "the owner-aware read runs its whole body - vanilla half included - under the refusal, "
                        + "instead of filtering the projected half alone");
        assertTrue(io.contains("InnerRedstoneNetwork.worldSignalAt(level, worldPos, worldDirection, "
                        + "reader)"),
                "and the carrier bridge still asks the owner-aware form, naming the reader");
        assertTrue(networks.contains("new CarrierWorldIo(pose, bridge.signals(), carrier, ctx.level())"),
                "with the carrier and the level injected where both are in hand");
        assertTrue(store.contains("carrier -> afterSolveWrote(level, carrier), bridge, level)"),
                "and the level can only come from CarrierStore#contextOf's own non-null parameter, so "
                        + "the production bridge always has one to index the projection table by");
    }

    // ------------------------------------------ 3. no silent degradation without them --

    @Test
    @DisplayName("a bridge that cannot refuse its reader is counted, not silently unfiltered")
    void aBridgeThatCannotRefuseIsCountedNotSilent() {
        int before = CarrierWorldIo.unfilteredReadCount();

        // The shape production must never build: a reader was named, and the level was lost. The read
        // still answers (a bridge must not go dark because of a bookkeeping gap) - but it says so.
        CarrierWorldIo noLevel = new CarrierWorldIo(
                CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)),
                (worldPos, worldDirection) -> 9, X, null);
        assertEquals(9, noLevel.signalIntoOwnFrame(new BlockPos(1, 0, 2), Direction.EAST),
                "the read still happens: the honest degradation is a read without the filter, not a "
                        + "silent zero and not a crash");
        assertEquals(before + 1, CarrierWorldIo.unfilteredReadCount(),
                "and it is COUNTED, so 'the parameter was missing, so nothing was filtered' can never "
                        + "be the invisible reason a machine stopped fading again");

        // A bridge that names no reader at all is a different fact - a fixture's bridge - and is counted
        // too, because both are reads that could not be filtered.
        CarrierWorldIo noReader = new CarrierWorldIo(
                CarrierPose.at(new Vector3d(10.0, 64.0, 10.0)), (worldPos, worldDirection) -> 4);
        assertEquals(4, noReader.signalIntoOwnFrame(new BlockPos(1, 0, 2), Direction.EAST));
        assertEquals(before + 2, CarrierWorldIo.unfilteredReadCount(), "both are counted");
    }

    // ------------------------------------------------------ 4. what must not move --

    @Test
    @DisplayName("the world side is untouched: no owner named, no filter applied")
    void theWorldSideAndP141AreUntouched() throws Exception {
        String network = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/"
                + "InnerRedstoneNetwork.java");

        assertTrue(network.contains("() -> worldSignalAt(level, worldPos, worldDirection))"),
                "CarrierWorldSignals - the three-argument entry point, and what SolverEnvironment's "
                        + "world-side read is built from - still names no owner, so no world-side read "
                        + "is filtered and none of its behaviour changed");
        assertTrue(network.contains("int vanilla = readSignal(level, worldPos, worldDirection);"),
                "the merged read is still the vanilla block plus the projection, in that order");
        assertTrue(network.contains("int projected = projectedSignalAt(level, worldPos, worldDirection,"
                        + " owner);"),
                "and the filter on the projected half is kept, one implementation");
        assertTrue(network.contains("if (!projectionReadableBy(host, owner)) {"),
                "with the rule it states still living next to the projected host");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
