package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

/**
 * <b>The selection box, as the client decides it.</b>
 *
 * <p>客户端曾被问了一个只有服务器能回答的问题: 选中框画在哪。服务器每个 level tick 重算答案
 * ({@code WrenchSelection#refresh})、变化时重发，客户端只按这个答案画（{@link HostRenderPass#liveSelection}），
 * 本地只剩"客户端有没有那块区块"这一问。选中不是一个独立的方框，而是该方块自身边框的颜色: 一个方块一帧，
 * 没有额外几何体、缓冲或姿态。
 */
class SelectionBoxTest {

    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));

    @Test
    @DisplayName("the client's whole rule: the server's answer, and a chunk to draw in")
    void theClientDrawsOnTheServersAnswerAlone() {
        BlockPos picked = new BlockPos(-233, -60, 88);

        assertSame(picked, HostRenderPass.liveSelection(picked, true, true),
                "a live pick with its chunk here is the box");
        assertNull(HostRenderPass.liveSelection(picked, false, true),
                "the server says the host is gone, so the box goes with it wherever the pick was");
        assertNull(HostRenderPass.liveSelection(picked, true, false),
                "a chunk this client has not got is nothing to draw in, and asking the level about it is how a "
                        + "renderer crashes on a position it was never sent the world for");
        assertNull(HostRenderPass.liveSelection(null, true, true),
                "nothing picked is nothing drawn, whatever the flag says");
    }

    @Test
    @DisplayName("the client's copy keeps the flag with the position, and forgets both together")
    void theClientsCopyIsOneAnswer() {
        BlockPos picked = new BlockPos(4, 5, 6);
        WrenchClientState.setSelection(picked, true);
        assertEquals(picked, WrenchClientState.selection());
        assertTrue(WrenchClientState.isLiveHost(), "a live pick is what the server said it was");

        WrenchClientState.setSelection(picked, false);
        assertEquals(picked, WrenchClientState.selection(), "a pick whose host is gone is still the pick");
        assertFalse(WrenchClientState.isLiveHost(), "and it draws nothing until the server says otherwise");

        WrenchClientState.setSelection(null, true);
        assertNull(WrenchClientState.selection());
        assertFalse(WrenchClientState.isLiveHost(),
                "a live flag with no position is not a highlight - there is nothing to frame");

        WrenchClientState.setSelection(picked, true);
        WrenchClientState.clear();
        assertNull(WrenchClientState.selection());
        assertFalse(WrenchClientState.isLiveHost(), "leaving the world leaves nothing behind");
        assertNull(WrenchClientState.carriedCarrier());
        assertNull(WrenchClientState.carriedLocal(), "and no structure is remembered either");
    }

    @Test
    @DisplayName("a carried pick's structure and local block travel together, and die with the pick")
    void theCarriedIdentityIsPartOfTheSameAnswer() {
        BlockPos picked = new BlockPos(-233, -60, 88);
        BlockPos local = new BlockPos(20481032, 128, 20489224);
        WrenchClientState.setSelection(picked, true, CONTRAPTION, local);
        assertEquals(CONTRAPTION, WrenchClientState.carriedCarrier(),
                "the box has to know WHICH structure, so it can be placed in that structure's own frame");
        assertEquals(local, WrenchClientState.carriedLocal(), "and which of its blocks");

        WrenchClientState.setSelection(picked, true, null, null);
        assertNull(WrenchClientState.carriedCarrier(),
                "a world pick names no structure, and says so - the previous carried pick's one must not survive it");
        assertNull(WrenchClientState.carriedLocal());

        WrenchClientState.setSelection(picked, true, CONTRAPTION, local);
        WrenchClientState.setSelection(picked, false, CONTRAPTION, local);
        assertNull(WrenchClientState.carriedCarrier(),
                "a pick whose host is gone has no structure to place a box in - the live flag gates the identity too, "
                        + "so a stale pick cannot leave a box behind on a machine it is no longer inside");

        WrenchClientState.setSelection(picked, true, CONTRAPTION, local);
        WrenchClientState.setSelection(null, true, CONTRAPTION, local);
        assertNull(WrenchClientState.carriedCarrier(), "and no position is no highlight, whatever else came with it");
    }

    @Test
    @DisplayName("the pick is the COLOUR of the border the block already draws - not a second box")
    void thePickReplacesTheBorderItAlreadyHas() throws IOException {
        String pass = readMain("client", "HostRenderPass.java");
        String world = bodyOf(pass, "private static void drawShells");
        String carried = bodyOf(pass, "private static void drawCarriedShellsInto");

        assertTrue(world.contains("isThePick(host.pos()) ? SELECTED_FRAME : gates.effectiveFrame(host.slot())"),
                "the world path REPLACES the host's border colour with the selection colour - the frame the block already "
                        + "draws is what tells the player they picked it");
        assertTrue(carried.contains("isThePick(host.carrier(), host.local())")
                        && carried.contains("? SELECTED_FRAME"),
                "and so does the carried path, for the host whose structure AND local block the pick names");
        assertTrue(world.contains("if (tint == 0 && frame == 0 && wireFrame == 0 && !holdsThePick)")
                        && carried.contains("if (tint == 0 && frame == 0 && wireFrame == 0 && !holdsThePick)"),
                "a list that holds the pick is drawn even with every gate shut: the pick is the wrench's, so taking the "
                        + "goggles off must not take it with them");
        assertTrue(world.contains("boolean holdsThePick = false;")
                        && carried.contains("boolean holdsThePick = false;"),
                "one lookup per list per frame, before the loop that draws - the rule is asked once, not per host");

        // Asserted on the CALL, not the name: one frame per block, so a second box cannot come back.
        assertFalse(pass.contains("drawCarriedHighlight("),
                "the second box is GONE - it was the same geometry at the same depth as the host's own border");
        assertFalse(pass.contains("carriedPickFrame") || pass.contains("carriedPickIsDrawn"),
                "and so is everything that existed only to place it (the frame from the interpolated pose, and the "
                        + "predicate that decided which path owned the pick)");
        assertFalse(pass.contains("drawHighlight(last, level, selected"),
                "the world path's own selection box is gone with it: the hover frame is the only box this pass draws now");
        assertTrue(pass.contains("drawHighlight(last, level, hovered, HOVER_FRAME, uv)"),
                "the hover frame marks the block being pointed at, not the picked one, and is the only box left");

        assertTrue(pass.contains("private static boolean isThePick(BlockPos pos)")
                        && pass.contains("private static boolean isThePick(CarrierId carrier, BlockPos local)"),
                "and the pick is identified the two ways the two frames name a block: a world position, or a structure "
                        + "plus a local coordinate");

        String worldSelection = bodyOf(pass, "private static BlockPos selectedWorldBlock");
        assertTrue(worldSelection.contains("WrenchClientState.carriedCarrier() != null"),
                "a pick inside a machine must not colour a world host that happens to sit where the pick is drawn");
    }

    @Test
    @DisplayName("the render pass draws it: the pick is the server's answer, and no local veto is left")
    void thePassDrawsOnTheFlagAlone() throws IOException {
        String pass = readMain("client", "HostRenderPass.java");
        String selection = bodyOf(pass, "private static BlockPos selectedWorldBlock");
        String rule = bodyOf(pass, "static BlockPos liveSelection");

        assertTrue(rule.contains("picked == null || !liveHost || !loaded"),
                "the rule has exactly three inputs, and the unit test above drives the same expression production "
                        + "runs");
        assertTrue(selection.contains("WrenchClientState.isLiveHost()"),
                "the server's flag is the first thing asked");
        assertTrue(selection.contains("level.hasChunkAt(picked)"),
                "and the chunk check is the only local one left");
        assertFalse(selection.contains("isAir()"),
                "NO AIR CHECK: the position a carried pick names is where the machine DRAWS the block, and the "
                        + "world has air there by construction");
        assertFalse(selection.contains("hasHostAt") || selection.contains("ClientHostCache"),
                "and no world-host record either: a carried host has none by construction, which keeps the ghost away");

        String cache = readMain("client", "ClientHostCache.java");
        assertFalse(cache.contains("boolean hasHostAt("),
                "the method that asked the question the client cannot answer must not come back");
    }

    @Test
    @DisplayName("one sender: the carried click no longer builds a payload of its own")
    void thereIsExactlyOneSenderOfTheHighlight() throws IOException {
        String carrier = readMain("carrier", "CarrierInteraction.java");
        assertFalse(carrier.contains("WrenchSelectionPayload"),
                "the carried click must not send the highlight itself: `liveHost` would be left at the "
                        + "two-argument constructor's default of FALSE, so a carried pick would tell the client "
                        + "'this is not a host'. WrenchSelection#refresh is the one sender");

        String network = readMain("network", "RCNetwork.java");
        assertFalse(network.contains("public WrenchSelectionPayload(boolean hasSelection, BlockPos pos) {"),
                "and the constructor that could leave the flag unsaid must not come back");
        assertTrue(network.contains("private static final String VERSION = \"12\";"),
                "a world position cannot describe a block a machine draws between the grid lines, so the carried "
                        + "pick's structure and local block are on the wire and the device reading is a varint: "
                        + "the two sides must be the same build");
    }

    // ------------------------------------------------------------- helpers --

    /** Finds one of the mod's main sources, failing loudly rather than silently skipping the guard. */
    private static String readMain(String packageName, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packageName, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + "); this guard reads the source on purpose, "
                + "so it must not be skipped silently");
    }

    /** The body of the first member whose declaration starts with {@code signature}, scoped by brace counting: a
     * line window would still pass once the method grows, which is how a guard quietly stops guarding. */
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
}
