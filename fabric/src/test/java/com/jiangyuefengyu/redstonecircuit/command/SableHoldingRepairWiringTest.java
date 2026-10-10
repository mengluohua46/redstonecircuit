package com.jiangyuefengyu.redstonecircuit.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * either), and the storage surgery behind the gate is the carrier half's own code, which is an optional source set
 * this build does not compile. What a source guard <em>can</em> hold is this tree's own half of the tool, plus the
 * blast radius, which is the whole risk: the command must name one chunk, go through the compat gate, and never reach
 * for a live structure or for the blocks.
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

        // The storage surgery itself - dropping the record from the file AND from Sable's in-memory copy - is the
        // carrier half's own code, and that half is an optional source set this build does not compile. What a guard
        // can still hold here, which is the whole risk the tool carries, is the blast radius.
        int tool = command.indexOf("private static LiteralArgumentBuilder<CommandSourceStack> sableHoldingSub() {");
        int after = command.indexOf("private static ServerLevel level(CommandContext<CommandSourceStack> ctx) {");
        assertTrue(tool >= 0 && after > tool, "the sable-holding subtree is still one contiguous piece of the command");
        String holding = command.substring(tool, after);
        assertTrue(holding.contains("if (outcome == null) {") && holding.contains("return 0;"),
                "and nothing is claimed when there is nothing to report: a gate with no backend answers null, and "
                        + "that case says so in chat rather than printing a repair that was never made");
        assertFalse(holding.contains("setBlock") || holding.contains("removeSubLevel")
                        || holding.contains("destroyAllBlocks"),
                "never a live structure, never the blocks and never the world: the subtree's whole reach is the one "
                        + "gated call, so a record whose data could not be read is the only thing a purge can take away");
    }

    /** Reads a source file of this project, addressed from its own directory, e.g. {@code src/main/java/...}. */
    private static String read(String... parts) throws IOException {
        Path path = Path.of("", parts);
        if (Files.isRegularFile(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}
