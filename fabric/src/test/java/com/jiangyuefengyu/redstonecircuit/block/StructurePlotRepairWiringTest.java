package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Wiring rules for a structure block with no pocket: the tick asks for a plot again, and every
 * refusal says itself.</b>
 *
 * <p>这些规则在需要运行中的服务器、口袋维度和活动载具才能走到的方法里，所以每个测试都读源码；挪动或丢掉其中一条，
 * 症状就会回来: 去掉重试，被拒绝的方块永远是一片空白；去掉拒绝消息，一个送不到的手势和没反应的手势一样。
 */
class StructurePlotRepairWiringTest {

    @Test
    @DisplayName("the tick asks for a plot again while there is none, and carries on once it has one")
    void theTickAsksForAPlotAgain() throws IOException {
        String entity = readStructureBlockEntity();
        String body = bodyOf(entity, "public static void serverTick(");

        int asks = body.indexOf("product.repairPlotIfDue(server);");
        assertTrue(asks > body.indexOf("instanceof ServerLevel server"),
                "the retry belongs to the server half: it reads a structure file and writes blocks, so the client's own "
                        + "copy of the block must not run it");
        assertTrue(body.indexOf("if (product.plotIndex < 0) {", body.indexOf("repairPlotIfDue(server)")) > asks,
                "and it is asked only while there is no plot - the ordinary case, a block standing over the plot it "
                        + "owns, must pay one int comparison and no file read");
        assertTrue(body.indexOf("product.updateOutput(server, pocket, frame);") > asks
                        && body.indexOf("PocketSpace.of(pocket).reown(product.plotIndex") > asks,
                "a block that has just been given a plot is an ordinary block from that tick on: it has an indicator to "
                        + "set, a live view to send, an output to publish and a record to settle at its own position, so "
                        + "the retry stands above them all and does not return once it succeeds");
    }

    @Test
    @DisplayName("the retry is throttled, reported once, and reads the structure file this save holds")
    void theRetryIsThrottledAndReadsTheFile() throws IOException {
        String entity = readStructureBlockEntity();
        String due = bodyOf(entity, "public boolean repairPlotIfDue(");

        assertTrue(due.contains("this.structureId == null"),
                "the placeholder case has no file to read, so it has nothing to repair and must not pay the throttle");
        assertTrue(due.contains("now < this.nextPlotRepairAt") && due.contains("PLOT_REPAIR_TICKS"),
                "a structure file is read from disk, so the attempt is rate limited rather than run every tick");
        assertTrue(due.contains("!this.plotRepairReported"),
                "and the reason it failed is said once per block: a line a second, per block, per world, is a log "
                        + "nobody can read");

        String repair = bodyOf(entity, "public PocketMaterializer.Result repairPlot(");
        assertTrue(repair.contains("StructureWorldStore.of(") && repair.contains("loadOrNull(this.structureId)"),
                "the blocks come back from the structure file this save holds for this block's id - the same store the "
                        + "item reads when it is placed, which is what makes a block refused a pocket at placement and a "
                        + "block whose plot was reclaimed later heal by one path");
        assertTrue(repair.contains("PocketMaterializer.materialize(server, payload"),
                "and they are written by the materializer, not by a second implementation of it");
        int set = repair.indexOf("this.setPlotIndex(result.plot());");
        int materialized = repair.indexOf("if (result.materialized()) {");
        assertTrue(materialized >= 0 && set > materialized,
                "the plot index is recorded only when a plot was really given - a refusal leaves the block as it was, "
                        + "which is what keeps the next press refused instead of aimed at somebody else's plot");
    }

    @Test
    @DisplayName("placing a structure reports its refusal to the player, and a press with no pocket refuses")
    void refusalsReachThePlayer() throws IOException {
        String item = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "item",
                "StructureBlockItem.java");
        String materialize = bodyOf(item, "private static void materialize(");
        assertTrue(materialize.contains("product.repairPlot(server)"),
                "placing a structure is repairing it, so the item reports the refusal the repair returns");
        assertTrue(materialize.contains("player.displayClientMessage"),
                "a refusal has to reach the player, or a rejected placement looks like a block that does nothing");

