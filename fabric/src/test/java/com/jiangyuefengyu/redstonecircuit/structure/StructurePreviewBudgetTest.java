package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The preview's second ceiling: how many cells it may have, on top of how many bytes it may take.
 *
 * <p>The byte budget bounds what crosses the network once; a preview that fits it can still be 110,592 cells,
 * which is what a client draws every frame. Both ceilings therefore have to be able to move a structure's scale,
 * and the coarser of the two is the one that decides - a preview that honours one budget and breaks the other is
 * not a preview of anything a player can look at.
 */
class StructurePreviewBudgetTest {

    private static CompoundTag tag(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    private static final CompoundTag STONE = tag("minecraft:stone");
    private static final CompoundTag AIR = tag("minecraft:air");

    /** A cube whose every cell is stone. */
    private static IndexArray solid(Vec3i size) {
        return IndexArray.of(new int[size.getX() * size.getY() * size.getZ()], 2);
    }

    @Test
    @DisplayName("a 48³ structure is drawn at scale 3 under the default cell budget")
    void aFortyEightCubeIsCoarsenedToTheCellBudget() {
        Vec3i size = new Vec3i(48, 48, 48);
        StructurePreview preview = StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536, 4096);

        System.out.println("[structure preview] 48³ under the default budgets: " + preview.describe());
        assertEquals(3, preview.scale(), "110,592 cells have to become 4,096: 48/3 is 16 and 16³ is 4,096");
        assertEquals(new Vec3i(16, 16, 16), preview.size());
        assertEquals(4096, preview.cellCount());
        assertTrue(preview.cellCount() <= 4096, "and the cell budget really is met, got " + preview.describe());
        assertTrue(preview.wireBytes() <= 65536, "nor may it break the byte budget, got " + preview.describe());
    }

    @Test
    @DisplayName("the byte budget alone would have kept the same structure at full detail")
    void theCellBudgetIsWhatMovedTheScale() {
        Vec3i size = new Vec3i(48, 48, 48);
        StructurePreview unchanged = StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536);

        assertEquals(1, unchanged.scale(),
                "a 48³ solid cube is a bit more than 13 KB on the wire, so the byte budget alone never asked for "
                        + "coarsening: " + unchanged.describe());
    }

    @Test
    @DisplayName("the stricter of the two budgets is the one that decides")
    void theByteBudgetCanStillWin() {
        Vec3i size = new Vec3i(48, 48, 48);

        StructurePreview bytes = StructurePreview.of(size, List.of(STONE, AIR), solid(size), 512, 4096);
        assertTrue(bytes.scale() > 3, "512 bytes is stricter than 4,096 cells here, got " + bytes.describe());
        assertTrue(bytes.wireBytes() <= 512, "and the byte budget is met, got " + bytes.describe());
        assertTrue(bytes.cellCount() <= 4096, "without breaking the cell budget, got " + bytes.describe());

        StructurePreview cells = StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536, 1000);
        assertTrue(cells.scale() > 3, "and the other way round: 1,000 cells is stricter than 64 KB, got "
                + cells.describe());
        assertTrue(cells.cellCount() <= 1000, "got " + cells.describe());
    }

    @Test
    @DisplayName("a preview still stops at the coarsest scale rather than growing without end")
    void theCoarsestScaleIsStillTheLimit() {
        Vec3i size = new Vec3i(48, 48, 48);
        StructurePreview impossible = StructurePreview.of(size, List.of(STONE, AIR), solid(size), 1, 1);

        assertEquals(StructurePreview.MAX_SCALE, impossible.scale(), "it stops at the coarsest scale");
        assertTrue(impossible.cellCount() >= 1, "and it is still a preview, not an empty one");
    }

    @Test
    @DisplayName("a budget that is exactly met is met, and one cell less is not")
    void theBoundaryIsExact() {
        Vec3i size = new Vec3i(4, 4, 4);

        assertEquals(1, StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536, 64).scale(),
                "64 cells is exactly the 64 a scale-one preview of a 4³ structure has");
        assertEquals(2, StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536, 63).scale(),
                "one cell less and the same structure has to coarsen: 63 is not 64");
        assertTrue(StructurePreview.of(size, List.of(STONE, AIR), solid(size), 65536, 63).cellCount() <= 63);
    }

    @Test
    @DisplayName("how many cells a preview will have is answered before it is built")
    void theCellCountOfAScaleIsArithmetic() {
        assertEquals(4096, StructurePreview.cellCountAt(new Vec3i(48, 48, 48), 3));
        assertEquals(110592, StructurePreview.cellCountAt(new Vec3i(48, 48, 48), 1));
        assertEquals(7, StructurePreview.cellCountAt(new Vec3i(20, 1, 1), 3),
                "a size the scale does not divide rounds up: the last cell is partial, not absent");
        assertEquals(216, StructurePreview.cellCountAt(new Vec3i(48, 48, 48), 8));
    }

    @Test
    @DisplayName("the new budget is a config option with a default and an accessor")
    void theBudgetIsConfigured() throws IOException {
        Path source = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCConfig.java");
        assertTrue(Files.exists(source), "RCConfig.java has to be readable from " + Path.of("").toAbsolutePath());
        String text = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertTrue(text.contains("DEFAULTS.put(\"structurePreviewCells\", 4096)"),
                "4,096 cells by default, which is what an unloaded config answers with");
        assertTrue(text.contains("public static int structurePreviewCells()"),
                "and the two budgets are read through one accessor each");
        assertTrue(text.contains("intValue(\"structurePreviewCells\", 4096)"),
                "the accessor reads that key, and falls back to that default");
        // This side clamps no range at all - neo does, through defineInRange 1024..110592 - which is a difference
        // this repository already has: the range is stated in the key's own javadoc and enforced nowhere.

        assertEquals(4096, RCConfig.structurePreviewCells(),
                "an unloaded config answers with the default the file ships");
    }
}
