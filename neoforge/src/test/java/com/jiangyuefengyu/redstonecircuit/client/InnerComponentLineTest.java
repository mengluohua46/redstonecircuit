package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
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
 * Jade over a physicalised block: resolving the carried host, and the fields that are shown.
 *
 * <p>A physicalised block's position in the level is the structure's <em>own</em> coordinate, where the ordinary host
 * sync has nothing; the carrier payload is keyed by that same coordinate, so a lookup there resolves the host.
 *
 * <p>The fields are the ones the world overlay prints, field for field, because both are built by
 * {@link InnerComponentLine#of}. An index that has not been told a dimension answers nothing and does not adopt the
 * asked-about one: an overlay is not an update, and it must never decide which level this client's view is about.
 */
class InnerComponentLineTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    private static final CarrierId CARRIER = CarrierId.subLevel(
            UUID.fromString("00000000-0000-0000-0000-000000005001"));

    /** A physicalised block's coordinate: the structure's own, not a world position. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20524040);

    private final CarrierIndex carriers = new CarrierIndex();

    private static Slot slot(ComponentType type) {
        return new Slot(type);
    }

    /** A client that has been told about one carried host, with a pose for its structure. */
    private void armed(BlockPos local, Slot contents) {
        carriers.applySnapshot(OVERWORLD,
                List.of(new CarrierIndex.CarriedView(CARRIER, local, new BlockPos(2400, 70, -1300),
                        new BlockPos(2400, 70, -1300), contents)),
                Map.of(CARRIER, CarrierPose.of(new Vector3d(2775.0, 77.0, -1395.0), new Quaterniond())));
    }

    @Test
    @DisplayName("a physicalised block resolves to its carried host, and the component comes from there")
    void aPhysicalisedBlockResolvesToItsCarriedHost() {
        Slot dust = slot(ComponentType.DUST);
        dust.power = 11;
        armed(LOCAL, dust);

        // The provider's resolution, without the provider: exactly the lookup it performs.
        CarrierIndex.CarriedView host = carriers.acceptsDimension(OVERWORLD)
                ? carriers.atLocalCoordinate(LOCAL).stream().findFirst().orElse(null)
                : null;

        assertNotNull(host, "the coordinate the block is named by is the coordinate the host is filed at");
        assertEquals(CARRIER, host.carrier());
        assertEquals(11, host.slot().power, "and the contents are the client's live copy");
        assertEquals(0, carriers.applyRemovals(CARRIER, List.of()),
                "and an empty removal notice - what every ordinary message carries - changes nothing");
    }

    @Test
    @DisplayName("a coordinate nothing is carried at resolves to nothing, so the overlay stays silent")
    void anOrdinaryCoordinateResolvesToNothing() {
        armed(LOCAL, slot(ComponentType.REPEATER));

        assertTrue(carriers.atLocalCoordinate(new BlockPos(0, 64, 0)).isEmpty(),
                "no 'looks connected and never displays' line: there is simply nothing here");
        assertTrue(carriers.atLocalCoordinate(LOCAL.offset(1, 0, 0)).isEmpty());
    }

    @Test
    @DisplayName("the overlay's dimension check never adopts a dimension: an overlay is not an update")
    void theOverlayNeverAdoptsADimension() {
        assertFalse(carriers.acceptsDimension(OVERWORLD), "nothing has been told to this index yet");
        assertNull(carriers.dimension(), "and asking must not be what sets it");
        assertFalse(carriers.acceptsDimension(null));

        armed(LOCAL, slot(ComponentType.REPEATER));
        assertTrue(carriers.acceptsDimension(OVERWORLD));
        assertFalse(carriers.acceptsDimension(NETHER),
                "a question about another level is not this index's question");
    }

    // ------------------------------------------------------------- the fields --

    @Test
    @DisplayName("a wire reports its strength, from either store, with the same fields")
    void aWireReportsItsStrength() {
        Slot dust = slot(ComponentType.DUST);
        dust.power = 7;
        InnerComponentLine line = InnerComponentLine.of(dust);

        assertNotNull(line);
        assertEquals(InnerComponentLine.StateKind.STRENGTH, line.kind());
        assertEquals(7, line.power());
        assertEquals("DUST out=7", line.describe());

        Slot superconductor = slot(ComponentType.SUPERCONDUCTOR);
        superconductor.power = 15;
        assertEquals(InnerComponentLine.StateKind.STRENGTH,
                InnerComponentLine.of(superconductor).kind());
    }

    @Test
    @DisplayName("a repeater reports output and delay, and its two delay fields agree with the type's own rule")
    void aRepeaterReportsOutputAndDelay() {
        Slot repeater = slot(ComponentType.DOUBLE_RETAINING_REPEATER);
        repeater.power = 15;
        repeater.delay = 3;
        InnerComponentLine line = InnerComponentLine.of(repeater);

        assertEquals(InnerComponentLine.StateKind.REPEATER, line.kind());
        assertTrue(line.hasOutput());
        assertEquals(3, line.delay());
        assertEquals(repeater.type.delayTicks(3), line.type().delayTicks(line.delay()),
                "the overlay prints the type's own ticks-per-setting, not a second opinion about it");
        assertEquals("DOUBLE_RETAINING_REPEATER output delay=3", line.describe());
    }

    @Test
    @DisplayName("a comparator reports its mode and never a delay setting")
    void aComparatorReportsItsMode() {
        Slot comparator = slot(ComponentType.COMPARATOR);
        comparator.mode = ComparatorMode.SUBTRACT;
        comparator.power = 15;
        InnerComponentLine subtract = InnerComponentLine.of(comparator);

        assertEquals(InnerComponentLine.StateKind.COMPARATOR, subtract.kind());
        assertTrue(subtract.subtrahend());
        assertEquals("COMPARATOR output mode=subtract", subtract.describe());

        comparator.mode = ComparatorMode.COMPARE;
        assertFalse(InnerComponentLine.of(comparator).subtrahend());
        assertEquals("COMPARATOR output mode=compare", InnerComponentLine.of(comparator).describe());
    }

    @Test
    @DisplayName("a torch is lit or dark, a switch is on or off, and a plate reports its strength")
    void theThreeSwitchShapedTypes() {
        Slot torch = slot(ComponentType.TORCH);
        assertEquals(InnerComponentLine.StateKind.TORCH, InnerComponentLine.of(torch).kind());
        assertEquals("TORCH unlit", InnerComponentLine.of(torch).describe());
        torch.power = 15;
        assertEquals("TORCH lit", InnerComponentLine.of(torch).describe());

        Slot lever = slot(ComponentType.LEVER);
        lever.powered = true;
        assertEquals(InnerComponentLine.StateKind.SWITCH, InnerComponentLine.of(lever).kind());
        assertEquals("LEVER on", InnerComponentLine.of(lever).describe());

        Slot button = slot(ComponentType.BUTTON);
        assertEquals("BUTTON off", InnerComponentLine.of(button).describe());

        Slot plate = slot(ComponentType.PRESSURE_PLATE);
        plate.power = 4;
        assertEquals(InnerComponentLine.StateKind.PLATE, InnerComponentLine.of(plate).kind());
        assertEquals("PRESSURE_PLATE out=4", InnerComponentLine.of(plate).describe(),
                "a plate reports the strength the solver wrote, not a powered flag nobody maintains");
    }

    @Test
    @DisplayName("the world overlay and the carried overlay print the same fields for the same component")
    void bothOverlaysAgreeFieldForField() {
        // The two readers reach the component through two different stores. What they must not do is
        // describe it differently, and the shared value is what makes that impossible rather than
        // merely intended: the assertion below is two independently built lines, not one object twice.
        Slot worldSlot = slot(ComponentType.RETAINING_REPEATER);
        worldSlot.power = 15;
        worldSlot.delay = 2;

        Slot carriedSlot = slot(ComponentType.RETAINING_REPEATER);
        carriedSlot.power = 15;
        carriedSlot.delay = 2;

        InnerComponentLine world = InnerComponentLine.of(worldSlot);
        InnerComponentLine carried = InnerComponentLine.of(carriedSlot);

        assertTrue(world.sameFieldsAs(carried));
        assertEquals(world.describe(), carried.describe());
        assertFalse(world.sameFieldsAs(null));
        assertFalse(world.sameFieldsAs(InnerComponentLine.of(slot(ComponentType.TORCH))),
                "a different component is a different line");
    }

    @Test
    @DisplayName("nothing to say is null, not an empty line")
    void nothingToSayIsNull() {
        assertNull(InnerComponentLine.of(null));
        assertNull(InnerComponentLine.of(new Slot(null)));
    }
}
