package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The save's own structure files, which is where a build actually lives.
 *
 * <p>Everything here is about the parts a player would feel: a structure that is written is still there
 * after a restart, an id from another save is <b>missing</b> rather than empty, an export never silently
 * overwrites an earlier one, and an interrupted write never leaves half a build behind.
 */
class StructureWorldStoreTest {

    private static StructurePayload payload(UUID id, String name) {
        PayloadHeader header = PayloadHeader.fresh(id, name, new Vec3i(2, 1, 2), BlockPos.ZERO,
                IoMode.ACTIVATION_FACES, true);
        CompoundTag stone = new CompoundTag();
        stone.putString("Name", "minecraft:stone");
        int[] cells = {0, 0, 0, 0};
        return StructurePayload.of(header, Map.of(Direction.NORTH,
                        java.util.List.of(new ActivationTable.FaceEntry(new BlockPos(0, 0, 0), 5))),
                java.util.List.of(), java.util.List.of(stone), IndexArray.of(cells, 1),
                java.util.List.of(new StructurePayload.ShellEntry(new BlockPos(-1, 0, 0), 0)),
                java.util.List.of(), java.util.List.of());
    }

    @Test
    @DisplayName("a stored structure is a file in this save, and it reads back exactly")
    void storedStructuresLiveInTheSave(@TempDir Path world) throws Exception {
        StructureWorldStore store = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID id = UUID.randomUUID();
        StructurePayload original = payload(id, "my machine");

        store.add(original);

        Path file = store.fileOf(id);
        assertEquals(world.resolve("redstonecircuit").resolve("structures"), store.structuresDirectory());
        assertTrue(Files.isRegularFile(file), "the structure must be a file under the world directory");
        assertEquals(id.toString(), file.getFileName().toString().replace(".nbt", ""));
        assertEquals(original, store.load(id), "and it must come back byte for byte");
        assertTrue(store.contains(id));
        assertFalse(Files.exists(file.resolveSibling(file.getFileName() + ".tmp")),
                "the temporary file of an atomic write must not be left behind");
    }

    @Test
    @DisplayName("an id this save has no file for is missing, not empty")
    void aStructureFromAnotherSaveIsMissing(@TempDir Path world) {
        StructureWorldStore store = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID fromElsewhere = UUID.randomUUID();

        assertFalse(store.contains(fromElsewhere));
        assertThrows(NoSuchFileException.class, () -> store.load(fromElsewhere),
                "a missing structure has to be tellable apart from an empty one");
        assertEquals(null, store.loadOrNull(fromElsewhere), "and the tolerant form says null");
    }

    @Test
    @DisplayName("the listing reads the directory, and one broken file does not hide the others")
    void listingSurvivesABrokenFile(@TempDir Path world) throws Exception {
        StructureWorldStore store = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        store.add(payload(first, "first"));
        store.add(payload(second, "second"));

        StructureWorldStore reader = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        assertEquals(2, reader.headers().size(), "both structures must be listed");
        assertEquals(0, reader.unreadable());

        Files.write(store.structuresDirectory().resolve(UUID.randomUUID() + ".nbt"),
                new byte[] {1, 2, 3, 4});
        StructureWorldStore after = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        assertEquals(2, after.headers().size(), "the two good structures are still listed");
        assertEquals(1, after.unreadable(), "and the rubbish file is counted, not thrown");

        Files.write(store.structuresDirectory().resolve("not-a-uuid.nbt"), new byte[] {1});
        StructureWorldStore names = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        assertEquals(2, names.headers().size(), "a file that is not named after an id is not ours");
        assertEquals(2, names.unreadable());
    }

    @Test
    @DisplayName("deleting a structure removes it from the save and from the listing")
    void deletingRemovesTheFile(@TempDir Path world) throws Exception {
        StructureWorldStore store = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID id = UUID.randomUUID();
        store.add(payload(id, "gone soon"));
        assertTrue(store.delete(id));
        assertFalse(store.contains(id));
        assertFalse(store.delete(id), "deleting twice is not a state change");
    }

    @Test
    @DisplayName("exporting twice under the same name keeps both files")
    void exportNeverOverwrites(@TempDir Path world) throws Exception {
        StructureWorldStore store = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID id = UUID.randomUUID();
        store.add(payload(id, "我的机器 / my machine"));

        Path first = store.exportTo(id);
        Path second = store.exportTo(id);

        assertTrue(Files.isRegularFile(first));
        assertTrue(Files.isRegularFile(second));
        assertNotEquals(first, second, "an export is never silently overwritten");
        assertTrue(second.getFileName().toString().contains("(2)"), second.getFileName().toString());
        assertTrue(first.getFileName().toString().contains("my_machine"),
                "a name is made safe for a filesystem but stays readable: " + first.getFileName());
    }

    @Test
    @DisplayName("an imported file becomes a new id, and the file it came from is untouched")
    void importRegistersANewId(@TempDir Path world) throws Exception {
        StructureWorldStore other = new StructureWorldStore(world.resolve("other"), PayloadLimits.DEFAULT);
        UUID originalId = UUID.randomUUID();
        other.add(payload(originalId, "imported"));
        Path sourceFile = other.fileOf(originalId);

        StructureWorldStore ours = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID imported = ours.importFrom(sourceFile);

        assertNotEquals(originalId, imported,
                "the id has to be new: the one in the file may already be in use here");
        assertTrue(Files.isRegularFile(sourceFile), "and the file it came from is left alone");
        StructurePayload back = ours.load(imported);
        assertEquals("imported", back.header().name());
        assertEquals(new Vec3i(2, 1, 2), back.header().size());
        assertEquals(other.load(originalId).header().size(), back.header().size(),
                "everything but the id comes across");
        assertEquals(back.computeHash(), back.payloadHash(),
                "and the cache key is recomputed for the new id's payload");
    }

    @Test
    @DisplayName("a structure written now is still found by a store that never saw it")
    void filesAreTheTruth(@TempDir Path world) throws Exception {
        StructureWorldStore writer = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        UUID id = UUID.randomUUID();
        writer.add(payload(id, "after a restart"));

        // A second store with an empty cache is what a server restart looks like.
        StructureWorldStore afterRestart = new StructureWorldStore(world, PayloadLimits.DEFAULT);
        Map<String, PayloadHeader> byName = new HashMap<>();
        afterRestart.headers().forEach(header -> byName.put(header.name(), header));
        assertTrue(byName.containsKey("after a restart"),
                "the listing is built from the files, so a restart cannot lose one");
        assertEquals(id, byName.get("after a restart").uuid());
    }
}
