package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mesh hooks must skip the hosts the overlay draws itself - and nothing else.
 *
 * <p>Sodium's {@code BlockRenderer#renderModel} is handed the <em>world</em> position plus a second
 * position that is section-local; adding the two and calling the result a world position invents
 * blocks that are not hosts, and a skipped block is a hole straight through the world. The tests
 * below pin down which reading is a world position, and the last one reads the hook's source so the
 * invented reading cannot come back.
 */
class HostCubeSkipTest {

    private static final int HOST_X = 122;
    private static final int HOST_Y = -60;
    private static final int HOST_Z = 46;
    private static final long HOST = BlockPos.asLong(HOST_X, HOST_Y, HOST_Z);

    @Test
    @DisplayName("a plain block next to a host stays in the mesh")
    void aPlainBlockNextToAHostIsStillMeshed() {
        // W + (W & 15) == H per axis, which is what the section-local position makes of this block.
        BlockPos plain = new BlockPos(117, -62, 39);
        BlockPos local = new BlockPos(plain.getX() & 15, plain.getY() & 15, plain.getZ() & 15);

        assertFalse(HostCubeSkip.skips(Set.of(HOST), plain, local),
                "a plain block must stay in the chunk mesh: it is not a host, and skipping it is the "
                        + "hole the goggles-wearing player sees next to a machine");
    }

    @Test
    @DisplayName("the host itself is skipped, whichever position carries the world coordinates")
    void theHostItselfIsSkipped() {
        Set<Long> hosts = Set.of(HOST);
        BlockPos host = new BlockPos(HOST_X, HOST_Y, HOST_Z);

        assertTrue(HostCubeSkip.skips(hosts, host, new BlockPos(HOST_X & 15, HOST_Y & 15, HOST_Z & 15)),
                "current Sodium hands in the world position first: that one has to match");
        assertTrue(HostCubeSkip.skips(hosts, host, null),
                "and the vanilla path has no second position at all");
    }

    @Test
    @DisplayName("the older arrangement - origin is the section origin, pos is section-local - still skips")
    void theSwappedArrangementIsStillUnderstood() {
        BlockPos origin = new BlockPos(112, -64, 32);
        BlockPos local = new BlockPos(10, 4, 14);

        assertEquals(HOST, origin.offset(local).asLong(), "the fixture is the two coordinates of one host");

        assertTrue(HostCubeSkip.skips(Set.of(HOST), local, origin),
                "a section origin plus a section-local position is the other reading of the same two "
                        + "arguments, and it names the same host");
    }

    @Test
    @DisplayName("without a second position only exact world coordinates count")
    void withoutASecondPositionOnlyExactCoordinatesCount() {
        Set<Long> hosts = Set.of(HOST);

        assertTrue(HostCubeSkip.skips(hosts, new BlockPos(HOST_X, HOST_Y, HOST_Z), null),
                "the vanilla mesher passes world coordinates, so the host matches");
        assertFalse(HostCubeSkip.skips(hosts, new BlockPos(HOST_X + 1, HOST_Y, HOST_Z), null),
                "and its neighbour does not");
        assertFalse(HostCubeSkip.skips(hosts, new BlockPos(10, 4, 14), null),
                "a section-local position with no section origin to add is not a world position, and "
                        + "reading it as one hides whatever happens to sit there");
    }

    @Test
    @DisplayName("every block the old reading invented is a plain block again")
    void everyBlockTheOldReadingInventedIsKept() {
        Set<Long> hosts = Set.of(HOST);
        Set<Long> invented = new LinkedHashSet<>();

        // Enumerate every block in reach of this host exactly as Sodium would mesh it: pos = world
        // coordinates of the block, origin = that block's own section-local coordinates.
        for (int x = HOST_X - 15; x <= HOST_X + 15; x++) {
            for (int y = HOST_Y - 15; y <= HOST_Y + 15; y++) {
                for (int z = HOST_Z - 15; z <= HOST_Z + 15; z++) {
                    if (BlockPos.asLong(x, y, z) == HOST) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(x, y, z);
                    BlockPos origin = new BlockPos(x & 15, y & 15, z & 15);
                    if (hosts.contains(origin.offset(pos).asLong())) {
                        invented.add(pos.asLong());
                    }
                }
            }
        }

        assertEquals(8, invented.size(),
                "the old reading invents two blocks per axis for one host; a different count means this "
                        + "test is no longer looking at the case it was written for");
        for (long packed : invented) {
            BlockPos plain = BlockPos.of(packed);
            BlockPos origin = new BlockPos(plain.getX() & 15, plain.getY() & 15, plain.getZ() & 15);
            assertFalse(HostCubeSkip.skips(hosts, plain, origin),
                    "block " + plain.toShortString() + " is not a host; hiding it leaves a hole");
        }
    }

    @Test
    @DisplayName("the Sodium hook asks the shared decision, not a second coordinate reading")
    void theSodiumHookAsksTheSharedDecision() throws IOException {
        String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "SodiumBlockRendererMixin.java");

        assertTrue(source.contains("ShaderCompat.skipsBlock("),
                "the hook must go through the shared decision, which is the only place that knows which "
                        + "argument is a world position");
        assertTrue(source.contains("noteMeshSkip("),
                "and it must report what it skipped, or a hole cannot be traced to a position");
        assertFalse(source.contains("origin.offset(pos)"),
                "adding the section-local position to the world position invents blocks: this is the "
                        + "reading that made plain blocks vanish");
        // Not gated on debugLog, so this is the release jar's only evidence that the mesher listened at all.
        assertTrue(source.contains("noteHookUsed("),
                "the Sodium hook must announce itself once per hook whatever the debug switch says: "
                        + "'nothing happens in game' cannot otherwise be told apart from 'the mesher "
                        + "ignored us'");
        String vanilla = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "BlockRenderDispatcherMixin.java");
        assertTrue(vanilla.contains("noteHookUsed("),
                "and so must the vanilla hook, for the same reason: a release log has to show whether a "
                        + "mesh hook ran");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The working directory differs between the test task and a build from the repository root, so both spellings are tried.
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
}
