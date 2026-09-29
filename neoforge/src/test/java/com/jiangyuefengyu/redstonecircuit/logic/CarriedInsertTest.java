package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHost;
import com.jiangyuefengyu.redstonecircuit.carrier.CarriedHostIndex;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierWorldIo;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Putting a component into, and taking it out of, a block a structure is carrying.</b>
 *
 * <h2>The defect these tests exist for</h2>
 * After a block was physicalised, placing a redstone component inside it showed nothing at all - no
 * frame and no component. The cause is a coordinate frame, not a renderer:
 * {@code InnerRedstoneInteraction} looks the click up with {@code WrenchLinks.slotAt(level, pos)}, which
 * reads the <b>world</b> store by a <b>world</b> coordinate, and a physicalised block's position in the
 * level is the <em>structure's own</em> coordinate. Claiming is a move, not a copy, so the world store
 * has never had an entry there: the click was neither claimed nor recorded, the host never entered the
 * carrier picture, and the overlay had nothing to draw.
 *
 * <p>What is pinned here is every rule the fix turns on, with no game running:
 *
 * <ul>
 *   <li>the gesture is the world's - sneak with a component stores it, sneak with a bare hand takes it
 *       out, and anything else is the block's own;</li>
 *   <li>the write lands <b>in the index, at the structure's own coordinate</b>, and never in the world
 *       store - which is what makes the host enter the picture the overlay draws from;</li>
 *   <li>one component per block, and the clicked face becomes the stored component's input side;</li>
 *   <li><b>exactly once</b>: the record is the state, so a second delivery of one gesture finds "already
 *       holds a component" or "nothing to take out" and changes nothing;</li>
 *   <li><b>a failed placement consumes nothing</b>, and an ordinary world coordinate is refused with
 *       nothing written at all.</li>
 *   <li>a retrieval owes the client a <b>host-level removal notice</b>: an emptied host is still riding
 *       and holds nothing, and the carrier payload's host list cannot say that (P1.43).</li>
 * </ul>
 */
class CarriedInsertTest {

    private static final CarrierId MINE =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000004001"));
    private static final CarrierId THEIRS =
            CarrierId.contraption(UUID.fromString("00000000-0000-0000-0000-000000004002"));

    /** The host's coordinate in the structure's own frame. Deliberately the Sable-shaped magnitude. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20524040);

    /** Where the pose puts it; only ever used for a broadcast or a drop. */
    private static final BlockPos WORLD = new BlockPos(2775, 77, -1395);

    /** Only used by the projection-table test: the table is keyed by world position and by dimension. */
    private static final ResourceKey<Level> DIMENSION = ResourceKey.create(Registries.DIMENSION,
            ResourceLocation.parse("redstonecircuit:carriedinserttest"));

    private final CarriedHostIndex index = new CarriedHostIndex();
    private final Map<Long, Slot> world = new HashMap<>();

    private static Slot dust() {
        return new Slot(ComponentType.DUST);
    }

    /**
     * Claims one host the way the assembly callback does, and leaves it <b>holding nothing</b>.
     *
     * <p>An empty host is the ordinary state of a block a player has just built into a machine, and it is
     * what the world store models as a node with no slot at all ({@code InnerRedstoneNode#isEmpty}). The
     * claim still has to be made against a real component - that is what a claim moves - so this claims a
     * piece of dust and then takes it out again through the same {@code updateSlot} path a retrieval
     * uses, which is also what makes the tests below about <em>placement</em> rather than about the
     * index's claiming rules.
     */
    private void carryEmpty(CarrierId carrier, BlockPos local, BlockPos origin) {
        world.put(origin.asLong(), dust());
        index.claim(carrier, local, origin, new MapWorld(world));
        assertNotNull(index.at(carrier, local), "the host has to be in the index to be about anything");
        index.removeSlot(carrier, local);
        assertNull(index.at(carrier, local).slot(), "and it holds nothing, which is what is tested here");
    }

    /** The world store, as a map - the same stand-in the index's own tests use. */
    private record MapWorld(Map<Long, Slot> slots) implements CarriedHostIndex.SlotWorld {

