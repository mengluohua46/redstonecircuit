package com.jiangyuefengyu.redstonecircuit.command;

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
 * The repair tool for a saved sub-level Sable can no longer read, held as a source guard rather than a game test.
 *
 * <p>A structure Sable is not currently loading is filed as a pointer in a per-chunk holding record, and the world
 * reports {@code Failed to load sub_level at global->[chunkPos=[x,z]]} on every load when the data behind that pointer
 * is gone; nothing in the game removes it.
 *
 * <p>The behaviour is Sable's, so this build cannot exercise it (there is no Sable at runtime here, and no client
 * either). What a source guard <em>can</em> hold is the blast radius, which is the whole risk: the tool must name one
 * chunk, go through the compat gate, and never reach for a live structure or for the blocks.
 */
class SableHoldingRepairWiringTest {

    @Test
    @DisplayName("修复工具：磁盘与 Sable 内存两半都清、只清一个区块、结果打在聊天里（含'没清掉'的诚实报告）")
    void theRepairNamesOneChunkAndSaysWhatItDid() throws IOException {
        String command = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCCommand.java");

        assertTrue(command.contains("Commands.literal(\"sable-holding\").then(Commands.literal(\"purge\").then(chunkX))"),
                "the tool is one chunk-coordinate command: /rc sable-holding purge <chunkX> <chunkZ>, which is exactly "
                        + "the unit the failure message prints");
        assertTrue(command.contains("PhysicsCompat.purgeHoldingChunk(level(ctx), chunkX, chunkZ)"),
                "and it goes through the compat gate, so this source set names no Sable type at all");
        assertTrue(command.contains("quit and reload to confirm"),
                "the outcome is printed and the player is told how to check it: dropping the record from the file "
                        + "alone is not enough, because Sable writes its own copy back out at the next save");
        assertTrue(command.contains("no Sable in this world"),
                "including the case where there is no Sable to ask");

        String gate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "PhysicsCompat.java");
        assertTrue(gate.contains("public static String purgeHoldingChunk(ServerLevel level, int chunkX, int chunkZ) {")
                        && gate.contains("if (backendClass == null || !sableLoaded() || level == null) {")
                        && gate.contains("return answer instanceof String text ? text : null;"),
                "the gate answers null - nothing to report - without Sable, exactly like every other entry point here");

        String repair = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableStorageRepair.java");
        assertTrue(repair.contains("map.getStorage().attemptRemoveHoldingChunk(chunk);"),
                "the compat half drops the record from the storage file through Sable's own storage API");
        assertTrue(repair.contains("forgetCached(map, chunk)"),
                "AND forgets Sable's in-memory copy of it: the map keeps what it read in loadedHoldingChunks / "
                        + "allHoldingSubLevels and writes it back out at shutdown, so a file-only repair undoes itself");
        assertTrue(repair.contains("instanceof SubLevelHoldingChunk") && repair.contains("instanceof HoldingSubLevel"),
                "the two cached maps are recognised by what they hold rather than by field name, so a rename inside "
                        + "Sable does not silently point this tool at the wrong thing");
        assertTrue(repair.contains("forgotten < pointers"),
                "and an incomplete repair is reported as one: a Sable that moved its bookkeeping shows up as \"forgot "
                        + "0\" in chat instead of as a silent failure that comes back on the next load");
        assertFalse(repair.contains("removeSubLevel") || repair.contains("destroyAllBlocks")
                        || repair.contains("setBlock"),
                "never a live structure, never the blocks and never the world: the record it drops is one whose data "
                        + "could not be read anyway, so nothing that still exists is being taken away");
    }

    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}
