package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentModel.Neighbours;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentVisuals.Placement;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A wire inside a physicalised block has to see the wire inside the block beside it: inside a structure, every block
 * drew as a dot, because the neighbour question answered nothing on all six sides.
 *
 * <p>{@code HostRenderPass#neighboursOf} asked the <b>world host index</b> at {@code host.origin()}. For a world host
 * {@code host.pos()} is a world position and the neighbouring host's data is in the world index at that position, but a
 * carried host's data has been <b>moved out</b> of the world index into the carrier index, which is keyed by the
 * structure's own coordinate - so the same question asked of two different stores in two different frames answered
 * nothing for every carried wire. The solver never had the defect ({@code CarrierSpace#nodeAt} asks the carrier index at
 * the local coordinate), so the drawing and the solving disagreed about one question, and the drawing lost.
 *
 * <p>What is asserted: the rule as a pure function ({@link HostRenderPass#carriedNeighbourHolds}); its <em>effect</em> on
 * the drawn arms for a corner and a crossing, which have to come out as a corner and a crossing rather than a straight
 * line; and the wiring that says which frames the carried path asks, and that the structure-frame index is built once
 * per frame rather than per side per host. A {@code ClientLevel} cannot be built here, so {@code neighboursOf} is pinned
 * by source assertions while the rule it delegates to is pinned behaviourally.
 */
class CarriedNeighbourhoodTest {

    private static final CarrierId MINE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000a142"));
    private static final CarrierId THEIRS =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000b142"));

    // ------------------------------------------------------- 1. the rule, pure --

    @Test
    @DisplayName("a host of the same structure one block away IS a neighbour")
    void theStructuresOwnHostIsANeighbour() {
        assertTrue(HostRenderPass.carriedNeighbourHolds(MINE, MINE, false),
                "two wires inside ONE physicalised structure, one block apart, "
                        + "are neighbours exactly as they are on the ground - without this the "
                        + "carried render path cannot see it, and every wire is drawn as a dot");
    }

    @Test
    @DisplayName("another structure's host at the same local coordinate is not")
    void anotherStructuresHostAtTheSameCoordinateIsNot() {
        assertNotEquals(MINE, THEIRS, "the fixture's two carriers really are different structures");
        assertFalse(HostRenderPass.carriedNeighbourHolds(THEIRS, MINE, false),
                "a plot coordinate names one sub-level's block, but a Create contraption's coordinates "
                        + "are 0..15 around its anchor, so two contraptions can hold a host at the same "
                        + "coordinate - a coincidence, not a connection. Comparing the carrier can only "
                        + "refuse an arm, never invent one");
    }

    @Test
    @DisplayName("an ordinary world host still counts, and empty space still does not")
    void theWorldHalfIsKept() {
        assertTrue(HostRenderPass.carriedNeighbourHolds(null, MINE, true),
                "a host block still standing where the machine came from is a neighbour through the world "
                        + "index, exactly as it is on the ground");
        assertTrue(HostRenderPass.carriedNeighbourHolds(MINE, MINE, true),
                "and the two frames are alternatives, not a choice: either one is enough");
        assertFalse(HostRenderPass.carriedNeighbourHolds(null, MINE, false),
                "nothing on that side in either frame: no arm, which is what a lone wire looks like");
        assertFalse(HostRenderPass.carriedNeighbourHolds(null, null, false),
                "and a host with no carrier of its own has no structure frame to ask at all");
    }

    // ------------------------------------------- 2. what the rule does to the arms --

    @Test
    @DisplayName("a corner inside a structure is drawn as a corner, not as a dot")
    void aCornerInsideAStructureDrawsBothArms() {
        Slot dust = new Slot(ComponentType.DUST);

        Placement corner = InnerComponentVisuals.placementFor(dust, structureNeighbours(
                Direction.EAST, Direction.SOUTH));

        assertEquals(Set.of(Direction.EAST, Direction.SOUTH), corner.wireSides(),
                "the L case: two arms and a bend, which is what the vanilla wire model draws out of "
                        + "these sides");
    }

    @Test
    @DisplayName("a crossing inside a structure is drawn as a crossing")
    void aCrossingInsideAStructureDrawsFourArms() {
        Slot dust = new Slot(ComponentType.DUST);

        Placement cross = InnerComponentVisuals.placementFor(dust, structureNeighbours(
                Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST));

        assertEquals(Set.of(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST),
                cross.wireSides(),
                "the other half of the scene: a four-way crossing has to be drawn as one, and "
                        + "the sides come from the same neighbour question the solver uses");
    }

    @Test
    @DisplayName("a wire with no neighbour at all is still a dot - the honest case")
    void aloneInsideAStructureIsStillADot() {
        Slot dust = new Slot(ComponentType.DUST);

        Placement alone = InnerComponentVisuals.placementFor(dust, direction -> false);

        assertTrue(alone.wireSides().isEmpty(),
                "a lone wire has no arms, and that is not the defect - the defect was every wire looking "
                        + "like this");
    }

    /** The neighbour question, answered the way the carried path answers it. */
    private static Neighbours structureNeighbours(Direction... sides) {
        Set<Direction> holds = EnumSet.noneOf(Direction.class);
        holds.addAll(java.util.List.of(sides));
        return direction -> HostRenderPass.carriedNeighbourHolds(
                holds.contains(direction) ? MINE : null, MINE, false);
    }

    // -------------------------------------------------------- 3. the wiring --

    @Test
    @DisplayName("the carried path asks the structure's own frame, once per frame")
    void theCarriedPathAsksTheStructuresOwnFrame() throws Exception {
        String pass = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/client/HostRenderPass.java");
        String cache = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/client/ClientHostCache.java");
        String index = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierIndex.java");

        assertTrue(pass.contains("ClientHostCache.carriedLocalIndex(level.dimension())"),
                "the carried stage builds the structure-frame index once per frame: the question is asked "
                        + "six times per drawn host, and the carrier index answers it by walking its "
                        + "sections");
        assertTrue(pass.contains("riding && local != null ? inStructure.get(local.relative(direction)"
                        + ".asLong()) : null"),
                "and it asks it at the NEIGHBOURING LOCAL COORDINATE - the frame the carrier index is "
                        + "keyed by - and only for a host that is really riding: a fallback host is back "
                        + "in the world and has no structure to be inside of");
        assertTrue(pass.contains("ClientHostCache.slotAt(level.dimension(), origin.relative(direction))"),
                "with the world index kept as the other frame, so an ordinary host block beside the block "
                        + "the component came from still counts");
        assertTrue(cache.contains("public static Map<Long, CarrierId> carriedLocalIndex("),
                "the client exposes it by local coordinate - the frame the neighbours are named in - and "
                        + "not by the section buckets, which are keyed by origin and cannot answer it");
        assertTrue(index.contains("public Map<Long, CarrierId> localHostIndex()"),
                "built from the hosts this client actually holds, per frame, so a station-keeping frame "
                        + "costs one walk of the carriers and not six walks per host");
        assertTrue(pass.contains("InnerComponentVisuals.of(slot, neighbours)"),
                "and both halves of the drawing - the vanilla wire model and our own connection bars - are "
                        + "handed the same answer, so the two can never disagree");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
