package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pocket-sound bridge: one gate per level question, one injection point, and the pocket's own copy is dropped
 * rather than doubled.
 *
 * <h2>What a wrong version of this looks like</h2>
 * The bridge is asked about <b>every</b> sound the server plays, so what it must not do is work before it has
 * established that the sound came from the hidden dimension; and once it has re-issued a sound on the owner's level the
 * pocket's copy must be dropped, or the same sound is sent twice the day anything listens inside a plot. Both are one
 * line each and both are invisible in game - a doubled sound and a slow server both look like "the game", not like this
 * file.
 *
 * <h2>Why the injection point is pinned here by its descriptor</h2>
 * Fabric has no {@code PlayLevelSoundEvent}, so the mod brings its own: a mixin at the head of
 * {@code ServerLevel#playSeededSound(Player;DDDHolder;SoundSource;FFJ)V}. That descriptor was read off the mapped jar,
 * and every position-shaped sound funnels through it (the {@code SoundEvent} overloads widen to a {@code Holder} on the
 * way in). A typo there is not a compile error - the mixin simply never applies - so the guard asks for the exact
 * signature and for the registration in the mixin config.
 */
class PocketSoundWiringTest {

    private static final String MIXIN =
            "src/main/java/com/jiangyuefengyu/redstonecircuit/mixin/ServerLevelSoundMixin.java";

    private static final String SOUNDS =
            "src/main/java/com/jiangyuefengyu/redstonecircuit/structure/pocket/PocketSounds.java";

    @Test
    @DisplayName("只在口袋维度里动手：先判维度，查主人，再重发；口袋那一份由注入点丢掉")
    void onlyThePocketIsBridgedAndTheOriginalIsDropped() throws IOException {
        String source = read(SOUNDS);

        assertTrue(source.contains("PocketDimension.STRUCTURE_SPACE"),
                "the dimension is the only thing that says 'this sound has nowhere to be heard'");
        int gate = source.indexOf("!pocket.dimension().equals(PocketDimension.STRUCTURE_SPACE)");
        int reissue = source.indexOf("owner.playSeededSound(");
        assertTrue(gate > 0, "the dimension gate has to be there at all");
        assertTrue(reissue > gate, "the gate comes first: nothing is re-issued before the dimension is known");
        assertTrue(source.contains("return true;") && source.lastIndexOf("return true;") > reissue,
                "and the answer after the replay is what makes the caller drop the pocket's copy, so it has to follow it");
        assertFalse(source.contains("owner.playSound(null, "),
                "the holder carries the sound's own range; re-issuing through SoundEvent would lose it");
        assertFalse(source.contains("@EventBusSubscriber"),
                "nothing here is subscribed to a NeoForge bus: this loader has none, the injection point is the hook");
    }

    @Test
    @DisplayName("注入点：descriptor 与 javap 核过的一致、挂在 HEAD 且可取消、在 mixin 配置里登记")
    void theInjectionPointIsTheOneSignatureEverythingFunnelsThrough() throws IOException {
        String mixin = read(MIXIN);

        assertTrue(mixin.contains("\"playSeededSound(Lnet/minecraft/world/entity/player/Player;DDD"
                        + "Lnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V\""),
                "the position overload, exactly as javap prints it: a wrong descriptor is a mixin that never applies, "
                        + "which reads in game as 'the fix did nothing'");
        assertTrue(mixin.contains("at = @At(\"HEAD\")"),
                "at HEAD, so the original body has not run yet and cancelling it means the pocket never speaks");
        assertTrue(mixin.contains("cancellable = true"), "the cancel is the whole point: without it the sound doubles");
        assertTrue(mixin.contains("@Mixin(ServerLevel.class)"),
                "ServerLevel, because the abstract declaration is on Level and every level a server runs is this one");
        assertTrue(mixin.indexOf("if (PocketSounds.replayAtOwner(") < mixin.indexOf("callback.cancel();"),
                "cancel only on the bridge's positive answer: cancelling unconditionally would silence the game");

        assertTrue(read("src/main/resources/redstonecircuit.mixins.json").contains("\"ServerLevelSoundMixin\""),
                "an unregistered mixin class is a class nobody mixes in");
    }

    @Test
    @DisplayName("有一个总开关，默认开：不想听就关掉")
    void thereIsOneSwitchAndItIsOn() throws IOException {
        String config = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RCConfig.java");
        assertTrue(config.contains("DEFAULTS.put(\"structurePocketSounds\", true)"),
                "on by default: silence was the bug");
        assertTrue(config.contains("public static boolean structurePocketSounds()"),
                "with one accessor, so the bridge does not read the raw key itself");

        String source = read(SOUNDS);
        assertTrue(source.contains("if (!RCConfig.structurePocketSounds())"),
                "and the bridge asks it before anything else");
        assertTrue(source.indexOf("if (!RCConfig.structurePocketSounds())")
                        < source.indexOf("!pocket.dimension().equals(PocketDimension.STRUCTURE_SPACE)"),
                "the switch is asked first: one boolean for every sound in the game, before any level arithmetic");
    }

    @Test
    @DisplayName("物理化之后声音跟着载体走：位置走 ProductFrame，与方块自己的信号同一条漏斗")
    void aCarriedStructureSpeaksFromWhereItIsCarried() throws IOException {
        String source = read(SOUNDS);

        assertTrue(source.contains("ProductFrame.carriedAt(owner, pos)"),
                "a physicalized structure moves the owner block, so its cell is not where the sound belongs");
        assertTrue(source.contains("frame.carried() ? frame.worldPosOf(pos) : pos"),
                "carried or standing is the frame's answer; a second one here would disagree with the signals");
        assertFalse(source.contains("CarrierProjection.project("),
                "and the arithmetic stays in one place: this file asks the frame, it does not re-project");
        assertFalse(source.contains("CarrierStore.poseOf("),
                "for the same reason: poseOf plus project is what ProductFrame already is");
    }

    private static String read(String relative) throws IOException {
        Path direct = Path.of(relative);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath()
                + "; this guard reads the file on purpose");
    }
}
