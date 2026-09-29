package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the two-phase identity: the rules that let a host be claimed before its structure has an
 * entity, and re-labelled onto the entity afterwards.
 *
 * <p>These are the tests for the defect that made components neither move nor draw: the claim used to
 * be abandoned whenever {@code contraption.entity} was still null. What has to be pinned down is
 * therefore not "does the arithmetic work" but <b>"is a claim ever dropped"</b> - so the cases here are
 * the decision table's four rows, the dual-path lookup, and the properties that make a provisional id
 * safe to store beside a real one.
 */
class CarrierIdentityTest {

    /** A real entity UUID, version 4 - what {@code Entity#getUUID()} always is. */
    private static final UUID ENTITY = UUID.fromString("3f1c4c1e-8b0a-4f1d-9c2e-6a7b8c9d0e1f");

    /** A second entity, standing in for "a different contraption object reused". */
    private static final UUID OTHER_ENTITY = UUID.fromString("aa2b3c4d-5e6f-4a1b-8c2d-3e4f5a6b7c8d");

    private static UUID provisional(long serial) {
        return CarrierIdentity.provisional(serial, 0x1234_5678, 100, 64, -200);
    }

    // ------------------------------------------------------- the fallback id --

    @Test
    @DisplayName("a provisional id is a version-8 UUID, so it can never be mistaken for an entity's")
    void provisionalIsVersionEight() {
        UUID id = provisional(1L);

        assertEquals(CarrierIdentity.PROVISIONAL_VERSION, id.version(),
                "the version field is the whole reason a provisional id is recognisable");
        assertEquals(2, id.variant(), "and it stays a well-formed RFC 4122 UUID");
        assertNotEquals(ENTITY.version(), id.version(), "an entity UUID is version 4");
    }

    @Test
    @DisplayName("the same structure gets the same provisional id every time it is asked")
    void provisionalIsStableForOneStructure() {
        assertEquals(provisional(7L), provisional(7L),
                "startMoving, the actor tick and stopMoving must all see one identity");
    }

    @Test
    @DisplayName("different structures get different provisional ids")
    void provisionalSeparatesStructures() {
        assertNotEquals(provisional(1L), provisional(2L), "the serial separates two in one tick");
        assertNotEquals(provisional(1L),
                CarrierIdentity.provisional(1L, 0x8765_4321, 100, 64, -200),
                "the contraption's identity hash separates two structures at one anchor");
        assertNotEquals(provisional(1L),
                CarrierIdentity.provisional(1L, 0x1234_5678, 101, 64, -200),
                "and the anchor is mixed in as well, so a log line can be read by eye");
    }

    @Test
    @DisplayName("a provisional id is recognised from the UUID alone, on both paths")
    void provisionalIsRecognisable() {
        assertTrue(CarrierIdentity.isProvisional(CarrierId.contraption(provisional(1L))));
        assertTrue(CarrierIdentity.isProvisional(provisional(1L)));
        assertFalse(CarrierIdentity.isProvisional(CarrierId.contraption(ENTITY)),
                "an entity's own UUID is not provisional, which is what makes the dual path safe");
        assertFalse(CarrierIdentity.isProvisional((CarrierId) null));
        assertFalse(CarrierIdentity.isProvisional((UUID) null));
    }

    // ---------------------------------------------------------- the first phase --

    @Test
    @DisplayName("phase one: an entity present is used, an entity absent spends a provisional id")
    void claimPicksTheIdentityThatExists() {
        CarrierIdentity.Identity withEntity = CarrierIdentity.claim(ENTITY, provisional(1L));
        assertEquals(CarrierIdentity.Phase.ENTITY, withEntity.phase());
        assertEquals(CarrierId.contraption(ENTITY), withEntity.carrier());
        assertFalse(withEntity.isProvisional());

        // THE INVARIANT: no entity is not "no claim". This is the row the old code got wrong by
        // returning early instead of substituting an identity.
        CarrierIdentity.Identity withoutEntity = CarrierIdentity.claim(null, provisional(1L));
        assertEquals(CarrierIdentity.Phase.PROVISIONAL, withoutEntity.phase());
        assertEquals(CarrierId.contraption(provisional(1L)), withoutEntity.carrier());
        assertTrue(withoutEntity.isProvisional());
    }

