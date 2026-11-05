package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>世界那一格变了, 结构自己那张网格盖着它 - 那么结构里对应那一格必须听见重算。</b>
 *
 * <p>The hole this pins, in the geometry that shows it: a carried host stands at plot
 * {@code 20481032,127,20524040} (drawn {@code -56,-60,22}), a 超导红石粉 of the machine's own grid stands above it at
 * plot {@code 20481032,128,20524040} (drawn {@code -56,-59,22}), and the structure's supply was a <b>world</b> block at
 * {@code -56,-60,23} - the cell drawn south of the host - which the player then broke. The wire was written 15 once and
 * never written back to 0, because a world block change produces notifications at world coordinates only while the wire
 * lives at a plot coordinate: nothing in the level changed where it stands, so nothing ever asked it again. The value
 * was never wrong - the same read answers 0 once it is made - so the fix is a trigger, and this is the trigger's
 * geometry: map the changed world position into the structure's frame with the projection the read side draws with,
 * inverted, and wake the cell it lands on <b>and the six around it</b>.
 *
 * <p>A {@code ServerLevel} cannot be built in a unit test, so what is pinned here is everything the pass decides that
 * does not need one: the inverse itself on the log's numbers, the exact set of cells the wake covers and, crucially,
 * that this set - and not the mapped cell alone - is what reaches that wire. The wiring that calls it is asserted by
 * reading the two sources, the way this project pins every decision that lives behind a running server.
 */
class StructureFrameWakeTest {

    /** The carried host's own coordinate inside the structure; for a Sable sub-level a real level position. */
    private static final BlockPos HOST_PLOT = new BlockPos(20481032, 127, 20524040);
    /** Where that host is drawn. */
    private static final BlockPos HOST_DRAWN = new BlockPos(-56, -60, 22);
    /** The 超导红石粉's own coordinate: directly above the host, in the structure's grid. */
    private static final BlockPos WIRE_PLOT = new BlockPos(20481032, 128, 20524040);
    /** Where that wire is drawn. */
    private static final BlockPos WIRE_DRAWN = new BlockPos(-56, -59, 22);
    /** The world block the player broke: the cell drawn south of the host. */
    private static final BlockPos BROKEN_WORLD = new BlockPos(-56, -60, 23);
    /** The structure cell that world block is the counterpart of - what the inverse must answer. */
    private static final BlockPos BROKEN_PLOT = new BlockPos(20481032, 127, 20524041);

    /**
     * The pose the log's numbers imply: unturned and undeformed, so {@code project} is a plain offset between the two
     * frames. Derived here rather than assumed - the two projections below are asserted against the log's own drawn
     * coordinates, so a wrong offset cannot hide.
     */
    private static CarrierPose pose() {
        return CarrierPose.at(new Vector3d(
                HOST_DRAWN.getX() - HOST_PLOT.getX(),
                HOST_DRAWN.getY() - HOST_PLOT.getY(),
                HOST_DRAWN.getZ() - HOST_PLOT.getZ()));
    }

    /** Every position the six-axis neighbourhood of {@code cells}' members reaches - i.e. what waking them wakes. */
    private static Set<BlockPos> hears(List<BlockPos> cells) {
        Set<BlockPos> reached = new LinkedHashSet<>();
        for (BlockPos cell : cells) {
            for (Direction direction : Direction.values()) {
                reached.add(cell.relative(direction));
            }
        }
        return reached;
    }

    // -------------------------------------------------------- the inverse itself --

    @Test
    @DisplayName("the broken world block maps onto the structure's OWN cell, through the projection the read side uses")
    void theWorldBlockBecomesTheStructureCellBesideTheHost() {
        CarrierPose pose = pose();

        assertEquals(HOST_DRAWN, CarrierProjection.project(pose, HOST_PLOT),
                "the pose is the log's geometry: the host is drawn at -56,-60,22 while it stands at "
                        + "20481032,127,20524040 in the structure's own grid");
        assertEquals(WIRE_DRAWN, CarrierProjection.project(pose, WIRE_PLOT),
                "and the wire one cell above it is drawn one block up, at -56,-59,22");

        assertEquals(BROKEN_PLOT, CarrierProjection.localBlockOf(pose, BROKEN_WORLD),
                "THE FIX'S FIRST HALF: the one place a world block becomes a structure coordinate, taken backwards. "
                        + "Without it every world change is a number in the wrong frame, which is why the wire was "
                        + "never asked again after -56,-60,23 was removed");
        assertNull(CarrierProjection.localBlockOf(null, BROKEN_WORLD),
                "and a structure with no pose has no coordinate to name: 'I cannot say where this is' must never "
                        + "become a position that gets woken");
    }

    // --------------------------------------------------- the wake covers the wire --

