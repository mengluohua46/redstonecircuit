package com.jiangyuefengyu.redstonecircuit;

import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.ConnectionState;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadLimits;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureRelease;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>This mod's own content survives a capture and a release, item by item.</b>
 *
 * <p>Components inside a block are not part of the block: they live in the world's
 * {@code InnerRedstoneStore}, keyed by position, so a capture that copies block states and stops there
 * produces a host block that arrives empty inside.
 *
 * <p>Two tests, and the second is the point: with the migration switched off the same round trip must
 * <em>fail</em> to carry the component, or the first test would also pass against an implementation that
 * preserves nothing.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class HostComponentRoundTripGameTest {

    private static final BlockPos FROM = new BlockPos(1, 2, 1);
    private static final BlockPos TO = new BlockPos(3, 2, 1);
    /**
     * Absolute and far away, and a <b>different</b> spot for each test: the tests of a batch run against
     * one level, so a short relative offset would land inside the next test's structure, and one test's
     * released component would be read by the other as its own result.
     */
    private static final BlockPos TARGET_MIGRATED = new BlockPos(120600, 100, 120000);
    private static final BlockPos TARGET_IGNORED = new BlockPos(120600, 100, 120200);

    /** A component with everything worth losing: type, facing, delay, power and two connection overrides. */
    private static Slot busyComponent() {
        InnerRedstoneNode node = InnerRedstoneNode.of(ComponentType.DOUBLE_RETAINING_REPEATER);
        Slot slot = node.slot();
        slot.facing = Direction.WEST;
        slot.delay = 3;
        slot.power = 7;
        slot.powered = true;
        slot.setConnection(Direction.UP, ConnectionState.ON);
        slot.setConnection(Direction.DOWN, ConnectionState.OFF);
        return slot;
    }

    private static void buildHost(GameTestHelper helper, ServerLevel level) {
        // A full cube is a valid host, and the component inside it is what this test is about.
        for (int x = 0; x <= 2; x++) {
            helper.setBlock(FROM.offset(x, 0, 0), Blocks.STONE);
        }
        helper.setBlock(FROM.offset(1, 0, 0), Blocks.STONE);
        InnerRedstoneStore.get(level).getOrCreate(helper.absolutePos(FROM.offset(1, 0, 0)))
                .setSlot(busyComponent());
    }

    /** Everything a player would notice is gone, compared field by field. */
    private static void assertSameComponent(GameTestHelper helper, Slot expected, BlockPos hostPos) {
        InnerRedstoneNode found = InnerRedstoneStore.get(helper.getLevel()).get(hostPos);
        helper.assertTrue(found != null && !found.isEmpty(),
                "there must be a component inside the host block at " + hostPos.toShortString()
                        + " through the round trip");
        Slot actual = found.slot();
        helper.assertTrue(actual.type == expected.type,
                "the component type must survive, expected " + expected.type + " got " + actual.type);
        helper.assertTrue(actual.facing == expected.facing,
                "its facing must survive, expected " + expected.facing + " got " + actual.facing);
        helper.assertTrue(actual.delay == expected.delay,
                "its delay must survive, expected " + expected.delay + " got " + actual.delay);
        helper.assertTrue(actual.power == expected.power && actual.powered == expected.powered,
                "its power must survive, expected " + expected.power + "/" + expected.powered + " got "
                        + actual.power + "/" + actual.powered);
        helper.assertTrue(actual.forcedOn.equals(expected.forcedOn) && actual.forcedOff.equals(expected.forcedOff),
                "its connection overrides must survive, expected on=" + expected.forcedOn + " off="
                        + expected.forcedOff + " got on=" + actual.forcedOn + " off=" + actual.forcedOff);
        helper.assertTrue(actual.describe().equals(expected.describe()),
                "and the whole description must read the same: expected '" + expected.describe()
                        + "' got '" + actual.describe() + "'");
    }

    private static StructurePayload capture(GameTestHelper helper, ServerLevel level, boolean migrateSlots) {
        StructureCapture.Result result = StructureCapture.capture(level,
                new StructureCapture.Request(helper.absolutePos(FROM), helper.absolutePos(TO),
                        IoMode.ACTIVATION_FACES, "host round trip", true, false, null),
                new StructureCapture.Limits(PayloadLimits.DEFAULT, Set.of(), 1, migrateSlots));
        helper.assertTrue(result.allowed(), "the capture must work: " + result.refusal());
        return result.captured().payload();
    }

    @GameTest(template = "empty", timeoutTicks = 200)
    public void aHostBlockKeepsItsComponentThroughCaptureAndRelease(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildHost(helper, level);
        Slot original = busyComponent();

        StructurePayload payload = capture(helper, level, true);
        helper.assertTrue(payload.slotData().size() == 1,
                "the component inside the host block must travel in the payload, got "
                        + payload.slotData().size() + " entr(ies)");
        InnerRedstoneNode source = InnerRedstoneStore.get(level).get(helper.absolutePos(FROM.offset(1, 0, 0)));
        helper.assertTrue(source == null || source.isEmpty(),
                "and a cut must take it out of the world store, or it would exist in two places");

        StructureRelease.Result released = StructureRelease.start(level, payload, TARGET_MIGRATED, true,
                new StructureRelease.Options(4096, true));
        helper.assertTrue(released.started(), released.refusal());

        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    assertSameComponent(helper, original, TARGET_MIGRATED.offset(1, 0, 0));
                    InnerRedstoneNode target = InnerRedstoneStore.get(level)
                            .get(TARGET_MIGRATED.offset(1, 0, 0));
                    helper.assertTrue(target != null && target.slot().type == ComponentType.DOUBLE_RETAINING_REPEATER,
                            "the component has to be the one that was captured, not a fresh default");
                })
                .thenSucceed();
    }

    /**
     * The negative control: with the migration off the same round trip loses the component, which is what
     * makes the test above a test of the migration rather than of nothing.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public void withoutMigrationTheSameRoundTripLosesIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        buildHost(helper, level);

        StructurePayload payload = capture(helper, level, false);
        helper.assertTrue(payload.slotData().isEmpty(),
                "with migration off the payload must carry no components at all, got "
                        + payload.slotData().size());

        // The cut must not delete it either: switching migration off must leave the blocks moving and the
        // insides staying, not destroy the player's components.
        InnerRedstoneNode source = InnerRedstoneStore.get(level).get(helper.absolutePos(FROM.offset(1, 0, 0)));
        helper.assertTrue(source != null && !source.isEmpty(),
                "with migration off a cut must leave the component where it is, not delete it");

        StructureRelease.Result released = StructureRelease.start(level, payload, TARGET_IGNORED, true,
                new StructureRelease.Options(4096, true));
        helper.assertTrue(released.started(), released.refusal());

        helper.startSequence()
                .thenIdle(3)
                .thenExecute(() -> {
                    InnerRedstoneNode target = InnerRedstoneStore.get(level).get(TARGET_IGNORED.offset(1, 0, 0));
                    helper.assertTrue(target == null || target.isEmpty(),
                            "with migration off the released host block must be empty inside - that is the "
                                    + "failure the migration exists to prevent");
                    helper.assertTrue(level.getBlockState(TARGET_IGNORED.offset(1, 0, 0)).is(Blocks.STONE),
                            "while the block itself still arrives");
                })
                .thenSucceed();
    }
}
