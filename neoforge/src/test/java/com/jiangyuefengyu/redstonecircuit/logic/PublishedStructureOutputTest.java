package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;

/**
 * The answer a carried structure gives where it is DRAWN.
 *
 * <p>A product block stands inside the carrier's storage, and notifying only that coordinate left the world beside the
 * machine reading air. The record itself is plain arithmetic and is tested here; the lines that publish, notify and
 * read it are wiring inside methods that need a running server, and are pinned by reading the source at the bottom of
 * this class.
 */
class PublishedStructureOutputTest {

    private static final BlockPos DRAWN = new BlockPos(-202, -60, 15);

    /** One strength per world face: 15 out of the west face, nothing anywhere else. */
    private static int[] westFifteen() {
        int[] strengths = new int[Direction.values().length];
        strengths[Direction.WEST.ordinal()] = 15;
        return strengths;
    }

    @AfterEach
    void clear() {
        PublishedStructureOutput.forgetForTests();
    }

    @Test
    @DisplayName("an answer is read back per world face, and only while it is fresh")
    void anAnswerLivesForAFewTicks() {
        PublishedStructureOutput.publish(Level.OVERWORLD, DRAWN, westFifteen(), 1000);

        assertEquals(15, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.WEST, 1000),
                "the face the structure emits out of answers with the strength it was published with");
        assertEquals(0, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.EAST, 1000),
                "and a face it does not emit out of answers nothing - the six faces are six answers, not one");
        assertEquals(15, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.WEST,
                        1000 + PublishedStructureOutput.PUBLISH_TICKS),
                "an answer stays good while the product keeps republishing it");
        assertEquals(0, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.WEST,
                        1000 + PublishedStructureOutput.PUBLISH_TICKS + 1L),
                "and it EXPIRES: a structure that stops being ticked cannot leave a phantom signal in the world - "
                        + "this is the same rule as every other carrier read, ask the live thing and never a table "
                        + "that remembers");
    }

    @Test
    @DisplayName("the record is keyed by dimension and position, so two structures never answer for each other")
    void theRecordIsKeyedByDimensionAndPosition() {
        PublishedStructureOutput.publish(Level.OVERWORLD, DRAWN, westFifteen(), 10);
        PublishedStructureOutput.publish(Level.NETHER, DRAWN, new int[Direction.values().length], 10);

        assertEquals(0, PublishedStructureOutput.answer(Level.NETHER, DRAWN, Direction.WEST, 10),
                "the same coordinate in another dimension is another position");
        assertEquals(0, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN.east(), Direction.WEST, 10),
                "and so is the block beside it");
        assertEquals(15, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.WEST, 10),
                "while the published one still answers");
    }

    @Test
    @DisplayName("the sweep behind the cap drops only what has gone stale")
    void theSweepDropsOnlyStaleEntries() {
        PublishedStructureOutput.publish(Level.OVERWORLD, DRAWN, westFifteen(), 10);
        PublishedStructureOutput.publish(Level.OVERWORLD, DRAWN.east(), westFifteen(), 10
                + PublishedStructureOutput.PUBLISH_TICKS + 1L);

        PublishedStructureOutput.forgetStale(
                10 + PublishedStructureOutput.PUBLISH_TICKS + 1L);

        assertEquals(1, PublishedStructureOutput.sizeForTests(),
                "the answer published in the same tick is still standing");
        assertEquals(0, PublishedStructureOutput.answer(Level.OVERWORLD, DRAWN, Direction.WEST,
                10 + PublishedStructureOutput.PUBLISH_TICKS + 1L), "and the stale one is gone");
    }

    @Test
    @DisplayName("the product publishes at the DRAWN position, wakes it, and the world reads it there")
    void theThreeLinesThatCarryTheAnswerAreWired() throws IOException {
        String product = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "StructureBlockEntity.java");
        String apply = bodyOf(product, "public int applyOutput(ServerLevel plotLevel, BlockPos plotOrigin, ");

        assertTrue(apply.contains("PublishedStructureOutput.publish(level.dimension(), drawn, byWorldFace(now, frame)"),
                "the six strengths go out indexed by WORLD face at the drawn position - a query names a world "
                        + "direction, and the structure's own faces are not that direction while a machine is turned");
        assertTrue(apply.contains("serverLevel.updateNeighborsAt(drawn, this.getBlockState().getBlock());"),
                "and the neighbours of that position are woken, or a device that never re-asks keeps its old answer "
                        + "for ever");
        int published = apply.indexOf("PublishedStructureOutput.publish(");
        int compared = apply.indexOf("if (now[face] != this.outputSignals[face])");
        assertTrue(published > 0 && compared > published,
                "publishing happens ABOVE the \"did it change\" test: the answer expires, so a steady "
                        + "output has to be republished every tick it is ticked");

        assertTrue(bodyOf(product, "public void updateOutputDrawn(ServerLevel pocket, ProductFrame frame)")
                        .contains("this.applyOutput(pocket, plot.origin(), frame, false);"),
                "the contraption path (carriedTick) runs this half too, and tells ONLY the drawn position, because a "
                        + "contraption's product stands at a local coordinate that is not a position of any level");

        String mixin = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                "BlockStateSignalMixin.java");
        assertTrue(mixin.contains("PublishedStructureOutput.answer(serverLevel.dimension(), pos,")
                        && mixin.contains("direction.getOpposite(), serverLevel.getGameTime());"),
                "and the query is answered on the OPPOSITE of vanilla's direction: that parameter names the way the "
                        + "signal travels from the asker to this block, while a published strength belongs to the face "
                        + "it leaves through - which is the face pointing back at the asker. The block on the ground "
                        + "does the same conversion (StructureBlock#getSignal passes side.getOpposite()); getting it "
                        + "wrong makes every answer the opposite face's, so a structure emitting out of its DOWN face "
                        + "hands the receiver underneath the UP answer");
        int gate = mixin.indexOf("if (answer == 0) {");
        int call = mixin.indexOf("PublishedStructureOutput.answer(");
        assertTrue(gate >= 0 && gate < call,
                "weak only: the strong question must not gain an answer the standing product never gave");

        assertTrue(mixin.contains("PhysicsCompat.carriedSignalAt(serverLevel, pos, direction, strong)"),
                "and a block that is DRAWN there is answered too - a redstone block the machine carries, a "
                        + "torch, another structure's product - because those are not ours and no record of ours knows "
                        + "about them. The direction is passed "
                        + "UNCONVERTED here, unlike the line above: the block in the plot has to be asked exactly as the "
                        + "block on the ground would be, and the pose conversion happens in the carrier's own frame");
        assertTrue(read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                        "SableProductDriver.java").contains("space.localFor(worldPos)"),
                "and that answer comes from the carrier's own inverse - the world position is turned back "
                        + "into each live sub-level's coordinates and the block standing there is asked - so there is no "
                        + "table to keep in step with a machine that moves, and nothing to go stale");

        assertTrue(apply.contains("InnerRedstoneNetwork.notifyOutputChangedNow(serverLevel,")
                        && apply.contains("InnerRedstoneNetwork.notifyOutputChanged(serverLevel,")
                        && apply.contains("drawn);"),
                "and the MOD'S OWN network is woken at the drawn position as well as vanilla's, which is a "
                        + "different reader: a host block in the world (a block holding inner redstone) only moved "
                        + "when an unrelated placement poked the solver");
        assertTrue(apply.contains("boolean arrived = drawn != null && drawn.asLong() != this.lastDrawnPos;")
                        && apply.contains("if (changed == 0 && !arrived) {"),
                "ARRIVING somewhere new is a reason to wake the world on its own - a structure that was "
                        + "already answering 15 when a carrier took it over changes nothing at the moment it is drawn "
                        + "somewhere else, and a notification that waits for a change never fires");

        String driver = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableProductDriver.java");
        assertTrue(driver.contains("tellDrawnSources(level, subLevel, plot, now);")
                        && driver.contains("if (toldFor(uuid).spots.add(drawn.asLong())) {"),
                "and every emitting block a machine draws is TOLD to the world the first time it is seen - "
                        + "answering a query is not enough, because a vanilla device only re-reads when somebody pokes "
                        + "it, and a carried redstone block has no tick of its own to do it");
        assertTrue(driver.contains("reviewTold(level, container);") && driver.contains("wakeTold(level, told);")
                        && driver.contains("pose.equals(told.pose)"),
                "and a machine that MOVED is turned off at once - the pose it was told under is compared "
                        + "every tick, and a different one wakes every position that pose produced in the same tick "
                        + "instead of waiting out a lease");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }
}
