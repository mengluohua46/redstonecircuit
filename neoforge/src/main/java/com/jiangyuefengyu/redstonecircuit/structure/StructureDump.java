package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;

/**
 * The lines {@code /rc structure dump} writes: one ASCII picture of each of a structure's six outermost layers,
 * before and after the preview budgets.
 *
 * <p>The question behind it is which half of the picture is wrong. A cell taken out of the world and a cell a
 * renderer declines to draw end up looking the same on screen, and they have nothing else in common, so the
 * answer has to come from the payload: the raw section is the file's own outermost plane, the preview section
 * is the same plane after the two budgets have coarsened it. Raw face empty where the layer behind it is full
 * means the capture lost the surface; raw face full and preview face empty means the coarsening did; both full
 * means neither, and the loss is downstream of the data.
 *
 * <p>Lines are built here rather than logged here, so the wording and the line lengths are decidable without a
 * running game. Nothing in this class reads or writes the world: a payload in, text out.
 *
 * <p>One map line stays inside {@link #MAX_LINE} by striding a layer that is too wide to print whole, and the
 * counts printed beside it always cover <b>every</b> cell of the layer, strided or not - the picture may be
 * sampled, the arithmetic may not.
 */
public final class StructureDump {

    /** What every line of this mod's log starts with. */
    public static final String PREFIX = "[redstonecircuit] ";

    /** Widest a map line gets; longer than this and a log reader's own wrapping breaks the picture up. */
    public static final int MAX_LINE = 60;

    /** Widest a legend or summary line gets - those are prose, and are wrapped by token rather than cut. */
    public static final int MAX_PROSE_LINE = 110;

    /** Most rows and columns a map prints; above this the picture is strided rather than endless. */
    public static final int MAX_MAP_SIDE = 32;

    /** How an empty cell is drawn: air is what a missing surface is made of, so it gets its own character. */
    public static final char AIR = '.';

    /** A cell the palette does not have, or none at all. Written as this rather than thrown on. */
    public static final char UNKNOWN = '?';

    /**
     * Every line of a dump of {@code payload}: the raw outermost layers first, then the preview's.
     *
     * <p>Both budgets are passed in rather than read from the config, so the preview here is the one a client
     * would be sent, not an approximation of it.
     */
    public static List<String> lines(StructurePayload payload, int maxBytes, int maxCells) {
        List<String> out = new ArrayList<>();
        PayloadHeader header = payload.header();
        out.add(PREFIX + "structure dump: \"" + header.name() + "\" " + header.uuid());
        add(out, "raw", header.size(), payload.indices(), payload.palette());
        StructurePreview preview = StructurePreview.ofPayload(payload, maxBytes, maxCells);
        if (preview == null) {
            out.add(PREFIX + "preview: none, there is no payload");
            return out;
        }
        String what = "preview(scale=" + preview.scale() + ")";
        out.add(PREFIX + what + " " + preview.describe() + " (budgets " + maxBytes + " byte(s), "
                + maxCells + " cell(s))");
        add(out, what, preview.size(), preview.cells(), preview.palette());
        return out;
    }

    private static void add(List<String> out, String what, Vec3i size, IndexArray cells,
                            List<CompoundTag> palette) {
        boolean[] air = airFlags(palette);
        out.add(PREFIX + what + " " + size.getX() + "x" + size.getY() + "x" + size.getZ()
                + " palette=" + palette.size() + " cells=" + cells.count()
                + " non-air=" + nonAir(cells, air) + " (the outermost plane of each face follows)");
        legend(out, what, palette, air);
        summary(out, what, size, cells, air);
        for (Direction face : OuterLayers.FACES) {
            picture(out, what, face, OuterLayers.of(size, cells, face), air);
        }
    }

    /** Index to block id, the air entries marked, so a map character can be read back as a block. */
    private static void legend(List<String> out, String what, List<CompoundTag> palette, boolean[] air) {
        List<String> entries = new ArrayList<>(palette.size());
        for (int index = 0; index < palette.size(); index++) {
            entries.add(index + "=" + palette.get(index).getString("Name")
                    + (air[index] ? "(air)" : ""));
        }
        entries.add("'" + AIR + "'=air, '" + UNKNOWN + "'=no cell");
        wrap(out, PREFIX + what + " palette: ", PREFIX + "  ", entries, ", ");
    }

