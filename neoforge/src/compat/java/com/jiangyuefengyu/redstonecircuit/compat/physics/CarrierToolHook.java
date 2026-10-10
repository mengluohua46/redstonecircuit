package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierHookLedger;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierInteraction;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;

/**
 * The Create half of the carrier-side wrench and rotation rod: a {@code MovingInteractionBehaviour} registered per host
 * block type.
 *
 * <p>A contraption's block click <b>never becomes a block click</b>: the client ray-traces the contraption and sends a
 * {@code ContraptionInteractionPacket}, which lands in {@code AbstractContraptionEntity#handlePlayerInteraction} and
 * looks the clicked position up in {@code contraption.interactors} - a registry <b>keyed by {@link Block}</b>, filled at
 * assembly time - so the only reliable hook is a behaviour registered for the host's block type, as
 * {@link MovementHostHook} does for {@code MovementBehaviour.REGISTRY}.
 *
 * <p>The block state in the structure is left exactly as it is, and everything the click means is carried data:
 * Create's three numbers (which structure, which local coordinate, which face) go to
 * {@link CarrierInteraction#handleContraptionClick}, which owns the whole decision, including which gesture a click is
 * from the held item and the sneak state. A click the carrier layer refuses comes back as {@code false}, Create's own
 * "not handled".
 *
 * <p>Registration: one behaviour per block type, registered the first time that type is seen holding a component, first
 * wins; a block type somebody else has already claimed is left alone, which is reported rather than hidden. The Sable
 * entry point is a different delivery and is unaffected.
 */
final class CarrierToolHook {

    /**
     * The one behaviour instance every host block type shares.
     *
     * <p>Stateless - everything it needs is in the arguments - so sharing it across block types is safe.
     */
    private static final MovingInteractionBehaviour HOOK = new MovingInteractionBehaviour() {

        @Override
        public boolean handlePlayerInteraction(Player player, InteractionHand hand, BlockPos localPos,
                                               AbstractContraptionEntity entity) {
            return onClick(player, hand, localPos, entity);
        }
    };

    /** Block types already given the hook, so a block is registered at most once. */
    private static final Set<Block> REGISTERED = new HashSet<>();

    private CarrierToolHook() {
    }

    /**
     * Registers the hook for one block type, the first time that block type is seen holding a component.
     *
     * <p>Called from the placement paths, on the server thread, exactly as the movement hook is. Never
     * registers twice for the same block, so the log line appears exactly once per block type.
     */
    static void register(Block block) {
        if (block == null || !REGISTERED.add(block)) {
            return;
        }
        String id = block.builtInRegistryHolder().key().location().toString();
        try {
            MovingInteractionBehaviour existing =
                    MovingInteractionBehaviour.REGISTRY.get(block.defaultBlockState());
            if (existing != null) {
                RCConfig.LOGGER.info("[redstonecircuit] not hooking the carried tools for {}: another "
                                + "MovingInteractionBehaviour is already registered for it, so a carried "
                                + "host of this type cannot be operated with the wrench or the rotation "
                                + "rod from a Create contraption",
                        id);
                // The same fact, kept where `/rc physics` can still read it an hour later: the line above is written
                // exactly once and is easy to miss in a long log, and the report is where an operator looks when the
                // tools "do nothing" on a contraption.
                CarrierHookLedger.HOOKS.refuse(id, "another MovingInteractionBehaviour is already "
                        + "registered for it (first wins, and overwriting it would break that mod)");
                return;
            }
            MovingInteractionBehaviour.REGISTRY.register(block, HOOK);
            RCConfig.LOGGER.info("[redstonecircuit] registered MovingInteractionBehaviour for {} "
                            + "(first block of this type to hold a component): a right-click on a "
                            + "contraption block of this type now reaches the carrier-side wrench and "
                            + "rotation rod",
                    id);
            CarrierHookLedger.HOOKS.hook(id);
        } catch (RuntimeException | LinkageError failed) {
            REGISTERED.remove(block);
            RCConfig.LOGGER.warn("[redstonecircuit] could not register MovingInteractionBehaviour for {}: {}",
                    block, failed.toString());
            CarrierHookLedger.HOOKS.refuse(id, "registration threw: " + failed);
        }
    }

    /** How many block types currently carry the tool hook; for the {@code /rc physics} report. */
    static int registeredCount() {
        return REGISTERED.size();
    }

    /**
     * The registration ledger's report lines, for {@code /rc physics}.
     *
     * <p>Bounded, read-only and never throws: it is a list of strings this class collected while
     * registering. See {@link CarrierHookLedger} for why the count above and the ledger's own numbers can
     * legitimately differ.
     */
    static List<String> hookReport() {
        return CarrierHookLedger.HOOKS.describe();
    }

    /**
     * One click on a contraption block.
     *
     * <p>Every failure mode here is a {@code false} return, which is Create's "not handled" answer - so a
     * block this mod does not own (the overwhelming majority of the machine) keeps whatever behaviour the
     * click would have had. Nothing is thrown out of here: this runs inside Create's own packet handling,
     * and an exception there would take the packet down.
     */
    private static boolean onClick(Player player, InteractionHand hand, BlockPos localPos,
                                   AbstractContraptionEntity entity) {
        try {
            if (!(player instanceof ServerPlayer serverPlayer) || entity == null || localPos == null) {
                return false;
            }
            return CarrierInteraction.handleContraptionClick(serverPlayer, entity.getUUID(), localPos,
                    faceOf(player), hand);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carrier tool hook failed at {}: {}", localPos,
                    failed.toString());
            return false;
        }
    }

    /**
     * Which face the player clicked. {@code ContraptionInteractionPacket} does carry a {@code face}, but
     * {@code AbstractContraptionEntity#handlePlayerInteraction} does not pass it on - only the position is handed to the
     * behaviour. Reconstructing it from the player's gaze is the same source Create's own client side picks a contraption
     * block from, and it is stable for the duration of one click. A helper rather than an inline call, so the one place
     * this mod reads a player's view direction for a carrier action is nameable.
     */
    private static net.minecraft.core.Direction faceOf(Player player) {
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0F);
        return net.minecraft.core.Direction.getNearest(look.x, look.y, look.z);
    }
}