        @Override
        public Slot get(BlockPos position) {
            return slots.get(position.asLong());
        }

        @Override
        public void set(BlockPos position, Slot slot) {
            if (slot == null) {
                slots.remove(position.asLong());
            } else {
                slots.put(position.asLong(), slot);
            }
        }
    }

    // ------------------------------------------------------------- inserting --

    @Test
    @DisplayName("a sneak-click with a component stores it in the index at the structure's own coordinate")
    void anInsertWritesTheStructureFrameRecord() {
        carryEmpty(MINE, LOCAL, WORLD);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, LOCAL, Direction.EAST, true,
                ComponentType.TORCH, false);

        assertEquals(CarriedInsert.Action.INSERT, action, "an empty carried host accepts a component");
        assertTrue(action.acted(), "an insert is an action");
        assertTrue(action.consumesItem(), "and it is the only outcome that spends the held item");
        Slot stored = index.at(MINE, LOCAL).slot();
        assertNotNull(stored, "the host now holds something, which is what puts it in the overlay");
        assertEquals(ComponentType.TORCH, stored.type);
        assertEquals(Direction.EAST, stored.facing,
                "the clicked face becomes the component's input side, exactly as in the world");
        assertEquals(ComponentType.TORCH.initialPower(), stored.power,
                "the stored component starts at its type's own initial power");
        assertFalse(world.containsKey(WORLD.asLong()),
                "and the world store is untouched in the direction of a write: a carried component "
                        + "lives in the index and nowhere else");
    }

    @Test
    @DisplayName("the record written is the one the solve and the broadcast both read")
    void theStoredRecordIsTheOneTheNetworkReads() {
        carryEmpty(MINE, LOCAL, WORLD);

        CarriedInsert.apply(index, MINE, LOCAL, Direction.UP, true, ComponentType.REPEATER, false);

        Slot stored = CarriedInsert.slotOf(index, MINE, LOCAL);
        assertNotNull(stored, "the read the log and the broadcast use agrees with the write");
        assertEquals(ComponentType.REPEATER, stored.type);
        assertNotNull(CarriedTool.entryOf(index, MINE, LOCAL),
                "the client is sent this record, and a record the client cannot be sent is an insert "
                        + "that would be invisible however correct the solve is");
        assertNotNull(CarriedTool.hostAt(index, MINE, LOCAL),
                "and the host is still live and still OURS: an insert must not move or release anything");
    }

    // ------------------------------------------------------------ retrieval --

    @Test
    @DisplayName("a sneak-click with an empty hand takes the component out, and leaves nothing behind")
    void aBareHandTakesItBackOut() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.TORCH, false);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true,
                null, false);

        assertEquals(CarriedInsert.Action.RETRIEVE, action);
        assertTrue(action.acted());
        assertFalse(action.consumesItem(), "a retrieval gives an item back rather than spending one");
        assertNull(index.at(MINE, LOCAL).slot(), "the host is empty again");
        assertTrue(CarriedTool.hostAt(index, MINE, LOCAL) != null,
                "but the HOST is still carried: taking a component out is not a release");
    }

    // -------------------------------------------- the notice the client is owed (P1.43) --

    /**
     * <b>A retrieval owes the host-level removal notice a break sends, and an insert owes none.</b>
     *
     * <h2>The defect these two tests exist for</h2>
     * The author's report: <em>"taking the redstone component out of a physicalised block with a bare
     * hand leaves the frame and the component on screen, and breaking the block afterwards drops
     * nothing and still draws them."</em> The server was right the whole time - the record is riding and
     * empty - so the only place the two sides disagreed was the message.
     *
     * <p>An emptied host cannot be expressed by the carrier payload's host list: that list names the
     * hosts that are riding <b>with their components</b>, and an empty one is deliberately left out of it
     * ({@code HostSync#carrierPayload}). On the wire, "not in the list" is how a <b>put-down</b> travels,
     * and the client is <em>required</em> to keep a put-down as a landed record - that is what makes a
     * component put down over air visible. So the emptied host left the list, the client read it as a
     * landing, and it kept drawing what it had been holding, component and all, at the position the block
     * came from.
     *
     * <p>{@link CarriedInsert#removalNotice} is the one line that separates the two cases, and it is a
     * pure function of the action {@link CarriedInsert#apply} just returned - so the rule is pinned here
     * rather than in a game. The client half of it (three deletions and no landed record) is pinned by
     * {@code CarrierEmptyHostNoticeTest}.
     */
    @Test
    @DisplayName("a retrieval owes the host-level removal notice a break sends; every other outcome owes none")
    void aRetrievalOwesTheRemovalNotice() {
        assertEquals(List.of(LOCAL), CarriedInsert.removalNotice(CarriedInsert.Action.RETRIEVE, LOCAL),
                "the emptied host is still riding and holds nothing, which the carrier payload's host "
                        + "list cannot say: without this notice the client reads 'absent' as 'put down' "
                        + "and keeps drawing the component it was just told was taken out");

        for (CarriedInsert.Action other : CarriedInsert.Action.values()) {
            if (other == CarriedInsert.Action.RETRIEVE) {
                continue;
            }
            assertTrue(CarriedInsert.removalNotice(other, LOCAL).isEmpty(),
                    other + " is not a removal of the host: an INSERT in particular names the host in "
                            + "the payload, and a notice there would delete what was just stored");
        }
        assertTrue(CarriedInsert.removalNotice(null, LOCAL).isEmpty(),
                "no action means no record was touched, so the message owes nothing");
        assertTrue(CarriedInsert.removalNotice(CarriedInsert.Action.RETRIEVE, null).isEmpty(),
                "a notice with no coordinate would either name nothing or - worse - read as the origin: "
                        + "removing a host nobody asked about is the one thing a removal message must "
                        + "never do");
    }

    @Test
    @DisplayName("the notice names the structure's own coordinate, and leaves the record riding and empty")
    void theNoticeNamesTheStructureCoordinate() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.DUST, false);

        CarriedInsert.Action retrieved = CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true,
                null, false);
        List<BlockPos> notice = CarriedInsert.removalNotice(retrieved, LOCAL);

        assertEquals(List.of(LOCAL), notice,
                "the client's own record is keyed by (carrier, local coordinate), so that is the frame "
                        + "the notice has to be in");
        assertFalse(notice.contains(WORLD),
                "and never the world position the host happens to be projected onto: that coordinate is "
                        + "rebuilt every tick from the pose, and a notice naming it would remove a host "
                        + "of whichever structure is standing there later");
        // The server's own record is untouched by the notice: it is still riding and it holds nothing,
        // which is the state the in-structure solve, the wrench and the next insert all read.
        CarriedHost host = index.at(MINE, LOCAL);
        assertNotNull(host, "the host is still carried - a retrieval is not a release");
        assertFalse(host.landed(), "and it is still riding");
        assertNull(host.slot(), "and it holds nothing: this is the state the notice is about");
        assertFalse(world.containsKey(WORLD.asLong()), "and the world store was still not written");
    }

    @Test
    @DisplayName("an empty hand on an empty carried host does nothing, rather than reporting success")
    void anEmptyHandOnAnEmptyHostIsRefused() {
        carryEmpty(MINE, LOCAL, WORLD);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, LOCAL, null, true, null, false);

        assertEquals(CarriedInsert.Action.NOTHING_TO_RETRIEVE, action);
        assertFalse(action.acted(), "nothing happened, so the click falls through instead of being eaten");
        assertFalse(action.consumesItem(), "and nothing was taken from the player");
    }

    // --------------------------------------------------------- the one-per-block rule --

    @Test
    @DisplayName("one component per block: an occupied host refuses a second one unless replacing is on")
    void anOccupiedHostRefusesASecondComponent() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.DUST, false);

        CarriedInsert.Action blocked = CarriedInsert.apply(index, MINE, LOCAL, Direction.SOUTH, true,
                ComponentType.TORCH, false);
        assertEquals(CarriedInsert.Action.BLOCKED, blocked);
        assertFalse(blocked.acted());
        assertFalse(blocked.consumesItem(),
                "the rule this stands for is 'a refused placement must not eat the item'");
        assertEquals(ComponentType.DUST, index.at(MINE, LOCAL).slot().type,
                "and the record is exactly what it was");

        CarriedInsert.Action replaced = CarriedInsert.apply(index, MINE, LOCAL, Direction.SOUTH, true,
                ComponentType.TORCH, true);
        assertEquals(CarriedInsert.Action.INSERT, replaced, "allowReplace is the opt-in, as in the world");
        assertEquals(ComponentType.TORCH, index.at(MINE, LOCAL).slot().type);
        assertEquals(Direction.SOUTH, index.at(MINE, LOCAL).slot().facing,
                "a replacement takes the new click's face, exactly as the world's does");
    }

    // ------------------------------------------------------------- exactly once --

    @Test
    @DisplayName("the record is the state: a second delivery of one insert cannot store two components")
    void asecondDeliveryFindsTheHostOccupied() {
        carryEmpty(MINE, LOCAL, WORLD);

        assertEquals(CarriedInsert.Action.INSERT, CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH,
                true, ComponentType.DUST, false));
        assertEquals(CarriedInsert.Action.BLOCKED, CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH,
                true, ComponentType.DUST, false),
                "this is why 'exactly once' is structural and not a counter: the second call reads the "
                        + "first call's result and refuses on its own terms");
    }

    @Test
    @DisplayName("the record is the state: a second delivery of one retrieve cannot take two components")
    void aSecondDeliveryFindsNothingLeftToTake() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.DUST, false);

        assertEquals(CarriedInsert.Action.RETRIEVE, CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH,
                true, null, false));
        assertEquals(CarriedInsert.Action.NOTHING_TO_RETRIEVE, CarriedInsert.apply(index, MINE, LOCAL,
                Direction.NORTH, true, null, false),
                "one component can only be taken out once, whatever the packet count");
    }

    // ------------------------------------------------------- the world is not ours --

    @Test
    @DisplayName("an ordinary world coordinate is refused first, with nothing written anywhere")
    void anOrdinaryPositionIsRefusedBeforeAnythingHappens() {
        carryEmpty(MINE, LOCAL, WORLD);
        BlockPos ordinary = new BlockPos(10, 64, 10);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, ordinary, Direction.NORTH, true,
                ComponentType.TORCH, false);

        assertEquals(CarriedInsert.Action.REFUSED_POSITION, action,
                "this is the world's click, and the world's own path must keep seeing it untouched");
        assertFalse(action.acted());
        assertFalse(action.consumesItem());
        assertNull(index.at(MINE, ordinary));
        assertFalse(world.containsKey(ordinary.asLong()), "and the world store was not written either");
    }

    @Test
    @DisplayName("an unknown structure and a null index are refused, not guessed at")
    void unknownStructuresAndNoIndexAreRefused() {
        carryEmpty(MINE, LOCAL, WORLD);

        assertEquals(CarriedInsert.Action.REFUSED_POSITION,
                CarriedInsert.apply(THEIRS.equals(THEIRS) ? index : index, THEIRS, LOCAL, Direction.NORTH,
                        true, ComponentType.TORCH, false),
                "a structure the index has never heard of carries no host of ours");
        assertEquals(CarriedInsert.Action.REFUSED_POSITION,
                CarriedInsert.apply(null, MINE, LOCAL, Direction.NORTH, true, ComponentType.TORCH, false),
                "no index means no answer, rather than an exception on the click path");
        assertNull(CarriedInsert.slotOf(null, MINE, LOCAL));
        assertNull(CarriedInsert.slotOf(index, MINE, LOCAL),
                "the setup never stored anything, so this is the honest null and not a bug");
    }

    @Test
    @DisplayName("a landed host is the world's business: the carrier layer must not act on it")
    void aLandedHostIsRefused() {
        carryEmpty(MINE, LOCAL, WORLD);
        index.release(MINE, LOCAL, landing -> WORLD, new MapWorld(world));

        assertEquals(CarriedInsert.Action.REFUSED_POSITION,
                CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.TORCH, false),
                "a put-down host is back in the world and the world's placement path owns it");
        assertNull(index.at(MINE, LOCAL).slot());
    }

    // ---------------------------------------------------------- not our gesture --

    @Test
    @DisplayName("a plain right-click with a component in hand is the block's own, not ours")
    void aPlainClickIsNotOurGesture() {
        carryEmpty(MINE, LOCAL, WORLD);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, false,
                ComponentType.TORCH, false);

        assertEquals(CarriedInsert.Action.NOT_SNEAKING, action,
                "the world's rule is the same sentence: sneak + component stores it, plain click does not");
        assertNull(index.at(MINE, LOCAL).slot(), "so nothing was written");
    }

    @Test
    @DisplayName("a click with no face cannot name an input side, so it stores nothing")
    void aClickWithNoFaceStoresNothing() {
        carryEmpty(MINE, LOCAL, WORLD);

        CarriedInsert.Action action = CarriedInsert.apply(index, MINE, LOCAL, null, true,
                ComponentType.TORCH, false);

        assertEquals(CarriedInsert.Action.REFUSED_DIRECTION, action);
        assertFalse(action.consumesItem(), "the player keeps the item, which is the whole point");
        assertNull(index.at(MINE, LOCAL).slot(), "and nothing was half-written");
    }

    // ----------------------------------------------------------- scoping --

    @Test
    @DisplayName("one structure's hosts are not another's, even at the same local coordinate")
    void actionsAreScopedToOneCarrier() {
        carryEmpty(MINE, LOCAL, WORLD);
        carryEmpty(THEIRS, LOCAL, WORLD);

        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.TORCH, false);

        assertEquals(ComponentType.TORCH, index.at(MINE, LOCAL).slot().type);
        assertNull(index.at(THEIRS, LOCAL).slot(),
                "the same local coordinate in another structure is a different host");
    }

    // ------------------------- the record must be indistinguishable from an assembled one --

    @Test
    @DisplayName("a component put in after physicalisation is the SAME record shape as one claimed with the block")
    void anInsertedComponentIsIndistinguishableFromAClaimedOne() {
        // Two structures carrying the same block at the same local coordinate, from the same world
        // position. One had its component in the block when it was physicalised; the other had one put in
        // afterwards - which is the author's "put a component into a physicalised block" case.
        carryEmpty(MINE, LOCAL, WORLD);
        world.put(WORLD.asLong(), new Slot(ComponentType.DUST));
        index.claim(THEIRS, LOCAL, WORLD, new MapWorld(world));

        assertEquals(CarriedInsert.Action.INSERT, CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH,
                true, ComponentType.DUST, false));

        CarriedHost assembled = index.at(THEIRS, LOCAL);
        CarriedHost inserted = index.at(MINE, LOCAL);

        // (1) in the carrier index, still riding
        assertNotNull(inserted, "the host has to be IN the index, or the overlay and the world bridge "
                + "cannot see it at all - which is exactly the 'invisible and inert towards the world' "
                + "report this round is about");
        assertFalse(inserted.landed(), "and riding, not put down");

        // (2) the same world position, computed through the pose, for both
        CarrierPose pose = CarrierPose.at(new Vector3d(WORLD.getX(), WORLD.getY(), WORLD.getZ()));
        assertEquals(CarrierProjection.project(pose, assembled.localPos()),
                CarrierProjection.project(pose, inserted.localPos()),
                "both hosts project from their own local coordinate through the same pose");

        // (3) field for field (the carrier id is the only thing that legitimately differs)
        assertEquals(assembled.origin(), inserted.origin(),
                "origin: where the block was when the structure picked it up - not the coordinate the "
                        + "click arrived at, and not something invented for the insertion");
        assertEquals(assembled.localPos(), inserted.localPos(), "the structure's own coordinate");
        assertEquals(assembled.at(), inserted.at(), "the world position the record currently answers at");
        assertEquals(assembled.landed(), inserted.landed());
        assertEquals(assembled.conflict(), inserted.conflict());
        assertEquals(assembled.slot().type, inserted.slot().type, "and the component itself");
        assertEquals(assembled.slot().facing, inserted.slot().facing);
        assertEquals(assembled.slot().power, inserted.slot().power);
        assertEquals(assembled.slot().powered, inserted.slot().powered);
        assertEquals(assembled.slot().delay, inserted.slot().delay);
        assertEquals(assembled.slot().mode, inserted.slot().mode);
        assertEquals(assembled.slot().forcedOn, inserted.slot().forcedOn);
        assertEquals(assembled.slot().forcedOff, inserted.slot().forcedOff);
    }

    @Test
    @DisplayName("the projection table can name the inserted host, at the projected position")
    void theProjectionTableResolvesAnInsertedHost() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.DUST, false);

        // The table is what the world bridge reads - and what a world-side query and a world-side break
        // resolve through - and it is keyed by the WORLD position the pose computes: never by the
        // structure's own coordinate, and never by the origin.
        CarrierPose pose = CarrierPose.at(new Vector3d(WORLD.getX(), WORLD.getY(), WORLD.getZ()));
        BlockPos projected = CarrierProjection.project(pose, LOCAL);
        CarrierProjection table = new CarrierProjection();
        table.begin(DIMENSION, true);
        table.put(DIMENSION, new CarrierProjection.ProjectedHost(MINE, LOCAL, projected, pose,
                index.at(MINE, LOCAL).slot(), 0));

        CarrierProjection.ProjectedHost found = table.at(DIMENSION, projected);
        assertNotNull(found, "the world position the pose computes is the one the table answers at");
        assertEquals(MINE, found.carrier());
        assertEquals(LOCAL, found.localPos());
        assertEquals(ComponentType.DUST, found.slot().type,
                "and the component the world bridge would read is the one that was just put in");
        assertNull(table.at(DIMENSION, LOCAL),
                "the structure's own coordinate is NOT a world position: asking there answers nothing");
        assertNull(table.at(DIMENSION, WORLD),
                "and neither is the origin, once the structure is somewhere else");
    }

    @Test
    @DisplayName("the world bridge reads an inserted host at its CURRENT projection, never the plot coordinate")
    void theWorldBridgeReadsTheCurrentProjection() {
        carryEmpty(MINE, LOCAL, WORLD);
        CarriedInsert.apply(index, MINE, LOCAL, Direction.NORTH, true, ComponentType.DUST, false);

        // This is the two outbound halves of the acceptance standard, driven through the real arithmetic:
        // `CarrierWorldIo` is what the in-structure solve asks about the world (external -> the host) and
        // it converts a local side into a WORLD position and a world direction; `emittedToward` is what
        // the world asks about the host (the host -> the world) and it converts the query back through
        // the same pose.
        CarrierPose pose = CarrierPose.at(new Vector3d(WORLD.getX(), WORLD.getY(), WORLD.getZ()));
        List<BlockPos> asked = new ArrayList<>();
        CarrierWorldIo io = new CarrierWorldIo(pose, (worldPos, worldDirection) -> {
            asked.add(worldPos);
            return 9;
        });

        assertEquals(9, io.signalInto(LOCAL, Direction.EAST));
        assertEquals(List.of(CarrierProjection.project(pose, LOCAL.relative(Direction.EAST))), asked,
                "the world is asked about the block the machine is NEXT TO, at the projected position");
        assertFalse(asked.contains(LOCAL),
                "and never about the structure's own coordinate, where there is no world block at all");
        assertFalse(asked.contains(WORLD.relative(Direction.EAST)),
                "nor about 'the origin plus a side', which is a different block once the machine moved");

        // The outbound half: the world's query is answered from the projection, using the component that
        // was just put in - so a lamp beside the machine lights.
        CarrierProjection table = new CarrierProjection();
        table.begin(DIMENSION, true);
        table.put(DIMENSION, new CarrierProjection.ProjectedHost(MINE, LOCAL,
                CarrierProjection.project(pose, LOCAL), pose, index.at(MINE, LOCAL).slot(), 0));
        int out = CarrierProjection.emittedToward(table.at(DIMENSION, CarrierProjection.project(pose, LOCAL)),
                Direction.EAST, false, true);
        assertTrue(out >= 0, "the arithmetic runs on the inserted component rather than answering nothing");
    }
}
