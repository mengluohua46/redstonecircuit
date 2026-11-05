package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「被搬着的方块被打掉，客户端原地还画着那颗元件」：世界路径那一格少了一句删除。
 *
 * <p>一个元件放进被搬着的方块里，服务端发的是<b>两份</b>记录：carrier 记录（键是结构自己的 local 坐标）和一条
 * <b>世界路径</b>的 host（键是这台宿主被<b>投影</b>到的那一格，见 {@code CarrierInteraction#broadcastPlacementChange}）。
 * 打掉那块方块时，{@code CarrierStore#dropCandidate} 只说了 carrier 那一半（掉落 + 带 {@code removed} 的 carrier
 * 消息），世界路径那一格没有任何一条消息说"这里空了"——世界里取出会说（同一个方法里的 {@code null} 那一支），
 * 打掉不说。客户端于是继续把那颗元件的连线画在一块已经不存在的方块上；退出重进就没了，因为服务端 store 里那一格
 * 从来就没有过记录，多出来的只有客户端这一份。
 *
 * <p>本类只钉三件事：缺失的那句删除落在唯一的写回里（源码接线）、它回答的正是放入时的那一句广播（源码接线）、
 * 以及"世界 store 真的持有那一格时这句删除必须缺席"（纯值，{@code HostSync#publishWorldEntry} 的守卫规则）。
 */
class CarrierDestroyedWorldNoticeTest {

    /** The world store, as a map - the same stand-in the carrier index's own tests use. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    /**
     * 唯一的写回是 {@code dropCandidate}，于是这句删除只写一次就覆盖两条触发（BreakEvent 与自愈清扫）。
     * 修复前这条断言红：{@code dropCandidate} 里只有 carrier 那一半，没有 {@code publishWorldEntry}。
     */
    @Test
    @DisplayName("打掉被搬着的方块：世界路径那一格也要被说成空了（就在唯一的写回里）")
    void aDestroyedCarriedHostAlsoSaysTheWorldPathIsEmpty() throws IOException {
        String body = body(sourceOf("carrier", "CarrierStore.java"),
                "private static Slot dropCandidate(",
                "// ------------------------------------------------- the self-healing sweep --");
        assertTrue(body.contains("HostSync.publishWorldEntry(level, destroyed.worldPos())"),
                "the one write-back for a destroyed carried host has to tell the world path that the "
                        + "position the host was PROJECTED onto holds nothing any more: the insert "
                        + "announced a component there, and nothing else ever takes it back");
        assertFalse(body.contains("HostSync.publishWorldEntry(level, destroyed.localPos())"),
                "the notice names the projected position, never the structure's own coordinate - a Sable "
                        + "plot coordinate is millions of blocks from anywhere a player can go, and the "
                        + "client filed the announcement under the projected position");
    }

    /**
     * 这一句删除不是新约定：放入元件时的那一句广播（{@code broadcastPlacementChange}）就是它要回答的一半，而且先
     * carrier 后世界。两半的顺序在 {@code CarrierInteraction} 里已经写死（取出那一路就是这么发的），这里把那条配对
     * 钉住，免得以后有人只改一边。
     */
    @Test
    @DisplayName("回答的是放入时那一句：世界路径的投影位置，carrier 先、世界后")
    void theInsertIsTheAnnouncementThisRemovalAnswers() throws IOException {
        String body = body(sourceOf("carrier", "CarrierInteraction.java"),
                "private static void broadcastPlacementChange(",
                "The acceptance line: carrier, local coordinate");
        assertTrue(body.contains("HostSync.broadcast(level, hit.worldPos(), entrySlot.slot())"),
                "the placement announces the component on the world path at hit.worldPos() - that is the "
                        + "record a break has to take back");
        int carrierMessage = body.indexOf("CarrierStore.broadcastCarrierNow(");
        int worldMessage = body.indexOf("HostSync.broadcast(level, hit.worldPos()");
        assertTrue(carrierMessage >= 0 && worldMessage > carrierMessage,
                "the carrier message goes first and the world entry second, the order the destruction "
                        + "path now repeats");
    }

    /** 自愈清扫（Create 的钻头之类拿掉方块时唯一能看到的那条路）走同一个写回，于是它也拿到同一句删除。 */
    @Test
    @DisplayName("自愈清扫与 BreakEvent 共用一个写回，所以两条触发都拿到这句删除")
    void theSelfHealingSweepGoesThroughTheSameWriteBack() throws IOException {
        String body = body(sourceOf("carrier", "CarrierStore.java"),
                "public static CarriedBlockRemoval.SelfHealTick pollDestroyedHosts(",
                "// --------------------------------------------------- talking to the client --");
        assertTrue(body.contains("dropCandidate(level, destroyed, \"found gone by the self-healing sweep\")"),
                "a block a structure lost without a BreakEvent has to leave the client in the same state "
                        + "as one the player broke");
    }

    /**
     * 纯值：{@code HostSync#publishWorldEntry} 的守卫规则——只有世界 store 真的什么都不持有那一格时，这条删除才该
     * 发出去。世界自己在那儿有宿主时它是那一格的主人（普通破坏路径会掉落它），这里不许抹掉。
     */
    @Test
    @DisplayName("世界 store 还持有那一格时，这句删除必须缺席")
    void aPositionTheWorldStoreHoldsIsLeftAlone() {
        FakeWorld world = new FakeWorld();
        BlockPos projected = new BlockPos(20481032, 128, 20491272);
        assertTrue(WorldHostTransfer.suppressesWorldHost(world, projected),
                "nothing stored there: the client's world record is the one the insert made and it has to "
                        + "go with the block");
        world.set(projected, new Slot(ComponentType.TORCH));
        assertFalse(WorldHostTransfer.suppressesWorldHost(world, projected),
                "the world's own host wins: that position belongs to the ordinary break path, and a "
                        + "removal sent here would erase a component the player really has");
        assertFalse(WorldHostTransfer.suppressesWorldHost(world, null),
                "no position means no message");
        assertFalse(WorldHostTransfer.suppressesWorldHost(null, projected),
                "no store to read means no message: the answer is never a guess");
    }

    // ------------------------------------------------------------------ utils --

    /** One source file of the main source set, found from the test working directory either way round. */
    private static String sourceOf(String... parts) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit");
        for (String part : parts) {
            relative = relative.resolve(part);
        }
        List<Path> candidates = new ArrayList<>();
        candidates.add(relative);
        candidates.add(Path.of("neoforge").resolve(relative));
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
    }

    /** The text of one declaration, from its signature to the marker that ends the section it sits in. */
    private static String body(String source, String start, String end) {
        int from = source.indexOf(start);
        assertTrue(from >= 0, "the source no longer contains: " + start);
        int to = source.indexOf(end, from);
        assertTrue(to > from, "the source no longer ends that region with: " + end);
        return source.substring(from, to);
    }
}
