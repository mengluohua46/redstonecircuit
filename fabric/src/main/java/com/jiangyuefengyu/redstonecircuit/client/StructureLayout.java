package com.jiangyuefengyu.redstonecircuit.client;

import java.util.LinkedHashMap;
import java.util.Map;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * Every position on the controller's screen, as data.
 *
 * <p>The numbers live here, one named rectangle per element, so that whoever can see the screen is also the one
 * holding the numbers; the layout tool that edits them is a standalone page outside the mod. Every caption sits 12
 * pixels above its own field and shares that field's x, and the two table pages carry one caption per box rather
 * than one per row.
 */
@Environment(EnvType.CLIENT)
final class StructureLayout {

    /** What a slot is drawn as in the tool, and what it means on the screen. */
    enum Kind {
        /** A caption: one line of text above its field. */
        CAPTION,
        /** A text field. */
        FIELD,
        /** A button. */
        BUTTON,
        /** A line of text that is not a widget (a hint, a state, a path). */
        LINE,
        /** A whole block of rows: {@code y} is the first row's top, {@code h} is the pitch between rows. */
        ROWS
    }

    /** A named rectangle, in the screen's own coordinates (the panel's top-left is 0,0). */
    record Slot(String id, Kind kind, int x, int y, int w, int h) {
    }

    private static final Map<String, Slot> SLOTS = new LinkedHashMap<>();

    /**
     * The numbers, and they are the whole layout.
     *
     * <p>The order is the order the tool lists in, and the ids are what its export names - so an id is a small piece of
     * API: {@code region.name.field} is one rectangle, and nothing else may claim that name.
     */
    private static void defaults() {
        // The row the page tabs and the three switches share, on every page.
        rows("header.row", 22, 0);

        // ------------------------------------------------- region page --
        caption("region.name.caption", 8, 44);
        field("region.name.field", 8, 54, 220, 16);
        button("region.rename.button", 232, 54, 100, 16);
        line("region.slot.line", 8, 70);
        button("region.reading.button", 8, 92, 324, 18);
        // One X/Y/Z caption per numeric box, 12 pixels above it and sharing its x, the way vanilla's own
        // structure block does it; a row of numbers has no group caption of its own.
        caption("region.offsetX.caption", 8, 116);
        caption("region.offsetY.caption", 112, 116);
        caption("region.offsetZ.caption", 216, 116);
        // The note at a row's right end belongs to that row, placed at x=292 so both the Chinese (two
        // characters) and the English fit.
        caption("region.offset.note", 292, 116);
        field("region.offsetX.field", 8, 128, 100, 16);
        field("region.offsetY.field", 112, 128, 100, 16);
        field("region.offsetZ.field", 216, 128, 100, 16);
        caption("region.sizeX.caption", 8, 152);
        caption("region.sizeY.caption", 112, 152);
        caption("region.sizeZ.caption", 216, 152);
        caption("region.size.note", 292, 152);
        field("region.sizeX.field", 8, 164, 100, 16);
        field("region.sizeY.field", 112, 164, 100, 16);
        field("region.sizeZ.field", 216, 164, 100, 16);
        button("region.apply.button", 8, 180, 140, 18);
        button("region.here.button", 152, 180, 90, 18);
        // The take-out button lives in the apply row's third slot: always visible, never on top of the
        // rename button above it, and not on the slot's own row.
        button("region.takeout.button", 246, 180, 86, 18);
        line("region.state.line", 8, 206);
        button("region.operate.button", 8, 222, 120, 18);
        button("region.cancel.button", 132, 222, 100, 18);
        button("region.close.button", 236, 222, 96, 18);
        button("region.locked.button", 8, 64, 324, 18);

        // -------------------------------------------------- panel page --
        line("panel.header.line", 8, 44);
        rows("panel.rows", 62, 20);
        button("panel.press.button", 236, 62, 40, 18);
        button("panel.remove.button", 280, 62, 52, 18);
        caption("panel.key.caption", 8, 148);
        caption("panel.cellX.caption", 126, 148);
        caption("panel.cellY.caption", 160, 148);
        caption("panel.cellZ.caption", 194, 148);
        caption("panel.strength.caption", 228, 148);
        caption("panel.label.caption", 258, 148);
        field("panel.key.field", 8, 160, 66, 16);
        button("panel.bind.button", 78, 160, 44, 18);
        field("panel.cellX.field", 126, 160, 30, 16);
        field("panel.cellY.field", 160, 160, 30, 16);
        field("panel.cellZ.field", 194, 160, 30, 16);
        field("panel.strength.field", 228, 160, 26, 16);
        field("panel.label.field", 258, 160, 74, 16);
        button("panel.add.button", 8, 182, 66, 18);
        line("panel.hint.line", 80, 182);
        button("panel.scroll.back", 292, 44, 18, 16);
        button("panel.scroll.forward", 314, 44, 18, 16);

        // -------------------------------------------------- faces page --
        line("faces.header.line", 8, 44);
        rows("faces.rows", 62, 20);
        button("faces.face.button", 78, 160, 44, 18);
        field("faces.cellX.field", 126, 160, 48, 16);
        field("faces.cellY.field", 178, 160, 48, 16);
        field("faces.cellZ.field", 230, 160, 48, 16);
        field("faces.strength.field", 282, 160, 22, 16);
        caption("faces.cellX.caption", 126, 148);
        caption("faces.cellY.caption", 178, 148);
        caption("faces.cellZ.caption", 230, 148);
        caption("faces.strength.caption", 282, 148);
        button("faces.add.button", 8, 160, 66, 18);
        button("faces.remove.button", 296, 62, 36, 18);
        line("faces.hint.line", 8, 182);
        button("faces.scroll.back", 292, 44, 18, 16);
        button("faces.scroll.forward", 314, 44, 18, 16);

        // ------------------------------------------------- output page --
        // The same rectangles as the faces page: the two tables are the same three numbers in the other
        // order, so a player who has filled one in knows where everything is on the other.
        line("output.header.line", 8, 44);
        rows("output.rows", 62, 20);
        button("output.face.button", 78, 160, 44, 18);
        field("output.cellX.field", 126, 160, 48, 16);
        field("output.cellY.field", 178, 160, 48, 16);
        field("output.cellZ.field", 230, 160, 48, 16);
        field("output.strength.field", 282, 160, 22, 16);
        caption("output.cellX.caption", 126, 148);
        caption("output.cellY.caption", 178, 148);
        caption("output.cellZ.caption", 230, 148);
        caption("output.strength.caption", 282, 148);
        button("output.add.button", 8, 160, 66, 18);
        button("output.remove.button", 296, 62, 36, 18);
        line("output.hint.line", 8, 182);
        button("output.scroll.back", 292, 44, 18, 16);
        button("output.scroll.forward", 314, 44, 18, 16);

        // -------------------------------------------------- files page --
        line("files.structures.line", 8, 52);
        line("files.structures.path", 8, 64);
        line("files.export.line", 8, 84);
        line("files.export.path", 8, 96);
        line("files.commands.line", 8, 124);
        line("files.export.cmd.line", 8, 136);
        line("files.import.cmd.line", 8, 148);
        line("files.note.line", 8, 176);
    }

