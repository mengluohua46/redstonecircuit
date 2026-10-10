package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the identity of a moving structure.
 *
 * <p>Short, and worth having anyway: the carrier id is the key every carried host is filed under, and
 * the two mistakes that would be hardest to see in game are both about identity - confusing a Create
 * contraption with the Sable sub-level it happens to be flying on, and using a position (the anchor)
 * as a key, which would collide the moment two machines were assembled on the same spot.
 */
class CarrierIdTest {

    private static final UUID ID = UUID.fromString("12345678-90ab-cdef-1234-567890abcdef");

    @Test
    @DisplayName("the kind is part of the identity, so a contraption and a sub-level never collide")
    void kindIsPartOfTheIdentity() {
        CarrierId contraption = CarrierId.contraption(ID);
        CarrierId subLevel = CarrierId.subLevel(ID);

        assertNotEquals(contraption, subLevel, "same UUID, different kind, different carrier");
        assertTrue(contraption.isContraption());
        assertFalse(contraption.isSubLevel());
        assertTrue(subLevel.isSubLevel());
    }

    @Test
    @DisplayName("two contraptions with different UUIDs are different carriers")
    void uuidDistinguishesCarriers() {
        // The case a position-based key would get wrong: two machines assembled at the same anchor.
        assertNotEquals(CarrierId.contraption(ID), CarrierId.contraption(UUID.randomUUID()));
    }

    @Test
    @DisplayName("the short id names the kind and is stable")
    void shortIdIsStableAndNamesTheKind() {
        assertEquals("c:12345678", CarrierId.contraption(ID).shortId());
        assertEquals("s:12345678", CarrierId.subLevel(ID).shortId());
        assertEquals(CarrierId.contraption(ID).shortId(), CarrierId.contraption(ID).shortId());
    }

    @Test
    @DisplayName("an id survives NBT with its kind")
    void idRoundTripsThroughNbt() {
        CarrierId original = CarrierId.subLevel(ID);

        CarrierId loaded = CarrierId.load(original.save());

        assertNotNull(loaded);
        assertEquals(original, loaded);
        assertEquals(CarrierKind.SUB_LEVEL, loaded.kind());
    }

    @Test
    @DisplayName("a tag that is not an id reads as absent")
    void brokenTagLoadsAsNull() {
        assertNull(CarrierId.load(null));
        assertNull(CarrierId.load(new CompoundTag()), "no UUID means no carrier");
    }

    @Test
    @DisplayName("an unknown kind name reads as a contraption rather than throwing")
    void unknownKindFallsBack() {
        CompoundTag tag = new CompoundTag();
        tag.putString("kind", "something-from-the-future");
        tag.putUUID("uuid", ID);

        CarrierId loaded = CarrierId.load(tag);

        assertNotNull(loaded);
        assertEquals(CarrierKind.CONTRAPTION, loaded.kind());
        assertEquals(ID, loaded.uuid());
    }
}