    @Test
    @DisplayName("the kind the caller passes survives: a Sable sub-level is never filed as a contraption")
    void claimKeepsTheCallersKind() {
        // THE DEFECT THE AUTHOR'S LOG SHOWS. CarrierStore#startMoving used to build the identity with
        // `Identity.ofEntity(uuid)`, which is always a CONTRAPTION - so the Sable sweep's own claim was
        // logged as `carrier claim c:6c60aeef` for a structure the same log calls
        // `sub-level 6c60aeef-…`. The Create half's orphan sweep then asked the Create half for that
        // carrier's pose, was told there is no such contraption, and wrote the components of a
        // still-flying sub-level back into the world.
        CarrierIdentity.Identity sable =
                CarrierIdentity.claim(ENTITY, null, CarrierKind.SUB_LEVEL);
        assertEquals(CarrierId.subLevel(ENTITY), sable.carrier());
        assertTrue(sable.carrier().isSubLevel(), "the same UUID under the sub-level kind");
        assertEquals("s:", sable.carrier().shortId().substring(0, 2));

        CarrierIdentity.Identity create =
                CarrierIdentity.claim(ENTITY, null, CarrierKind.CONTRAPTION);
        assertEquals(CarrierId.contraption(ENTITY), create.carrier());
        assertEquals("c:", create.carrier().shortId().substring(0, 2));

        // The two are the same UUID and different identities: that is the whole situation, and the kind
        // is the only thing that can tell them apart.
        assertEquals(sable.carrier().uuid(), create.carrier().uuid());
        assertNotEquals(sable.carrier(), create.carrier());
    }

    @Test
    @DisplayName("a provisional id is always a contraption's, whatever kind the caller claims")
    void provisionalStaysAContraption() {
        // A provisional id is minted during a Create assembly, so it is a contraption by construction.
        // Letting a caller declare it a sub-level would put an id in the sub-level namespace that Sable
        // has never heard of - and the sub-level lookup would then answer "gone" for a live claim.
        CarrierId provisionalId = CarrierId.contraption(provisional(9L));
        CarrierIdentity.Identity identity =
                CarrierIdentity.claim(null, provisionalId, CarrierKind.CONTRAPTION);

        assertTrue(identity.isProvisional());
        assertTrue(identity.carrier().isContraption());
        assertEquals(provisionalId.uuid(), identity.carrier().uuid());

        // A sub-level has no assembly phase, so a "provisional" sub-level claim is really a caller that
        // already knows the identity: it is passed through unchanged rather than re-derived.
        CarrierId realSubLevel = CarrierId.subLevel(ENTITY);
        CarrierIdentity.Identity alreadyKnown =
                CarrierIdentity.claim(null, realSubLevel, CarrierKind.SUB_LEVEL);
        assertFalse(alreadyKnown.isProvisional());
        assertEquals(realSubLevel, alreadyKnown.carrier());
    }

    // --------------------------------------------------------- the decision table --

    @Test
    @DisplayName("a claim already made under the entity is left alone")
    void entityClaimIsAuthoritative() {
        CarrierIdentity.Identity claim = CarrierIdentity.Identity.ofEntity(ENTITY);
        CarrierIdentity.Identity present = CarrierIdentity.Identity.ofEntity(ENTITY);

        CarrierIdentity.Resolution same = CarrierIdentity.resolve(claim, present, ENTITY);
        assertFalse(same.rekeys());
        assertEquals(claim, same.claim());

        // Even a different entity: the entity is the authority, and re-keying is not how that is fixed.
        CarrierIdentity.Resolution different =
                CarrierIdentity.resolve(claim, present, OTHER_ENTITY);
        assertFalse(different.rekeys(), "an entity-backed claim is never re-keyed");
    }

