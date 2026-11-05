package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The 同步火把 group really reaches the screen, and only through the goggles.</b>
 *
 * <p>These rules live in a frame that needs a running client to reach, so they are read off the source: the
 * drawing call, the branch it sits in, and the gate that branch asks. Remove the gate and a player without the
 * goggles sees lines to blocks whose insides are hidden; remove the call and a channel is a colour nobody can
 * find the rest of.
 *
 * <p>The channels come from the client's own copy of what the server synced, never from the hosts a frame
 * happened to draw: a 同步火把 standing in the world has no host slot at all, so a channel list filtered by
 * drawn hosts would leave every group of standing torches without a single line - which is the case the feature
 * exists for.
 */
class SyncGroupLinesWiringTest {

    private static final int CHANNEL = 12;

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    @Test
    @DisplayName("the frame draws the group lines, inside the goggles' own branch")
    void theFrameDrawsTheGroupLines() throws IOException {
        String pass = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "HostRenderPass.java");

        String frame = bodyOf(pass, "private static void runStage(");
        int goggles = frame.indexOf("boolean goggles = wearingGoggles(player);");
        int gates = frame.indexOf("CarrierGeometry.Gates.of(RCConfig.overlayMode(), goggles)");
        assertTrue(goggles > 0, "the goggles are asked once per frame; the guard below reads that answer");
        assertTrue(gates > goggles, "and the gates are built from it, so one answer decides the whole overlay");
        assertTrue(bodyOf(pass, "private static boolean wearingGoggles(")
                        .contains("RCRegistry.isWearingGoggles(player)"),
                "that one answer is the mod's own goggles check: the lines must not invent a second idea of who "
                        + "is wearing them");

