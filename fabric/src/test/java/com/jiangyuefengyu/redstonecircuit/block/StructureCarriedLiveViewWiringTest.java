package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A carried structure's picture is sampled every tick while its input is changing, and the three links of that
 * chain are wiring rather than arithmetic.
 *
 * <p>The client's picture must not be throttled on the carried path: the burst that bypasses
 * {@code LIVE_VIEW_INTERVAL} is armed inside {@code pushInjection}, and {@code carriedTick} reaches it through
 * one call. Reordering those two lines would put the miniature back to one frame in ten and nothing would fail,
 * because the throttle is silent by construction.
 */
class StructureCarriedLiveViewWiringTest {

    @Test
    @DisplayName("the carried tick sends the picture (at the DRAWN position) and then pushes, in that order")
    void theCarriedTickReachesTheCallThatArmsTheBurst() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), "public static void carriedTick(");

        assertTrue(body.contains("product.sendLiveView(level, pocket, frame.worldPosOf(localPos));"),
                "the carried tick has to address the picture to where the structure is DRAWN: this block entity sits "
                        + "at a contraption's local coordinate, and a reach test there answers \"nobody is watching\" "
                        + "for the whole ride");
        assertTrue(body.contains("product.pushInjection(pocket);"),
                "and it has to push, because that call is the only thing on this path that arms the burst the picture "
                        + "path consults - a burst nobody arms is one frame in ten");
        int sent = body.indexOf("product.sendLiveView(");
        int pushed = body.indexOf("product.pushInjection(");
        assertTrue(sent >= 0 && pushed > sent,
                "the picture goes out before the push, exactly as the standing tick does it: the arming then belongs "
                        + "to this tick and the sampling to the next one");
    }

    @Test
    @DisplayName("a change of a cell arms the burst, a sampled change re-arms it, and it outlives a piston's move")
    void theBurstIsArmedWhereTheCellsChange() throws IOException {
        String source = readStructureBlockEntity();
        String push = bodyOf(source, "public int pushInjection(ServerLevel pocket)");

        assertTrue(push.contains("if (changed > 0) {"),
                "only a real change inside the plot is a reason to look closely - that is what keeps the burst from "
                        + "re-arming on every tick it causes and sampling for ever");
        assertTrue(push.contains("this.liveViewBurstUntil = Math.max(this.liveViewBurstUntil, "
                        + "pocket.getGameTime() + LIVE_VIEW_BURST_TICKS);"),
                "the recording line beside it (\"injection at … changed N cell(s)\") is written in this very "
                        + "branch, so every such line is also the receipt for a burst armed to "
                        + "now + LIVE_VIEW_BURST_TICKS");
        assertTrue(bodyOf(source, "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)")
                        .contains("this.liveViewBurstUntil = Math.max(this.liveViewBurstUntil, gameTime + "
                                + "LIVE_VIEW_BURST_TICKS);"),
                "and a sample that saw a change re-arms it: while something is still changing, keep looking "
                        + "every tick");

        int burst = intConstant(source, "LIVE_VIEW_BURST_TICKS");
        int move = intConstant(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "StructureLiveView.java"), "MOVE_TICKS");
        assertTrue(burst > move, "the window has to outlive the thing it exists for: a piston's whole move is "
                + move + " tick(s) and the burst is " + burst);
    }

    @Test
    @DisplayName("an open burst bypasses the interval, and the reach test asks the drawn position")
    void theBurstBypassesTheIntervalOnTheDrawnPosition() throws IOException {
        String body = bodyOf(readStructureBlockEntity(),
                "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)");

        assertTrue(body.contains("!resending && gameTime >= this.liveViewBurstUntil"),
                "the burst is what opens the gate");
        int burstGate = body.indexOf("gameTime >= this.liveViewBurstUntil");
        int intervalGate = body.indexOf("Math.floorMod(gameTime, LIVE_VIEW_INTERVAL)");
        assertTrue(burstGate > 0 && intervalGate > burstGate,
                "and it is asked BEFORE the interval, so an open window never reaches the ten-tick test: with the "
                        + "tests the other way round a carried structure would be sampled once in ten while its "
                        + "input is changing");
        assertTrue(body.contains("Vec3.atCenterOf(drawnPos)"),
                "who is watching is decided at the drawn position, not at the carrier's own coordinate");
        assertTrue(body.contains("new RCNetwork.StructureLivePayload(this.worldPosition, batch, ids, restart)"),
                "while the payload stays keyed by this block entity's own position - the key the client applies the "
                        + "cells under (StructureLiveViews.apply(payload.pos(), …)) and the key its renderer looks "
                        + "them up by (StructurePreviewGeometry.cells(level, preview, product.getBlockPos()))");
    }

    // ------------------------------------------------------------- helpers --

    private static String readStructureBlockEntity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlockEntity.java");
    }

    /** Reads a source file of this project, addressed from its own directory, e.g. {@code src/main/java/...}. */
    private static String read(String... parts) throws IOException {
        Path path = Path.of("", parts);
        if (Files.isRegularFile(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
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

    /** The value of a {@code static final int} constant declared in {@code source}. */
    private static int intConstant(String source, String name) {
        Matcher matcher = Pattern.compile("static final int " + name + " = (\\d+);").matcher(source);
        assertTrue(matcher.find(), "the source no longer declares " + name + "; the guard is stale");
        return Integer.parseInt(matcher.group(1));
    }
}
