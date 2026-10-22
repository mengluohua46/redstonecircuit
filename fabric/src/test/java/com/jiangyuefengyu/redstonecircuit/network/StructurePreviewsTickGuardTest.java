package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A preview border is read back out of the world, and reading the world back is what parked the server: the
 * snapshot asked the level for a block entity, the level asked for a chunk that was not ready, and the server
 * thread waited for a task only that same thread could have run. Every one of the three decisions that keep it
 * from happening fails as a hang rather than as an error, so they are pinned on the source they are written in.
 */
class StructurePreviewsTickGuardTest {

    private static final Path SOURCE =
            Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                    "StructurePreviews.java");

    private static final String SNAPSHOT = "public static List<Entry> snapshot(ServerLevel level)";
    private static final String PUBLISH = "public static void publish(@Nullable Level level, BlockPos pos)";
    private static final String WITHDRAW = "public static void withdraw(@Nullable Level level, BlockPos pos)";
    private static final String FLUSH = "public static void flush(ServerLevel level)";
    private static final String BROADCAST = "public static void broadcast(ServerLevel level)";
    private static final String SEND_SNAPSHOT_TO = "public static void sendSnapshotTo(ServerPlayer player)";
    private static final String ENTRY_FOR = "private static Entry entryFor(ServerLevel level, BlockPos pos, "
            + "RedstoneStructureBlockEntity controller)";
    private static final String RESIDENT_OCCUPIED =
            "private static int residentOccupied(ServerLevel level, BlockPos origin, Vec3i size)";
    private static final String LEVEL_TICK = "public static void onLevelTick(LevelTickEvent.Post event)";

    @Test
    @DisplayName("a snapshot reads the world through a chunk that is already there, never through the level")
    void theSnapshotNeverAsksTheLevelForAChunk() throws IOException {
        for (Path origin : sources()) {
            String body = bodyOf(read(origin), SNAPSHOT, origin);

            assertFalse(body.contains("level.getBlockEntity("), why(origin,
                    "this is the read that deadlocked the server: it asks for the chunk with requireChunk = true "
                            + "and is then parked in BlockableEventLoop#managedBlock until the very thread that "
                            + "must finish building that chunk runs the task"));
            assertFalse(body.contains("level.getChunk("), why(origin,
                    "one step below the same road, so it has to stay out of a snapshot too"));
            assertFalse(body.contains("level.getBlockState("), why(origin,
                    "and the same road again, one block at a time"));
            assertFalse(body.contains("level.isLoaded("), why(origin,
                    "this is no guard either: it answers from the ticket table, so it says yes for a chunk that "
                            + "has a ticket and cannot be read yet - which is how the wait got reached at all"));
            assertTrue(body.contains("level.getChunkSource().getChunkNow("), why(origin,
                    "the read that cannot park: a resident chunk, or null"));
            assertTrue(body.contains("chunk.getBlockEntity(pos)"), why(origin,
                    "and the controller is read out of that chunk, not out of the level"));
        }
    }

    @Test
    @DisplayName("a changed border is sent at the end of the tick, never from the block entity that changed it")
    void aChangeIsSentAtTheEndOfTheTickAndNotWhereItHappens() throws IOException {
        for (Path origin : sources()) {
            String text = read(origin);

            String publish = bodyOf(text, PUBLISH, origin);
            assertFalse(publish.contains("broadcast("), why(origin,
                    "publish is reached from the block entity's own loading path, so a broadcast from here is "
                            + "the server reading the chunk it is still building"));
            assertTrue(publish.contains("DIRTY.add("), why(origin,
                    "the change is recorded for the end of the tick instead"));

            String withdraw = bodyOf(text, WITHDRAW, origin);
            assertFalse(withdraw.contains("broadcast("), why(origin,
                    "withdraw is reached from setRemoved, which is the other half of the same loading"));
            assertTrue(withdraw.contains("DIRTY.add("), why(origin,
                    "setRemoved is the other half of the same loading, so the removal is recorded for the end of "
                            + "the tick for the same reason the publish is"));

            String flush = bodyOf(text, FLUSH, origin);
            int cleared = flush.indexOf("DIRTY.remove(");
            int sent = flush.indexOf("broadcast(level)");
            assertTrue(cleared > 0 && sent > cleared, why(origin,
                    "the flag is cleared before the send, so a change made while the snapshot is being built "
                            + "marks the dimension again instead of being lost with this tick"));
            assertTrue(countOf(text, "broadcast(") == 2, why(origin,
                    "one definition and one call from the flush: a second call anywhere else in this class would "
                            + "be a border sent from the path that cannot wait"));

            assertTrue(text.contains("ServerTickEvents.END_WORLD_TICK.register(StructurePreviews::flush)")
                    || text.contains("LevelTickEvent.Post event"), why(origin,
                    "the flush has to be registered at the end of a level tick, the moment the loader already "
                            + "ticks StructureReleaseTickHandler at"));
            if (text.contains("LevelTickEvent.Post event")) {
                assertTrue(bodyOf(text, LEVEL_TICK, origin).contains("flush(level)"), why(origin,
                        "the event bus listener is what calls the flush"));
            }

            assertTrue(bodyOf(text, BROADCAST, origin).contains("snapshot(level)"), why(origin,
                    "a broadcast is still a thing a caller may do explicitly"));
            assertTrue(bodyOf(text, SEND_SNAPSHOT_TO, origin).contains("snapshot(level)"), why(origin,
                    "and a player who arrives is still sent the borders directly - only the change path is "
                            + "deferred"));
        }
    }

    @Test
    @DisplayName("what a release would overwrite is counted off resident chunks, never through the level")
    void theOverwriteCountReadsOnlyResidentChunks() throws IOException {
        for (Path origin : sources()) {
            String text = read(origin);

            assertFalse(text.contains("level.getBlockState("), why(origin,
                    "Level#getBlockState goes through Level#getChunk, so one such read anywhere in this class is "
                            + "the deadlock again"));
            String entryFor = bodyOf(text, ENTRY_FOR, origin);
            assertFalse(entryFor.contains("StructureRelease.occupied("), why(origin,
                    "the exact count reads Level#getBlockState one block at a time; it stays for the "
                            + "confirmation sentence, which a player triggers, and must not be reached from a "
                            + "snapshot"));
            assertTrue(entryFor.contains("residentOccupied("), why(origin,
                    "the snapshot counts over chunks that are already resident"));

            String counted = bodyOf(text, RESIDENT_OCCUPIED, origin);
            assertTrue(counted.contains("getChunkNow("), why(origin,
                    "getChunkNow is the only read without a future: getChunk(..., false) still reaches "
                            + "MainThreadExecutor#managedBlock on its cache-miss path, so requireChunk = false does "
                            + "not make it safe"));
            assertFalse(counted.contains("level.getBlockState("), why(origin,
                    "a target that reaches into a chunk that is not resident is under-counted rather than "
                            + "waited for: the number is a warning, and the count that decides anything is "
                            + "StructureRelease.start's own conflict guard"));
        }
    }

    // ------------------------------------------------------------- helpers --

    /**
     * Every copy of the class this build can reach.
     *
     * <p>The two loaders keep a verbatim mirror of it, and the working directory differs between a module's own
     * test task and a run from the repository root, so every spelling that exists is checked rather than the one
     * that was in mind when this was written.
     */
    private static List<Path> sources() {
        List<Path> found = new ArrayList<>();
        for (Path root : List.of(Path.of(""), Path.of("fabric"), Path.of("neoforge"),
                Path.of("..").resolve("fabric"), Path.of("..").resolve("neoforge"))) {
            Path candidate = root.resolve(SOURCE).normalize().toAbsolutePath();
            if (Files.isRegularFile(candidate) && !found.contains(candidate)) {
                found.add(candidate);
            }
        }
        assertFalse(found.isEmpty(),
                "no StructurePreviews.java found from " + Path.of("").toAbsolutePath()
                        + "; this guard reads the source on purpose");
        return found;
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static int countOf(String source, String needle) {
        return source.split(Pattern.quote(needle), -1).length - 1;
    }

    private static String why(Path origin, String reason) {
        return origin + ": " + reason;
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature, Path origin) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, origin + " no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, origin + " has no body for '" + signature + "'");
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
        throw new IllegalStateException(origin + ": unbalanced braces after '" + signature + "'");
    }
}
