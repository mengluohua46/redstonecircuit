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
 * Where a live view writes down that an observer has the whole structure, pinned on the source it is written in.
 *
 * <p>The rule that fails here fails silently and expensively: the question "does anybody need the structure itself" is
 * answered once per pass, and the record of the answer is the only thing that stops the next pass asking the same
 * question about the same audience. A branch inserted between the two - an early return, a second condition - leaves the
 * answer unrecorded, and the whole structure is then resent on every sampling tick for as long as it stands. Nothing
 * crashes; the client simply rebuilds its miniature twenty times a second.
 */
class StructureLiveViewPictureRecordGuardTest {

    private static final String SEND_LIVE_VIEW =
            "void sendLiveView(ServerLevel server, ServerLevel pocket, BlockPos drawnPos)";
    private static final String ASKED =
            "StructureLiveView.needsWholePicture(this.liveViewPictureHolders, watching)";
    private static final String RECORDED = "this.liveViewPictureHolders.addAll(watching);";
    private static final String SENT = "ServerPlayNetworking.send(player, payload);";

    @Test
    @DisplayName("the answer is recorded beside the question, with nothing in between")
    void theRecordFollowsTheQuestion() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SEND_LIVE_VIEW);

        int asked = body.indexOf(ASKED);
        int recorded = body.indexOf(RECORDED);
        assertTrue(asked > 0 && recorded > asked,
                "who needs the structure is asked, and the answer is written down in the same breath");
        assertFalse(body.substring(asked, recorded).contains("return"),
                "a return between the question and the record is a pass that asks and never records: every later pass "
                        + "asks again, and the whole structure is streamed for ever - which is the 20 packets a second "
                        + "nobody asked for");
        assertTrue(body.substring(asked, recorded).contains("if (wholePicture) {"),
                "and it is recorded only when the answer was yes: recording an observer that was not sent the picture "
                        + "would leave it with a client this end believes is up to date");
    }

    @Test
    @DisplayName("the record is made in exactly one place, and the picture is started in exactly one place")
    void oneRecordAndOneStart() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SEND_LIVE_VIEW);

        assertTrue(countOf(body, RECORDED) == 1,
                "a second site that records observers is a second answer to the same question");
        assertTrue(countOf(body, "new StructureLiveView.Resend(") == 1,
                "and a second site that starts a whole picture is the timer this pair replaced");
        assertTrue(body.indexOf(RECORDED) < body.indexOf("new StructureLiveView.Resend("),
                "the record is written when the question is answered, not when the stream it starts reaches its first "
                        + "batch: the sample, the diff and the arm of the burst all sit between the two");
    }

    @Test
    @DisplayName("nobody watching empties the record, and nothing else in the pass does")
    void onlyAnEmptyAudienceEmptiesTheRecord() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SEND_LIVE_VIEW);

        assertTrue(countOf(body, "this.liveViewPictureHolders.clear();") == 1,
                "the record is emptied where it is forgotten on purpose - a structure nobody is looking at - and "
                        + "nowhere else in the pass, or every pass would start a whole picture again");
        int empty = body.indexOf("if (watchers.isEmpty()) {");
        assertTrue(empty > 0 && body.indexOf("this.liveViewPictureHolders.clear();") > empty,
                "and that is the branch with no audience: a structure nobody watches remembers no observer, which is "
                        + "what bounds the record by the players looking rather than by the server's uptime");
        assertTrue(body.indexOf("this.liveResend = null;") > empty,
                "the picture in flight is dropped with the record: a stream whose audience has gone is work for "
                        + "nobody, and its cursor is meaningless to the next viewer");
    }

    @Test
    @DisplayName("a structure with nothing to say leaves before anything is dispatched")
    void aQuietStructureLeavesBeforeDispatch() throws IOException {
        String body = bodyOf(readStructureBlockEntity(), SEND_LIVE_VIEW);

        int nothingToSend = body.indexOf("if (this.liveResend == null && changedPreview.length == 0) {");
        int dispatch = body.indexOf(SENT);
        assertTrue(nothingToSend > 0 && dispatch > nothingToSend,
                "the pass must be able to end without a packet: a sampled structure that did not change and has no "
                        + "picture in flight has nothing to say, and sending an empty payload is a client rebuilding its "
                        + "miniature for a picture that is already correct");
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