    /** One line per face with its outermost layer's own numbers, then the same for all six together. */
    private static void summary(List<String> out, String what, Vec3i size, IndexArray cells, boolean[] air) {
        List<String> perFace = new ArrayList<>(6);
        for (Direction face : OuterLayers.FACES) {
            OuterLayers.Layer layer = OuterLayers.of(size, cells, face);
            int total = layer.rows() * layer.columns();
            int filled = layer.count(index -> !isEmpty(air, index));
            perFace.add(OuterLayers.label(face) + " " + filled + "/" + total + " (" + percent(filled, total) + ")");
            out.add(PREFIX + what + " " + OuterLayers.label(face) + " face (" + OuterLayers.axisName(face) + "="
                    + layer.layer() + "): non-air " + filled + "/" + total + ", air " + (total - filled) + "/"
                    + total);
        }
        wrap(out, PREFIX + what + " outer layers (non-air / cells): ", PREFIX + "  ", perFace, " | ");
    }

    /** One face's outermost layer as a map, headed by the numbers it is a picture of. */
    private static void picture(List<String> out, String what, Direction face, OuterLayers.Layer layer,
                                boolean[] air) {
        int rowStep = Math.max(1, (layer.rows() + MAX_MAP_SIDE - 1) / MAX_MAP_SIDE);
        int columnStep = Math.max(1, (layer.columns() + MAX_MAP_SIDE - 1) / MAX_MAP_SIDE);
        // Said out loud, because a sampled picture that did not say so would read as a face with rows missing.
        String strided = rowStep > 1 || columnStep > 1
                ? ", sampled every " + rowStep + " of " + layer.rows() + " rows and every " + columnStep + " of "
                        + layer.columns() + " columns"
                : "";
        out.add(PREFIX + what + " " + OuterLayers.label(face) + " face map (" + OuterLayers.rowAxisName(face)
                + " down the rows, " + OuterLayers.axisName(face) + "=" + layer.layer() + strided + "):");
        StringBuilder line = new StringBuilder(MAX_LINE);
        for (int row = 0; row < layer.rows(); row += rowStep) {
            line.setLength(0);
            line.append(PREFIX).append("  ");
            line.append(pad(OuterLayers.rowAxisName(face) + layer.rowCoordinate(row), 4));
            for (int column = 0; column < layer.columns(); column += columnStep) {
                line.append(symbol(layer.at(row, column), air));
            }
            out.add(line.toString());
        }
    }

    /** The character a cell is drawn as: air has its own, everything else its palette index in base 36. */
    public static char symbol(int paletteIndex, boolean[] air) {
        if (paletteIndex < 0 || paletteIndex >= air.length) {
            return UNKNOWN;
        }
        return air[paletteIndex] ? AIR : Character.forDigit(paletteIndex, 36);
    }

    /** True when the palette says this entry is air. An index the palette does not have is not air, it is
     * unknown, and a count that quietly treated it as empty would hide a broken payload as a hole. */
    public static boolean isEmpty(boolean[] air, int paletteIndex) {
        return paletteIndex >= 0 && paletteIndex < air.length && air[paletteIndex];
    }

    private static int nonAir(IndexArray cells, boolean[] air) {
        int filled = 0;
        for (int index = 0; index < cells.count(); index++) {
            if (!isEmpty(air, cells.get(index))) {
                filled++;
            }
        }
        return filled;
    }

    private static boolean[] airFlags(List<CompoundTag> palette) {
        boolean[] air = new boolean[palette.size()];
        for (int index = 0; index < palette.size(); index++) {
            air[index] = StructurePreview.isAir(palette.get(index));
        }
        return air;
    }

    private static String percent(int part, int total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * part / total);
    }

    private static String pad(String text, int width) {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }

    private static void wrap(List<String> out, String first, String continuation, List<String> tokens,
                             String separator) {
        StringBuilder line = new StringBuilder(first);
        boolean started = false;
        for (String token : tokens) {
            if (started && line.length() + separator.length() + token.length() > MAX_PROSE_LINE) {
                out.add(line.toString());
                line = new StringBuilder(continuation);
                started = false;
            }
            if (started) {
                line.append(separator);
            }
            line.append(token);
            started = true;
        }
        out.add(line.toString());
    }

    private StructureDump() {
    }
}
