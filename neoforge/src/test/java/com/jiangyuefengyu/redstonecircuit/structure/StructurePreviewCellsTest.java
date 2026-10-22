package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.Vec3i;

/**
 * Which preview cell a structure's own cell is drawn in.
 *
 * <p>The live view speaks the structure's own numbering, because that is what the server samples; a coarsened
 * preview speaks its own. The two are one division apart, and the one place that division can be wrong is the
 * edge: a raw coordinate the preview does not have must be clamped into it, never wrapped, because a wrap draws
 * a lamp in the miniature's first column and reads as a structure that is not the one in the block.
 *
 * <p>Pure arithmetic, so it is decided here without a level, a frame or a packet.
 *
 * <p>What a coarsened cell <em>stands for</em> is the other half of the same question, and it is asked by three callers
 * that each number their cells differently - a capture by palette index, a live view and an icon by block state id - so
 * the tests below pin one answer per data set and not one answer per numbering (see {@link StructurePreviewCells.Rank}).
 */
class StructurePreviewCellsTest {

    /** The two blocks of the fixture: they tie in the cell below, and the log is the one named first. */
    private static final String LOG = "minecraft:oak_log";
    private static final String PLANKS = "minecraft:oak_planks";
    private static final String AIR = "minecraft:air";

    /** A 2³ structure at scale two: one preview cell of eight cells, all of them skin, four planks and four logs. */
    private static final Vec3i GROUP = new Vec3i(2, 2, 2);

    /** State ids of the same two blocks, chosen so that the planks carry the smaller one - as a real registry may. */
    private static final int LIVE_LOG = 91;
    private static final int LIVE_PLANKS = 12;

    @Test
    @DisplayName("one preview cell stands for every structure cell its scale covers")
    void aPreviewCellStandsForTheCellsItCovers() {
        Vec3i raw = new Vec3i(6, 1, 1);
        Vec3i preview = new Vec3i(3, 1, 1);

        assertEquals(0, StructurePreviewCells.previewIndex(StructureLiveView.index(0, 0, 0, raw), raw, 2, preview));
        assertEquals(0, StructurePreviewCells.previewIndex(StructureLiveView.index(1, 0, 0, raw), raw, 2, preview),
                "the second structure cell is still the first preview cell");
        assertEquals(1, StructurePreviewCells.previewIndex(StructureLiveView.index(2, 0, 0, raw), raw, 2, preview));
        assertEquals(1, StructurePreviewCells.previewIndex(StructureLiveView.index(3, 0, 0, raw), raw, 2, preview));
        assertEquals(2, StructurePreviewCells.previewIndex(StructureLiveView.index(4, 0, 0, raw), raw, 2, preview));
        assertEquals(2, StructurePreviewCells.previewIndex(StructureLiveView.index(5, 0, 0, raw), raw, 2, preview));
    }

    @Test
    @DisplayName("a structure whose size the scale does not divide ends in a partial preview cell")
    void aPartialLastCellStillBelongsToThePreview() {
        // 20 at scale 3 is seven preview cells: six of three and one of two.
        Vec3i raw = new Vec3i(20, 1, 1);
        Vec3i preview = new Vec3i(7, 1, 1);

        assertEquals(0, StructurePreviewCells.previewCoordinate(0, 3, 7));
        assertEquals(6, StructurePreviewCells.previewCoordinate(18, 3, 7));
        assertEquals(6, StructurePreviewCells.previewCoordinate(19, 3, 7),
                "the last two structure cells share the seventh preview cell");
        assertTrue(7 * 3 >= 20, "the preview's seven cells do cover the twenty structure cells");
    }

    @Test
    @DisplayName("a coordinate the preview does not have is clamped into it, never wrapped")
    void nothingMapsOutsideThePreview() {
        assertEquals(6, StructurePreviewCells.previewCoordinate(20, 3, 7), "one past the structure stays in the last cell");
        assertEquals(6, StructurePreviewCells.previewCoordinate(1_000, 3, 7));
        assertEquals(0, StructurePreviewCells.previewCoordinate(-1, 3, 7), "and one before it stays in the first");
        assertEquals(0, StructurePreviewCells.previewCoordinate(0, 3, 7));
        assertEquals(5, StructurePreviewCells.previewCoordinate(5, 0, 7),
                "a scale of zero is one: a division that cannot happen is not a preview of scale nothing");
        assertEquals(0, StructurePreviewCells.previewCoordinate(5, 3, 0), "and a preview with no cells has one");
    }