    private static void caption(String id, int x, int y) {
        put(new Slot(id, Kind.CAPTION, x, y, 0, 8));
    }

    private static void line(String id, int x, int y) {
        put(new Slot(id, Kind.LINE, x, y, 0, 8));
    }

    private static void field(String id, int x, int y, int w, int h) {
        put(new Slot(id, Kind.FIELD, x, y, w, h));
    }

    private static void button(String id, int x, int y, int w, int h) {
        put(new Slot(id, Kind.BUTTON, x, y, w, h));
    }

    private static void rows(String id, int top, int pitch) {
        put(new Slot(id, Kind.ROWS, 0, top, 0, pitch));
    }

    private static void put(Slot slot) {
        SLOTS.put(slot.id(), slot);
    }

    /**
     * The rectangle with this name.
     *
     * <p>A typo must not take a screen down: an empty slot at the origin is visible immediately and harmless, and the
     * log line names the id that was wrong.
     */
    static Slot of(String id) {
        Slot slot = SLOTS.get(id);
        if (slot == null) {
            com.jiangyuefengyu.redstonecircuit.RCConfig.LOGGER.warn("[redstonecircuit] no such UI slot '{}'", id);
            return new Slot(id, Kind.LINE, 0, 0, 0, 8);
        }
        return slot;
    }

    static int x(String id) {
        return of(id).x();
    }

    static int y(String id) {
        return of(id).y();
    }

    static int w(String id) {
        return of(id).w();
    }

    static int h(String id) {
        return of(id).h();
    }

    private StructureLayout() {
    }

    static {
        defaults();
    }
}
