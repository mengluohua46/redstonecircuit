package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The order of the two halves of a structure's tick is the cross-boundary delay, and nothing else is.</b>
 *
 * <p>A structure stands in the world and its machine stands in a pocket dimension, which the server ticks
 * <em>after</em> the world. So an external signal that changes during tick T is read by the inward half at the end of
 * T, its write into the pocket is synchronous, and the outward half of <b>T+1</b> is what answers the world: one tick,
 * half at each end. That is the delay every circuit built against this mod is timed to, and it is decided by exactly
 * two positions in {@code StructureBlockEntity#serverTick}:
 *
 * <ul>
 *   <li>{@code updateOutput} runs <b>before</b> {@code readFaces}. Moving it below makes the outward half read a pocket
 *       that already contains this tick's input, and the delay silently becomes <b>two</b> ticks;</li>
 *   <li>{@code readFaces} runs <b>before</b> the face-driven {@code pushInjection}, and that is the last thing the tick
 *       does. Moving the write above the read - or above {@code updateOutput} - makes the same tick see its own input,
 *       and the delay silently becomes <b>zero</b>, which is a machine that is faster than the wire feeding it.</li>
 * </ul>
 *
 * <p>Neither mistake throws, logs or renders differently: they change when a lamp lights up by one tick, which is the
 * only thing a player of a redstone mod ever measures. So both positions are pinned here, on the source, and the
 * synchronous write they depend on is pinned with them.
 *
 * <p>The other half of the premise - that the pocket level is ticked after the world - is vanilla's own ordering of
 * {@code MinecraftServer#tickChildren} over the levels it holds, and this mod adds no code to it: the pocket is an
 * ordinary datapack dimension (see {@code PocketDimension}). It is not pinned here because there is nothing here to
 * pin; what is pinned is everything this mod decides.
 *
 * <p>The manual mode is the one path that pushes without reading a face, and it is pinned as such: its
 * {@code pushInjection} sits <em>above</em> {@code readFaces} because its input is a player's key press, not a face, and
 * the branch returns before the face path is reached - that return is asserted too, or "manual" would be a second way to
 * inject the same tick the output was published in.
 */
class CrossBoundaryOrderTest {

    private static final String SERVER_TICK =
            "public static void serverTick(Level level, BlockPos pos, BlockState state, StructureBlockEntity product)";
    private static final String OUTWARD = "product.updateOutput(server, pocket, frame);";
    private static final String READ_FACES = "int[] now = product.readFaces(server, pos, frame);";
    private static final String INWARD = "product.pushInjection(pocket);";
    private static final String MANUAL = "if (product.ioMode == IoMode.MANUAL) {";

    @Test
    @DisplayName("the outward half runs before the inward half, which is the one-tick contract")
    void theOutwardHalfRunsFirst() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SERVER_TICK);

        assertTrue(body.contains(OUTWARD), "the outward half is called where the tick publishes its answer");
        assertTrue(body.contains(READ_FACES), "and the inward half reads the six faces");

        int outward = body.indexOf(OUTWARD);
        int inward = body.indexOf(READ_FACES);
        assertTrue(outward > 0 && inward > outward,
                "updateOutput has to run BEFORE readFaces: below it, the outward half reads a pocket that already holds "
                        + "this tick's input and the cross-boundary delay becomes TWO ticks instead of one");
        assertTrue(body.lastIndexOf(INWARD) > inward,
                "and the face-driven push has to run AFTER the read: above it, the same tick sees its own input and the "
                        + "delay becomes ZERO, which is a structure faster than the wire that feeds it");
    }

    @Test
    @DisplayName("the inward half is the last thing the tick does")
    void theInwardHalfClosesTheTick() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SERVER_TICK);

        int last = body.lastIndexOf(INWARD);
        assertTrue(last > 0, "the face-driven push is the inward half of the tick");
        assertTrue(body.substring(last + INWARD.length()).isBlank(),
                "nothing may run after the push: the write has to be the last thing the tick does, so that the world's "
                        + "own earlier half of the same tick can never have read a pocket this write had already changed");
    }

    @Test
    @DisplayName("pushInjection is reached twice: the face path, and a manual branch that returns before the faces are read")
    void theManualBranchIsNotASecondInwardHalf() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SERVER_TICK);

        assertTrue(countOf(body, INWARD) == 2,
                "two call sites are the whole of it: the face path below, and the manual branch above - a third one "
                        + "would be a way to inject outside the order this test pins");
        int manual = body.indexOf(MANUAL);
        int readFaces = body.indexOf(READ_FACES);
        assertTrue(manual > 0 && manual < readFaces,
                "the manual branch is above the read, so its own push is above it too");
        int manualPush = body.indexOf(INWARD, manual);
        assertTrue(manualPush > manual && manualPush < readFaces,
                "and the manual push it makes is inside that branch");
        assertTrue(body.substring(manual, readFaces).contains("return;"),
                "the branch has to return: a manual press must not fall through into the face path, or a key press and a "
                        + "face would both inject the same tick the output was published in");
    }

    @Test
    @DisplayName("the pocket write is synchronous: applyInjection schedules nothing")
    void thePocketWriteIsSynchronous() throws IOException {
        String space = readPocketSpace();

        assertFalse(space.contains("scheduleTick"),
                "the half-tick depends on the write landing in the pocket inside the tick it is pushed: a scheduled tick "
                        + "would put the whole delay at one end and make the contract two ticks or more, with the pocket's "
                        + "own tick in between");
        String apply = bodyOf(space, "public int applyInjection(int index, PocketInjection.Sources sources)");
        assertTrue(apply.contains("ledger.update("),
                "and the update is the write: the ledger's changed cells are notified in the same call");
        assertTrue(apply.contains("updateNeighborsAt("),
                "with vanilla's own notification, still inside it - the cells that recompute here are the ones a later "
                        + "read of the same tick has to see");
        assertTrue(apply.indexOf("refreshInjectionGate()") < apply.indexOf("updateNeighborsAt("),
                "and the gate is refreshed before those notifications, because the recomputing dust asks the signal "
                        + "question while the gate is read");
    }

    // ------------------------------------------------------------- helpers --

    private static String readStructureBlockEntity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlockEntity.java");
    }

    private static String readPocketSpace() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure", "pocket",
                "PocketSpace.java");
    }

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings are tried.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }

    private static int countOf(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }
}
