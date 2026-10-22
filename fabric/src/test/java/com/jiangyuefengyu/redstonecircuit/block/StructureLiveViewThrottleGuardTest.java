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
 * What a live view is allowed to cost is decided in three places in one class, and every one of them fails silently: a
 * structure that is sampled too often only costs ticks, and one that is sampled too rarely only looks wrong from across
 * the room. The three decisions are pinned on the source they are written in.
 */
class StructureLiveViewThrottleGuardTest {

    private static final String SEND_LIVE_VIEW =
            "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)";

    @Test
    @DisplayName("a whole picture is sent to an observer that lacks one, not to everybody on a timer")
    void aWholePictureIsNotRepeatedOnATimer() throws IOException {
        String source = readStructureBlockEntity();

        assertFalse(source.contains("LIVE_VIEW_RESYNC_TICKS"),
                "a whole picture on a timer is 216 packets per 48³ structure, repeated for observers that are already "
                        + "up to date: cost with nothing to repair");
        assertFalse(source.contains("lastLiveResync"),
                "and the timer's own state is gone with it, so no arm of it can come back by accident");

        String body = bodyOf(source, SEND_LIVE_VIEW);
        assertTrue(body.contains("StructureLiveView.needsWholePicture(this.liveViewPictureHolders, watching)"),
                "who needs the structure itself is asked per observer");
        assertTrue(body.contains("if (wholePicture) {"), "and that answer is the only thing that starts a pass");
        assertTrue(countOf(body, "new StructureLiveView.Resend(") == 1,
                "exactly one place starts a whole picture in this method; a second one would be a timer again");
    }

