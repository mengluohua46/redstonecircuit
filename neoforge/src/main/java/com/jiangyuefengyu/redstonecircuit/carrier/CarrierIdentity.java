package com.jiangyuefengyu.redstonecircuit.carrier;

import java.util.UUID;

import org.jetbrains.annotations.Nullable;

/**
 * <b>The two-phase identity of a carrier</b>: the rules that let a host be claimed before the moving
 * structure has an entity, and be re-labelled the moment it does.
 *
 * <h2>The defect this exists to fix</h2>
 * A Create {@code MovementBehaviour} is handed a {@code MovementContext} whose {@code contraption}
 * field is set, but whose {@code Contraption#entity} may still be {@code null} while the structure is
 * being assembled. The previous implementation derived the carrier's identity from
 * {@code contraption.entity.getUUID()} and <em>gave up</em> when that was absent:
 *
 * <pre>
 *   if (level == null || entity == null || context.localPos == null) { diag(...); return; }
 * </pre>
 *
 * <p>The host was therefore never claimed ({@code moved=0}), the put-down callback could not find the
 * carrier ({@code UNKNOWN_CARRIER}), and nothing followed the machine. The invariant that replaces it
 * is blunt: <b>if the block really holds a component, the claim happens</b>. A provisional identity is
 * spent first and settled afterwards, rather than the claim being abandoned.
 *
 * <h2>Same tick where it can be, the next tick where it cannot</h2>
 * The entity is available on the assembly <em>tick</em> (Create calls
 * {@code MovementBehaviour#tick} on the contraption's actors once the entity exists) even when it was
 * absent at {@code startMoving}, so a provisional claim is normally settled within the same tick.
 * {@link com.jiangyuefengyu.redstonecircuit.compat.physics.CarrierResolver} drives that from the
 * contraption object, which is the same object across all of one assembly's callbacks.
 *
 * <h2>Why a provisional identity is safe</h2>
 * <ol>
 *   <li><b>It is not a world position.</b> {@link CarrierId} explains at length why an anchor cannot be
 *       an identity. A provisional id is a UUID that is <em>derived from</em> the structure's anchor
 *       only so a log line can be read by eye; nothing matches on the anchor.</li>
 *   <li><b>It cannot collide with an entity.</b> A real entity UUID is version 4; every provisional id
 *       is stamped version 8 (RFC 9562's "custom" version) and is only ever built through
 *       {@link #provisional}, so the two namespaces are disjoint by construction - not by luck.</li>
 *   <li><b>Its difference from the real id is detectable.</b> {@link #isProvisional} reads the version
 *       back, which is what lets the put-down path match <em>both</em> identities without being told
 *       which one it is looking at.</li>
 * </ol>
 *
 * <h2>Pure logic, on purpose</h2>
 * Nothing here names Minecraft, Create or Sable: the reconciliation decision is a function of "what the
 * claim was made under", "what is in the index" and "what the entity says now", which is exactly the
 * shape a unit test can pin down. The class is the decision table; {@link CarriedHostIndex#rekey} and
 * the compat half only carry it out.
 */
public final class CarrierIdentity {

    /**
     * The RFC 4122/9562 version stamped into every provisional id.
     *
     * <p>Version 8 is "custom", which is precisely what this is, and it is a version no entity UUID
     * ever carries (vanilla's {@code Mth.createInsecureUUID} and {@code UUID.randomUUID()} are both
     * version 4). Reading it back is how a provisional id is recognised.
     */
    public static final int PROVISIONAL_VERSION = 8;

    /** The variant half of the same stamp; RFC 4122's variant, so the UUID stays a well-formed one. */
    private static final long PROVISIONAL_VARIANT = 0x8000_0000_0000_0000L;

    private CarrierIdentity() {
    }

    /**
     * Where a carrier's identity came from.
     *
     * <p>The distinction exists for exactly one reason: a {@link #PROVISIONAL} claim is the one that
     * still has to be settled, and putting it down while it is still provisional is a case that must be
     * reported rather than silently succeed.
     */
    public enum Phase {

        /** The contraption entity was available; this is {@code Entity#getUUID()}. */
        ENTITY,

        /** The entity was not there yet; this is a stand-in that is reconciled as soon as it is. */
        PROVISIONAL
    }

