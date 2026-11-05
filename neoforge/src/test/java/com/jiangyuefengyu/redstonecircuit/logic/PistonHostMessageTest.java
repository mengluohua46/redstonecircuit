package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.HostEntry;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * What the clients are told when moved blocks carry their components along, as a rule rather than as a line.
 *
 * <p>A row of pushed blocks is the case that broke: one cell is both the destination of the block behind it and the
 * origin of the block in it, so a message per move ends with the departure arriving <em>after</em> the arrival it must
 * not touch. The client then draws nothing in that cell, and every component of a pushed row but the outermost
 * disappears from the goggles while the server still holds all of them. The plan below is one message per position, in
 * its final state - arithmetic over a few positions, no level and no piston.
 */
class PistonHostMessageTest {

    private static final BlockPos FIRST = new BlockPos(10, 64, 10);
    private static final BlockPos SECOND = new BlockPos(11, 64, 10);
    private static final BlockPos THIRD = new BlockPos(12, 64, 10);

    private static PistonHostTransfer.Move move(BlockPos from, BlockPos to, int power) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = power;
        slot.injectedPower = power;
        InnerRedstoneNode node = InnerRedstoneNode.of(ComponentType.DUST);
        node.setSlot(slot);
        return new PistonHostTransfer.Move(from, to, new InnerRedstoneStore.Stored(node, null, null));
    }

    @Test
    @DisplayName("一块方块：旧位置说空了，新位置说是什么")
    void oneBlockIsTwoMessages() {
        Map<BlockPos, HostEntry> messages =
                PistonHostTransfer.clientMessagesFor(List.of(move(FIRST, SECOND, 7)));

        assertEquals(2, messages.size(), "one arrival and one departure");
        assertNull(messages.get(FIRST).slot(), "the cell it left holds nothing now");
        assertNotNull(messages.get(SECOND).slot(), "the cell it arrived in holds the component");
        assertEquals(7, messages.get(SECOND).slot().injectedPower,
                "and the same component, not a copy of the default");
    }

    @Test
    @DisplayName("一排方块：既是落点又是起点的格子只说一次，说的是落点")
    void aCellThatIsBothAnOriginAndADestinationIsAnnouncedOnceAsTheDestination() {
        Map<BlockPos, HostEntry> messages = PistonHostTransfer.clientMessagesFor(List.of(
                move(FIRST, SECOND, 7),
                move(SECOND, THIRD, 3)));

        assertEquals(3, messages.size(),
                "two arrivals and one departure - not four messages, because " + SECOND.toShortString()
                        + " is both and can only be said once");
        assertNotNull(messages.get(SECOND).slot(),
                "the arrival at " + SECOND.toShortString() + " must survive the departure from it, or the client "
                        + "draws nothing there and the goggles lose every component of a pushed row but the outermost");
        assertEquals(7, messages.get(SECOND).slot().injectedPower, "the block that arrived there is the one behind it");
        assertEquals(3, messages.get(THIRD).slot().injectedPower, "and the end of the row keeps its own");
        assertNull(messages.get(FIRST).slot(), "only the row's tail is empty afterwards");
    }

    /**
     * 频道跟着元件走: 活塞搬的是一个 {@code Stored}, 而频道在 {@code Slot} 上, 所以它不需要谁去修那张位置表 - 这一条
     * 把"不需要"钉成一句话, 因为一旦它变成"需要", 一组同步火把里被推动过的那一支就会悄悄退组。
     */
    @Test
    @DisplayName("活塞搬走一个同步火把: 频道跟着元件一起到新位置")
    void syncTorchChannelTravelsWithTheComponent() {
        Slot slot = new Slot(ComponentType.SYNC_TORCH);
        slot.syncGroup = 5;
        InnerRedstoneNode node = InnerRedstoneNode.of(ComponentType.SYNC_TORCH);
        node.setSlot(slot);

        Map<BlockPos, HostEntry> messages = PistonHostTransfer.clientMessagesFor(List.of(
                new PistonHostTransfer.Move(FIRST, SECOND,
                        new InnerRedstoneStore.Stored(node, null, null))));

        assertEquals(5, messages.get(SECOND).slot().syncGroup,
                "the arriving copy is on the same channel: a pushed sync torch does not leave its group");
        assertNull(messages.get(FIRST).slot(), "and the cell it left holds nothing");
    }

    /**
     * 世界形态的那一支没有槽位, 它的频道存在 store 的位置表里, 所以它得由 {@code PistonHostTransfer} 自己搬 - 而搬法与
     * 内嵌元件那一条规则逐字相同: 一个格子里站着不动的占用者时, 两边都留下。
     */
    @Test
    @DisplayName("世界形态的同步火把被推走: 频道行跟到新位置, 不动的占用者不被覆盖")
    void syncTorchChannelRowFollowsTheBlock() {
        Map<BlockPos, Integer> moving = Map.of(FIRST, 9);

        assertEquals(Map.of(SECOND, 9),
                PistonHostTransfer.channelMovesFor(moving, Set.of(FIRST), Direction.EAST, pos -> false),
                "向东一格就是 " + SECOND.toShortString() + "，而频道跟着它一起到那里");

        assertTrue(PistonHostTransfer.channelMovesFor(moving, Set.of(FIRST), Direction.EAST, pos -> true).isEmpty(),
                "目标格上已经站着一支不动的火把: 那一行不能被覆盖, 于是就留在这里 - 一个格子不能有两支火把");
    }

    @Test
    @DisplayName("没有移动就没有消息")
    void noMovesNoMessages() {
        assertTrue(PistonHostTransfer.clientMessagesFor(List.of()).isEmpty(),
                "a piston that carries nothing puts nothing on the wire");
    }
}
