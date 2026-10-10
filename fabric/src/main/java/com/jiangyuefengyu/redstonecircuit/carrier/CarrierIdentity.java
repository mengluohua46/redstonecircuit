package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * The two-phase identity of a carrier: a host is claimed before the moving structure has an entity, and is
 * re-labelled the moment it has one. The invariant is blunt - if the block really holds a component, the
 * claim happens - so a provisional identity is spent first and settled afterwards rather than the claim
 * being abandoned.
 *
 * <p>A provisional id is stamped version 8 (RFC 9562 "custom"), which no entity UUID (version 4) carries,
 * so {@link #isProvisional} recognises one from the value alone and the two namespaces are disjoint by
 * construction; the structure's anchor is mixed in only so a printed id stays readable. Nothing here names
 * Minecraft, Create or Sable: this class is the decision table, and the compat half carries it out.
 */
public final class CarrierIdentity {

    /**
     * The RFC 4122/9562 version stamped into every provisional id: 8 is "custom", and no entity UUID
     * carries it (vanilla's {@code Mth.createInsecureUUID} and {@code UUID.randomUUID()} are both version
     * 4). Reading it back is how a provisional id is recognised.
     */
    public static final int PROVISIONAL_VERSION = 8;

    /** The variant half of the same stamp; RFC 4122's variant, so the UUID stays a well-formed one. */
    private static final long PROVISIONAL_VARIANT = 0x8000_0000_0000_0000L;

    private CarrierIdentity() {
    }

    /**
     * Where a carrier's identity came from: a {@link #PROVISIONAL} claim is the one that still has to be
     * settled, and putting a structure down while it is still provisional must be reported rather than
     * silently accepted.
     */
    public enum Phase {

        /** The contraption entity was available; this is {@code Entity#getUUID()}. */
        ENTITY,

        /** The entity was not there yet; this is a stand-in that is reconciled as soon as it is. */
        PROVISIONAL
    }

    /** One value a carrier is (or is about to be) known by: the identity and where it came from. */
    public record Identity(CarrierId carrier, Phase phase) {

        /** The identity derived from a contraption entity. */
        public static Identity ofEntity(UUID entityUuid) {
            return new Identity(CarrierId.contraption(entityUuid), Phase.ENTITY);
        }

        /**
         * The identity of a structure the caller already knows the sort of. The kind is not a detail: a
         * Sable sub-level and a Create contraption can carry the same UUID, and filing a sub-level under
         * {@link CarrierKind#CONTRAPTION} makes the orphan sweep declare a live sub-level gone.
         */
        public static Identity of(UUID uuid, CarrierKind kind) {
            return new Identity(new CarrierId(kind, uuid), Phase.ENTITY);
        }

        /** The identity a structure is claimed under while it has no entity yet. */
        public static Identity ofProvisional(UUID provisionalUuid) {
            return new Identity(CarrierId.contraption(provisionalUuid), Phase.PROVISIONAL);
        }

        /** True while this identity still has to be reconciled onto a real entity. */
        public boolean isProvisional() {
            return phase == Phase.PROVISIONAL;
        }

        /** {@code entity} / {@code provisional}, for the log lines. */
        public String describe() {
            return phase == Phase.ENTITY ? "entity" : "provisional";
        }
    }

    /**
     * What to do about the identity a claim was made under, now that the entity may be known.
     *
     * <p>{@code present} is what the index holds for this structure, or {@code null} when it holds nothing
     * at all; {@code entityUuid} is {@code null} while there is still no entity.
     */
    public record Resolution(Identity claim, @Nullable Identity present,
                             @Nullable UUID entityUuid) {

        /** True when the index has to be re-keyed for {@link #claim} to be the right identity. */
        public boolean rekeys() {
            return present != null && !present.carrier().equals(claim.carrier());
        }

        /** One line for the log: what was decided and under which identity it can be trusted. */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("identity=").append(claim.describe());
            if (present != null && !present.carrier().equals(claim.carrier())) {
                sb.append(" was=").append(present.carrier().shortId());
            }
            if (entityUuid != null) {
                sb.append(" entity=").append(CarrierId.contraption(entityUuid).shortId());
            } else {
                sb.append(" entity=absent");
            }
            sb.append(rekeys() ? " reconciled" : " settled");
            return sb.toString();
        }
    }

    /**
     * The identity a claim made at this moment belongs under: the entity when it is there, otherwise a
     * provisional id rather than a dropped claim, so the caller never has to read "no entity" as "no
     * claim".
     *
     * <p>A provisional id is always a contraption's - it is minted during a Create assembly - while an
     * entity-backed claim takes the kind the caller passed.
     */
    public static Identity claim(@Nullable UUID entityUuid, UUID provisional) {
        return entityUuid != null ? Identity.ofEntity(entityUuid) : Identity.ofProvisional(provisional);
    }

    /**
     * The same, keeping the caller's {@link CarrierKind} on the entity-uuid path. Two overloads rather than
     * one, because the kind only has meaning when the UUID names a real structure: a provisional id is a
     * contraption by construction, and letting a caller declare it a sub-level would be a lie.
     */
    public static Identity claim(@Nullable UUID entityUuid, @Nullable CarrierId provisional,
                                 CarrierKind kind) {
        if (entityUuid != null) {
            return Identity.of(entityUuid, kind);
        }
        if (provisional != null && provisional.isSubLevel()) {
            // A sub-level has no assembly phase and always has its own UUID, so such a claim is really a
            // caller that already knows the identity: pass it through rather than re-deriving a kind.
            return new Identity(provisional, Phase.ENTITY);
        }
        if (provisional == null) {
            // Neither an entity nor a stand-in: the caller refuses the claim with a warning, and a zero
            // UUID keeps this method total rather than throwing.
            return Identity.ofProvisional(new UUID(0L, 0L));
        }
        return Identity.ofProvisional(provisional.uuid());
    }

    /**
     * The decision table: which identity the claim belongs under, given everything now known.
     *
     * <p>An entity-backed claim is kept as it is - the entity is the newer, authoritative answer; a
     * provisional claim is re-keyed onto the entity once one appears (or already settled when the UUID is
     * the same); and while there is no entity the provisional identity stands and is retried on the next
     * callback. The claim stands either way, which is the invariant.
     */
    public static Resolution resolve(Identity claim, @Nullable Identity present,
                                     @Nullable UUID entityUuid) {
        if (claim.phase() == Phase.ENTITY) {
            // Nothing to settle: an entity-backed identity is the authoritative one, and re-keying could
            // only move a claim that is already under the right name.
            return new Resolution(claim, present, entityUuid);
        }
        if (entityUuid == null) {
            // No entity yet: hold the provisional identity. This is the normal state during an assembly,
            // not an error, and the claim it protects is already in the index.
            return new Resolution(claim, present, null);
        }
        if (claim.carrier().uuid().equals(entityUuid)) {
            // The same UUID: the entity was there all along, or a previous callback already settled this
            // carrier. Either way it is finished.
            return new Resolution(Identity.ofEntity(entityUuid), present, entityUuid);
        }
        // The reconciliation: the provisional claim is re-labelled onto the entity that has just appeared.
        // From here on only the entity UUID is used, on both sides of the move.
        return new Resolution(Identity.ofEntity(entityUuid), present, entityUuid);
    }

    /**
     * A provisional UUID for one structure being assembled: a process-local serial (two structures in one
     * tick cannot collide), the contraption object's identity hash (the same structure gets the same id on
     * every callback of one assembly) and the anchor, mixed in only so a printed id stays readable.
     *
     * <p>Version 8 and the RFC variant are stamped on top, so the result is a well-formed UUID no entity
     * can ever carry - see {@link #PROVISIONAL_VERSION}.
     */
    public static UUID provisional(long serial, int identityHash, int anchorX, int anchorY,
                                   int anchorZ) {
        long mixed = 0x9E37_79B9_7F4A_7C15L;
        mixed = (mixed ^ serial) * 0xBF58_476D_1CE4_E5B9L;
        mixed = (mixed ^ identityHash) * 0x94D0_49BB_1331_11EBL;
        mixed = (mixed ^ anchorX) * 0xBF58_476D_1CE4_E5B9L;
        mixed = (mixed ^ anchorY) * 0x94D0_49BB_1331_11EBL;
        mixed = (mixed ^ anchorZ) * 0xBF58_476D_1CE4_E5B9L;
        // A second, independent mix, so the high half is not a plain function of the low half.
        long high = Long.rotateLeft(mixed * 0xD6E8_FEB8_6659_FD93L, 31) ^ (serial << 17);
        long low = (mixed >>> 3) ^ (identityHash * 0x2545_F491_4F6C_DD1DL);

        // version 8 in bits 12..15 of the most significant half, RFC 4122 variant in the top two bits of
        // the least significant half, exactly as UUID#version()/#variant() read them back.
        long msb = (high & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_8000L;
        long lsb = (low & 0x3FFF_FFFF_FFFF_FFFFL) | PROVISIONAL_VARIANT;
        return new UUID(msb, lsb);
    }

    /**
     * Whether a carrier id is one of ours rather than a real entity's. This is what makes the put-down
     * side a dual-path match - the claim can be looked up under the entity UUID and, when the structure
     * came apart before it ever had one, under the provisional id it was filed under - from the id alone.
     */
    public static boolean isProvisional(CarrierId carrier) {
        return carrier != null && carrier.uuid().version() == PROVISIONAL_VERSION;
    }

    /** Whether a bare UUID is a provisional one; the same test, for an id whose kind is not yet known. */
    public static boolean isProvisional(@Nullable UUID uuid) {
        return uuid != null && uuid.version() == PROVISIONAL_VERSION;
    }
}