    /**
     * One value a carrier is (or is about to be) known by.
     *
     * @param carrier the identity itself
     * @param phase where it came from
     */
    public record Identity(CarrierId carrier, Phase phase) {

        /** The identity derived from a contraption entity. */
        public static Identity ofEntity(UUID entityUuid) {
            return new Identity(CarrierId.contraption(entityUuid), Phase.ENTITY);
        }

        /**
         * The identity of a structure the caller already knows the sort of.
         *
         * <p>The kind is <b>not</b> a detail: a Sable sub-level and a Create contraption can carry the
         * same UUID - the author's log has both a {@code c:6c60aeef} and an {@code s:6c60aeef} for one
         * structure, because the contraption entity and the sub-level share it - and filing a sub-level
         * under {@link CarrierKind#CONTRAPTION} is what made the Create half's orphan sweep declare a
         * live sub-level gone. See {@code CarrierStore#startMoving} for the whole story.
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
     * @param claim the identity the claim is currently filed under
     * @param present the identity the index actually holds for this structure, or {@code null} when the
     *     index has nothing for it at all
     * @param entityUuid the contraption entity's UUID, or {@code null} while there is still no entity
     */
    public record Resolution(Identity claim, @Nullable Identity present,
                             @Nullable UUID entityUuid) {

        /** True when the index has to be re-keyed for {@link #claim} to be the right identity. */
        public boolean rekeys() {
            return present != null && !present.carrier().equals(claim.carrier());
        }

        /**
         * One line for the log: what was decided, and why it can be trusted.
         *
         * <p>Written for the {@code claim} stage of the three-stage diagnostic, so that "was the host
         * claimed" and "under which identity" are answered by the same line.
         */
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
     * The identity a claim made at this moment belongs under.
     *
     * <p>The whole of the first phase: if the entity is there, use it and never look back; if it is
     * not, spend a provisional id rather than dropping the claim. The caller never has to decide
     * whether "no entity" means "no claim".
     *
     * <p>A provisional id is always a contraption's: it is minted during an assembly, which is a Create
     * concept. An entity-backed claim takes the kind the caller passed - which is the fix for "a Sable
     * sub-level was filed as a Create contraption"; see {@link Identity#of}.
     *
     * @param entityUuid the contraption entity's UUID, or {@code null} when the assembly has not
     *     attached one yet
     * @param provisional the stand-in to use when it has not
     */
    public static Identity claim(@Nullable UUID entityUuid, UUID provisional) {
        return entityUuid != null ? Identity.ofEntity(entityUuid) : Identity.ofProvisional(provisional);
    }

    /**
     * The same, keeping the caller's {@link CarrierKind} on the entity-uuid path.
     *
     * <p>Two overloads rather than one, because the kind only has a meaning when the UUID names a real
     * structure: a provisional id is a value this mod minted during a Create assembly, so it is a
     * contraption by construction and letting a caller declare it a sub-level would be a lie.
     *
     * @param entityUuid the structure's UUID, or {@code null} while it has none
     * @param provisional the stand-in to use when it has none
     * @param kind which sort of structure the UUID names
     */
    public static Identity claim(@Nullable UUID entityUuid, @Nullable CarrierId provisional,
                                 CarrierKind kind) {
        if (entityUuid != null) {
            return Identity.of(entityUuid, kind);
        }
        if (provisional != null && provisional.isSubLevel()) {
            // A sub-level has no assembly phase and always has its own UUID, so a provisional sub-level
            // claim is really a caller that already knows the identity. Pass it through unchanged
            // rather than re-deriving a kind from nothing.
            return new Identity(provisional, Phase.ENTITY);
        }
        if (provisional == null) {
            // The caller has neither an entity nor a stand-in. The claim is about to be refused by the
            // caller with a warning; a zero UUID here keeps this method total rather than throwing.
            return Identity.ofProvisional(new UUID(0L, 0L));
        }
        return Identity.ofProvisional(provisional.uuid());
    }

    /**
     * <b>The decision table.</b> Which identity the claim belongs under, given everything now known.
     *
     * <p>Four cases, and each one is a statement about what is true rather than a guess:
     *
     * <table>
     *   <tr><th>claim was made under</th><th>entity now</th><th>decision</th></tr>
     *   <tr><td>the entity</td><td>anything</td>
     *       <td>keep it. Either it is the same entity (a repeated callback) or the contraption object
     *           was reused for a genuinely new machine - and the entity is the newer, authoritative
     *           answer either way.</td></tr>
     *   <tr><td>provisional</td><td>present and different</td>
     *       <td><b>reconcile</b>: re-key the index from the provisional id to the entity's. This is
     *           the case the defect lived in.</td></tr>
     *   <tr><td>provisional</td><td>the same UUID</td>
     *       <td>already done; a second callback must not re-key anything.</td></tr>
     *   <tr><td>provisional</td><td>still absent</td>
     *       <td>keep the provisional identity and try again on the next callback. The claim stands
     *           either way, which is the invariant.</td></tr>
     * </table>
     *
     * @param claim what the current claim is filed under
     * @param present what the index holds for this structure, or {@code null}
     * @param entityUuid the contraption entity's UUID, or {@code null} when there is still none
     */
    public static Resolution resolve(Identity claim, @Nullable Identity present,
                                     @Nullable UUID entityUuid) {
        if (claim.phase() == Phase.ENTITY) {
            // Nothing to settle: an entity-backed identity is the authoritative one, and re-keying it
            // would only be able to move a claim that is already under the right name.
            return new Resolution(claim, present, entityUuid);
        }
        if (entityUuid == null) {
            // No entity yet: hold the provisional identity. Deliberately not an error - this is the
            // normal state during an assembly, and the claim it protects is already in the index.
            return new Resolution(claim, present, null);
        }
        if (claim.carrier().uuid().equals(entityUuid)) {
            // The entity was there all along under the provisional id, or a previous callback already
            // settled this carrier. Either way it is finished.
            return new Resolution(Identity.ofEntity(entityUuid), present, entityUuid);
        }
        // THE RECONCILIATION: the provisional claim is re-labelled onto the entity that has just
        // appeared. From here on only the entity UUID is used, on both sides of the move.
        return new Resolution(Identity.ofEntity(entityUuid), present, entityUuid);
    }

    /**
     * A provisional UUID for one structure being assembled.
     *
     * <p>Built from a process-local serial (so two structures in one tick cannot collide), the
     * contraption object's identity hash (so the same structure gets the same id on every callback of
     * one assembly) and the structure's anchor, which is mixed in only so that a provisional id printed
     * in a log next to a real one is still readable by a human.
     *
     * <p>Version 8 and the RFC variant are stamped on top of the mixed value, so the result is a
     * well-formed UUID that no entity can ever carry - see {@link #PROVISIONAL_VERSION}.
     *
     * @param serial a value unique to this structure within this process
     * @param identityHash {@code System.identityHashCode(contraption)}
     * @param anchorX the structure's anchor, or 0 when it is not known
     * @param anchorY the structure's anchor, or 0 when it is not known
     * @param anchorZ the structure's anchor, or 0 when it is not known
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

        // version = 8 (bits 12..15 of the most significant half), variant = RFC 4122 (top two bits of
        // the least significant half), exactly as UUID#version()/#variant() read them back.
        long msb = (high & 0xFFFF_FFFF_FFFF_0FFFL) | 0x0000_0000_0000_8000L;
        long lsb = (low & 0x3FFF_FFFF_FFFF_FFFFL) | PROVISIONAL_VARIANT;
        return new UUID(msb, lsb);
    }

    /**
     * Whether a carrier id is one of ours rather than a real entity's.
     *
     * <p>This is what makes the put-down side a <b>dual-path match</b>: a lookup can be tried under the
     * entity UUID and, when the structure came apart before it ever had one, under the provisional id
     * the claim was filed under. Recognising it from the UUID alone means the put-down path needs
     * nothing but the id it already has.
     */
    public static boolean isProvisional(CarrierId carrier) {
        return carrier != null && carrier.uuid().version() == PROVISIONAL_VERSION;
    }

    /** Whether a bare UUID is a provisional one; the same test, for an id whose kind is not yet known. */
    public static boolean isProvisional(@Nullable UUID uuid) {
        return uuid != null && uuid.version() == PROVISIONAL_VERSION;
    }
}
