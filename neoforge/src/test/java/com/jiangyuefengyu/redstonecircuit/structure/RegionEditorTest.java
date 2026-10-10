package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 相对/绝对: the two ways a screen lets a player place a region, and the proof that neither can lose a cell.
 *
 * <p>The reading a player types is the rectangle that is shown and then taken: relative and absolute are two
 * spellings of one box, and a conversion that is off by a cell would be a preview that lies about the capture.
 */
class RegionEditorTest {

    private static final BlockPos CONTROLLER = new BlockPos(100, 64, -200);

    @Test
    @DisplayName("relative offsets count from the controller's own cell, which is 0,0,0")
    void relativeCountsFromTheController() {
        PreviewRegion region = RegionEditor.relative(new Vec3i(0, 0, 0), new Vec3i(1, 1, 1))
                .resolve(CONTROLLER);
        assertEquals(CONTROLLER, region.min(), "the controller's own cell is the origin of relative mode");
        assertEquals(new Vec3i(1, 1, 1), region.size());

        PreviewRegion ahead = RegionEditor.relative(new Vec3i(2, -1, 3), new Vec3i(2, 1, 4))
                .resolve(CONTROLLER);
        assertEquals(new BlockPos(102, 63, -197), ahead.min());
        assertEquals(new Vec3i(2, 1, 4), ahead.size());
    }

    @Test
    @DisplayName("absolute offsets are world coordinates, controller or not")
    void absoluteIsWorldCoordinates() {
        PreviewRegion region = RegionEditor.absolute(new Vec3i(-40, 70, 12), new Vec3i(3, 2, 1))
                .resolve(CONTROLLER);
        assertEquals(new BlockPos(-40, 70, 12), region.min());
        assertEquals(new Vec3i(3, 2, 1), region.size());
    }

    @Test
    @DisplayName("the same rectangle round-trips through both readings")
    void bothReadingsDescribeTheSameRegion() {
        PreviewRegion region = PreviewRegion.of(new BlockPos(98, 65, -198), new BlockPos(101, 66, -196));

        RegionEditor fromController = RegionEditor.of(CONTROLLER, region);
        assertEquals(region, fromController.resolve(CONTROLLER),
                "what a screen shows relative to the controller must be the region itself");

        RegionEditor absolute = RegionEditor.absoluteOf(region);
        assertEquals(region, absolute.resolve(CONTROLLER),
                "and so must the absolute reading, which does not need the controller at all");
        assertEquals(fromController.resolve(CONTROLLER), absolute.resolve(CONTROLLER));

        // And a screen that toggles the switch back and forth is showing one box, not two.
        RegionEditor toggled = fromController.withRelative(false)
                .withOffset(new Vec3i(region.min().getX(), region.min().getY(), region.min().getZ()));
        assertEquals(region, toggled.resolve(CONTROLLER));
        assertEquals(RegionEditor.absoluteOf(region), toggled, "and the toggled fields are the absolute ones");
    }

    @Test
    @DisplayName("a size below 1 is clamped up, not accepted as an empty region")
    void sizesAreClamped() {
        assertEquals(new Vec3i(1, 1, 1), RegionEditor.relative(Vec3i.ZERO, new Vec3i(0, -3, 1)).size());
        assertEquals(new Vec3i(RegionEditor.MAX_SIZE, 1, 1),
                RegionEditor.relative(Vec3i.ZERO, new Vec3i(4096, 1, 1)).size());
    }

    @Test
    @DisplayName("an offset far past the world is clamped: it must not overflow into somewhere else")
    void offsetsAreClamped() {
        RegionEditor wild = RegionEditor.absolute(new Vec3i(Integer.MAX_VALUE, Integer.MIN_VALUE, 12),
                new Vec3i(1, 1, 1));
        assertEquals(RegionEditor.MAX_OFFSET, wild.offset().getX());
        assertEquals(-RegionEditor.MAX_OFFSET, wild.offset().getY());
        assertEquals(12, wild.offset().getZ(), "an ordinary number is left alone");
        // The point of clamping: the resolved first cell is still the number that was asked for, not a wrap.
        assertEquals(RegionEditor.MAX_OFFSET, wild.resolve(CONTROLLER).min().getX());
    }

    @Test
    @DisplayName("editing one field leaves the others where they were")
    void editsAreValues() {
        RegionEditor editor = RegionEditor.relative(new Vec3i(0, 0, 0), new Vec3i(3, 1, 1));
        assertEquals(new Vec3i(5, 0, 0), editor.withOffset(new Vec3i(5, 0, 0)).offset());
        assertEquals(new Vec3i(3, 1, 1), editor.withOffset(new Vec3i(5, 0, 0)).size(), "moving is not resizing");
        assertEquals(new Vec3i(2, 2, 2), editor.withSize(new Vec3i(2, 2, 2)).size());
        assertEquals(new Vec3i(0, 0, 0), editor.withSize(new Vec3i(2, 2, 2)).offset(), "resizing is not moving");
        assertFalse(editor.withRelative(false).relative(), "the toggle is a value too");
        assertEquals(new Vec3i(3, 1, 1), editor.size(), "and none of it touched the original");
    }

    @Test
    @DisplayName("a table's cell is an offset from the controller, and the region it lands in is the same one")
    void cellsAreOffsetsFromTheController() {
        // The panel row's boxes are offsets from the controller, not world coordinates: "0 0 0" is the controller's
        // own cell, and cell(...) is what turns an offset into the world position the server checks against the
        // region.
        BlockPos controller = new BlockPos(100, 64, -200);
        assertEquals(controller, RegionEditor.cell(controller, new Vec3i(0, 0, 0)),
                "0,0,0 is the controller itself");
        assertEquals(new BlockPos(101, 64, -200), RegionEditor.cell(controller, new Vec3i(1, 0, 0)));

        // A region that starts one cell east of the controller: the row's default (the region's minimum, expressed
        // relative to the controller) is inside it, while the controller's own cell is not.
        PreviewRegion region = new PreviewRegion(controller.offset(1, 0, 0), new Vec3i(10, 10, 10));
        Vec3i local = RegionEditor.of(controller, region).offset();
        assertEquals(new Vec3i(1, 0, 0), local, "the default the screen fills in");
        assertTrue(region.box().contains(RegionEditor.cell(controller, local)),
                "and that default is inside the selection, which is what makes the add succeed");
        assertFalse(region.box().contains(RegionEditor.cell(controller, new Vec3i(0, 0, 0))),
                "while the controller's own cell is outside it");
    }
}
