package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;

import net.minecraft.core.BlockPos;

/**
 * The wrench highlight, as the server decides it.
 *
 * <p>The client-side half is {@code SelectionBoxTest}. This one decides which pick the client is shown, whether the box
 * is drawn at it, and where a pick inside a machine is drawn - which is the <em>carrier's projected position</em>, not
 * the plot cell the component really lives at, and not the position the pick recorded when it was made.
 *
 * <p>Two properties matter, and both are asserted here rather than in a game: <b>the decision table</b> - a carried pick
 * is the newer gesture and wins over a world pick the same player still holds, and each pick keeps its own answer, so a
 * stale carried pick does not fall back to an older world pick's box; and <b>one sender, sent on change</b> - the
 * payload goes out from exactly one place and only when the answer differs from what the client was last told, because
 * a box that is re-asserted every tick cannot be turned off by a lost one.
 */
class SelectionHighlightTest {

    private static final BlockPos WORLD = new BlockPos(10, 64, 10);
    private static final BlockPos DRAWN = new BlockPos(-233, -60, 88);
    /** A plot-space local coordinate, i.e. what a Sable sub-level's own blocks are addressed by. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 128, 20489224);
    private static final CarrierId CONTRAPTION =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-00000000c0ff"));

    @Test
    @DisplayName("the decision: carried wins as the newer gesture, and each pick keeps its own answer")
    void theDecisionTable() {
        assertNull(WrenchSelection.desired(null, false, null),
                "nothing picked anywhere: the client is told nothing at all");

        assertEquals(new WrenchSelection.Shown(null, null, WORLD, true), WrenchSelection.desired(WORLD, true, null),
                "a world pick whose block still holds a component is the box");
        assertEquals(new WrenchSelection.Shown(null, null, WORLD, false), WrenchSelection.desired(WORLD, false, null),
                "and one whose block was taken away is the same position with the box off - not a forgotten pick, "
                        + "because the second click of the gesture still has to be able to use it");

        WrenchSelection.Shown carried = new WrenchSelection.Shown(CONTRAPTION, LOCAL, DRAWN, true);
        assertEquals(carried, WrenchSelection.desired(WORLD, true, carried),
                "with both held, the carried pick is the near end - the world's own select half clears the carrier's "
                        + "ledger, and the carrier's select deliberately does not clear the world's, so the carried "
                        + "pick is by construction the newer one");
        WrenchSelection.Shown staleCarried = new WrenchSelection.Shown(CONTRAPTION, LOCAL, DRAWN, false);
        assertEquals(staleCarried, WrenchSelection.desired(WORLD, true, staleCarried),
                "and a stale carried pick must NOT fall back to the older world pick: that box would be over a block "
                        + "that is not the near end of the line any more");
    }

    @Test
    @DisplayName("a carried pick names its structure AND its local block, or it is not sent as one")
    void aCarriedPickIsAlreadyNamed() throws IOException {
        String source = readMain("logic", "WrenchSelection.java");
        String picker = bodyOf(source, "private static Shown carriedPicker");
        assertTrue(picker.contains("carried.carrier()") && picker.contains("carried.localPos()"),
                "the structure and the local block come from the pick itself - the same two values CarriedTool "
                        + "compares on the second click, so the box and the gesture name one block");
        assertTrue(picker.contains("carried == null || drawn == null"),
                "and a pick whose projected position could not be worked out is not sent as a placed box");

        String publish = bodyOf(source, "private static boolean publish");
        assertTrue(publish.contains("next.carried()") && publish.contains("next.carriedLocal()"),
                "the identity travels with the position and the flag, in one message: they are one answer");
    }

    @Test
    @DisplayName("the answer is recomputed every level tick, and sent only when it changes")
    void theAnswerIsKeptFresh() throws IOException {
        String source = readMain("logic", "WrenchSelection.java");

        String tick = bodyOf(source, "private static void onLevelTick(ServerLevel level)");
        assertTrue(tick.contains("refresh(players.get(i))"),
                "one pass per level tick over the players in that level, which is the only thing that can see a "
                        + "structure pick a block up, carry it away or lose its pose");
        assertTrue(source.contains("ServerTickEvents.END_WORLD_TICK.register(WrenchSelection::onLevelTick)"),
                "and it is the server's own per-world tick that is being answered, handed the level itself: "
                        + "there is no client half of this hook and no block event can reach it");

        String refresh = bodyOf(source, "private static void refresh(ServerPlayer player)");
        assertTrue(refresh.contains("CarriedSelection.get(player)"),
                "both ledgers are read: the carried pick is the one that has to be projected, and the world pick is "
                        + "the ordinary case");
        assertTrue(refresh.contains("carriedPicker(carried, carriedPick, carriedLive)"),
                "the carried answer is built in one place, so the structure, its local block and the projected position "
                        + "cannot disagree with each other");
        assertTrue(refresh.contains("desired(worldPick, worldLive, shownCarried)"),
                "and the decision is the pure method the table above drives - not a second copy of it");
        assertTrue(refresh.contains("publish(player, next)"),
                "which is then published through the one sender");

        String drawn = bodyOf(source, "private static BlockPos drawnPositionOf");
        assertTrue(drawn.contains("CarrierProjection.project(pose, pick.localPos())"),
                "a carried pick's box is the projection of its LOCAL coordinate through the carrier's current pose - "
                        + "this is what makes it follow the machine, and the reason a box inside a machine can be "
                        + "drawn at all");
        assertTrue(drawn.contains("pose == null ? pick.worldPos()"),
                "and a pose that cannot be read is not a refusal: the recorded position is where the player was shown "
                        + "the block when they clicked, which is strictly better than dropping the box for a tick");

        String publish = bodyOf(source, "private static boolean publish");
        assertTrue(publish.contains("current == null ? next == null : current.equals(next)"),
                "the ledger of what the client has is what makes this one packet per CHANGE rather than twenty a "
                        + "second per held pick");
        assertTrue(publish.contains("WrenchSelectionPayload.none()"),
                "and 'nothing' is a message of its own, so a pick that ends up empty does not leave the last box on "
                        + "screen");
    }

    @Test
    @DisplayName("the world pick's answer is still the three lookups, and the carried one is the index")
    void theTwoAnswersAreAskedWhereTheyLive() throws IOException {
        String source = readMain("logic", "WrenchSelection.java");
        String holds = bodyOf(source, "private static boolean holdsHost");
        assertTrue(holds.contains("InnerRedstoneStore.get(level).slotAt(pos)"),
                "frame one: the world store at the pick's own position");
        assertTrue(holds.contains("ProductFrame.carriedAt(level, pos).worldPosOf(pos)"),
                "frame two: a click inside a machine arrives at the plot cell, so the pick has to be mapped to where "
                        + "the block is drawn before the projection can be asked");
        assertFalse(holds.contains("RCConfig.LOGGER"),
                "and the lookup no longer logs: it runs once per player per tick now, so a line here would flood the "
                        + "log - the one line per CHANGE is written by refresh instead");

        String carried = bodyOf(source, "private static boolean holdsCarried");
        assertTrue(carried.contains("CarriedTool.hostAt(index, pick.carrier(), pick.localPos())"),
                "a carried host is live while the structure's own index holds a record at its local coordinate - the "
                        + "same lookup the rest of the carrier layer asks");
        assertTrue(carried.contains("!host.landed()"),
                "and only while it is RIDING: a record that has been put down still answers from the index (see "
                        + "CarriedHost#landed), so without this a pick would keep its box at the position the block was "
                        + "picked up from instead of following it into the world");
        assertTrue(carried.contains("host.slot() != null"),
                "an empty host is a state, not a host with something in it: the player took the component back out");
    }

    // ------------------------------------------------------------- helpers --

    /** Finds one of the mod's main sources, failing loudly rather than silently skipping the guard. */
    private static String readMain(String packageName, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packageName, fileName);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + "); this guard reads the source on purpose, "
                + "so it must not be skipped silently");
    }

    /**
     * The body of the first member whose declaration starts with {@code signature}, scoped by brace counting
     * rather than by a line window: a window still passes once the method grows.
     */
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
