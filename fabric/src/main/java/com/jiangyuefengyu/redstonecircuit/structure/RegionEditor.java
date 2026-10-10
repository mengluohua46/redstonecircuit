package com.jiangyuefengyu.redstonecircuit.structure;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

/**
 * 相对 / 绝对 - the two ways a player says where a region is, and the one conversion between them.
 *
 * <p>The screen needs this pair of fields: an offset, a size, and which of the two readings the offset has. What a player
 * is looking at, and what the controller stores, is a <em>rectangle</em> (see {@link PreviewRegion}) - relative and
 * absolute are two ways to <em>type</em> the same rectangle, so they are a property of the editor and not of the world,
 * and storing them on the controller as well would be a second description of one box.
 *
 * <p>Loader-free and game-free, because the conversion is arithmetic: the whole of "what did the player mean by (0,1,0)
 * size 3x1x1" is unit-tested without a running game. The screen only reads text boxes into these fields and calls
 * {@link #resolve}.
 */
public record RegionEditor(boolean relative, Vec3i offset, Vec3i size) {

    /** A region needs at least one cell on every axis; the same floor {@link PreviewRegion} enforces. */
    public static final int MIN_SIZE = 1;

    /** The ceiling a text field may claim, matching {@link PreviewRegion#MAX_AXIS}. */
    public static final int MAX_SIZE = PreviewRegion.MAX_AXIS;

    /**
     * The furthest a region may be placed, on any axis: the world border's own limit.
     *
     * <p>Clamped rather than refused, because the number arrives from a text field and from a client: two billion would
     * overflow {@code BlockPos.offset} into a rectangle somewhere else entirely, and a region that silently lands
     * elsewhere is worse than one that is out of reach - out of reach is refused later, by the build-height and budget
     * checks, with a sentence.
     */
    public static final int MAX_OFFSET = 30000000;

    public RegionEditor {
        offset = new Vec3i(clampOffset(offset.getX()), clampOffset(offset.getY()), clampOffset(offset.getZ()));
        size = new Vec3i(clampSize(size.getX()), clampSize(size.getY()), clampSize(size.getZ()));
    }

    /** The reading vanilla's own structure block starts in: an offset from the controller. */
    public static RegionEditor relative(Vec3i offset, Vec3i size) {
        return new RegionEditor(true, offset, size);
    }

    /** The same fields read as world coordinates. */
    public static RegionEditor absolute(Vec3i offset, Vec3i size) {
        return new RegionEditor(false, offset, size);
    }

    /**
     * The fields that describe {@code region} <b>relative to the controller</b> - what a screen shows first.
     *
     * <p>Lossless: the region is <em>exactly</em> the cells this says, because relative mode adds the controller
     * back. A reading that could lose a cell would be a reading that lies about the selection.
     */
    public static RegionEditor of(BlockPos controller, PreviewRegion region) {
        return relative(region.min().subtract(controller), region.size());
    }

    /** The same, read as world coordinates. */
    public static RegionEditor absoluteOf(PreviewRegion region) {
        return absolute(new Vec3i(region.min().getX(), region.min().getY(), region.min().getZ()), region.size());
    }

    /**
     * The rectangle these fields describe.
     *
     * <p>In relative mode the offset is added to the controller's own position - the controller's cell is
     * {@code (0,0,0)} - in absolute mode it is the region's first cell.
     */
    public PreviewRegion resolve(BlockPos controller) {
        return new PreviewRegion(this.relative ? controller.offset(this.offset) : new BlockPos(this.offset),
                this.size);
    }

    public RegionEditor withRelative(boolean nowRelative) {
        return new RegionEditor(nowRelative, this.offset, this.size);
    }

    /**
     * <b>One cell, typed the way this screen types cells: as an offset from the controller.</b>
     *
     * <p>The region page's offset reads the same way - the controller's own cell is {@code (0,0,0)} - which is why a
     * table's input row can be filled with {@code 0 0 0} and mean "this block". What the <em>server</em> checks a cell
     * against is the region in world coordinates ({@code StructureEdits#requireCellInside}), so somebody has to add the
     * two together, and that has to be a function with a test rather than an expression inside a button handler: a row
     * sent as {@code 0, 0, 0} <em>world</em> coordinates answers every add with "0, 0, 0 is outside the selection",
     * which reads exactly like a button that does nothing.
     */
    public static BlockPos cell(BlockPos controller, Vec3i local) {
        return controller.offset(local);
    }

    public RegionEditor withOffset(Vec3i newOffset) {
        return new RegionEditor(this.relative, newOffset, this.size);
    }

    public RegionEditor withSize(Vec3i newSize) {
        return new RegionEditor(this.relative, this.offset, newSize);
    }

    private static int clampSize(int value) {
        return Math.max(MIN_SIZE, Math.min(MAX_SIZE, value));
    }

    private static int clampOffset(int value) {
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, value));
    }
}