    @Test
    @DisplayName("the wake is the mapped cell AND its six neighbours - the six are what reaches the wire above the host")
    void theSixNeighboursAreWhatReachTheWire() {
        List<BlockPos> wake = InnerRedstoneTickHandler.structureCellsAround(BROKEN_PLOT);

        assertEquals(7, wake.size(), "one cell and its six neighbours, and no second rule about which six");
        assertEquals(BROKEN_PLOT, wake.get(0), "the mapped cell first, so the reason it is in the set is readable");
        for (Direction direction : Direction.values()) {
            assertTrue(wake.contains(BROKEN_PLOT.relative(direction)),
                    "every axis neighbour is in the set: " + direction);
        }
        assertFalse(wake.contains(BROKEN_PLOT.offset(1, 1, 1)),
                "and no corner is: a notification reaches the six faces of a cell, so a corner neighbour of the "
                        + "mapped cell is not woken by it and pretending otherwise would wake a diagonal for nothing");

        assertTrue(hears(wake).contains(WIRE_PLOT),
                "WHY THE SIX ARE THERE AT ALL: the wire stands directly above the host, which is one of the mapped "
                        + "cell's neighbours - so waking that cell and its six reaches a block one more step out, "
                        + "and that step is exactly the wire");
        assertFalse(hears(List.of(BROKEN_PLOT)).contains(WIRE_PLOT),
                "COUNTERFACTUAL, and the reason this test goes red if the dilation is ever narrowed: waking the "
                        + "mapped cell alone reaches its six neighbours - the host among them - and never the wire "
                        + "above the host. The smallest thing that fixes the report is seven cells, not one");
    }

    // ----------------------------------------------------- the production wiring --

    @Test
    @DisplayName("the hook offers the changed position, the tick maps it, and the wake is the structure's own cell notice")
    void theThreeLinesThatCarryTheWakeAreWired() throws IOException {
        String handler = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "InnerRedstoneTickHandler.java");

        String hook = bodyOf(handler, "public static void onNeighborNotify(ServerLevel level, BlockPos pos, "
                + "int notifiedSides,");
        assertTrue(hook.contains("if (CarrierStore.isActive()) { offerWorldChange(level, pos); }"),
                "THE TRIGGER: the changed WORLD position is offered from the neighbour hook - and only while a "
                        + "carrier layer is installed, so a jar without one pays one static boolean per event");
        int offered = hook.indexOf("offerWorldChange(level, pos)");
        int worldHalf = hook.indexOf("InnerRedstoneStore store = InnerRedstoneStore.get(level);");
        assertTrue(offered > 0 && worldHalf > offered,
                "offered BEFORE the world store's own early return: the structure frame is a second frame over the "
                        + "same level, and a level whose world store is empty can still have a machine flying "
                        + "through it");
        int refusal = hook.indexOf("carried(level, pos, carriersPossible)");
        assertTrue(refusal > offered,
                "and OUTSIDE THE CARRIED-HOST REFUSAL the world half applies: a carried host's world position is "
                        + "precisely a coordinate the structure frame owns, so testing carried() first would refuse "
                        + "the very change this pass exists for");

        String tick = bodyOf(handler, "private static void onLevelTick(ServerLevel level)");
        assertTrue(tick.contains("wakeStructureFrames(level);"),
                "THE TICK PASS: the queue is drained once per level tick");
        assertTrue(tick.contains("CarrierGuards.POSE.call(null, () -> {"),
                "and behind its own guard entry, so a failure here is reported as the pose pass and cannot be "
                        + "confused with the solver's own failure");
        assertTrue(tick.indexOf("CarrierStore.tick(level);") < tick.indexOf("wakeStructureFrames(level);"),
                "after the pose pass - the projection table it reads is this tick's - and before the solver, so "
                        + "whatever the wake sets off settles in the same tick");

        String pass = bodyOf(handler, "private static void wakeStructureFrames(ServerLevel level)");
        assertTrue(pass.contains("CarrierStore.carrierAt(level, worldPos)"),
                "THE FILTER, ONE LOOKUP: which structure's world bounds cover the position. Not a walk over the "
                        + "carriers per block change - a position nothing covers stops here");
        assertTrue(pass.contains("CarrierStore.poseOf(level, carrier)")
                        && pass.contains("CarrierProjection.localBlockOf(pose, worldPos)"),
                "and then the read side's own helper, inverted, to turn that position into a structure cell");
        assertTrue(pass.contains("CarrierStore.notifyCellChanged(level, carrier, cell)"),
                "THE ENTRY POINT a structure's own block has: the notice lands at the structure's own level "
                        + "coordinate, which is where a Sable sub-level's blocks really are");
        assertFalse(pass.contains("InnerRedstoneNetwork.markDirty("),
                "and NOT the solver's world queue: that one is resolved against the world store, whose slotAt has "
                        + "nothing at a structure cell, so the wire - which is not a host - would be dropped on the "
                        + "floor precisely where it needs waking");
        assertTrue(pass.contains("RCConfig.carrierNotifyStructureNeighbours()"),
                "and the capability switch the structure half already answers to, read once per pass");

        String store = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierStore.java");
        String accessor = bodyOf(store, "public static CarrierId carrierAt(ServerLevel level, BlockPos worldPos)");
        assertTrue(accessor.contains("return carrierSource.carrierAt(level, worldPos);"),
                "the carrier layer's own world-bounds question, exposed rather than re-implemented here: one "
                        + "implementation of 'which structure covers this world position'");
        assertTrue(accessor.contains("catch (RuntimeException | LinkageError unreadable)"),
                "and 'I could not tell' answers null instead of a coordinate");
    }

    // ---------------------------------------------------------------- helpers --

    /** Reads a source file of this module, addressed from the module directory or from the repository root. */
    private static String read(String... parts) throws IOException {
        Path relative = Path.of("", parts);
        for (Path candidate : List.of(Path.of("neoforge").resolve(relative), relative)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8)
                        .replace("\r\n", "\n").replaceAll("\\s+", " ");
            }
        }
        throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath()
                + "; this guard reads the source on purpose");
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
                    return source.substring(open, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced braces after '" + signature + "'");
    }
}
