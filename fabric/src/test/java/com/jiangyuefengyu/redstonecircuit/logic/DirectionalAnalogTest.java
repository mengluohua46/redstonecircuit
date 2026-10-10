package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * The borrowed directional-analogue question.
 *
 * <p>A comparator inside a block cannot see 机械动力：航空学's 舵轮 turning: the helm answers 0 to vanilla's analogue
 * question and implements only Simulated's directional one, so this class asks that one - by name, because the API lives
 * inside a mod this project does not compile against. The part a query can never be allowed to do is throw.
 */
class DirectionalAnalogTest {

    @Test
    @DisplayName("nothing to ask about answers 0 instead of throwing")
    void nothingToAskAnswersZero() {
        assertEquals(0, DirectionalAnalog.analogOutputFrom(null, null, null, null),
                "a missing state, level, position or direction is a read that cannot happen, not an exception in the "
                        + "middle of a solve");
        assertFalse(DirectionalAnalog.answersDirectionally(null),
                "and 'no block' is not a directional source");
    }

    @Test
    @DisplayName("a mod that is not installed is a cached 'no', never a loaded class")
    void anAbsentModIsANormalAnswer() throws IOException {
        String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "DirectionalAnalog.java");
        assertTrue(source.contains("Class.forName(INTERFACE, false,"),
                "the lookup must not initialise the class: a comparator query is not a reason to start somebody "
                        + "else's mod");
        assertTrue(source.contains("catch (ClassNotFoundException | LinkageError absent)"),
                "and a mod that is absent - or present but broken - is caught, with the answer cached as 0 for the "
                        + "rest of the session");
        assertTrue(source.contains("Math.max(0, Math.min(15, value))"),
                "a foreign value is clamped to the range the caller's arithmetic is written for, whatever a module "
                        + "returns");
        assertTrue(source.contains("failureLogged"),
                "a failure is said once rather than on every query of every tick");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        Path direct = Path.of("", parts);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }
}
