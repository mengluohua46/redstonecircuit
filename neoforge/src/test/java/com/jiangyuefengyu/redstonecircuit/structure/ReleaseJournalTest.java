package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * The release journal's file layer: the part that has to be right for an interrupted release to be repairable at all.
 *
 * <p>A release bigger than one tick writes down where it is before its first block, and the next server start
 * finishes it. Everything rests on this: an entry that survives a restart, one that is gone once the release is done,
 * and a file that cannot be read being left alone rather than crashed on or obeyed.
 *
 * <p>Driven from a temporary directory, so the whole of write/read/remove is checked with no game running; which
 * level and which payload is the game test's business.
 */
class ReleaseJournalTest {

    private static final UUID ID = UUID.fromString("12345678-1234-5678-1234-567812345678");
    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private static ReleaseJournal.Entry entry() {
        return new ReleaseJournal.Entry(ID, "the door machine", OVERWORLD, new BlockPos(10, 64, -30));
    }

    @Test
    @DisplayName("a release written down is a release found again after a restart")
    void anEntrySurvivesTheFileLayer(@TempDir Path directory) {
        ReleaseJournal.writeTo(directory, entry());
        List<ReleaseJournal.Entry> read = ReleaseJournal.readFrom(directory);
        assertEquals(1, read.size(), "the entry must be there, got " + read);
        ReleaseJournal.Entry found = read.get(0);
        assertEquals(ID, found.structureId(), "the structure id is what the payload is loaded by");
        assertEquals("the door machine", found.name());
        assertEquals(OVERWORLD, found.dimension(), "and the dimension is which level to resume in");
        assertEquals(new BlockPos(10, 64, -30), found.origin(), "and the origin is where");
    }

    @Test
    @DisplayName("a finished release leaves nothing behind")
    void removingAnEntryLeavesNothingBehind(@TempDir Path directory) {
        ReleaseJournal.writeTo(directory, entry());
        ReleaseJournal.removeFrom(directory, ID);
        assertTrue(ReleaseJournal.readFrom(directory).isEmpty(),
                "a record that outlives its release would resume a finished structure at the next start");
        // Removing one that is not there is not an error: a finished release removes its own record.
        ReleaseJournal.removeFrom(directory, ID);
        assertTrue(ReleaseJournal.readFrom(directory).isEmpty());
    }

    @Test
    @DisplayName("a tag that names no release is not a journal")
    void aTagThatNamesNoReleaseIsNotAJournal() {
        assertNull(ReleaseJournal.load(new CompoundTag()), "an empty tag describes nothing");
        CompoundTag almost = ReleaseJournal.save(entry());
        almost.remove("dimension");
        assertNull(ReleaseJournal.load(almost), "no dimension means no level to resume in");
        CompoundTag noOrigin = ReleaseJournal.save(entry());
        noOrigin.remove("origin");
        assertNull(ReleaseJournal.load(noOrigin), "and no origin means no place to write");
    }

    @Test
    @DisplayName("a file that cannot be read as a journal is left alone, and does not hide the others")
    void unreadableFilesAreLeftAlone(@TempDir Path directory) throws IOException {
        ReleaseJournal.writeTo(directory, entry());
        // Something else in the directory: a human's note, and a file with the right suffix that is not NBT.
        Files.writeString(directory.resolve("notes.txt"), "not a journal");
        Files.write(directory.resolve("rubbish.nbt"), new byte[] { 1, 2, 3 });
        List<ReleaseJournal.Entry> read = ReleaseJournal.readFrom(directory);
        assertEquals(1, read.size(), "only the real entry may be read, got " + read);
        assertEquals(ID, read.get(0).structureId());
        assertTrue(Files.exists(directory.resolve("rubbish.nbt")),
                "an unreadable file is left where it is: deleting somebody else's file is worse than a warning");
    }

    @Test
    @DisplayName("a journal for a structure that was renamed or moved still names its own id")
    void theIdIsTheIdentity(@TempDir Path directory) {
        ReleaseJournal.writeTo(directory, entry());
        UUID other = UUID.randomUUID();
        ReleaseJournal.writeTo(directory, new ReleaseJournal.Entry(other, "another", OVERWORLD, BlockPos.ZERO));
        assertEquals(2, ReleaseJournal.readFrom(directory).size(), "two releases in flight are two entries");
        ReleaseJournal.removeFrom(directory, other);
        List<ReleaseJournal.Entry> left = ReleaseJournal.readFrom(directory);
        assertEquals(1, left.size());
        assertEquals(ID, left.get(0).structureId(), "removing one must not remove the other");
    }

    @Test
    @DisplayName("the file name is the id, in one spelling, so one release cannot become two files")
    void theFileNameIsTheId(@TempDir Path directory) {
        UUID mixedCase = UUID.fromString("ABCDEF01-2345-6789-ABCD-EF0123456789");
        Path first = ReleaseJournal.fileOf(directory, mixedCase);
        Path second = ReleaseJournal.fileOf(directory, UUID.fromString(mixedCase.toString().toLowerCase()));
        assertEquals(first, second,
                "an id has one file name: two spellings on a case-insensitive filesystem would be two records for "
                        + "one release");
        assertTrue(first.getFileName().toString().endsWith(".nbt"));
        assertTrue(first.getFileName().toString().startsWith("abcdef01"),
                "and it is lower case, which is the spelling the payload's own file uses");
    }
}
