package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The text {@code /rc structure dump} writes, which is the whole of what a bug report gets to see.
 *
 * <p>Three things are worth pinning. That the six faces are the outermost plane and print every cell of it.
 * That the raw section and the preview section are both there, because the difference between them is the
 * only thing that separates "the capture lost the surface" from "the coarsening did". And that no map line
 * runs past what a log reader can show in one line, which is the only reason a picture is strided at all.
 */
class StructureDumpTest {

    private static CompoundTag state(String name) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", name);
        return tag;
    }

    /** The cells of a shell, which is what a captured structure of any size is. */
    private static Predicate<BlockPos> shell(Vec3i size) {
        return pos -> pos.getX() == 0 || pos.getX() == size.getX() - 1
                || pos.getY() == 0 || pos.getY() == size.getY() - 1
                || pos.getZ() == 0 || pos.getZ() == size.getZ() - 1;
    }

    /** A payload of {@code size} cells whose palette is air and planks. */
    private static StructurePayload payload(Vec3i size, Predicate<BlockPos> filled) {
        List<CompoundTag> palette = List.of(state("minecraft:air"), state("minecraft:oak_planks"));
        int[] indices = new int[size.getX() * size.getY() * size.getZ()];
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    indices[OuterLayers.index(size.getX(), size.getZ(), x, y, z)] =
                            filled.test(new BlockPos(x, y, z)) ? 1 : 0;
                }
            }
        }
        PayloadHeader header = new PayloadHeader(PayloadHeader.CURRENT_FORMAT, UUID.randomUUID(), "dump sample",
                size, BlockPos.ZERO, 1, IoMode.ACTIVATION_FACES, false);
        return StructurePayload.of(header, Map.of(), List.of(), palette, IndexArray.of(indices, palette.size()),
                List.of(), List.of(), List.of());
    }

    /** A line's worth of the block that starts at the line beginning with {@code head}, wraps included. */
    private static String block(List<String> lines, String head) {
        StringBuilder text = new StringBuilder();
        boolean inside = false;
        for (String line : lines) {
            if (inside && !line.startsWith(StructureDump.PREFIX + "  ")) {
                break;
            }
            if (!inside) {
                if (!line.startsWith(head)) {
                    continue;
                }
                inside = true;
                text.append(line);
                continue;
            }
            text.append(' ').append(line.substring(StructureDump.PREFIX.length()).trim());
        }
        return text.toString();
    }

    /** The rows printed under the map header beginning with {@code head}. */
    private static List<String> mapLines(List<String> lines, String head) {
        List<String> found = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (!inside) {
                inside = line.startsWith(head);
                continue;
            }
            if (!line.startsWith(StructureDump.PREFIX + "  ")) {
                inside = false;
                continue;
            }
            found.add(line);
        }
        return found;
    }

    /** Every row of every map, whichever face and whichever section it belongs to. */
    private static List<String> everyMapLine(List<String> lines) {
        List<String> found = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (line.contains(" face map ")) {
                inside = true;
                continue;
            }
            if (inside) {
                if (!line.startsWith(StructureDump.PREFIX + "  ")) {
                    inside = false;
                    continue;
                }
                found.add(line);
            }
        }
        return found;
    }

    @Test
    @DisplayName("a hollow cube prints a full face for all six of its outermost layers")
    void sixFullFacesForAShell() {
        Vec3i size = new Vec3i(3, 3, 3);
        List<String> lines = StructureDump.lines(payload(size, shell(size)), 65_536, 4_096);

        for (Direction face : OuterLayers.FACES) {
            String label = OuterLayers.label(face);
            List<String> heads = new ArrayList<>();
            for (String line : lines) {
                if (line.startsWith(StructureDump.PREFIX + "raw " + label + " face (")) {
                    heads.add(line);
                }
            }
            assertEquals(1, heads.size(), "one line per face, never a loop of them: " + label);
            assertTrue(heads.get(0).endsWith(": non-air 9/9, air 0/9"), "the whole face is there: " + heads.get(0));
            assertEquals(3, mapLines(lines, StructureDump.PREFIX + "raw " + label + " face map").size(),
                    label + " prints one line per row, no more and no fewer");
        }

        String summary = block(lines, StructureDump.PREFIX + "raw outer layers");
        for (Direction face : OuterLayers.FACES) {
            assertTrue(summary.contains(OuterLayers.label(face) + " 9/9 (100.0%)"),
                    "the summary names every face and its share: " + summary);
        }
    }

    @Test
    @DisplayName("the legend says which palette entry is air, and a map cell reads back as its index")
    void theLegendTiesACharacterToABlock() {
        List<String> lines = StructureDump.lines(payload(new Vec3i(2, 2, 2), pos -> true), 65_536, 4_096);

        String legend = block(lines, StructureDump.PREFIX + "raw palette: ");
        assertTrue(legend.contains("0=minecraft:air(air)"), "the air entry is marked: " + legend);
        assertTrue(legend.contains("1=minecraft:oak_planks"), "and so is every other: " + legend);
        assertTrue(legend.contains("'" + StructureDump.AIR + "'=air"), "a dot is air: " + legend);

        boolean[] air = {true, false};
        assertEquals(StructureDump.AIR, StructureDump.symbol(0, air));
        assertEquals('1', StructureDump.symbol(1, air));
        assertEquals(StructureDump.UNKNOWN, StructureDump.symbol(2, air), "an index outside the palette is unknown");
        assertEquals(StructureDump.UNKNOWN, StructureDump.symbol(-1, air));
    }

    @Test
    @DisplayName("a surface the capture lost is visible in the raw section, with the other five faces full")
    void aMissingSurfaceShowsInTheRawSection() {
        Vec3i size = new Vec3i(3, 3, 3);
        // A shell with its whole -X face taken away: what a capture that lost a layer leaves behind.
        List<String> lines = StructureDump.lines(payload(size, shell(size).and(pos -> pos.getX() != 0)),
                65_536, 4_096);

        assertTrue(block(lines, StructureDump.PREFIX + "raw -X face (").endsWith("non-air 0/9, air 9/9"),
                "the -X face is empty: " + lines);
        assertTrue(block(lines, StructureDump.PREFIX + "raw +X face (").endsWith("non-air 9/9, air 0/9"),
                "while the far face is whole, so the payload really lost one layer: " + lines);
    }

    @Test
    @DisplayName("a side the scale does not divide keeps a full preview face, so the coarsening ate no layer")
    void coarseningKeepsTheOutermostLayer() {
        Vec3i size = new Vec3i(5, 5, 5);
        // 125 cells at scale 1 and 27 at scale 2, so these budgets force scale 2 - and 5/2 is not whole.
        List<String> lines = StructureDump.lines(payload(size, shell(size)), 65_536, 27);

        String preview = block(lines, StructureDump.PREFIX + "preview(scale=2) 3x3x3");
        assertTrue(preview.contains("at scale 2"), "the preview is the coarsened one: " + preview);
        for (Direction face : OuterLayers.FACES) {
            String label = OuterLayers.label(face);
            assertTrue(block(lines, StructureDump.PREFIX + "raw " + label + " face (")
                            .endsWith("non-air 25/25, air 0/25"),
                    "the raw face is the whole 5x5: " + label);
            assertTrue(block(lines, StructureDump.PREFIX + "preview(scale=2) " + label + " face (")
                            .endsWith("non-air 9/9, air 0/9"),
                    "and so is the preview's outermost layer, remainder included: " + label);
        }
        assertEquals(3, mapLines(lines, StructureDump.PREFIX + "preview(scale=2) +Y face map").size(),
                "three rows for three preview cells, the last of which stands for one raw cell");
    }

    @Test
    @DisplayName("no map line runs past what a log reader can show, however large the structure is")
    void noMapLineRunsPastTheWidth() {
        Vec3i size = new Vec3i(48, 48, 48);
        List<String> lines = StructureDump.lines(payload(size, pos -> pos.getX() < 2), 65_536, 4_096);

        List<String> maps = everyMapLine(lines);
        assertTrue(maps.size() > 0, "the sample really printed maps: " + lines.size() + " line(s)");
        for (String line : maps) {
            assertTrue(line.length() <= StructureDump.MAX_LINE,
                    "a map line has to fit a log: " + line.length() + " characters: " + line);
        }
        assertTrue(block(lines, StructureDump.PREFIX + "raw +X face map (").contains("every "),
                "a layer too wide to print whole says how it was sampled");
        assertTrue(block(lines, StructureDump.PREFIX + "raw +X face (").endsWith("non-air 0/2304, air 2304/2304"),
                "while the count beside it still covers every cell of the layer");
    }
}
