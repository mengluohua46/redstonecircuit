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
 * <b>The Create half of the carrier-side wrench and rotation rod: a
 * {@code MovingInteractionBehaviour} registered per host block type.</b>
 *
 * <h2>What {@code javap} settled, and why this is the hook and not a guessed one</h2>
 * P1.20 bet on "a click on a physicalised block arrives as {@code PlayerInteractEvent.RightClickBlock}
 * at plot coordinates". That is true for a <b>Sable sub-level</b> and false for a Create
 * <b>contraption</b>, and the difference is visible in the bytecode:
 *
 * <pre>
 *   ContraptionHandlerClient#rightClickingOnContraptionsGetsHandledLocally
 *       -&gt; rayTraceContraption(...)                     // the contraption's own blocks
 *       -&gt; new ContraptionInteractionPacket(hand, entityId, localPos, face)
 *   ContraptionInteractionPacket#handle(ServerPlayer)
 *       -&gt; Level#getEntity(target) instanceof AbstractContraptionEntity
 *       -&gt; entity.handlePlayerInteraction(player, localPos, face, hand)
 *   AbstractContraptionEntity#handlePlayerInteraction
 *       -&gt; Contraption.interactors.get(localPos)         // a Map&lt;BlockPos, MovingInteractionBehaviour&gt;
 *       -&gt; behaviour.handlePlayerInteraction(player, hand, localPos, entity)
 *   Contraption#&lt;init&gt;
 *       -&gt; MovingInteractionBehaviour.REGISTRY.get(state)  // SimpleRegistry&lt;Block, ...&gt;
 * </pre>
 *
 * <p>So a contraption's block click <b>never becomes a block click</b>: it is a packet, and the only
 * thing that sees it is whatever is in {@code contraption.interactors}, which Create fills at assembly
 * time from a registry <b>keyed by {@link Block}</b>. That is the same shape as
 * {@code MovementBehaviour.REGISTRY}, which this mod already registers into for
 * {@code minecraft:oak_planks} (see {@link MovementHostHook}) - so the reliable hook for a contraption is
 * a behaviour registered for the host's block type, and this class is it.
 *
 * <h2>What it does not do</h2>
 * It does not touch the contraption: the block state stored in the structure is left exactly as it is,
 * and the method deliberately does <b>not</b> extend {@code SimpleBlockMovingInteraction} - that class's
 * contract is "return a new block state and I will write it into the structure", which would rewrite an
 * {@code oak_planks} block for a redstone action. Everything the click means is carried data, so all
 * this does is hand the three numbers Create gives it (which structure, which local coordinate, which
 * face) to {@link CarrierInteraction#handleContraptionClick}, which owns the entire decision.
 *
 * <h2>Why it does not filter on the held item</h2>
 * It used to call itself "the wrench hook" and return early unless the hand held a wrench or the rod.
 * That filter is now the wrong place for the question: the same behaviour is the only delivery a
 * Create contraption has for <b>every</b> carrier gesture, and a redstone component in a sneaking
 * player's hand is one of them (storing it inside the carried block). Which gesture a click is, is
 * decided once, inside {@code CarrierInteraction}, from the held item and the sneak state - so one
 * hook serves both and the two can never disagree about what the item meant. A click the carrier layer
 * refuses comes back as {@code false}, which is Create's own "not handled".
 *
 * <h2>The registration rule, and what happens when it cannot be kept</h2>
 * One behaviour per block type, registered the first time that type is seen holding a component - the
 * same rule, the same first-wins check and the same honest log line as {@link MovementHostHook}. The
 * registry is a plain map with no freeze check (P0 established that), and a block type somebody else has
 * already claimed is left alone: overwriting it would break that mod, and the cost of not doing so is
 * that <em>this</em> block type's carried components cannot be operated by the wrench from a Create
 * contraption. That is reported rather than hidden, and the Sable entry point is unaffected because it is
 * a different delivery entirely.
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
                // P1.23: the same fact, kept where `/rc physics` can still read it an hour later. The
                // line above is written exactly once and is easy to miss in a long log; the report is
                // where an operator looks when the tools "do nothing" on a contraption.
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
     * Which face the player clicked.
     *
     * <h2>Why the packet's own face is not used</h2>
     * {@code ContraptionInteractionPacket} does carry a {@code face}, but
     * {@code AbstractContraptionEntity#handlePlayerInteraction} does not pass it on - only the position
     * is handed to the behaviour. Reconstructing it from the player's gaze is the same source Create's
     * own client side picks a contraption block from in the first place, and it is stable for the
     * duration of one click.
     *
     * <p>Deliberately a helper rather than an inline call so the one place this mod reads a player's view
     * direction for a carrier action is nameable.
     */
    private static net.minecraft.core.Direction faceOf(Player player) {
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0F);
        return net.minecraft.core.Direction.getNearest(look.x, look.y, look.z);
    }
}