    @Test
    @DisplayName("a structure cell's flat index maps to a preview cell's flat index")
    void theFlatIndexMapsToo() {
        Vec3i raw = new Vec3i(5, 3, 7);
        Vec3i preview = new Vec3i(3, 2, 4);

        // (4, 2, 5) of a 5x3x7 structure: the last cell of every axis.
        int rawIndex = StructureLiveView.index(4, 2, 5, raw);
        int previewIndex = StructurePreviewCells.previewIndex(rawIndex, raw, 2, preview);

        assertEquals(4, StructurePreviewCells.rawX(rawIndex, raw), "a flat index is read back as the cell it was");
        assertEquals(2, StructurePreviewCells.rawY(rawIndex, raw));
        assertEquals(5, StructurePreviewCells.rawZ(rawIndex, raw));
        assertEquals(StructureLiveView.index(2, 1, 2, preview), previewIndex,
                "(4,2,5) of a 5x3x7 structure is (2,1,2) of its 3x2x4 preview");
    }

    @Test
    @DisplayName("every cell of a real coarsened preview maps inside it")
    void everyStructureCellLandsInsideThePreview() {
        Vec3i raw = new Vec3i(20, 20, 20);
        int[] indices = new int[20 * 20 * 20];
        StructurePreview preview = StructurePreview.build(raw,
                java.util.List.of(blockTag("minecraft:stone"), blockTag("minecraft:air")),
                IndexArray.of(indices, 2), 3);

        assertEquals(7, preview.size().getX(), "20 at scale 3 is seven preview cells");
        for (int y = 0; y < raw.getY(); y++) {
            for (int z = 0; z < raw.getZ(); z++) {
                for (int x = 0; x < raw.getX(); x++) {
                    int mapped = StructurePreviewCells.previewIndex(StructureLiveView.index(x, y, z, raw), raw,
                            preview.scale(), preview.size());
                    assertTrue(mapped >= 0 && mapped < preview.cellCount(),
                            "(" + x + "," + y + "," + z + ") mapped to " + mapped + " of "
                                    + preview.cellCount() + " cell(s)");
                }
            }
        }
    }

    @Test
    @DisplayName("a tie is decided by what the blocks are, not by where the palette happens to list them")
    void aTieIsNotDecidedByThePaletteOrder() {
        String[] picture = halfPlanksHalfLogs();
        // A palette is the order a structure's blocks arrived in, and it used to be the whole of the capture's
        // numbering. A picture is not the order its blocks were listed in, so the two listings below are one picture
        // and must answer one block - the log, because a tie is decided by what a block is called.
        assertEquals(LOG, capture(java.util.List.of(PLANKS, LOG, AIR), picture),
                "four planks and four logs in one coarsened cell: the log is named first, so the cell is a log");
        assertEquals(LOG, capture(java.util.List.of(LOG, PLANKS, AIR), picture),
                "and the same structure listed the other way round is the same picture");
    }

    @Test
    @DisplayName("a capture, a live view and an icon answer a tie with the same block")
    void theThreeEntriesAgreeOnATie() {
        String[] picture = halfPlanksHalfLogs();

        // 1. The capture (StructurePreview.build): palette indices, listed with the log first.
        assertEquals(LOG, capture(java.util.List.of(LOG, PLANKS, AIR), picture), "the capture draws the log");

        // 2. The live view (StructureBlockEntity#representativeId): the same cells as block state ids, and here the
        // planks carry the smaller id, which is the numbering that used to win this tie and drew a ring of logs.
        int[] states = statesOf(picture, LIVE_PLANKS, LIVE_LOG);
        StructurePreviewCells.Rank rank = byName(id -> id == LIVE_LOG ? LOG : PLANKS);
        int live = StructurePreviewCells.representative(GROUP, rawCells(states), id -> false, 0, 0, 0, 0, 2, rank);
        assertEquals(LIVE_LOG, live, "the live view draws the log too");

        // 3. The icon (StructurePreviewGeometry#icon): the same ids renumbered into that one order and handed to the
        // half-resolution rule, which compares numbers and nothing else.
        StructurePreviewCells.Numbering ranked = StructurePreviewCells.numbering(states, rank);
        int[] reduced = PreviewLod.half(ranked.cells(), 0, GROUP, PreviewLod.half(GROUP));
        assertEquals(LIVE_LOG, ranked.value(reduced[0]), "and the icon of that cell is the log as well");
    }

