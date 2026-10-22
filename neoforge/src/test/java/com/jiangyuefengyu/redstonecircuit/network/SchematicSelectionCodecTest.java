package com.jiangyuefengyu.redstonecircuit.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintExport;

import io.netty.handler.codec.DecoderException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The announcement a client sends before uploading a blueprint: which file, from which region of which level.
 *
 * <p>It is the one packet whose whole job is to let the server read the world on the sender's behalf, so its decoder
 * is the first gate: a name that is not a bare {@code *.nbt} file name is refused here, before anything on the server
 * has a path to build from it. The rest of the decision - how big a box may be, how far away - needs the player and
 * is not the codec's business.
 */
class SchematicSelectionCodecTest {

    private static final ResourceKey<Level> NETHER =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));

    @Test
    @DisplayName("选区公告：文件名 / 维度 / 两个角 原样过线")
    void theAnnouncementSurvivesTheWire() {
        RCNetwork.SchematicSelectionPayload sent = new RCNetwork.SchematicSelectionPayload("my_machine.nbt", NETHER,
                new BlockPos(-120, 64, 340), new BlockPos(-40, 90, 420));

        RegistryFriendlyByteBuf buffer = buffer();
        RCNetwork.SchematicSelectionPayload.STREAM_CODEC.encode(buffer, sent);
        RCNetwork.SchematicSelectionPayload read = RCNetwork.SchematicSelectionPayload.STREAM_CODEC.decode(buffer);

        assertEquals(sent, read, "四件都要一模一样：文件名、维度、两个角");
        assertEquals("my_machine.nbt", read.schematic());
        assertEquals(NETHER, read.dimension(),
                "维度必须跟着走：盒子只是一组坐标，放进哪个 level 才是它能读的地方");
        assertEquals(new BlockPos(-120, 64, 340), read.first());
        assertEquals(new BlockPos(-40, 90, 420), read.second());
    }

    @Test
    @DisplayName("选区公告：不是裸 .nbt 文件名的名字在解码端就拒掉")
    void aNameThatIsNotAPlainFileIsRefusedAtTheDecoder() {
        for (String name : new String[] {"../evil.nbt", "sub/dir/evil.nbt", "evil", "", "evil.dat"}) {
            RegistryFriendlyByteBuf buffer = encodeFields(name);
            assertThrows(DecoderException.class,
                    () -> RCNetwork.SchematicSelectionPayload.STREAM_CODEC.decode(buffer),
                    "名字 '" + name + "' 一旦过线，服务端就会拿它去拼路径 - 这道闸就是不放它过去的那一道");
        }

        RegistryFriendlyByteBuf tooLong = buffer();
        tooLong.writeUtf("a".repeat(BlueprintExport.MAX_NAME_LENGTH) + ".nbt", 400);
        tooLong.writeResourceLocation(ResourceLocation.withDefaultNamespace("overworld"));
        tooLong.writeBlockPos(BlockPos.ZERO);
        tooLong.writeBlockPos(BlockPos.ZERO);
        assertThrows(DecoderException.class,
                () -> RCNetwork.SchematicSelectionPayload.STREAM_CODEC.decode(tooLong),
                "比文件名上限还长的名字同样不许过线");
    }

    private static RegistryFriendlyByteBuf encodeFields(String schematic) {
        RegistryFriendlyByteBuf buffer = buffer();
        buffer.writeUtf(schematic, BlueprintExport.MAX_NAME_LENGTH);
        buffer.writeResourceLocation(ResourceLocation.withDefaultNamespace("overworld"));
        buffer.writeBlockPos(BlockPos.ZERO);
        buffer.writeBlockPos(BlockPos.ZERO);
        return buffer;
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}