        String trigger = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "StructureManualTrigger.java");
        String pressIn = bodyOf(trigger, "public static Result pressIn(");
        int noPlot = pressIn.indexOf("product.plotIndex() < 0");
        int rules = pressIn.indexOf("ManualTrigger.check(");
        assertTrue(noPlot >= 0 && noPlot < rules,
                "a block with no pocket is refused where the other lookups are, above the rules: a press that cannot be "
                        + "delivered at all is a different answer from a press the player aimed badly");
        assertTrue(pressIn.contains("no pocket right now"),
                "and the sentence names the state, so a refused press is not left as a warning only");
    }

    @Test
    @DisplayName("a refused press answers the player, and the pocket commands say who owns what")
    void refusalsAndOwnersAreVisible() throws IOException {
        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "RCNetwork.java");
        String handler = bodyOf(network, "ServerPlayNetworking.registerGlobalReceiver(ManualTriggerPayload.TYPE, "
                + "(payload, context) -> {");
        assertTrue(handler.contains("!result.accepted()") && handler.contains("player.displayClientMessage"),
                "every refusal goes into the log and, for a player, into the chat, so both halves must be wired: the "
                        + "receiver is registered inline here and already runs on the server thread, so the refusal is "
                        + "answered in that same body and needs no queue hop first");

        String command = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketCommand.java");
        String clear = bodyOf(command, "private static int clear(");
        int said = clear.indexOf("describeClaim(");
        int released = clear.indexOf("space.release(index)");
        assertTrue(said >= 0 && released > said,
                "the command that frees every pocket says what it is about to empty before it empties it - a count "
                        + "is not evidence");
        assertTrue(bodyOf(command, "private static int list(").contains("describeClaim(claims.get(index), now)"),
                "and the listing command answers the same question for a world that is merely full rather than broken");
    }

    @Test
    @DisplayName("a released plot tells the block that owned it, so it can ask for another one")
    void aReleasedPlotTellsItsBlock() throws IOException {
        String space = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketSpace.java");
        String release = bodyOf(space, "public boolean release(");
        assertTrue(release.contains("forgetOwner(claim, index);"),
                "/rc pocket clear releases every plot in the world, and the retry only runs while plotIndex < 0: a "
                        + "block left holding an index nothing answers for can never repair itself, and its presses land "
                        + "in cells that may belong to somebody else's structure by then");
        String forget = bodyOf(space, "private void forgetOwner(");
        assertTrue(forget.contains("product.plotIndex() == index") && forget.contains("product.setPlotIndex(-1);"),
                "and it is the block that is still claiming THIS plot that is told - not one that has already moved on "
                        + "(reown gave it another index) or one at a position that no longer holds it");
    }

    @Test
    @DisplayName("a full budget hands a plot back instead of refusing, and an index outliving its plot is dropped")
    void aFullBudgetMakesRoom() throws IOException {
        String space = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketSpace.java");
        String claim = bodyOf(space, "public Plotted claim(");
        int reclaim = claim.indexOf("this.handBackOnePlot()");
        int refused = claim.indexOf("return new Plotted(null, verdict.reason());");
        assertTrue(reclaim > 0 && refused > reclaim,
                "a full budget hands a plot back instead of refusing: a pocket nobody is using pays chunk tickets for "
                        + "nobody, so it is handed back and the claim is tried again");

        String pick = bodyOf(space, "private Integer pickReclaimable(");
        int dead = pick.indexOf("return dead;");
        int travelling = pick.indexOf("return travelling;");
        int unloaded = pick.indexOf("return unloaded;");
        assertTrue(dead > 0 && travelling > dead && unloaded > travelling,
                "and what is handed back is chosen by how much is known about the owner: a record nothing answers for "
                        + "first, then the oldest travelling stamp (the block has had the longest to come home), then an "
                        + "owner in an unloaded chunk - which is not ticking, so nothing in it is being driven");
        assertTrue(pick.contains("IDLE_EVICT_TICKS"),
                "the last resort is the plot untouched longest, and it needs an idle window: a standing structure works "
                        + "its ledger every tick, so choosing it can only happen when the pocket is genuinely out of "
                        + "idle entries and something has to give");

        String tick = bodyOf(readStructureBlockEntity(), "public static void serverTick(");
        assertTrue(tick.contains("!PocketSpace.of(pocket).hasPlot(product.plotIndex)"),
                "and a block whose plot went out from under it - a sweep, /rc pocket clear, or the pocket making room "
                        + "while the block was in an unloaded chunk - must drop the index it can no longer use: keeping "
                        + "it means every press passes every check, injections land in cells that may belong to another "
                        + "structure, and the repair path (which is what plotIndex < 0 means) never runs");
        String hasPlot = bodyOf(space, "public boolean hasPlot(");
        assertTrue(hasPlot.contains("this.plots.containsKey(index)")
                        && hasPlot.contains("savedPlots().entry(index) != null"),
                "and the question is asked of both halves of what a plot is: the grid this session rebuilt from the "
                        + "table, and the table itself");
        assertTrue(bodyOf(space, "public PocketInjection injection(").contains("this.touch(index);"),
                "the pocket has to know which plots are being worked, and this is the door every owner comes through "
                        + "every tick - the standing tick flushes a held change here and the carried tick does the same");
    }

    @Test
    @DisplayName("a copy never inherits a plot number - not from a capture, and not from an old payload")
    void aCopyNeverInheritsAPlot() throws IOException {
        String collect = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "StructureCapture.java"), "public static Result capture(ServerLevel level, Request request, "
                + "Limits limits)");
        int saved = collect.indexOf("saveWithoutMetadata(");
        int stripped = collect.indexOf("data.remove(\"plot\")");
        assertTrue(saved > 0 && stripped > saved,
                "a region may contain a placed structure block (that is nesting depth 1), and its tag records which "
                        + "pocket plot that instance held: a copy that inherits the number reads and drives whatever "
                        + "structure owns that number now, so the number is dropped where the copy is made");

        // The pair of calls is what matters, not which of the two methods holds them.
        String write = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "structure",
                "pocket", "PocketMaterializer.java"), "public static Result materializeInto(");
        int loaded = write.indexOf("created.loadWithComponents(data,");
        int forced = write.indexOf("product.setPlotIndex(-1)");
        assertTrue(loaded > 0 && forced > loaded,
                "and the materializer forces the plot-less state anyway, because payloads written earlier are still "
                        + "on disk and a structure file keeps its inner blocks' tags for ever");
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
}