    @Test
    @DisplayName("a provisional claim is re-labelled onto the entity that has just appeared")
    void provisionalClaimIsReconciled() {
        CarrierIdentity.Identity claim = CarrierIdentity.Identity.ofProvisional(provisional(1L));
        CarrierIdentity.Identity present = claim;

        CarrierIdentity.Resolution resolution = CarrierIdentity.resolve(claim, present, ENTITY);

        assertTrue(resolution.rekeys(), "this is THE case: the entity appeared after the claim");
        assertEquals(CarrierId.contraption(ENTITY), resolution.claim().carrier());
        assertEquals(CarrierIdentity.Phase.ENTITY, resolution.claim().phase());
        assertEquals(claim, resolution.present());
        assertTrue(resolution.describe().contains("reconciled"));
    }

    @Test
    @DisplayName("a provisional claim with no entity yet is kept, not dropped")
    void provisionalClaimWaitsForTheEntity() {
        CarrierIdentity.Identity claim = CarrierIdentity.Identity.ofProvisional(provisional(1L));

        CarrierIdentity.Resolution resolution = CarrierIdentity.resolve(claim, claim, null);

        assertFalse(resolution.rekeys());
        assertEquals(claim, resolution.claim(), "the claim stands under its provisional name");
        assertTrue(resolution.describe().contains("entity=absent"));
        assertTrue(resolution.describe().contains("settled"));
    }

    @Test
    @DisplayName("a second reconciliation of the same carrier is a no-op")
    void secondReconciliationDoesNothing() {
        UUID id = provisional(1L);
        CarrierIdentity.Identity claim = CarrierIdentity.Identity.ofProvisional(id);

        CarrierIdentity.Resolution again =
                CarrierIdentity.resolve(claim, claim, id);

        assertFalse(again.rekeys(), "the id already is the entity's, so there is nothing to move");
    }

    @Test
    @DisplayName("a claim the index has never heard of resolves to the entity without a re-key")
    void unknownClaimDoesNotTryToRekey() {
        CarrierIdentity.Identity claim = CarrierIdentity.Identity.ofProvisional(provisional(3L));

        CarrierIdentity.Resolution resolution = CarrierIdentity.resolve(claim, null, ENTITY);

        assertFalse(resolution.rekeys(), "nothing to move: the index holds no entry");
        assertEquals(CarrierId.contraption(ENTITY), resolution.claim().carrier());
        assertNull(resolution.present());
    }

    @Test
    @DisplayName("the resolution never invents an identity: it is always one of the two inputs")
    void resolutionOnlyEverPicksAnInput() {
        UUID id = provisional(1L);
        CarrierIdentity.Identity provisionalClaim = CarrierIdentity.Identity.ofProvisional(id);

        // Still no entity: the provisional id itself comes back.
        CarrierIdentity.Resolution waiting =
                CarrierIdentity.resolve(provisionalClaim, provisionalClaim, null);
        assertEquals(provisionalClaim, waiting.claim());
        assertEquals(id, waiting.claim().carrier().uuid());

        // An entity appeared: the entity's UUID comes back.
        CarrierIdentity.Resolution settled =
                CarrierIdentity.resolve(provisionalClaim, provisionalClaim, ENTITY);
        assertEquals(ENTITY, settled.claim().carrier().uuid());

        // The entity happens to be the same UUID: still that UUID, and no re-key.
        CarrierIdentity.Resolution alreadyThere =
                CarrierIdentity.resolve(provisionalClaim, provisionalClaim, id);
        assertEquals(id, alreadyThere.claim().carrier().uuid());
        assertFalse(alreadyThere.rekeys());
    }
}
