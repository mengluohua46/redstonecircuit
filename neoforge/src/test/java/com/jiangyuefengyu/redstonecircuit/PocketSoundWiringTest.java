package com.jiangyuefengyu.redstonecircuit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The pocket-sound bridge: one gate per level question, and the pocket's own copy is dropped rather than doubled.
 *
 * <h2>What a wrong version of this looks like</h2>
 * The handler runs for <b>every</b> sound the server plays, so what it must not do is work before it has established
 * that the sound came from the hidden dimension; and once it has re-issued a sound on the owner's level it must cancel
 * the original, or the same sound is sent twice the day anything listens inside a plot. Both are one line each and
 * both are invisible in game - a doubled sound and a slow server both look like "the game", not like this file.
 */
class PocketSoundWiringTest {

    @Test
    @DisplayName("只在口袋维度里动手：先判维度，再查主人，取消之后才重发")
    void onlyThePocketIsBridgedAndTheOriginalIsDropped() throws IOException {
        String source = read("src/main/java/com/jiangyuefengyu/redstonecircuit/structure/pocket/PocketSounds.java");

        assertTrue(source.contains("PocketDimension.STRUCTURE_SPACE"),
                "the dimension is the only thing that says 'this sound has nowhere to be heard'");
        int gate = source.indexOf("!pocket.dimension().equals(PocketDimension.STRUCTURE_SPACE)");
        int cancel = source.indexOf("event.setCanceled(true);");
        int reissue = source.indexOf("owner.playSeededSound(");
        assertTrue(gate > 0, "the dimension gate has to be there at all");
        assertTrue(cancel > gate && reissue > cancel,
                "the order is the rule: gate first, cancel before re-issuing, so the pocket's copy never survives");
        assertTrue(source.contains("@EventBusSubscriber(modid = RedstoneCircuit.MODID)"),
                "and it is on the game bus, or nothing is subscribed");
        assertFalse(source.contains("playSound(null, "),
                "the holder carries the sound's own range; re-issuing through SoundEvent would lose it");
    }

    @Test
    @DisplayName("有一个总开关，默认开：不想听就关掉")
    void thereIsOneSwitchAndItIsOn() throws IOException {
        String config = read("src/main/java/com/jiangyuefengyu/redstonecircuit/RCConfig.java");
        assertTrue(config.contains(".define(\"structurePocketSounds\", true)"),
                "on by default: silence was the bug");
        assertTrue(config.contains("public static boolean structurePocketSounds()"),
                "with one accessor, so the handler does not read the spec itself");

        String source = read("src/main/java/com/jiangyuefengyu/redstonecircuit/structure/pocket/PocketSounds.java");
        assertTrue(source.contains("if (!RCConfig.structurePocketSounds())"),
                "and the handler asks it before anything else");
    }

    @Test
    @DisplayName("物理化之后声音跟着载体走：位置走 ProductFrame，与方块自己的信号同一条漏斗")
    void aCarriedStructureSpeaksFromWhereItIsCarried() throws IOException {
        String source = read("src/main/java/com/jiangyuefengyu/redstonecircuit/structure/pocket/PocketSounds.java");

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
        Path underNeoforge = Path.of("neoforge").resolve(relative);
        Path direct = Path.of(relative);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
    }
}
