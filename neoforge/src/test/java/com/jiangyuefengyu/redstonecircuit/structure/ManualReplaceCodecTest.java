package com.jiangyuefengyu.redstonecircuit.structure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;

/**
 * The panel's "update" button on the wire: one entry replaced by index.
 *
 * <p>Two things are pinned down here and both are about a client that means something the server reads differently.
 * The first is that the index, the cell, the strength and the name land in <b>the slots the server reads them from</b>
 * - an off-by-one would rename a neighbour's entry. The second is that the new verb stays at the <b>end</b> of the
 * enum: a verb is an ordinal on the wire, so slotting it beside the other manual verbs would turn every
 * {@code SET_OUTPUT} received from an older client into a rename.
 */
class ManualReplaceCodecTest {

    private static final BlockPos POS = new BlockPos(-140, 71, 2000);

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }

    private static RCNetwork.StructureEditPayload roundTrip(RCNetwork.StructureEditPayload payload) {
        RegistryFriendlyByteBuf buffer = buffer();
        RCNetwork.StructureEditPayload.STREAM_CODEC.encode(buffer, payload);
        RCNetwork.StructureEditPayload read = RCNetwork.StructureEditPayload.STREAM_CODEC.decode(buffer);
        assertEquals(payload, read, "the edit must arrive as the one that was sent");
        assertEquals(0, buffer.readableBytes(), "and nothing may be left behind in the buffer");
        return read;
    }

    @Test
    @DisplayName("a replace edit carries the index, the cell, the strength and the name")
    void theReplaceEditCarriesItsValues() {
        RCNetwork.StructureEditPayload read = roundTrip(RCNetwork.StructureEditPayload.manualReplace(POS, 7,
                "the blast door", new BlockPos(12, 66, -27), 9));
        assertEquals(RCNetwork.StructureEditPayload.Verb.REPLACE_MANUAL, read.verb());
        assertEquals(POS, read.pos(), "and it is about the controller it was sent for");
        assertEquals(7, read.a(), "the index of the entry being replaced");
        assertEquals(12, read.b());
        assertEquals(66, read.c());
        assertEquals(-27, read.d());
        assertEquals(9, read.e(), "the strength, in the slot the server reads it from");
        assertEquals("the blast door", read.first(), "the name is the row's whole text - it must survive");
        assertEquals("", read.second(), "the new name is the key and the label, so there is no second string");
    }

    @Test
    @DisplayName("a name up to the limit survives; a longer one is what the decoder refuses")
    void aNameAtTheLimitSurvivesAndAPastItIsRefused() {
        String atTheLimit = "x".repeat(RCNetwork.StructureEditPayload.MAX_TEXT);
        RCNetwork.StructureEditPayload read = roundTrip(RCNetwork.StructureEditPayload.manualReplace(POS, 0,
                atTheLimit, BlockPos.ZERO, 15));
        assertEquals(atTheLimit, read.first(), "a name exactly at the limit is a legal name");

        // The encoder bounds what it writes, so an over-long name is what a hand-written packet carries: the decoder
        // is the only thing that stops it, and it must.
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeBlockPos(POS);
        buffer.writeVarInt(RCNetwork.StructureEditPayload.Verb.REPLACE_MANUAL.ordinal());
        for (int ignored = 0; ignored < 6; ignored++) {
            buffer.writeVarInt(0);
        }
        buffer.writeUtf("x".repeat(RCNetwork.StructureEditPayload.MAX_TEXT + 1),
                RCNetwork.StructureEditPayload.MAX_TEXT + 1);
        buffer.writeUtf("", 8);
        assertThrows(DecoderException.class,
                () -> RCNetwork.StructureEditPayload.STREAM_CODEC.decode(buffer),
                "a decoder that trusts a length is a decoder that allocates");
    }

    @Test
    @DisplayName("the new verb is the last ordinal, so no verb a client already sends changes meaning")
    void theNewVerbIsAppended() {
        RCNetwork.StructureEditPayload.Verb[] verbs = RCNetwork.StructureEditPayload.Verb.values();
        assertEquals(20, RCNetwork.StructureEditPayload.Verb.REPLACE_MANUAL.ordinal());
        assertEquals(verbs.length - 1, RCNetwork.StructureEditPayload.Verb.REPLACE_MANUAL.ordinal(),
                "a verb appended at the end leaves every existing ordinal where it was");
        assertEquals(13, RCNetwork.StructureEditPayload.Verb.SET_MANUAL.ordinal());
        assertEquals(14, RCNetwork.StructureEditPayload.Verb.REMOVE_MANUAL.ordinal());
        assertEquals(15, RCNetwork.StructureEditPayload.Verb.CLEAR_MANUAL.ordinal());
        assertEquals(19, RCNetwork.StructureEditPayload.Verb.TAKE_OUT.ordinal());
    }
}
