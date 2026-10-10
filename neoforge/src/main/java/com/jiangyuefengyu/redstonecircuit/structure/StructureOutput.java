package com.jiangyuefengyu.redstonecircuit.structure;

import java.util.List;
import java.util.function.ToIntFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * 已放置结构六个面各自对外答出的强度。
 *
 * <p>是电平不是脉冲: 被监视的格有信号时该面答出条目的强度, 一停立即答 0, 不锁存也不触发一次。答的是条目自己的强度而不是
 * 格的强度, 所以载 4 的格可以驱动一个 15 的面; 任何非零信号都算"有信号", 没有阈值要存。同一个面被两条条目监视时强者胜。
 *
 * <p>读取方是函数, 所以测试可以在没有存档的情况下为某一格作答, 方块也可以传入口袋维度自己的信号查询。
 */
public final class StructureOutput {

    /**
     * The strength every face answers with, indexed by {@link Direction#ordinal()}.
     *
     * @param table the structure's output table, or null when it has none
     * @param signalAt what a cell inside the selection is carrying right now; 0 for "nothing"
     */
    public static int[] faceStrengths(List<ActivationTable.OutputEntry> table, ToIntFunction<BlockPos> signalAt) {
        int[] strengths = new int[Direction.values().length];
        if (table == null || table.isEmpty()) {
            return strengths;
        }
        for (ActivationTable.OutputEntry entry : table) {
            if (entry == null || entry.localPos() == null || entry.strength() <= 0) {
                // A strength of 0 is legal in the table and means nothing to emit - the same rule a face entry has.
                continue;
            }
            if (signalAt.applyAsInt(entry.localPos()) <= 0) {
                continue;
            }
            int face = entry.face().ordinal();
            strengths[face] = Math.max(strengths[face], entry.strength());
        }
        return strengths;
    }

    private StructureOutput() {
    }
}
