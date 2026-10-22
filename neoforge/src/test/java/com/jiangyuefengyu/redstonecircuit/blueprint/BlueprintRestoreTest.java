package com.jiangyuefengyu.redstonecircuit.blueprint;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * The hand-off between a loaded blueprint and the blocks that will carry it.
 *
 * <p>A blueprint's components are known before any of its blocks exist, and are given out one landing at a time. What
 * is worth pinning here is what happens to the ones that are never asked for, because nothing else in the game will
 * ever mention them again: they must not accumulate, a second copy of the same blueprint must replace the first, and
 * anything arriving without a level - the shapes the hooks degrade to when Create changes - must be a no-op rather
 * than an exception inside somebody else's placement loop.
 */
class BlueprintRestoreTest {

    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(
            ResourceKey.createRegistryKey(ResourceLocation.withDefaultNamespace("dimension")),
            ResourceLocation.withDefaultNamespace("overworld"));

    @AfterEach
    void clear() {
        BlueprintRestore.forgetAll(DIMENSION);
    }

    @Test
    @DisplayName("记账：记下一份蓝图，没被消化之前一直在；忘掉之后一条不剩")
    void whatIsRememberedStaysUntilItIsForgotten() {
        assertEquals(0, BlueprintRestore.pending(DIMENSION), "开始是空的");
        BlueprintRestore.remember(DIMENSION, new BlockPos(4, 64, 4), budget(2, 1, 1));
        assertEquals(4, BlueprintRestore.pending(DIMENSION), "两只元件、一只时钟、一个延长器");
        BlueprintRestore.forgetAll(DIMENSION);
        assertEquals(0, BlueprintRestore.pending(DIMENSION), "忘掉就是忘掉");
    }

    @Test
    @DisplayName("同一处放两次：后一次换掉前一次，不是叠加")
    void aSecondBlueprintAtTheSameAnchorReplacesTheFirst() {
        BlockPos anchor = new BlockPos(0, 64, 0);
        BlueprintRestore.remember(DIMENSION, anchor, budget(5, 0, 0));
        assertEquals(5, BlueprintRestore.pending(DIMENSION));
        BlueprintRestore.remember(DIMENSION, anchor, budget(2, 0, 0));
        assertEquals(2, BlueprintRestore.pending(DIMENSION), "第二次放置之后，第一次剩下的不该还在等");
    }

    @Test
    @DisplayName("没人来取的会被挤掉：一个维度的等待数量有上限，且挤掉的是最老的")
    void abandonedBlueprintsCannotAccumulate() {
        for (int i = 0; i <= BlueprintRestore.MAX_PENDING_PER_DIMENSION; i++) {
            BlueprintRestore.remember(DIMENSION, new BlockPos(i * 100, 64, 0), budget(1, 0, 0));
        }
        assertEquals(BlueprintRestore.MAX_PENDING_PER_DIMENSION, BlueprintRestore.pending(DIMENSION),
                "超过上限之后，最老的那一份必须被丢掉，否则被放弃的蓝图会一直留在内存里");
    }

    @Test
    @DisplayName("空蓝图：什么都不记，也不留一个空壳")
    void anEmptyBlueprintIsNotRememberedAtAll() {
        BlueprintRestore.remember(DIMENSION, BlockPos.ZERO, budget(0, 0, 0));
        assertEquals(0, BlueprintRestore.pending(DIMENSION));
        BlueprintRestore.remember(DIMENSION, BlockPos.ZERO, null);
        BlueprintRestore.remember(null, BlockPos.ZERO, budget(1, 0, 0));
        BlueprintRestore.remember(DIMENSION, null, budget(1, 0, 0));
        assertEquals(0, BlueprintRestore.pending(DIMENSION));
    }

    @Test
    @DisplayName("加农炮的最后一遍：先记下锚点，等炮弹落地再来取，取一次就没了")
    void theLastPassWaitsForTheProjectilesInTheAir() {
        BlockPos cannon = new BlockPos(3, 70, 3);
        BlockPos anchor = new BlockPos(3, 64, 3);
        BlueprintRestore.markFinal(DIMENSION, cannon, anchor);
        assertEquals(anchor, BlueprintRestore.takeFinal(DIMENSION, cannon), "炮弹落地时还知道蓝图锚在哪");
        assertNull(BlueprintRestore.takeFinal(DIMENSION, cannon), "取过一次就没有了，不会settle两遍");
        assertNull(BlueprintRestore.takeFinal(DIMENSION, null));
        assertNull(BlueprintRestore.takeFinal(DIMENSION, cannon));
    }

    @Test
    @DisplayName("降级：拿不到世界/拿不到锚点的时候，一律什么都不做，也不抛")
    void aHookThatCannotSeeAWorldDoesNothing() {
        assertDoesNotThrow(() -> BlueprintRestore.settle(null, BlockPos.ZERO));
        assertDoesNotThrow(() -> BlueprintRestore.settle(null, null));
        assertDoesNotThrow(() -> BlueprintRestore.finish(null, BlockPos.ZERO));
        assertDoesNotThrow(() -> BlueprintRestore.finish(null, null));
        assertDoesNotThrow(() -> BlueprintRestore.markFinal(null, BlockPos.ZERO, BlockPos.ZERO));
        assertDoesNotThrow(() -> BlueprintRestore.forgetAll(null));
        assertEquals(0, BlueprintRestore.pending(null));
        assertDoesNotThrow(() -> BlueprintDeploy.blockLanded(null, BlockPos.ZERO));
        assertDoesNotThrow(() -> BlueprintDeploy.blockLanded(null, null));
        assertDoesNotThrow(() -> BlueprintDeploy.sectionRead(null));
        assertDoesNotThrow(() -> BlueprintDeploy.afterSchematicLoad(null, null));
        assertDoesNotThrow(() -> BlueprintDeploy.packetFinished(null, null));
        assertDoesNotThrow(() -> BlueprintDeploy.printingFinished(null));
        assertDoesNotThrow(() -> BlueprintDeploy.flyingBlocksTicked(null));
    }

    // --------------------------------------------------------------- helpers --

    private static BlueprintPlacement.Budget budget(int nodes, int clocks, int extenders) {
        Map<BlockPos, Slot> nodeTable = new LinkedHashMap<>();
        for (int i = 0; i < nodes; i++) {
            Slot slot = new Slot(ComponentType.DUST);
            slot.power = i;
            nodeTable.put(new BlockPos(i, 0, 0), slot);
        }
        Map<BlockPos, ClockRule.Setting> clockTable = new LinkedHashMap<>();
        for (int i = 0; i < clocks; i++) {
            clockTable.put(new BlockPos(0, i, 0), ClockRule.Setting.DEFAULT);
        }
        Map<BlockPos, ExtenderRule.Setting> extenderTable = new LinkedHashMap<>();
        for (int i = 0; i < extenders; i++) {
            extenderTable.put(new BlockPos(0, 0, i), ExtenderRule.Setting.DEFAULT);
        }
        return new BlueprintPlacement.Budget(nodeTable, clockTable, extenderTable);
    }
}
