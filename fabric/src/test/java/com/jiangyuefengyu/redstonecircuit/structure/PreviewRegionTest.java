package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The preview region: the rectangle a player is shown, and the rectangle a capture then takes.
 *
 * <p>These are the facts the two-stage operation rests on. The border, the "will this be refused" answer and
 * the region handed to {@code StructureCapture} all come out of this record, and all three have to describe
 * the same cells - a preview one block short of the capture is exactly the bug this class exists to make
 * impossible.
 */
class PreviewRegionTest {

    private static final PayloadLimits LIMITS = new PayloadLimits(48, 110592, 32);

    @Test
    @DisplayName("two corners describe the same rectangle in either order")
    void cornersAreOrderFree() {
        PreviewRegion forwards = PreviewRegion.of(new BlockPos(3, 4, 5), new BlockPos(1, 2, 3));
        PreviewRegion backwards = PreviewRegion.of(new BlockPos(1, 2, 3), new BlockPos(3, 4, 5));
        assertEquals(forwards, backwards, "the same two cells cannot be two different boxes");
        assertEquals(new BlockPos(1, 2, 3), forwards.min());
        assertEquals(new Vec3i(3, 3, 3), forwards.size());
        assertEquals(27, forwards.volume());
    }

    @Test
    @DisplayName("one cell is a 1x1x1 region, and max() is inclusive")
    void aSingleCellIsARegion() {
        PreviewRegion single = PreviewRegion.one(new BlockPos(10, 64, -3));
        assertEquals(new Vec3i(1, 1, 1), single.size());
        assertEquals(single.min(), single.max(), "an inclusive max is the same cell as the min");
        assertEquals(1, single.volume());
    }

    @Test
    @DisplayName("max() is the last cell, not one past it")
    void maxIsTheLastCell() {
        PreviewRegion region = new PreviewRegion(new BlockPos(0, 0, 0), new Vec3i(2, 3, 4));
        assertEquals(new BlockPos(1, 2, 3), region.max());
        assertEquals(24, region.volume());
    }

    @Test
    @DisplayName("a size below 1 is clamped: a preview is never an invisible empty box")
    void sizesBelowOneAreClamped() {
        PreviewRegion region = new PreviewRegion(new BlockPos(0, 0, 0), new Vec3i(0, -5, 2));
        assertEquals(new Vec3i(1, 1, 2), region.size());
        assertEquals(2, region.volume(), "two cells, not zero");
    }

    @Test
    @DisplayName("the region and the capture's box are the same rectangle")
    void theBoxIsTheCapturesBox() {
        PreviewRegion region = PreviewRegion.of(new BlockPos(5, 70, 5), new BlockPos(7, 68, 9));
        StructureCapture.Box box = region.box();
        assertEquals(region.min(), box.min());
        assertEquals(region.max(), box.max());
        assertEquals(region.size(), box.size());
        assertEquals(region.volume(), box.volume());
        assertTrue(box.contains(new BlockPos(6, 69, 7)));
        assertFalse(box.contains(new BlockPos(8, 69, 7)), "the cell past the box is not in it");
    }

    @Test
    @DisplayName("an axis past the limit is refused, and the refusal says which number was hit")
    void oversizedAxisIsRefused() {
        Optional<String> refusal = PreviewRegion.one(BlockPos.ZERO)
                .resized(new Vec3i(49, 1, 1)).refusal(LIMITS, -64, 320);
        assertTrue(refusal.isPresent(), "49 blocks on one axis is past the 48 limit");
        assertTrue(refusal.get().contains("48"), "the player is told the limit: " + refusal.get());
    }

    @Test
    @DisplayName("a legal region inside the world is accepted")
    void aLegalRegionIsAccepted() {
        assertEquals(Optional.empty(), PreviewRegion.one(new BlockPos(0, 64, 0))
                .resized(new Vec3i(48, 48, 48)).refusal(LIMITS, -64, 320));
    }

    @Test
    @DisplayName("a region that leaves the buildable area is refused before anything happens")
    void regionsOutsideTheWorldAreRefused() {
        assertTrue(PreviewRegion.one(new BlockPos(0, -65, 0)).refusal(LIMITS, -64, 320).isPresent(),
                "below the world");
        assertTrue(PreviewRegion.one(new BlockPos(0, 320, 0)).refusal(LIMITS, -64, 320).isPresent(),
                "at the top the world stops: y=319 is the last cell");
        assertTrue(PreviewRegion.one(new BlockPos(0, 319, 0)).refusal(LIMITS, -64, 320).isEmpty());
    }

    @Test
    @DisplayName("moving and resizing give a new region and leave the old one alone")
    void editsAreValues() {
        PreviewRegion original = new PreviewRegion(new BlockPos(0, 0, 0), new Vec3i(2, 2, 2));
        PreviewRegion moved = original.movedTo(new BlockPos(10, 10, 10));
        PreviewRegion resized = moved.resized(new Vec3i(1, 1, 1));
        assertEquals(new BlockPos(0, 0, 0), original.min(), "an edit is not a mutation");
        assertEquals(new Vec3i(2, 2, 2), original.size());
        assertEquals(new BlockPos(10, 10, 10), moved.min());
        assertEquals(new Vec3i(2, 2, 2), moved.size(), "moving does not resize");
        assertEquals(new Vec3i(1, 1, 1), resized.size());
        assertEquals(new BlockPos(10, 10, 10), resized.min(), "resizing does not move");
    }

    @Test
    @DisplayName("a saved region loads back, and a tag that names no position is not a region")
    void saveAndLoad() {
        PreviewRegion region = PreviewRegion.of(new BlockPos(-40, 12, 2000), new BlockPos(-38, 14, 2004));
        CompoundTag tag = region.save();
        assertEquals(region, PreviewRegion.load(tag), "a chunk unload must not move the border");
        assertNull(PreviewRegion.load(new CompoundTag()), "no minimum, no region");
        assertNull(PreviewRegion.load(null), "and no tag at all is not a crash");
        CompoundTag broken = region.save();
        broken.remove("min");
        assertNull(PreviewRegion.load(broken));
    }

    @Test
    @DisplayName("the region survives the wire, and a nonsense axis is refused at the decoder")
    void streamRoundTrip() {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        PreviewRegion region = PreviewRegion.of(new BlockPos(-1000, 70, 1000), new BlockPos(-998, 72, 1003));
        PreviewRegion.STREAM_CODEC.encode(buffer, region);
        PreviewRegion read = PreviewRegion.STREAM_CODEC.decode(buffer);
        assertNotNull(read);
        assertEquals(region, read, "the border the client draws is the border the server chose");

        RegistryFriendlyByteBuf bad = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        bad.writeBlockPos(BlockPos.ZERO);
        bad.writeVarInt(PreviewRegion.MAX_AXIS + 1);
        bad.writeVarInt(1);
        bad.writeVarInt(1);
        assertThrows(DecoderException.class, () -> PreviewRegion.STREAM_CODEC.decode(bad),
                "a border 513 blocks wide is a number nobody typed");
    }

    @Test
    @DisplayName("the one-line description names the size and the first cell")
    void describeNamesBoth() {
        assertEquals("3x2x1 at 10,64,-3",
                PreviewRegion.of(new BlockPos(10, 64, -3), new BlockPos(12, 65, -3)).describe());
    }
}
