package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierSpace;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>The run half of 一条光网络是一个节点: a partner's own network is PART OF THE PARTNER, not a supply to it.</b>
 *
 * <p>The arrangement, taken from a live log (carrier {@code s:518100eb}, two host components side by
 * side at {@code 20481032,128,20524040} and {@code ...20524041}, each {@code SUPERCONDUCTOR}, each with a lossless
 * wire above it): the run reads host A's own supply and A's south side <b>is</b> host B - a lossless host of the
 * <em>same</em> structure, i.e. a cell of A's own network. The log printed
 * {@code RUN PAIR SIDES ... answer=15 :: ... SOUTH s=15 w=15}, and the carrier half's {@code PAIR SIDES} for the same
 * pair printed 0, because {@code CarrierSpace#ownSupplyOf} hands {@code readerCells()} - the host's own network,
 * narrowed to lossless wire-typed cells plus the host's own cell - to <b>both</b> frames and
 * {@code ownSupplySidesExcept} then never asks a member. A value is {@code max(own supply, neighbours)}, so with each
 * half copying the other's stored fifteen at a hop that costs nothing, {@code A = B} and {@code B = A} hold for
 * <b>every</b> value: fifteen stays fifteen with the lever off.
 *
 * <p><b>What is pinned here, and why it is pinned this way.</b> The fix is one exclusion, built the way the carrier
 * half builds its own, so the rule is stated where it is decided: {@link InnerRedstoneNetwork#partnerOwnCells} narrows
 * a component walk to its lossless wire-typed cells and always adds the host's own cell. That function is level-free
 * on purpose - the walk is handed in - so the tests below drive the <b>real</b>
 * {@link CarrierLightNetwork#collectComponent} of a <b>real</b> {@link CarrierSpace}, built from a
 * {@link CarriedHostIndex} and a slot map exactly as {@code CarrierOwnNetworkSupplyTest} builds one. What a unit test
 * cannot have is a {@code ServerLevel} (so {@code SeamLookup#frameOf} cannot be driven) and a superconductor
 * <em>block state</em> (the block registry throws before it returns): the frame resolution is pinned as source text
 * instead, which is how this project pins every carrier bridge.
 *
 * <p>Each behavioural test names, in its own message, the revert that turns it red. The three revert shapes are:
 * (1) dropping the partner half and excluding {@code readerNetworkAt(runCell)} alone - the state before this round;
 * (2) widening the narrowing to "anything the walk reaches", which would cut a lever or a torch host; and (3)
 * answering an empty walk with an empty exclusion instead of {@code null}, which would silently exclude more.
 */
class PartnerOwnNetworkExclusionTest {

    /** The structure the two side-by-side hosts belong to - 被搬运的一方, the one whose network is the node. */
    private static final CarrierId CARRIER =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000f0a1"));

    /** An independently physicalised structure: a host of this one is the seam, never a member. */
    private static final CarrierId OTHER_STRUCTURE =
            CarrierId.subLevel(UUID.fromString("00000000-0000-0000-0000-00000000f0b2"));

    /** The carried contents, the same accessor the index writes through. */
    private static final class FakeWorld implements CarriedHostIndex.SlotWorld {

        private final Map<Long, Slot> slots = new HashMap<>();

        @Override
        @Nullable
        public Slot get(BlockPos pos) {
            return slots.get(pos.asLong());
        }

        @Override
        public void set(BlockPos pos, @Nullable Slot slot) {
            if (slot == null) {
                slots.remove(pos.asLong());
            } else {
                slots.put(pos.asLong(), slot);
            }
        }
    }

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final FakeWorld world = new FakeWorld();

    /** Every pickup needs a world cell of its own; one counter keeps them apart without a second fixture. */
    private int pickups;

    private static BlockPos pos(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    /** Picks a component up into a structure, the way the assembly callback does, and plants a strength in it. */
    private Slot carry(CarrierId carrier, BlockPos local, ComponentType type, int power) {
        BlockPos origin = pos(local.getX() + 100, 64, local.getZ() + 100 + pickups++);
        world.set(origin, new Slot(type));
        index.claim(carrier, local, origin, world);
        Slot held = index.at(carrier, local).slot();
        assertNotNull(held, "the structure is carrying a component at " + local);
        held.power = power;
        return held;
    }

    /**
     * The inside of this structure, wired the way a solve wires it: no carrier access and no world bridge, because
     * this round needs neither - only the component walk and the nodes it reaches.
     */
    private CarrierSpace space() {
        return new CarrierSpace(CARRIER, index, world, null, null, true);
    }

    /** One frame of the pair read, as a table of what each side offers - a {@code SideSupply} stub. */
    private static InnerRedstoneNetwork.SideSupply offering(Map<BlockPos, Integer> offered) {
        return (side, direction) -> offered.getOrDefault(side.immutable(), 0);
    }

    /** The union the run half now builds, spelled the way {@code withPartnerOwnNetwork} spells it. */
    private static Set<BlockPos> union(Set<BlockPos> readerCells, Set<BlockPos> partner) {
        Set<BlockPos> all = new HashSet<>(readerCells);
        all.addAll(partner);
        return all;
    }

    // ------------------------------------------- (i) the latch, on the run side --

    @Test
    @DisplayName("(i) two lossless hosts of one structure side by side: the run reading A's own supply answers 0")
    void twoLosslessHostsOfOneStructureSideBySideAnswerZero() {
        // Host A is the cell the run reads; host B stands on A's east side, is lossless, and belongs to the same
        // structure - so it is a cell of A's own network and not a supply to it.
        BlockPos host = pos(1, 0, 2);
        BlockPos member = host.relative(Direction.EAST);
        // The run's own cell, one step from A: for a Sable sub-level a plot coordinate and a level position are the
        // same number, which is why one set can be named in either frame.
        BlockPos runCell = host.relative(Direction.NORTH);
        carry(CARRIER, host, ComponentType.SUPERCONDUCTOR, 15);
        carry(CARRIER, member, ComponentType.SUPERCONDUCTOR, 15);

        CarrierSpace space = space();
        List<BlockPos> component = CarrierLightNetwork.collectComponent(space, host);
        assertTrue(component.contains(member.immutable()),
                "THE PREMISE: the walk from A reaches the lossless host beside it - 一条光网络是一个节点, so B is a "
                        + "cell of A's own network and never a neighbour of it");

        Set<BlockPos> partner = InnerRedstoneNetwork.partnerOwnCells(component, host, space);
        assertNotNull(partner, "a walk that answered is narrowed, never dropped");
        assertEquals(Set.of(host.immutable(), member.immutable()), partner,
                "both cells are lossless wires, and the host's own cell is always in the set - exactly what "
                        + "CarrierSpace#readerCells() produces on the other side of the same pair");

        // What the structure frame answers at each side of A: the neighbouring host's own record, fifteen.
        Map<BlockPos, Integer> record = new HashMap<>();
        record.put(member, 15);
        InnerRedstoneNetwork.SideSupply read = offering(record);

        // THE STATE THIS ROUND REPLACES: the run's own network, which can never contain B.
        assertEquals(15, InnerRedstoneNetwork.supplyAroundExcept(host, Set.of(runCell), read, null),
                "RED WHEN THE PARTNER HALF IS DROPPED: with only the run's own cells excluded, B is asked as a "
                        + "supply and hands over its stored fifteen - the RUN PAIR SIDES answer=15, and the "
                        + "latch itself");

        assertEquals(0, InnerRedstoneNetwork.supplyAroundExcept(host, union(Set.of(runCell), partner), read, null),
                "with the partner's own network excluded beside the run's, A has nothing beside it and measures "
                        + "nothing: A = B, B = A has no value left to hold");

        assertEquals(0, InnerRedstoneNetwork.supplyAroundExcept(host, partner, read, null),
                "and the answer does not depend on the run's cell being named: the partner's network alone removes "
                        + "the pair's own term, which is why the fix is one exclusion and not an extra rule");

        // Adding cells can only ever lower the answer - the reason the whole change is safe.
        record.put(runCell, 15);
        assertEquals(0, InnerRedstoneNetwork.supplyAroundExcept(host, union(Set.of(runCell), partner), read, null),
                "a source that is ALSO in the exclusion cannot raise it: the exclusion only ever removes terms "
                        + "from a maximum");
    }

    // ------------------------------- (ii) everything outside the network still arrives --

    @Test
    @DisplayName("(ii) a lever host of the same structure is narrowed out and still contributes its full value")
    void aLeverHostIsNarrowedOutAndStillContributes() {
        BlockPos host = pos(1, 4, 2);
        BlockPos lever = host.relative(Direction.EAST);
        BlockPos runCell = host.relative(Direction.NORTH);
        carry(CARRIER, host, ComponentType.SUPERCONDUCTOR, 15);
        carry(CARRIER, lever, ComponentType.LEVER, 15);

        CarrierSpace space = space();
        List<BlockPos> component = CarrierLightNetwork.collectComponent(space, host);
        assertTrue(component.contains(lever.immutable()),
                "the walk reaches the lever: it is connected to the host, so it is part of the same COMPONENT");

        Set<BlockPos> partner = InnerRedstoneNetwork.partnerOwnCells(component, host, space);
        assertNotNull(partner, "the walk answered");
        assertFalse(partner.contains(lever.immutable()),
                "RED IF THE NARROWING IS WIDENED TO 'ANYTHING THE WALK REACHES': a lever is a SOURCE, not a cell of "
                        + "the network whose echo this removes, and excluding it darkens the mechanism this all "
                        + "exists for");
        assertEquals(Set.of(host.immutable()), partner,
                "so the narrowed set is the host alone: lossless wires are the node, everything else the walk reaches "
                        + "is a supply");

        Map<BlockPos, Integer> record = new HashMap<>();
        record.put(lever, 15);
        assertEquals(15, InnerRedstoneNetwork.supplyAroundExcept(host, union(Set.of(runCell), partner),
                        offering(record), null),
                "and the source still arrives at full strength: a superconductor charges nothing for the hop, so a "
                        + "lit lever inside a host still lights the wire on its surface");
    }

    @Test
    @DisplayName("(ii) a lossless host of ANOTHER component, and a host of ANOTHER structure, still contribute")
    void aLosslessHostOfAnotherComponentAndAnotherStructureStillContribute() {
        // ANOTHER COMPONENT: a lossless host of the same structure two cells away, reachable through nothing - its
        // own node, fed through the wire rather than through this solve.
        BlockPos host = pos(1, 8, 2);
        BlockPos farHost = pos(4, 8, 2);
        BlockPos runCell = host.relative(Direction.NORTH);
        carry(CARRIER, host, ComponentType.SUPERCONDUCTOR, 15);
        carry(CARRIER, farHost, ComponentType.SUPERCONDUCTOR, 15);

        // THE SEAM: a host of an independently physicalised structure standing on the side being read.
        BlockPos otherHost = host.relative(Direction.EAST);
        carry(OTHER_STRUCTURE, otherHost, ComponentType.SUPERCONDUCTOR, 15);

        CarrierSpace space = space();
        List<BlockPos> component = CarrierLightNetwork.collectComponent(space, host);
        assertFalse(component.contains(farHost.immutable()),
                "'lossless' alone is never the test: another component of the same structure is a different node, so "
                        + "the walk never reaches it");
        assertFalse(component.contains(otherHost.immutable()),
                "and a host of ANOTHER structure is not in this carrier's index at all: the physicalisation seam is "
                        + "collected by THAT structure's walk, never by this one");

        Set<BlockPos> partner = InnerRedstoneNetwork.partnerOwnCells(component, host, space);
        assertNotNull(partner, "the walk answered");
        assertFalse(partner.contains(farHost.immutable()),
                "a lossless host of another COMPONENT is not a member, so it is never excluded");
        assertFalse(partner.contains(otherHost.immutable()),
                "and neither is another structure's host");

        Map<BlockPos, Integer> record = new HashMap<>();
        record.put(farHost, 15);
        record.put(otherHost, 15);
        InnerRedstoneNetwork.SideSupply read = offering(record);
        assertEquals(15, InnerRedstoneNetwork.supplyAroundExcept(host, union(Set.of(runCell), partner), read, null),
                "RED IF THE EXCLUSION IS EVER TAKEN FROM A WIDER SET than the partner's own network: both of these "
                        + "are real supplies standing beside the partner, and both must keep answering at full "
                        + "strength");
    }

    // ------------------------------------ (iii) the fallback excludes less, never more --

    @Test
    @DisplayName("(iii) an unreadable partner network answers nothing at all, so the run's own cells stand alone")
    void anUnreadablePartnerNetworkFallsBackToTheRunsOwnCells() {
        BlockPos host = pos(1, 12, 2);
        BlockPos member = host.relative(Direction.EAST);
        BlockPos runCell = host.relative(Direction.NORTH);
        carry(CARRIER, host, ComponentType.SUPERCONDUCTOR, 15);
        carry(CARRIER, member, ComponentType.SUPERCONDUCTOR, 15);
        CarrierSpace space = space();
        List<BlockPos> component = CarrierLightNetwork.collectComponent(space, host);

        // EVERY UNREADABLE SHAPE ANSWERS null - "no walk to narrow" - and never an empty set: an empty set would
        // read as "the partner has no network", which is a claim this read cannot make.
        assertNull(InnerRedstoneNetwork.partnerOwnCells(null, host, space),
                "no component (a frame that could not be resolved) adds nothing to the exclusion");
        assertNull(InnerRedstoneNetwork.partnerOwnCells(List.of(), host, space),
                "RED IF AN EMPTY WALK ANSWERS AN EMPTY SET: a carrier that was released walks to nothing, and the "
                        + "fallback has to be the set the caller already had");
        assertNull(InnerRedstoneNetwork.partnerOwnCells(component, host, null),
                "no space to ask adds nothing either");
        assertNull(InnerRedstoneNetwork.partnerOwnCells(null, null, null),
                "and a question about nothing is not one");

        // A coordinate the structure holds nothing at: the walk is empty for the same reason, so the read is the one
        // that already stood rather than a zero.
        BlockPos elsewhere = pos(9, 12, 9);
        assertEquals(List.of(), CarrierLightNetwork.collectComponent(space, elsewhere),
                "a cell this structure carries nothing at walks to nothing");
        assertNull(InnerRedstoneNetwork.partnerOwnCells(
                        CarrierLightNetwork.collectComponent(space, elsewhere), elsewhere, space),
                "so the exclusion falls back to the run's own network - exclude less, never more");

        Map<BlockPos, Integer> record = new HashMap<>();
        record.put(member, 15);
        assertEquals(15, InnerRedstoneNetwork.supplyAroundExcept(host, Set.of(runCell), offering(record), null),
                "the answer the fallback leaves behind is exactly the answer that stood before this round: a bridge "
                        + "that failed must never darken a machine that works");
    }

    // --------------------------------------------------------- (iv) the wiring --

    @Test
    @DisplayName("(iv) both run-side pair reads take the union, at the partner host's own cell; carrier/** is untouched")
    void bothPairReadsTakeTheUnionAndTheCarrierHalfIsUntouched() throws Exception {
        String network = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/logic/InnerRedstoneNetwork.java");

        // THE PARTNER'S NETWORK IS BUILT AT THE PARTNER HOST'S CELL, through the same frame the merged solve's ports
        // resolve a structure through, from the same walk the carrier half uses.
        assertTrue(network.contains("SeamLookup.of(level, InnerRedstoneStore.get(level)).frameOf(partnerCarrier)"),
                "the partner host names its carrier, and that carrier's own frame is asked for its space");
        assertTrue(network.contains("CarrierLightNetwork.collectComponent(frame.space(), partnerCell)"),
                "the walk is literally the one CarrierSpace#readerCells() makes, from the host's own cell");
        assertTrue(network.contains("if (node != null && lossless(node.type())) {"),
                "narrowed to lossless wire-typed cells, which is the carrier half's own filter one space over");
        assertTrue(network.contains("cells.add(hostCell.immutable());"),
                "and the host's own cell is always in the set, so the fallback is the old one cell");

        // THE STRUCTURE-FRAME PAIR READ: the union is built BEFORE the read and handed to it.
        assertTrue(network.contains("excluded = withPartnerOwnNetwork(level, carrier, cell, excluded);"),
                "the run half's structure-table pair read unions the partner's own network into its exclusion");
        int union = network.indexOf("excluded = withPartnerOwnNetwork(level, carrier, cell, excluded);");
        int read = network.indexOf("int answer = supplyAroundExcept(cell, excluded,");
        assertTrue(union >= 0 && read > union,
                "built before the read, not after it: a set assembled afterwards would exclude nothing");
        assertTrue(network.contains("worldFrameSupplyAround(level, carrier, excluded)"),
                "and the world half is handed the same union, so the two frames of one pair exclude the same cells");

        // THE PROJECTED PAIR READ: the same rule one table over.
        assertTrue(network.contains("excluded = withPartnerOwnNetwork(level, host.carrier(), host.localPos(), excluded);"),
                "a projected host names its carrier too, so its own network joins the exclusion in the structure's "
                        + "frame - and the pose carries it into the world half");

        // NOTHING ELSE MOVED: the pair read's own shape, and the carrier half's rule, are the ones that stood.
        assertTrue(network.contains("return structureSupplyAt(server, neighbour, readerNetworkAt(runCell));"),
                "the pair read still asks structureSupplyAt for the reader's own network - this round adds a term "
                        + "to that read, it does not replace it");
        assertTrue(network.contains("return level.getSignal(neighbour, direction);"),
                "and every neighbour outside a pair keeps the stock read, byte for byte");

        String space = flat("src/main/java/com/jiangyuefengyu/redstonecircuit/carrier/CarrierSpace.java");
        assertTrue(space.contains("Set<BlockPos> reader = readerCells();"),
                "RED IF THE CARRIER HALF IS REACHED INTO: it still hands the host's own network to both frames "
                        + "(CarrierOwnNetworkSupplyTest is the guard for that, and it is untouched here)");
        assertFalse(space.contains("withPartnerOwnNetwork"),
                "and none of the run half's new plumbing leaked into carrier/**, which this round may not touch");
    }

    /** One file's text, whitespace-flattened, so a wiring assertion is not a formatting assertion. */
    private static String flat(String relative) throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of(relative))
                .replace("\r\n", "\n").replaceAll("\\s+", " ");
    }
}
