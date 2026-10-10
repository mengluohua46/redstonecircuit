package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.HashMap;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 立在世界里的每一件延长器, 它的保持什么时候结束.
 *
 * <p>和答案一样是"此刻的事", 不是关于方块的事实: 换一次存档就没有"刚才那个输入是什么时候走的"了, 所以既不进存档也不进
 * 方块状态。设定（秒与刻）在 {@code InnerRedstoneStore}, 答案在 {@link BlockAnswerCache}, 这里只放那条截止时刻 - 三样东西
 * 各自的理由都写在它们自己的类上。
 *
 * <p>一个位置一个 {@code long}, 与 {@link BlockAnswerCache} 同一套手写键表, 理由也一样: 每次查询都装箱一个 {@code Long}
 * 不值当。
 */
public final class ExtenderDeadlines {

    private static final Map<ResourceKey<Level>, Map<Long, Long>> DEADLINES = new HashMap<>();

    private ExtenderDeadlines() {
    }

    /** 这个位置的截止时刻, 没有就是 {@link ExtenderRule#NO_DEADLINE}. */
    public static long get(ServerLevel level, BlockPos pos) {
        Map<Long, Long> deadlines = DEADLINES.get(level.dimension());
        if (deadlines == null) {
            return ExtenderRule.NO_DEADLINE;
        }
        Long deadline = deadlines.get(pos.asLong());
        return deadline == null ? ExtenderRule.NO_DEADLINE : deadline;
    }

    /** 写下这个位置的截止时刻 - 写的只有一个地方, 就是这件方块自己的 {@code announce}. */
    public static void put(ServerLevel level, BlockPos pos, long deadline) {
        DEADLINES.computeIfAbsent(level.dimension(), key -> new HashMap<>()).put(pos.asLong(), deadline);
    }

    /** 一件延长器不在了: 它的截止时刻跟着走. */
    public static void forget(ServerLevel level, BlockPos pos) {
        Map<Long, Long> deadlines = DEADLINES.get(level.dimension());
        if (deadlines != null) {
            deadlines.remove(pos.asLong());
        }
    }

    /** 整个维度都不算了 - 世界卸载, 以及测试之间. */
    public static void clear(ResourceKey<Level> dimension) {
        DEADLINES.remove(dimension);
    }

    /** 这个维度记着多少条, 给诊断和测试看. */
    public static int size(ServerLevel level) {
        Map<Long, Long> deadlines = DEADLINES.get(level.dimension());
        return deadlines == null ? 0 : deadlines.size();
    }
}