        String stage = bodyOf(pass, "private static void runStage(");
        String gate = bodyOf(stage, "if (gates.goggles()) {");
        assertTrue(gate.contains("SyncGroupLines.Render.draw("),
                "the lines are drawn from the world stage, and from inside the goggles' branch: a player without "
                        + "them sees no line at all, which is the same rule the components obey");
        assertTrue(gate.contains("SyncGroupLines.Render.draw(pose,"),
                "and they are handed the pose stack, because a pair inside a machine has to be drawn in that "
                        + "machine's frame rather than at a world block");
    }

    @Test
    @DisplayName("the channels are the client's own table, not the hosts this frame happened to draw")
    void channelsComeFromTheClientsOwnTable() throws IOException {
        String pass = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "HostRenderPass.java");
        String gate = bodyOf(bodyOf(pass, "private static void runStage("), "if (gates.goggles()) {");
        assertTrue(gate.contains("SyncGroupView.channels(level.dimension())"),
                "the channels come from the table the server synced (维度 → 频道 → 成员), which is the only thing "
                        + "that knows a group exists");
        assertFalse(pass.contains("syncChannels"),
                "and not from a list built out of the drawn hosts: a 同步火把 standing in the world has no host "
                        + "slot, so that list is empty for exactly the case this feature is for");
        assertFalse(pass.contains("ComponentType.SYNC_TORCH"),
                "no part of the render pass asks a slot for a channel any more - that question belongs to the "
                        + "client's cache");

        String lines = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "SyncGroupLines.java");
        String draw = bodyOf(lines, "static void draw(");
        assertTrue(draw.contains("SyncGroupView.membersOf(level.dimension(), channel)"),
                "the members are that same cache, not a second list built here");
        assertTrue(draw.contains("level::hasChunkAt"),
                "a member the client cannot read is skipped instead of being looked up in a chunk it does not hold");
        assertTrue(draw.contains("SyncTorchRule.colour("),
                "the colour is derived from the channel number at this end, which is why nothing carries it");
        assertTrue(lines.contains("RenderStateShard.NO_DEPTH_TEST") && lines.contains("RenderStateShard.COLOR_WRITE"),
                "the lines ignore depth and write none, exactly as the rest of the goggles' geometry does");
        assertTrue(draw.contains("WorldGate gate = gateOf(level)"),
                "and the one thing a frame may refuse is the fallback: a pair whose end the client cannot read AND "
                        + "which sits absurdly far away. Two torches of one group are still joined across a base - "
                        + "that pair has both chunks loaded, so this gate cannot remove it");
    }

    @Test
    @DisplayName("a carried member is drawn only after its machine's frame is on the pose stack")
    void carriedMembersAreDrawnInsideTheirMachinesFrame() throws IOException {
        String lines = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "SyncGroupLines.java");
        String draw = bodyOf(lines, "static void draw(");
        int push = draw.indexOf("pose.pushPose()");
        int frame = draw.indexOf("HostRenderPass.applyCarrierFrame(pose, link.carrier())");
        int emit = draw.indexOf("emit(pose.last(), buffer, frame, link.local(), argb)");
        int pop = draw.indexOf("pose.popPose()");
        assertTrue(push >= 0, "a pair inside a machine needs a frame on the stack: the push is the first half");
        assertTrue(frame > push,
                "the machine's frame is applied inside that push - through the one method the shells and contents "
                        + "use, not a second copy of T(position)·R·S");
        assertTrue(emit > frame,
                "and the vertices of that pair are emitted after it, which is what makes the line follow the "
                        + "machine's position, turn and scale");
        assertTrue(pop > emit, "the stack is put back, so the world half of the same batch is unaffected");
        assertTrue(draw.indexOf("emit(pose.last(), buffer, frame, link.world(), argb)") >= 0,
                "a pair that is not inside one machine is still drawn at world coordinates - the cross-boundary "
                        + "line has no other frame to be drawn in");
    }

    @Test
    @DisplayName("a machine this frame has no pose for contributes no pair at all")
    void aCarrierWithoutAPoseIsSkipped() throws IOException {
        String lines = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "SyncGroupLines.java");
        String spots = bodyOf(lines, "private static List<Spot> spots(");
        assertTrue(spots.contains("SyncGroupView.carriedOf(level.dimension(), channel)"),
                "the carried members are the same cache the standing ones come from, not a list built here");
        assertTrue(spots.contains("if (pose == null)") && spots.contains("continue;"),
                "a machine whose pose cannot be had is skipped whole: half a group is a wrong picture, and a "
                        + "guessed position is a worse one");
        String pose = bodyOf(lines, "private static CarrierPose poseOf(");
        assertTrue(pose.contains("PhysicsCompat.renderPoseOf("),
                "the live pose is asked for through the compat gate by name, so the main source set never names a "
                        + "Sable type");
        assertTrue(pose.contains("ClientHostCache.carrierPose("),
                "and the pose the network sent is the fallback, exactly as the carried contents resolve theirs");
        assertTrue(pose.contains("return null;"), "there is an answer for 'no pose', and it is not an exception");
    }

    @Test
    @DisplayName("the cache keeps both segments, and a channel that lives only inside a machine is still a channel")
    void bothSegmentsAreCached() {
        CarrierId machine = CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-0000000000b1"));
        RCNetwork.SyncGroupPayload.CarriedMember carried =
                new RCNetwork.SyncGroupPayload.CarriedMember(machine, new BlockPos(3, 0, 0));
        java.util.Map<Integer, java.util.Collection<BlockPos>> standing =
                Map.of(CHANNEL, List.of(new BlockPos(0, 64, 0)));
        java.util.Map<Integer, java.util.Collection<RCNetwork.SyncGroupPayload.CarriedMember>> onlyCarried =
                Map.of(CHANNEL, List.of(carried));
        try {
            SyncGroupView.clear();
            // 包那边的顺序: 世界段先换, 搬运段后到。
            SyncGroupView.apply(OVERWORLD, standing);
            SyncGroupView.applyCarried(OVERWORLD, onlyCarried);
            assertEquals(Set.of(carried), SyncGroupView.carriedOf(OVERWORLD, CHANNEL),
                    "the carried half is what the server said it was");
            assertEquals(1, SyncGroupView.carriedCount(OVERWORLD), "and it is counted where the diagnostics ask");
            assertTrue(SyncGroupView.channels(OVERWORLD).contains(CHANNEL),
                    "a channel both halves agree on is one channel, drawn with two kinds of vertex");

            java.util.Map<Integer, java.util.Collection<BlockPos>> noWorld = Map.of();
            SyncGroupView.apply(OVERWORLD, noWorld);
            SyncGroupView.applyCarried(OVERWORLD, onlyCarried);
            assertTrue(SyncGroupView.channels(OVERWORLD).contains(CHANNEL),
                    "a group whose members are all inside machines is still a group: its channels cannot be read "
                            + "off the world half, which is empty");

            // 机器落地: 这一份快照只有世界段, 接收方根本不会被叫到。
            SyncGroupView.apply(OVERWORLD, Map.of(CHANNEL + 1, List.of(new BlockPos(1, 64, 0))));
            assertTrue(SyncGroupView.carriedOf(OVERWORLD, CHANNEL).isEmpty(),
                    "a snapshot with no carried members leaves none behind: the world half clears the pair, so an "
                            + "old server and a machine that has landed are the same answer");
            assertEquals(Set.of(CHANNEL + 1), SyncGroupView.channels(OVERWORLD),
                    "so the channel list is this snapshot's, not a union with a stale half");
        } finally {
            SyncGroupView.clear();
        }
    }

    @Test
    @DisplayName("the client half installs the payload's carried sink, and lands it after the world half")
    void theCarriedSinkIsInstalled() throws IOException {
        String view = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "SyncGroupView.java");
        assertTrue(view.contains("RCNetwork.SyncGroupPayload.installCarriedSink(SyncGroupView::applyCarried)"),
                "the carried half is handed over by the payload's own sink, installed once from the client half");

        String accept = bodyOf(view, "public static void accept(RCNetwork.SyncGroupPayload payload) {");
        int world = accept.indexOf("apply(payload.dimension(), world);");
        int carried = accept.indexOf("sink.accept(payload.dimension(), carried);");
        assertTrue(world >= 0 && carried > world,
                "and the world half is landed first, because it is what clears the carried half: a snapshot with no "
                        + "carried members never reaches the sink, so the clearing is the only thing that ends them");
    }

    @Test
    @DisplayName("a group of standing torches draws its lines with no host drawn at all")
    void aGroupOfStandingTorchesStillDrawsLines() {
        BlockPos north = new BlockPos(0, 64, 0);
        BlockPos south = new BlockPos(600, 70, 900);
        try {
            SyncGroupView.clear();
            SyncGroupView.apply(OVERWORLD, Map.of(CHANNEL, List.of(north, south)));

            assertTrue(SyncGroupView.channels(OVERWORLD).contains(CHANNEL),
                    "the channel is known from the table alone - nothing here is a drawn host");
            List<SyncGroupLines.Segment> segments = SyncGroupLines.lines(CHANNEL,
                    SyncGroupView.membersOf(OVERWORLD, CHANNEL), SyncGroupLines.Loaded.ALL);
            assertEquals(1, segments.size(),
                    "two standing torches are one line, six hundred blocks apart: this is the picture the feature "
                            + "exists for, and it needs no host block to be visible");
        } finally {
            SyncGroupView.clear();
        }
    }

    @Test
    @DisplayName("an unbound torch is in no channel, so it names no line")
    void anUnboundTorchNamesNoChannel() {
        BlockPos first = new BlockPos(0, 64, 0);
        BlockPos second = new BlockPos(4, 64, 0);
        try {
            SyncGroupView.clear();
            // The payload carries shared channels only - a torch bound to nothing is "a group of one" on the
            // server and never reaches this cache - so an empty table is the whole answer there.
            assertTrue(SyncGroupView.channels(OVERWORLD).isEmpty(),
                    "nothing synced is not 'channel 0': it is no channel at all");
            assertEquals(0, SyncGroupLines.lines(SyncTorchRule.UNBOUND, List.of(first, second),
                    SyncGroupLines.Loaded.ALL).size(),
                    "and the geometry refuses a number that is not a channel even if one is handed to it");
        } finally {
            SyncGroupView.clear();
        }
    }

    @Test
    @DisplayName("a sync torch says its own words: its own StateKind, and Jade's own two keys")
    void theOverlayHasItsOwnWording() throws IOException {
        String line = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "InnerComponentLine.java");
        assertTrue(line.contains("case SYNC_TORCH -> StateKind.SYNC_TORCH"),
                "a sync torch is not a torch: its own kind is what lets both overlays word it as itself instead of "
                        + "reporting a redstone torch's input");

        String jade = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "jade", "RedstoneCircuitJadePlugin.java");
        assertTrue(jade.contains("\"jade.redstonecircuit.inner.sync_torch\"")
                        && jade.contains("\"jade.redstonecircuit.inner.sync_torch.off\""),
                "the plugin names the two keys it prints, so the switch and the translation file cannot drift apart");
        assertTrue(jade.contains("case SYNC_TORCH ->"),
                "and the two keys are what its own kind prints - the K_CLOCK / K_EXTENDER shape");

        String en = read("src", "main", "resources", "assets", "redstonecircuit", "lang", "en_us.json");
        String zh = read("src", "main", "resources", "assets", "redstonecircuit", "lang", "zh_cn.json");
        for (String lang : List.of(en, zh)) {
            assertTrue(lang.contains("\"jade.redstonecircuit.inner.sync_torch\":")
                            && lang.contains("\"jade.redstonecircuit.inner.sync_torch.off\":"),
                    "a key the plugin prints has to exist, or the overlay prints its own name at the player");
        }
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The working directory is the neoforge project for the test task, but a build invoked from the repository
        // root is not, so both spellings are tried - and a missing file is a failure rather than a silent skip.
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
}
