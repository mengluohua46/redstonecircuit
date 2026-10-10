package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

/**
 * The pocket's plot table, as a piece of saved data.
 *
 * <p>The registries are <b>not</b> bootstrapped in these tests and do not need to be: an entry is a
 * dimension key, a position, a size and two numbers, all of which are plain NBT. The provider argument of
 * {@code save}/{@code load} is therefore unused, and the tests pass {@code null} - if a future field needs a
 * registry, this test is where that will be noticed.
 */
class PocketPlotsTest {

    @Test
    @DisplayName("a plot whose block is away travelling says so, and a restart remembers it")
    void travellingIsWrittenDown() {
        PocketPlots plots = new PocketPlots();
        PocketPlots.Entry home = new PocketPlots.Entry(Level.OVERWORLD, new BlockPos(4, 65, 4),
                new Vec3i(3, 3, 3), false, 1);
        plots.put(5, home.travelling(12_000L));

        PocketPlots.Entry reloadedEntry = PocketPlots.load(plots.save(new CompoundTag(), null), null).entry(5);
        assertNotNull(reloadedEntry);
        assertEquals(12_000L, reloadedEntry.travellingSince(),
                "a machine that is still in the air when the server stops must still be protected when it starts");
        assertTrue(reloadedEntry.travelling(12_500L, PocketSpace.TRAVEL_LEASE_TICKS),
                "and it is protected while the ride lasts");
        assertFalse(reloadedEntry.travelling(12_000L + PocketSpace.TRAVEL_LEASE_TICKS + 1,
                PocketSpace.TRAVEL_LEASE_TICKS),
                "but a ride nobody came back from ends: the lease is what keeps a destroyed machine from "
                        + "holding a plot for ever");
    }

    @Test
    @DisplayName("coming home clears the travelling stamp and keeps everything else")
    void landingClearsTheStamp() {
        PocketPlots.Entry away = new PocketPlots.Entry(Level.OVERWORLD, new BlockPos(4, 65, 4),
                new Vec3i(3, 5, 3), true, 2).travelling(700L);

        PocketPlots.Entry landed = away.standingAt(Level.OVERWORLD, new BlockPos(40, 70, -9));
        assertEquals(0L, landed.travellingSince(), "a block that is standing is not travelling");
        assertEquals(new BlockPos(40, 70, -9), landed.ownerPos(), "and the record says where it stands");
        assertEquals(away.size(), landed.size(), "the size, the shape and the budget cost are not the block's");
        assertEquals(away.shell(), landed.shell());
        assertEquals(away.columns(), landed.columns());
    }

    @Test
    @DisplayName("a save with no travelling stamp reads back as standing at home")
    void oldSavesHaveNoStamp() {
        // Exactly what an older build wrote: no travelling_since key at all.
        PocketPlots plots = new PocketPlots();
        plots.put(2, new PocketPlots.Entry(Level.OVERWORLD, new BlockPos(1, 2, 3), new Vec3i(1, 1, 1), false, 1));
        CompoundTag tag = plots.save(new CompoundTag(), null);
        tag.getList("plots", 10).getCompound(0).remove("travelling_since");

        PocketPlots.Entry reloaded = PocketPlots.load(tag, null).entry(2);
        assertNotNull(reloaded);
        assertEquals(0L, reloaded.travellingSince(), "absent means 0, which means standing - not 'unknown'");
        assertFalse(reloaded.travelling(999_999L, PocketSpace.TRAVEL_LEASE_TICKS));
    }

    @Test
    @DisplayName("plots survive being written and read, owner and all")
    void entriesRoundTrip() {
        PocketPlots plots = new PocketPlots();
        plots.put(3, new PocketPlots.Entry(Level.OVERWORLD, new BlockPos(10, 70, -4),
                new Vec3i(5, 3, 7), true, 4));
        plots.put(9, new PocketPlots.Entry(Level.NETHER, BlockPos.ZERO, new Vec3i(1, 1, 1), false, 1));

        CompoundTag tag = plots.save(new CompoundTag(), null);
        PocketPlots reloaded = PocketPlots.load(tag, null);

        assertEquals(2, reloaded.size(), "both plots must come back");
        PocketPlots.Entry first = reloaded.entry(3);
        assertNotNull(first);
        assertEquals(Level.OVERWORLD, first.ownerDimension(), "the owner's dimension is how a restart finds it");
        assertEquals(new BlockPos(10, 70, -4), first.ownerPos());
        assertEquals(new Vec3i(5, 3, 7), first.size());
        assertTrue(first.shell());
        assertEquals(4, first.columns());
        assertNotNull(reloaded.entry(9));
        assertNull(reloaded.entry(4), "and nothing that was never claimed appears");
    }

    @Test
    @DisplayName("forgetting a plot is written down, so a restart cannot resurrect it")
    void removingIsRecorded() {
        PocketPlots plots = new PocketPlots();
        plots.put(1, new PocketPlots.Entry(Level.OVERWORLD, BlockPos.ZERO, new Vec3i(2, 2, 2), false, 1));
        assertNotNull(plots.remove(1));
        assertNull(plots.remove(1), "forgetting it twice is not a state change");
        assertEquals(0, PocketPlots.load(plots.save(new CompoundTag(), null), null).size());
    }

    @Test
    @DisplayName("a table from another format is read as empty rather than guessed at")
    void anotherFormatStartsEmpty() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("data_version", 99);
        CompoundTag one = new CompoundTag();
        one.putInt("plot", 0);
        one.putString("owner_dimension", "minecraft:overworld");
        one.putLong("owner_pos", 0L);
        one.putInt("size_x", 1);
        one.putInt("size_y", 1);
        one.putInt("size_z", 1);
        one.putBoolean("shell", false);
        one.putInt("columns", 1);
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        list.add(one);
        tag.put("plots", list);

        assertEquals(0, PocketPlots.load(tag, null).size(),
                "a version this build does not know is not something to interpret");
    }

    @Test
    @DisplayName("one unreadable entry does not cost the others")
    void aBrokenEntryIsSkipped() {
        PocketPlots plots = new PocketPlots();
        plots.put(2, new PocketPlots.Entry(Level.OVERWORLD, new BlockPos(1, 2, 3), new Vec3i(2, 2, 2),
                true, 4));
        CompoundTag tag = plots.save(new CompoundTag(), null);
        // An entry whose dimension cannot be parsed, appended by hand.
        CompoundTag broken = new CompoundTag();
        broken.putInt("plot", 5);
        broken.putString("owner_dimension", "not a dimension id at all");
        broken.putLong("owner_pos", 0L);
        tag.getList("plots", 10).add(broken);

        PocketPlots reloaded = PocketPlots.load(tag, null);
        assertEquals(1, reloaded.size(), "the good entry must still be there");
        assertNotNull(reloaded.entry(2));
        assertNull(reloaded.entry(5));
    }
}
