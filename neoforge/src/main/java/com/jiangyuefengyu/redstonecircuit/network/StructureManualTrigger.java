package com.jiangyuefengyu.redstonecircuit.network;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.structure.ManualTrigger;
import com.jiangyuefengyu.redstonecircuit.structure.pocket.PocketDimension;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The server half of a manual press: turns "player P pressed entry E of the structure at X" into a pulse in a
 * pocket plot.
 *
 * <p>This does the lookups that need a level (is the block there, is it one of ours, which level is the
 * pocket) and hands the numbers to {@link ManualTrigger}, where the rules live and are testable without a
 * running game. One entry point for the packet handler, the {@code /rc structure manual press} command and
 * the {@code /rc pocket manualtest} self-test, so a rule cannot differ between them.
 */
@EventBusSubscriber(modid = RedstoneCircuit.MODID)
public final class StructureManualTrigger {

    /**
     * The game time of each player's last accepted press. Per player and not per structure: the thing being
     * rate limited is a person pressing keys.
     */
    private static final Map<UUID, Long> LAST_PRESS = new HashMap<>();

    /** Either the press went through, or the sentence saying why not. */
    public record Result(boolean accepted, String refusal, int cells) {

        static Result refused(String reason) {
            return new Result(false, reason, 0);
        }
    }

    /** Presses one entry of the structure at {@code pos}. */
    public static Result press(ServerLevel level, UUID playerId, Vec3 playerEye, BlockPos pos, int entry) {
        ServerLevel pocket = PocketDimension.of(level.getServer());
        if (pocket == null) {
            return Result.refused("this server has no pocket dimension, so there is nowhere to push into");
        }
        return pressIn(level, pocket, playerId, playerEye, pos, entry);
    }

    /**
     * The same, for a caller that already knows which level the plot lives in - the pocket dimension in a
     * game, the level the test has in a GameTest harness, which cannot host a datapack dimension at all.
     */
    public static Result pressIn(ServerLevel level, ServerLevel pocket, UUID playerId, Vec3 playerEye,
                                 BlockPos pos, int entry) {
        long now = level.getGameTime();
        if (!level.isLoaded(pos)) {
            return Result.refused("the structure at " + pos.toShortString() + " is not loaded");
        }
        if (!(level.getBlockEntity(pos) instanceof StructureBlockEntity product)) {
            return Result.refused("there is no structure block at " + pos.toShortString());
        }
        if (product.isMissing()) {
            return Result.refused("that structure block holds no structure id (the placeholder case)");
        }
        if (product.plotIndex() < 0) {
            // A press into a block with no pocket says so rather than doing nothing; the block re-asks once a second.
            return Result.refused("that structure has no pocket right now, so a press has nowhere to go "
                    + "(the block asks for one again once a second)");
        }
        // Measured where the structure is DRAWN: for a physicalised structure that is inside the carrier's storage.
        BlockPos drawn = com.jiangyuefengyu.redstonecircuit.carrier.ProductFrame.carriedAt(level, pos)
                .worldPosOf(pos);
        double distance = Math.sqrt(playerEye.distanceToSqr(Vec3.atCenterOf(drawn)));
        ManualTrigger.Verdict verdict = ManualTrigger.check(product.ioMode(), product.manual().size(), entry,
                distance, RCConfig.structureManualRange(), LAST_PRESS.getOrDefault(playerId, -1L), now,
                RCConfig.structureManualCooldownTicks());
        if (!verdict.allowed()) {
            if (RCConfig.debugLog()) {
                RCConfig.LOGGER.info("[redstonecircuit] manual press refused at {}: {}", pos.toShortString(),
                        verdict.refusal());
            }
            return Result.refused(verdict.refusal());
        }
        LAST_PRESS.put(playerId, now);
        // The rate limit lives where the signal enters the pocket (structureInjectionLimitTicks), so a quick press is not refused.
        boolean started = product.pressManual(entry, now, RCConfig.structureManualPulseTicks(), pocket);
        if (!started) {
            // The rules said yes and the block said no, so the panel changed in between; the answer is a refusal.
            return Result.refused("that structure has no manual entry " + entry + " any more");
        }
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] manual press: entry {} of the structure at {} pushed for {} "
                            + "tick(s) ({} block(s) away)", entry, pos.toShortString(),
                    RCConfig.structureManualPulseTicks(), (int) distance);
        }
        return new Result(true, "", product.pulses().size());
    }

    /** Forgets a player's cooldown when they leave: the next session starts with a clean slate. */
    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            LAST_PRESS.remove(player.getUUID());
        }
    }

    /** The last accepted press of a player, or -1 - for the diagnostics and for the tests. */
    public static long lastPressTick(@Nullable UUID playerId) {
        return playerId == null ? -1 : LAST_PRESS.getOrDefault(playerId, -1L);
    }

    /** For the tests: forget every cooldown, as a server restart does. */
    public static void forgetAll() {
        LAST_PRESS.clear();
    }

    private StructureManualTrigger() {
    }
}
