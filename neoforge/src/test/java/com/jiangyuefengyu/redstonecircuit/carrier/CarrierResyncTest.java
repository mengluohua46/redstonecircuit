package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The diff that sends a joined pair's answer to the screens in the tick it was worked out.
 *
 * <p>Why it exists: a structure's own pass calls {@code sync} inside {@code solve}, and the cross-structure union
 * is composed after every structure's pass has finished - so the values the union writes were never part of what
 * the write-back carried, and the seam reached the goggles one tick late while the records were already right.
 * {@link CarrierNetworks#markForResend} is that missing diff, on its own so it can be pinned without a level: the
 * records are the truth, {@code synced} is what went out, and the difference is what has to go out again.
 */
class CarrierResyncTest {

    private static CarriedHost hostAt(int x, int power) {
        Slot slot = new Slot(ComponentType.SUPERCONDUCTOR);
        slot.power = power;
        BlockPos local = new BlockPos(x, 1, 1);
        return CarriedHost.captured(local, local, slot);
    }

    @Test
    @DisplayName("a host the clients have never been told about is marked")
    void anUntoldHostIsMarked() {
        Long2IntOpenHashMap synced = new Long2IntOpenHashMap();
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();

        CarrierNetworks.markForResend(List.of(hostAt(1, 15)), synced, changed);

        assertEquals(1, changed.size(), "the clients were told nothing about this structure");
        assertEquals(15, changed.get(hostAt(1, 15).localPos().asLong()));
    }

    @Test
    @DisplayName("a host the clients already have at that value is left alone")
    void anAgreeingHostIsNotMarked() {
        Long2IntOpenHashMap synced = new Long2IntOpenHashMap();
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        CarriedHost host = hostAt(2, 7);
        synced.put(host.localPos().asLong(), 7);

        CarrierNetworks.markForResend(List.of(host), synced, changed);

        assertTrue(changed.isEmpty(), "nothing moved, so there is nothing to send - a quiet tick costs no packet");
    }

    @Test
    @DisplayName("a value the union moved is marked, and only that one")
    void theOneTheUnionMovedIsMarked() {
        Long2IntOpenHashMap synced = new Long2IntOpenHashMap();
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        CarriedHost still = hostAt(3, 15);
        CarriedHost moved = hostAt(4, 0);
        synced.put(still.localPos().asLong(), 15);
        synced.put(moved.localPos().asLong(), 15);

        CarrierNetworks.markForResend(List.of(still, moved), synced, changed);

        assertEquals(1, changed.size(), "only the half the union brought down");
        assertEquals(0, changed.get(moved.localPos().asLong()));
        assertTrue(!changed.containsKey(still.localPos().asLong()), "the half that did not move is not resent");
    }

    @Test
    @DisplayName("a host with no slot is skipped: the solve must never be what empties a block")
    void anEmptyHostIsSkipped() {
        Long2IntOpenHashMap synced = new Long2IntOpenHashMap();
        Long2IntOpenHashMap changed = new Long2IntOpenHashMap();
        BlockPos local = new BlockPos(5, 1, 1);

        CarrierNetworks.markForResend(List.of(new CarriedHost(local, local, null, local, false, false)),
                synced, changed);

        assertTrue(changed.isEmpty(), "a coordinate with nothing in it has nothing to write back");
    }
}
