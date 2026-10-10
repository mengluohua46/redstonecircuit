package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.Vec3i;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The screen's one packet: every verb across the wire, and the two ways a hand-written message is refused.
 *
 * <p>The screen is the only part of this feature a test cannot drive, so what can be pinned down is pinned down
 * hard: that the verb survives, that every number survives <b>in the slot the server reads it from</b> (an off-by-one
 * in the encoder would be a region one block away, silently), and that the decoder refuses an unknown verb and an
 * over-long string instead of trusting them.
 */
class StructureEditCodecTest {

    private static final BlockPos POS = new BlockPos(-140, 71, 2000);

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static RCNetwork.StructureEditPayload roundTrip(RCNetwork.StructureEditPayload payload) {
        RegistryFriendlyByteBuf buffer = buffer();
        RCNetwork.StructureEditPayload.STREAM_CODEC.encode(buffer, payload);
        RCNetwork.StructureEditPayload read =
                RCNetwork.StructureEditPayload.STREAM_CODEC.decode(buffer);
        assertEquals(payload, read, "the edit must arrive as the one that was sent");
        assertEquals(0, buffer.readableBytes(), "and nothing may be left behind in the buffer");
        return read;
    }

    @Test
    @DisplayName("a region edit carries the first cell and the size in their own slots")
    void regionCarriesBothHalves() {
        RCNetwork.StructureEditPayload sent = RCNetwork.StructureEditPayload.region(POS,
                new BlockPos(10, 64, -30), new Vec3i(3, 2, 5));
        RCNetwork.StructureEditPayload read = roundTrip(sent);
        assertEquals(10, read.a(), "the first cell's x");
        assertEquals(64, read.b());
        assertEquals(-30, read.c());
        assertEquals(3, read.d(), "the size's x - a size in the cell's slot would be a region in the wrong place");
        assertEquals(2, read.e());
        assertEquals(5, read.f());
        assertEquals(RCNetwork.StructureEditPayload.Verb.SET_REGION, read.verb());
        assertEquals(POS, read.pos(), "and it is about the controller it was sent for");
    }

    @Test
    @DisplayName("a face edit carries the face, the cell and the strength")
    void faceCarriesItsFourValues() {
        RCNetwork.StructureEditPayload read = roundTrip(RCNetwork.StructureEditPayload.face(POS,
                Direction.EAST, new BlockPos(11, 65, -28), 12));
        assertEquals(Direction.EAST.ordinal(), read.a());
        assertEquals(11, read.b());
        assertEquals(65, read.c());
        assertEquals(-28, read.d());
        assertEquals(12, read.e());
        assertEquals(RCNetwork.StructureEditPayload.Verb.SET_FACE, read.verb());

        RCNetwork.StructureEditPayload cleared = roundTrip(
                RCNetwork.StructureEditPayload.clearFace(POS, Direction.DOWN));
        assertEquals(Direction.DOWN.ordinal(), cleared.a());
        assertEquals(RCNetwork.StructureEditPayload.Verb.CLEAR_FACE, cleared.verb());
    }

    @Test
    @DisplayName("a panel edit carries the cell, the strength, the key and the label")
    void manualCarriesItsStrings() {
        RCNetwork.StructureEditPayload read = roundTrip(RCNetwork.StructureEditPayload.manual(POS,
                "key.keyboard.g", new BlockPos(12, 66, -27), 9, "the door"));
        assertEquals(12, read.a());
        assertEquals(66, read.b());
        assertEquals(-27, read.c());
        assertEquals(9, read.d());
        assertEquals("key.keyboard.g", read.first(), "the key name is the entry's identity - it must survive");
        assertEquals("the door", read.second());
    }

    @Test
    @DisplayName("the a-name edit and the flag edits carry what they say")
    void textAndFlags() {
        assertEquals("my machine", roundTrip(RCNetwork.StructureEditPayload.text(POS,
                RCNetwork.StructureEditPayload.Verb.SET_NAME, "my machine")).first());
        assertTrue(roundTrip(RCNetwork.StructureEditPayload.flag(POS,
                RCNetwork.StructureEditPayload.Verb.SET_MODE, true)).a() != 0, "release");
        assertTrue(roundTrip(RCNetwork.StructureEditPayload.flag(POS,
                RCNetwork.StructureEditPayload.Verb.SET_TIER, false)).a() == 0, "cut");
        assertEquals(3, roundTrip(RCNetwork.StructureEditPayload.entry(POS,
                RCNetwork.StructureEditPayload.Verb.PRESS_MANUAL, 3)).a());
    }

    @Test
    @DisplayName("every verb in the enum survives a round trip, so none of them is unreachable")
    void everyVerbRoundTrips() {
        for (RCNetwork.StructureEditPayload.Verb verb : RCNetwork.StructureEditPayload.Verb.values()) {
            RCNetwork.StructureEditPayload sent = new RCNetwork.StructureEditPayload(POS, verb,
                    1, 2, 3, 4, 5, 6, "k", "l");
            roundTrip(sent);
        }
    }

    @Test
    @DisplayName("a verb this build does not know is refused at the decoder")
    void unknownVerbIsRefused() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeBlockPos(POS);
        buffer.writeVarInt(RCNetwork.StructureEditPayload.Verb.values().length + 1);
        for (int ignored = 0; ignored < 6; ignored++) {
            buffer.writeVarInt(0);
        }
        buffer.writeUtf("", 8);
        buffer.writeUtf("", 8);
        assertThrows(DecoderException.class,
                () -> RCNetwork.StructureEditPayload.STREAM_CODEC.decode(buffer),
                "a packet naming a verb that does not exist is not an edit");
    }

    @Test
    @DisplayName("a text longer than a name or a key may be is refused at the decoder")
    void overlongTextIsRefused() {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeBlockPos(POS);
        buffer.writeVarInt(RCNetwork.StructureEditPayload.Verb.SET_NAME.ordinal());
        for (int ignored = 0; ignored < 6; ignored++) {
            buffer.writeVarInt(0);
        }
        buffer.writeUtf("x".repeat(RCNetwork.StructureEditPayload.MAX_TEXT * 4),
                RCNetwork.StructureEditPayload.MAX_TEXT * 4);
        buffer.writeUtf("", 8);
        assertThrows(DecoderException.class,
                () -> RCNetwork.StructureEditPayload.STREAM_CODEC.decode(buffer),
                "a decoder that trusts a length is a decoder that allocates");
    }
}