    @Test
    @DisplayName("the answer to a tie does not change when the same picture is numbered differently")
    void aTieDoesNotDependOnTheNumbering() {
        String[] picture = halfPlanksHalfLogs();
        // One picture in two numberings that disagree about which block comes first, as a capture's palette and a live
        // view's state ids do. Both are described by one rank, because a rank is about the blocks and not the numbers.
        java.util.Map<Integer, String> names = java.util.Map.of(LIVE_PLANKS, PLANKS, LIVE_LOG, LOG, 400, PLANKS, 7, LOG);
        StructurePreviewCells.Rank rank = byName(names::get);

        // By the numbers alone the same picture answers the planks in one numbering and the log in the other: that is
        // the disagreement, and it is why a vote cannot be decided by the numbers.
        assertEquals(PLANKS, names.get(StructurePreviewCells.representative(GROUP,
                        rawCells(statesOf(picture, LIVE_PLANKS, LIVE_LOG)), id -> false, 0, 0, 0, 0, 2)),
                "by the numbers, the planks come first in this numbering");
        assertEquals(LOG, names.get(StructurePreviewCells.representative(GROUP,
                        rawCells(statesOf(picture, 400, 7)), id -> false, 0, 0, 0, 0, 2)),
                "and the log comes first in the other one");

        int planksFirst = StructurePreviewCells.representative(GROUP,
                rawCells(statesOf(picture, LIVE_PLANKS, LIVE_LOG)), id -> false, 0, 0, 0, 0, 2, rank);
        int logsFirst = StructurePreviewCells.representative(GROUP,
                rawCells(statesOf(picture, 400, 7)), id -> false, 0, 0, 0, 0, 2, rank);
        assertEquals(LOG, names.get(planksFirst), "described by what they stand for, both numberings answer the log");
        assertEquals(LOG, names.get(logsFirst));
        assertNotEquals(planksFirst, logsFirst,
                "although they answer with different numbers, which is all the old rule had to go on");
    }

    private static net.minecraft.nbt.CompoundTag blockTag(String name) {
        net.minecraft.nbt.CompoundTag tag = new net.minecraft.nbt.CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    /** Four planks and four logs among the eight cells of {@link #GROUP}: a cell that holds as many of one as the other. */
    private static String[] halfPlanksHalfLogs() {
        String[] picture = new String[GROUP.getX() * GROUP.getY() * GROUP.getZ()];
        for (int y = 0; y < GROUP.getY(); y++) {
            for (int z = 0; z < GROUP.getZ(); z++) {
                for (int x = 0; x < GROUP.getX(); x++) {
                    picture[StructureLiveView.index(x, y, z, GROUP)] = x == 0 ? PLANKS : LOG;
                }
            }
        }
        return picture;
    }

    /** The same picture as one numbering of block state ids: the planks on {@code planks}, the logs on {@code logs}. */
    private static int[] statesOf(String[] picture, int planks, int logs) {
        int[] states = new int[picture.length];
        for (int i = 0; i < picture.length; i++) {
            states[i] = LOG.equals(picture[i]) ? logs : planks;
        }
        return states;
    }

    private static StructurePreviewCells.RawCells rawCells(int[] states) {
        return (x, y, z) -> states[StructureLiveView.index(x, y, z, GROUP)];
    }

    /** The rank a live view and an icon pass: what the number stands for, never the number. */
    private static StructurePreviewCells.Rank byName(java.util.function.IntFunction<String> name) {
        return (left, right) -> name.apply(left).compareTo(name.apply(right));
    }

    /** The name a capture puts in the single preview cell of {@link #GROUP} for a palette in this order. */
    private static String capture(java.util.List<String> palette, String[] picture) {
        java.util.List<net.minecraft.nbt.CompoundTag> tags = new java.util.ArrayList<>();
        for (String name : palette) {
            tags.add(blockTag(name));
        }
        int[] cells = new int[picture.length];
        for (int i = 0; i < picture.length; i++) {
            cells[i] = palette.indexOf(picture[i]);
        }
        StructurePreview preview = StructurePreview.build(GROUP, tags, IndexArray.of(cells, palette.size()), 2);
        assertEquals(1, preview.cellCount(), "a 2x2x2 structure at scale two is one preview cell");
        return preview.palette().get(preview.cells().get(0)).getString("Name");
    }
}
