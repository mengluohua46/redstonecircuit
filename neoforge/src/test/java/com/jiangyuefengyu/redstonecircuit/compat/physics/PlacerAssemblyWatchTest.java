package com.jiangyuefengyu.redstonecircuit.compat.physics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The ledger of structures that were assembled and not yet judged.
 *
 * <p>A structure's body cannot be read in the call that made it, so it is registered here and judged on a later
 * walk - which means this list is the only thing standing between "assembled" and "judged", and it has to keep
 * three properties: the entry carries the sub-level and the world cell it was built at (the two facts a log line
 * needs), a structure registered twice is still one entry, and the list is bounded so a walk that never runs
 * cannot grow it for the rest of the run.
 */
class PlacerAssemblyWatchTest {

    private static final ResourceKey<Level> OVERWORLD = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:placer_assembly_watch_test"));
    private static final ResourceKey<Level> NETHER = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:placer_assembly_watch_test_other"));

    private static final UUID FIRST = UUID.fromString("9f7a1c2e-0001-4000-8000-000000000001");
    private static final UUID SECOND = UUID.fromString("9f7a1c2e-0002-4000-8000-000000000002");

    @BeforeEach
    void nothingWaits() {
        PlacerAssemblyWatch.clearForTests();
    }

    @Test
    @DisplayName("组装过的结构带着 uuid、维度、它被指向的世界格和组装的那个 tick 排队")
    void aWatchedStructureCarriesWhatTheLogNeeds() {
        BlockPos target = new BlockPos(12, 70, -34);
        PlacerAssemblyWatch.watch(FIRST, OVERWORLD, target, 100L);

        List<PlacerAssemblyWatch.Pending> waiting = PlacerAssemblyWatch.pending();
        assertEquals(1, waiting.size());
        assertEquals(FIRST, waiting.get(0).uuid(), "the sub-level, so the line can be matched against Sable's own");
        assertEquals(OVERWORLD, waiting.get(0).dimension(),
                "the dimension, so only that level's walk judges it - a walk of another level cannot see it at all");
        assertEquals(target, waiting.get(0).worldTarget(), "the world cell the machine was pointing at");
        assertEquals(100L, waiting.get(0).tick(), "and the tick it was assembled in, so the same tick never judges it");

        assertTrue(PlacerAssemblyWatch.resolve(FIRST), "judging it takes it off the list");
        assertTrue(PlacerAssemblyWatch.pending().isEmpty());
        assertFalse(PlacerAssemblyWatch.resolve(FIRST), "and a second judgement finds nothing to judge");
    }

    @Test
    @DisplayName("同一个结构再登记一次还是只算一条")
    void watchingTheSameStructureAgainReplacesTheEntry() {
        PlacerAssemblyWatch.watch(FIRST, OVERWORLD, new BlockPos(1, 2, 3), 10L);
        PlacerAssemblyWatch.watch(FIRST, OVERWORLD, new BlockPos(1, 2, 3), 11L);

        assertEquals(1, PlacerAssemblyWatch.pending().size());
        assertEquals(11L, PlacerAssemblyWatch.pending().get(0).tick(), "the newest registration is the one kept");
    }

    @Test
    @DisplayName("排队有上限，超出时丢掉最老的那条：自检是兜底，不是账本")
    void theListIsBounded() {
        int extra = 5;
        for (int i = 0; i < PlacerAssemblyWatch.MAX_PENDING + extra; i++) {
            PlacerAssemblyWatch.watch(new UUID(0L, i + 1L), OVERWORLD, new BlockPos(i, 64, 0), 10L + i);
        }

        assertEquals(PlacerAssemblyWatch.MAX_PENDING, PlacerAssemblyWatch.pending().size());
        UUID newest = new UUID(0L, PlacerAssemblyWatch.MAX_PENDING + extra);
        assertTrue(PlacerAssemblyWatch.pending().stream().anyMatch(entry -> entry.uuid().equals(newest)),
                "the structure assembled last is the one that must still be judged");
        assertFalse(PlacerAssemblyWatch.pending().stream().anyMatch(entry -> entry.uuid().equals(new UUID(0L, 1L))),
                "the oldest were given up instead");
    }

    @Test
    @DisplayName("两样都不齐的登记不占位置")
    void anEntryWithoutItsFactsIsNotQueued() {
        PlacerAssemblyWatch.watch(null, OVERWORLD, BlockPos.ZERO, 1L);
        PlacerAssemblyWatch.watch(FIRST, null, BlockPos.ZERO, 1L);
        PlacerAssemblyWatch.watch(SECOND, NETHER, null, 1L);

        assertTrue(PlacerAssemblyWatch.pending().isEmpty(), "a registration that cannot name all three cannot be judged");
    }
}
