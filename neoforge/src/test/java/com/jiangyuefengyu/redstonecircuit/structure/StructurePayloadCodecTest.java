package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

/**
 * The payload codec: what a structure is, from the file on disk all the way back.
 *
 * <p>No live registry is needed: the palette is stored as raw block-state tags, so a palette -
 * properties included - round-trips without the block registry that a bootstrap would have to build.
 */
class StructurePayloadCodecTest {

    /** {Name: minecraft:stone} - the shape NbtUtils writes, written by hand so no registry is needed. */
    private static CompoundTag stone() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:stone");
        return tag;
    }

    /** {Name: minecraft:redstone_wire, Properties: {power: 7}} - a state, not just a block. */
    private static CompoundTag poweredWire() {
        CompoundTag properties = new CompoundTag();
        properties.putString("power", "7");
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", "minecraft:redstone_wire");
        tag.put("Properties", properties);
        return tag;
    }

    /** A chest with something in it: the "container contents travel with the structure" decision. */
    private static CompoundTag chestWithStone() {
        CompoundTag item = new CompoundTag();
        item.putString("id", "minecraft:stone");
        item.putByte("Count", (byte) 3);
        ListTag items = new ListTag();
        items.add(item);
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.put("Items", items);
        return chest;
    }

    private static Map<Direction, List<ActivationTable.FaceEntry>> sampleFaces() {
        return Map.of(
                Direction.NORTH, List.of(new ActivationTable.FaceEntry(new BlockPos(0, 0, 0), 15),
                        new ActivationTable.FaceEntry(new BlockPos(2, 1, 3), 7)),
                Direction.UP, List.of(new ActivationTable.FaceEntry(new BlockPos(1, 1, 1), 4)));
    }

    private static List<ActivationTable.ManualEntry> sampleManual() {
        return List.of(new ActivationTable.ManualEntry("key.keyboard.g", new BlockPos(1, 0, 2), 12, "open"));
    }

    private static List<CompoundTag> samplePalette() {
        return List.of(stone(), poweredWire());
    }

    private static IndexArray sampleIndices() {
        int[] cells = new int[24];
        for (int i = 0; i < cells.length; i++) {
            cells[i] = i % 2;
        }
        return IndexArray.of(cells, 2);
    }

    private static StructurePayload sample() {
        PayloadHeader header = PayloadHeader.fresh(UUID.fromString("12345678-1234-1234-1234-1234567890ab"),
                "test structure", new Vec3i(3, 2, 4), new BlockPos(-1, 0, 2), IoMode.MANUAL, true);
        return StructurePayload.of(header, sampleFaces(), sampleManual(), samplePalette(),
                sampleIndices(),
                List.of(new StructurePayload.ShellEntry(new BlockPos(-1, 0, 0), 0)),
                List.of(new StructurePayload.SlotEntry(new BlockPos(1, 1, 1), componentInside())),
                List.of(new StructurePayload.BlockEntityEntry(new BlockPos(2, 0, 3), chestWithStone())));
    }

    /** This mod's own component inside a block, as the world store saves it. */
    private static CompoundTag componentInside() {
        CompoundTag node = new CompoundTag();
        node.putString("type", "DUST");
        node.putInt("power", 7);
        return node;
    }

    /** The sample with one part replaced - a payload that need not be legal, so refusals can be tested. */
    private static StructurePayload variant(PayloadHeader header,
                                            Map<Direction, List<ActivationTable.FaceEntry>> faces,
                                            List<ActivationTable.ManualEntry> manual,
                                            List<CompoundTag> palette) {
        StructurePayload base = sample();
        return StructurePayload.of(header, faces, manual, palette, base.indices(), base.contextShell(),
                base.slotData(), base.blockEntities());
    }

    private static PayloadHeader headerWithSize(Vec3i size) {
        return new PayloadHeader(PayloadHeader.CURRENT_FORMAT, sample().header().uuid(),
                "test structure", size, new BlockPos(-1, 0, 2), 0, IoMode.MANUAL, true);
    }

    @Test
    @DisplayName("the output table travels through the file, order and all, and is part of the payload's key")
    void anOutputTableTravelsThroughTheFile() throws Exception {
        // One face watched by two cells (the list is not a map), a legal silent strength of 0, and two
        // cells on different faces of one structure.
        List<ActivationTable.OutputEntry> rows = List.of(
                new ActivationTable.OutputEntry(new BlockPos(0, 0, 0), Direction.UP, 9),
                new ActivationTable.OutputEntry(new BlockPos(1, 0, 2), Direction.UP, 12),
                new ActivationTable.OutputEntry(new BlockPos(1, 0, 2), Direction.EAST, 0));
        StructurePayload plain = sample();
        StructurePayload withOutput = StructurePayload.of(plain.header(), plain.faces(), plain.manual(), rows,
                plain.palette(), plain.indices(), plain.contextShell(), plain.slotData(),
                plain.blockEntities());

        StructurePayload reloaded = StructurePayload.fromBytes(withOutput.toBytes(), PayloadLimits.DEFAULT);

        assertEquals(rows, reloaded.output(), "the table has to be in the file, in the order it was built");
        assertEquals(withOutput.payloadHash(), reloaded.payloadHash(),
                "and the key the render cache uses has to come back with it");
        assertNotEquals(plain.payloadHash(), withOutput.payloadHash(),
                "a structure with an output table is not the same structure as one without");
    }

    @Test
    @DisplayName("an output row outside the selection is refused when the file is read")
    void anOutputRowOutsideTheSelectionIsRefused() {
        StructurePayload plain = sample();
        // (3, 0, 2) is one past the sample's size of 3x2x4 - a hand-edited file, the case the injector
        // would otherwise drop in silence.
        StructurePayload bad = StructurePayload.of(plain.header(), plain.faces(), plain.manual(),
                List.of(new ActivationTable.OutputEntry(new BlockPos(3, 0, 2), Direction.NORTH, 7)),
                plain.palette(), plain.indices(), plain.contextShell(), plain.slotData(),
                plain.blockEntities());

        PayloadFormatException refused = assertThrows(PayloadFormatException.class, () -> bad.validate(PayloadLimits.DEFAULT));
        assertTrue(refused.getMessage().contains("output entry"),
                "the refusal has to name what is wrong, got " + refused.getMessage());
    }

    @Test
    @DisplayName("a payload survives compressed bytes unchanged, block entity data included")
    void roundTripsThroughBytes() throws Exception {
        StructurePayload original = sample();
        byte[] bytes = original.toBytes();
        StructurePayload reloaded = StructurePayload.fromBytes(bytes, PayloadLimits.DEFAULT);

        assertEquals(original, reloaded, "every field must come back, or the file is a lossy store");
        assertEquals(PayloadHeader.CURRENT_FORMAT, reloaded.header().formatVersion());
        assertEquals(original.header().uuid(), reloaded.header().uuid());
        assertEquals(IoMode.MANUAL, reloaded.header().ioMode());
        assertTrue(reloaded.header().byCut());
        assertEquals(new BlockPos(-1, 0, 2), reloaded.header().relativeOrigin());
        assertEquals(2, reloaded.palette().size());
        assertEquals("7", reloaded.palette().get(1).getCompound("Properties").getString("power"),
                "a block state's properties are part of the structure");
        assertEquals(chestWithStone(), reloaded.blockEntities().get(0).nbt(),
                "container contents are captured, so they must round-trip");
        assertEquals(componentInside(), reloaded.slotData().get(0).node(),
                "this mod's own component inside a block is not a block state, so it must travel in its "
                        + "own list");
        assertEquals(new BlockPos(-1, 0, 0), reloaded.contextShell().get(0).relativePos(),
                "and the context shell keeps the block that sat just outside the selection");
        assertEquals(2, reloaded.faces().get(Direction.NORTH).size());
        assertFalse(reloaded.faces().containsKey(Direction.SOUTH),
                "a face with no table stays absent: an unfilled face does nothing");
        assertEquals(15, reloaded.faces().get(Direction.NORTH).get(0).strength());
        assertEquals("open", reloaded.manual().get(0).label());
    }

    @Test
    @DisplayName("the payload goes to a file and comes back")
    void roundTripsThroughAFile(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("12345678-1234-1234-1234-1234567890ab.nbt");
        StructurePayload original = sample();
        original.writeTo(file);

        assertTrue(Files.size(file) > 0, "the file must actually be written");
        assertEquals(original, StructurePayload.readFrom(file, PayloadLimits.DEFAULT));
    }

    @Test
    @DisplayName("a file that is not there is a missing file, not a corrupt one")
    void aMissingFileIsNotACorruptFile(@TempDir Path dir) {
        Path missing = dir.resolve("not-a-uuid.nbt");
        assertThrows(NoSuchFileException.class,
                () -> StructurePayload.readFrom(missing, PayloadLimits.DEFAULT),
                "no file for this uuid and a broken file are different answers, and so must the code say");
    }

    @Test
    @DisplayName("damaged bytes are refused instead of loading a half structure")
    void damagedBytesAreRefused(@TempDir Path dir) throws Exception {
        byte[] good = sample().toBytes();
        byte[] damaged = good.clone();
        for (int i = good.length / 2; i < Math.min(good.length, good.length / 2 + 8); i++) {
            damaged[i] = (byte) ~damaged[i];
        }
        assertThrows(PayloadFormatException.class,
                () -> StructurePayload.fromBytes(damaged, PayloadLimits.DEFAULT));

        Path file = dir.resolve("damaged.nbt");
        Files.write(file, damaged);
        assertThrows(PayloadFormatException.class,
                () -> StructurePayload.readFrom(file, PayloadLimits.DEFAULT),
                "a damaged file on disk is a format problem, and must be reported as one");
    }

    @Test
    @DisplayName("another format version is refused by name")
    void anotherFormatVersionIsRefused() {
        PayloadHeader fromTheFuture = new PayloadHeader(99, sample().header().uuid(), "x",
                sample().header().size(), BlockPos.ZERO, 0, IoMode.ACTIVATION_FACES, false);
        StructurePayload future = variant(fromTheFuture, sampleFaces(), sampleManual(), samplePalette());

        PayloadFormatException refused = assertThrows(PayloadFormatException.class,
                () -> future.validate(PayloadLimits.DEFAULT));
        assertTrue(refused.getMessage().contains("format 99"), refused.getMessage());
    }

    @Test
    @DisplayName("a structure bigger than the limits is refused, and says by how much")
    void oversizedStructuresAreRefused() {
        StructurePayload big = variant(headerWithSize(new Vec3i(64, 1, 1)), sampleFaces(),
                sampleManual(), samplePalette());

        PayloadFormatException refused = assertThrows(PayloadFormatException.class,
                () -> big.validate(PayloadLimits.DEFAULT));
        assertTrue(refused.getMessage().contains("64 blocks on its longest axis"), refused.getMessage());
    }

    @Test
    @DisplayName("an activation entry pointing outside the selection is refused")
    void entriesOutsideTheSelectionAreRefused() {
        StructurePayload outside = variant(sample().header(),
                Map.of(Direction.NORTH, List.of(new ActivationTable.FaceEntry(new BlockPos(9, 0, 0), 4))),
                sampleManual(), samplePalette());

        PayloadFormatException refused = assertThrows(PayloadFormatException.class,
                () -> outside.validate(PayloadLimits.DEFAULT));
        assertTrue(refused.getMessage().contains("outside"), refused.getMessage());
    }

    @Test
    @DisplayName("a manual panel beyond the configured limit is refused")
    void tooManyManualEntriesAreRefused() {
        PayloadLimits tight = new PayloadLimits(48, 110592, 1);
        StructurePayload twoEntries = variant(sample().header(), sampleFaces(),
                List.of(new ActivationTable.ManualEntry("key.keyboard.g", new BlockPos(0, 0, 0), 5, ""),
                        new ActivationTable.ManualEntry("key.keyboard.h", new BlockPos(0, 0, 1), 5, "")),
                samplePalette());

        PayloadFormatException refused = assertThrows(PayloadFormatException.class,
                () -> twoEntries.validate(tight));
        assertTrue(refused.getMessage().contains("limit is 1"), refused.getMessage());
    }

    @Test
    @DisplayName("the hash is stable for one structure and changes when the structure does")
    void theHashIsACacheKeyForTheContent() {
        StructurePayload one = sample();
        StructurePayload same = sample();
        assertEquals(one.payloadHash(), same.payloadHash(), "same structure, same key");

        StructurePayload changed = variant(sample().header(), sampleFaces(), sampleManual(),
                List.of(stone()));
        assertNotEquals(one.payloadHash(), changed.payloadHash(),
                "a different palette is a different structure");
        assertNotEquals(0, one.payloadHash(), "and a real structure must not hash to zero");
    }
}
