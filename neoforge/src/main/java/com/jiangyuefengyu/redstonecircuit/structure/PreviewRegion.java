package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.Optional;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/**
 * 预览区域: the box the controller is showing, in world coordinates.
 *
 * <p>The preview can be edited several ways (relative or absolute corners, an offset, a size) but those are
 * all descriptions of one box, and a controller storing them separately could be edited into disagreeing
 * with itself. So the controller stores the box, resolved, and nothing else.
 *
 * <p>Same shape as {@link StructureCapture.Box}, down to {@link #box()}, because the region a player is shown
 * and the region a capture takes have to be the same rectangle. Immutable, so it can travel:
 * {@link #STREAM_CODEC} is what the client is told and {@link #save()} is what the block entity keeps.
 */
public record PreviewRegion(BlockPos min, Vec3i size) {

    /** The box a structure block shows when it has no region yet: itself, exactly as vanilla starts out. */
    public static PreviewRegion one(BlockPos at) {
        return new PreviewRegion(at, new Vec3i(1, 1, 1));
    }

    /**
     * A size below 1 is clamped rather than refused: a text field can be typed into with "0", and an empty
     * preview is a border the player cannot see.
     */
    public PreviewRegion {
        size = new Vec3i(Math.max(1, size.getX()), Math.max(1, size.getY()), Math.max(1, size.getZ()));
    }

    /** The box between two corners, in either order - the same rectangle {@code /rc structure select} means. */
    public static PreviewRegion of(BlockPos a, BlockPos b) {
        int minX = Math.min(a.getX(), b.getX());
        int minY = Math.min(a.getY(), b.getY());
        int minZ = Math.min(a.getZ(), b.getZ());
        return new PreviewRegion(new BlockPos(minX, minY, minZ),
                new Vec3i(Math.abs(a.getX() - b.getX()) + 1, Math.abs(a.getY() - b.getY()) + 1,
                        Math.abs(a.getZ() - b.getZ()) + 1));
    }

    /** The last cell, inclusive - the corner the payload and the capture use. */
    public BlockPos max() {
        return this.min.offset(this.size.getX() - 1, this.size.getY() - 1, this.size.getZ() - 1);
    }

    public int volume() {
        return this.size.getX() * this.size.getY() * this.size.getZ();
    }

    /** The same rectangle, with its first cell somewhere else. */
    public PreviewRegion movedTo(BlockPos newMin) {
        return new PreviewRegion(newMin, this.size);
    }

    /** The same first cell, a different size. */
    public PreviewRegion resized(Vec3i newSize) {
        return new PreviewRegion(this.min, newSize);
    }

    /** The rectangle as the layer that builds payloads and releases structures wants it. */
    public StructureCapture.Box box() {
        return new StructureCapture.Box(this.min, max(), this.size);
    }

    /**
     * Whether this region may be captured at all, as the size rules see it - or why not. Asked while previewing,
     * so a player is never shown a border for a region that can never be taken; includes the vertical bounds.
     */
    public Optional<String> refusal(PayloadLimits limits, int minY, int maxY) {
        Optional<String> fromSize = limits.refuseSize(this.size);
        if (fromSize.isPresent()) {
            return fromSize;
        }
        if (this.min.getY() < minY || max().getY() >= maxY) {
            return Optional.of("the selection reaches from y=" + this.min.getY() + " to y=" + max().getY()
                    + ", and this world's buildable area is y=" + minY + " to y=" + (maxY - 1));
        }
        return Optional.empty();
    }

    /** One line for a player, a command or the log: {@code 3x2x1 at 10,64,-3}. */
    public String describe() {
        return this.size.getX() + "x" + this.size.getY() + "x" + this.size.getZ() + " at "
                + this.min.getX() + "," + this.min.getY() + "," + this.min.getZ();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        BlockPos.CODEC.encodeStart(NbtOps.INSTANCE, this.min).result()
                .ifPresent(encoded -> tag.put("min", encoded));
        tag.putInt("size_x", this.size.getX());
        tag.putInt("size_y", this.size.getY());
        tag.putInt("size_z", this.size.getZ());
        return tag;
    }

    /** A tag with no usable minimum is not a region: null rather than a box at the origin. */
    public static PreviewRegion load(CompoundTag tag) {
        if (tag == null || !tag.contains("min")) {
            return null;
        }
        BlockPos min = BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get("min")).result().orElse(null);
        if (min == null) {
            return null;
        }
        return new PreviewRegion(min, new Vec3i(tag.getInt("size_x"), tag.getInt("size_y"),
                tag.getInt("size_z")));
    }

    /** The largest axis a preview may claim, so a malformed packet cannot describe a box of 2 billion. */
    public static final int MAX_AXIS = 512;

    public static final StreamCodec<RegistryFriendlyByteBuf, PreviewRegion> STREAM_CODEC =
            StreamCodec.of(PreviewRegion::encode, PreviewRegion::decode);

    private static void encode(RegistryFriendlyByteBuf buffer, PreviewRegion region) {
        buffer.writeBlockPos(region.min());
        buffer.writeVarInt(region.size().getX());
        buffer.writeVarInt(region.size().getY());
        buffer.writeVarInt(region.size().getZ());
    }

    private static PreviewRegion decode(RegistryFriendlyByteBuf buffer) {
        BlockPos min = buffer.readBlockPos();
        int x = axis(buffer.readVarInt(), "x");
        int y = axis(buffer.readVarInt(), "y");
        int z = axis(buffer.readVarInt(), "z");
        return new PreviewRegion(min, new Vec3i(x, y, z));
    }

    /** A decoder that carries a nonsense axis on is a decoder that trusts the sender. */
    private static int axis(int value, String name) {
        if (value < 1 || value > MAX_AXIS) {
            throw new DecoderException("a preview region cannot be " + value + " blocks on its " + name
                    + " axis");
        }
        return value;
    }
}