    @Test
    @DisplayName("the observer record is pruned to who is watching, and emptied when nobody is")
    void theObserverRecordCannotGrow() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SEND_LIVE_VIEW);

        int empty = body.indexOf("if (watchers.isEmpty()) {");
        int cleared = body.indexOf("this.liveViewPictureHolders.clear();");
        assertTrue(empty > 0 && cleared > empty,
                "a structure nobody watches must forget every observer it was remembering: that is what keeps the "
                        + "record bounded by the number of players looking rather than by how long the server ran");
        int prune = body.indexOf("StructureLiveView.needsWholePicture(this.liveViewPictureHolders, watching)");
        int sample = body.indexOf("int[] now = sample(pocket, plot);");
        assertTrue(prune > 0 && sample > prune,
                "and the pruning happens before the sample, so a player who left costs nothing from that tick on");
    }

    @Test
    @DisplayName("the sampler writes into buffers it keeps instead of allocating the structure every pass")
    void theSamplerReusesItsBuffers() throws IOException {
        String source = readStructureBlockEntity();
        String body = bodyOf(source, "private int[] sample(ServerLevel pocket, PocketSpace.Plot plot)");

        assertFalse(body.contains("new int[StructureLiveView.count"),
                "one array of 110,592 cells per pass, plus the copy the belief would make of it, is 880 KB every "
                        + "ten ticks");
        assertFalse(body.contains("StructureLiveView.believe("),
                "the allocating form stays for its callers that have no buffer - the periodic pass is not one");
        assertTrue(body.contains("StructureLiveView.believeInto("));
        assertTrue(body.contains("return believed;"),
                "what is returned is the buffer this block entity keeps, so the caller must be told that");
        assertTrue(body.contains("this.liveWorldBuffer.length != count"),
                "the buffers are rebuilt only when the structure's own size changed under them");

        String pick = bodyOf(source, "private int[] sampleBuffer(int count)");
        assertTrue(pick.contains("this.liveSampleBuffers[0] == this.lastLiveSample ? 1 : 0"),
                "the buffer written next is never the one the new sample is compared against");
        assertTrue(pick.contains("this.liveSampleBuffers[slot].length != count"),
                "and it is rebuilt only when the size changed under it");
    }

    @Test
    @DisplayName("a pass that carries a batch remembers it without copying the structure")
    void aPartialBatchIsMergedInPlace() throws IOException {
        String source = readStructureBlockEntity();
        String body = bodyOf(source, "private int[] claimSent(int[] now, int[] sent)");

        assertFalse(body.contains(".clone()"), "a copy of 110,592 cells per packet is what this avoids");
        assertFalse(body.contains("new boolean["), "and the marking of what was sent went with it");
        assertTrue(body.contains("this.lastLiveSample == now"),
                "the pass that recorded this very sample as the one to compare against has nothing to merge with, and "
                        + "merging a sample into itself would read what it writes");
        assertTrue(body.contains("StructureLiveView.remember(now, sent, this.lastLiveSample)"),
                "the cells the packet did not carry are the ones the remembered sample still holds the old value of, "
                        + "so the merge writes into that sample");
    }

    @Test
    @DisplayName("both the quiet interval and the every-tick window scale with the volume")
    void theIntervalAndTheBurstScaleWithTheVolume() throws IOException {
        String source = readStructureBlockEntity();
        String body = bodyOf(source, SEND_LIVE_VIEW);

        assertTrue(body.contains("int interval = LIVE_VIEW_INTERVAL * StructureLiveView.sampleIntervalScale(cells);"),
                "the quiet interval is the base one times what this volume can afford");
        assertTrue(body.contains("Math.floorMod(gameTime, interval)"),
                "and that scaled interval is the one the phase is tested against");

        String arm = bodyOf(source, "private void armLiveViewBurst(long gameTime)");
        assertTrue(arm.contains("StructureLiveView.burstTicks(liveViewCells(), LIVE_VIEW_BURST_TICKS)"),
                "the every-tick window is the full window cut to the volume");

        assertTrue(body.contains("this.armLiveViewBurst(gameTime);"),
                "a sample that saw a change arms it through the one method that scales it");
        assertTrue(bodyOf(source, "public int pushInjection(ServerLevel pocket)")
                        .contains("this.armLiveViewBurst(pocket.getGameTime());"),
                "and so does the injection half, which is where a busy structure arms it in the first place");
        assertFalse(source.contains("gameTime + LIVE_VIEW_BURST_TICKS"),
                "no arming site may bypass the scaling and hand a 48³ structure twelve ticks of every-tick sampling");

        String cells = bodyOf(source, "private int liveViewCells()");
        assertTrue(cells.contains("StructureLiveView.count(this.size)"),
                "what the scale is worked out from is read from the structure as it is now, on every call: a volume "
                        + "remembered from an earlier pass would rate a resized structure at the old size");
    }

    @Test
    @DisplayName("a structure that is replaced, moved or reloaded forgets everything the live view remembered")
    void aDifferentStructureForgetsTheLiveView() throws IOException {
        String source = readStructureBlockEntity();

        String forget = bodyOf(source, "private void forgetLiveView()");
        for (String reset : new String[] {"this.lastLiveSample = null;", "this.liveResend = null;",
                "this.liveSampleBuffers[0] = new int[0];", "this.liveSampleBuffers[1] = new int[0];",
                "this.liveWorldBuffer = new int[0];", "this.liveViewPictureHolders.clear();",
                "this.liveViewBurstUntil = 0;"}) {
            assertTrue(forget.contains(reset), "forgetting the live view has to clear " + reset + ": a diff, a whole "
                    + "picture in flight, a buffer of the old size and an observer record are all about the structure "
                    + "that is not there any more");
        }

        assertTrue(bodyOf(source, "public void setPlotIndex(int plotIndex)").contains("forgetLiveView();"),
                "a new plot means the sample names another plot's cells");
        assertTrue(bodyOf(source, "protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries)")
                        .contains("forgetLiveView();"),
                "and a structure read out of a tag comes with its own size");
        String replaced = bodyOf(source, "List<ActivationTable.OutputEntry> output,");
        assertTrue(replaced.contains("this.size = size;") && replaced.contains("forgetLiveView();"),
                "the whole of the crash: a structure of another size leaves a sample that cannot be compared with the "
                        + "new one, and StructureLiveView#changed refuses two sizes inside a tick");

        String body = bodyOf(source, SEND_LIVE_VIEW);
        int checked = body.indexOf("StructureLiveView.sampleMatches(this.lastLiveSample, cells)");
        int compared = body.indexOf("StructureLiveView.changed(this.lastLiveSample, now)");
        assertTrue(checked > 0 && compared > checked,
                "and no comparison is reached without that check first, whatever wrote the size");
    }

    // ------------------------------------------------------------- helpers --

    private static String readStructureBlockEntity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlockEntity.java");
    }

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings are tried.
        Path underFabric = Path.of("fabric").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underFabric)) {
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
