package com.jiangyuefengyu.redstonecircuit.logic;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * <b>频道 → 成员, 加上"自成一组的那些", 一张纯表: 没有世界, 没有存档.</b>
 *
 * <p>抽出来的理由只有一个: 组归属的<b>形状</b>可以离开 Minecraft 去检查, 而这几条正是最容易写错的地方 - 一个位置只能
 * 属于一个频道、换频道就是搬走而不是复制、最后一个人走了频道就不存在。{@link SyncGroups} 只负责把世界里的两种形态收集成
 * 这张表并写回答案, 于是那座"世界"的薄壳里没有第二条规则。
 *
 * <p>为什么还有 {@link #putSolo}: 一支<b>没绑频道</b>的火把不是"不属于任何组", 而是"自成一个只含它自己的组" - 它照样
 * 跟着自己的吸附面亮灭, 只是不跟别人联动 ({@code SyncGroups#tick})。把它塞进某个假频道号会有两个后果: 两个未绑定的火把
 * 会互相点亮, 而客户端会被告知一条它画不出来的连线。所以它单独一张表, 不进频道、不上线。
 */
public final class SyncChannels {

    /** 频道 → 成员, 按第一次出现的顺序, 于是同一份世界每次都得到同一份遍历顺序。 */
    private final Map<Integer, Set<BlockPos>> members = new LinkedHashMap<>();
    /** 成员 → 频道, 与上面那张表一一对应; 0 表示这个位置不属于任何共享频道。 */
    private final Map<BlockPos, Integer> channelOf = new LinkedHashMap<>();
    /** 没绑频道的成员: 每个都是自己一个人的一组, 所以它们彼此之间没有任何关系。 */
    private final Set<BlockPos> solo = new LinkedHashSet<>();

    /**
     * Binds one position to one channel. A position that was in another channel is <b>moved</b>: it can only be in one.
     *
     * <p>只接受真频道号; 未绑定是 {@link #putSolo} 那种状态, 不是"频道 0"。
     */
    public void put(BlockPos pos, int channel) {
        if (pos == null || !SyncTorchRule.isChannel(channel)) {
            return;
        }
        BlockPos key = pos.immutable();
        solo.remove(key);
        Integer previous = channelOf.put(key, channel);
        if (previous != null && previous != channel) {
            Set<BlockPos> old = members.get(previous);
            if (old != null) {
                old.remove(key);
                if (old.isEmpty()) {
                    // An empty channel does not exist - otherwise "somebody is on channel 3" would be true for ever
                    // after the last member left, and the pass would keep visiting a group of nobody.
                    members.remove(previous);
                }
            }
        }
        members.computeIfAbsent(channel, ignored -> new LinkedHashSet<>()).add(key);
    }

    /** 一个没绑频道的成员: 自成一组的那个, 且不再是任何共享频道的成员。 */
    public void putSolo(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos key = pos.immutable();
        dropFromChannel(key);
        solo.add(key);
    }

    /** A position left its group, whichever kind it was. */
    public void remove(BlockPos pos) {
        if (pos == null) {
            return;
        }
        BlockPos key = pos.immutable();
        if (solo.remove(key)) {
            return;
        }
        dropFromChannel(key);
    }

    private void dropFromChannel(BlockPos key) {
        Integer previous = channelOf.remove(key);
        if (previous == null) {
            return;
        }
        Set<BlockPos> group = members.get(previous);
        if (group != null) {
            group.remove(key);
            if (group.isEmpty()) {
                members.remove(previous);
            }
        }
    }

    /** The channel at one position, or 0 when it belongs to no shared channel (unbound counts as 0). */
    public int channelOf(BlockPos pos) {
        Integer channel = channelOf.get(pos);
        return channel == null ? 0 : channel;
    }

    /** The members of one channel, as a copy: an empty set when the channel does not exist. */
    public Set<BlockPos> membersOf(int channel) {
        Set<BlockPos> group = members.get(channel);
        return group == null ? Set.of() : Set.copyOf(group);
    }

    /** 没绑频道的那些成员, 每一个都是自己一组。 */
    public Set<BlockPos> soloPositions() {
        return Set.copyOf(solo);
    }

    /** Every channel that has at least one member. */
    public Set<Integer> channels() {
        return Set.copyOf(members.keySet());
    }

    /** Every channel with its members - the shape the client payload and the diagnostics want. Solo members are left
     * out on purpose: a client is told the groups it can draw, and a group of one has no line to draw. */
    public Map<Integer, Collection<BlockPos>> snapshot() {
        Map<Integer, Collection<BlockPos>> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Set<BlockPos>> entry : members.entrySet()) {
            out.put(entry.getKey(), Set.copyOf(entry.getValue()));
        }
        return out;
    }

    /** 表里一共有多少个成员, 共享频道上的和自成一组的都算。 */
    public int size() {
        return channelOf.size() + solo.size();
    }

    /** True while nothing at all is in the table. */
    public boolean isEmpty() {
        return channelOf.isEmpty() && solo.isEmpty();
    }

    public void clear() {
        members.clear();
        channelOf.clear();
        solo.clear();
    }
}
