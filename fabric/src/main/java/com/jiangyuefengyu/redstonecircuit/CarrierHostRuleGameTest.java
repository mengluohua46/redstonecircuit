package com.jiangyuefengyu.redstonecircuit;

import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierAccess;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

import org.jetbrains.annotations.Nullable;

/**
 * A block a structure is carrying is judged by its own state, exactly as it was before it moved.
 *
 * <p>One question - {@code HostRules#isValidHost} - asked about a block whose state is no longer read from the level,
 * so it was never asked about it at all. The four families of block are all covered here, plus the positive control
 * that keeps the rule from being "refuse everything carried".
 *
 * <p>A game test rather than a unit test, because the rule needs real block states (vanilla's registries are not
 * bootstrapped in the headless test JVM) and a real <b>view</b> to read them through. {@code ServerLevel} is itself a
 * {@code BlockGetter}, so the structure's own view is stood in for by the level the blocks were placed in - a real
 * read of the block this test just placed. The coordinate asked about must be the <b>level</b> coordinate a plot cell
 * is keyed by, never the posed one.
 *
 * <p>The source is installed only for the duration of the assertions, and the previous one is put back in a
 * {@code finally} - the whole body runs inside one tick, so nothing else can observe it.
 */
public final class CarrierHostRuleGameTest implements FabricGameTest {

    /** The structure the cells below belong to; only its identity is read. */
    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-000000008401"));

    @GameTest(template = "redstonecircuit:empty")
    public void aCarriedBlockIsJudgedByItsOwnState(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos glassAt = new BlockPos(1, 1, 1);
        BlockPos tableAt = new BlockPos(1, 2, 1);
        BlockPos slabAt = new BlockPos(1, 3, 1);
        BlockPos productAt = new BlockPos(2, 1, 1);
        BlockPos stoneAt = new BlockPos(2, 2, 1);

        helper.setBlock(glassAt, Blocks.GLASS);
        helper.setBlock(tableAt, Blocks.CRAFTING_TABLE);
        helper.setBlock(slabAt, Blocks.OAK_SLAB);
        helper.setBlock(productAt, RCRegistry.STRUCTURE);
        helper.setBlock(stoneAt, Blocks.STONE);

        // The view is the level, and a level reads absolute positions; setBlock took relative ones.
        BlockPos glass = helper.absolutePos(glassAt);
        BlockPos table = helper.absolutePos(tableAt);
        BlockPos slab = helper.absolutePos(slabAt);
        BlockPos product = helper.absolutePos(productAt);
        BlockPos stone = helper.absolutePos(stoneAt);

        CarrierStore.CarrierSource previous = CarrierStore.source();
        try {
            CarrierStore.install(new CarrierStore.CarrierSource() {

                @Nullable
                @Override
                public CarrierPose poseOf(ServerLevel ignored, CarrierId carrier) {
                    return null;
                }

                @Nullable
                @Override
                public CarrierId carrierAt(ServerLevel ignored, BlockPos worldPos) {
                    return null;
                }

                /** Every cell of this test belongs to the one structure; that is all the gate needs. */
                @Nullable
                @Override
                public CarrierId plotOwnerAt(ServerLevel ignored, BlockPos levelPos) {
                    return CARRIER;
                }

                /** The structure's own view, as the compat half supplies it - here, the real level. */
                @Nullable
                @Override
                public CarrierAccess access() {
                    return carrier -> level;
                }

                @Override
                public String describe() {
                    return "game-test source";
                }
            });

            helper.assertFalse(CarrierStore.hostAcceptsComponent(CARRIER, glass),
                    "物理化后的玻璃: a transparent block, so the world's own rule refuses a component inside it "
                            + "and the carried copy of that block has to refuse it too");
            helper.assertFalse(CarrierStore.hostAcceptsComponent(CARRIER, table),
                    "工作台: a full opaque cube the design keeps solid, refused by the deny list");
            helper.assertFalse(CarrierStore.hostAcceptsComponent(CARRIER, slab),
                    "台阶: a partial cube, refused because its collision shape is not full");
            helper.assertFalse(CarrierStore.hostAcceptsComponent(CARRIER, product),
                    "结构方块: a product of this mod, refused by class - a component inside one would give the "
                            + "block two owners");
            helper.assertTrue(CarrierStore.hostAcceptsComponent(CARRIER, stone),
                    "and one of our own carrying a plain stone block still accepts a component: the rule is "
                            + "'the same as before the move', not 'refuse everything carried'");
            helper.assertFalse(CarrierStore.hostAcceptsComponent(level, glass, null),
                    "the filing gate's level-keyed form answers the same thing about the same cell, which is "
                            + "what keeps a refused click from leaving an empty carrier record behind");
            helper.assertTrue(CarrierStore.hostAcceptsComponent(level, stone, null),
                    "and it asks the same question, so the two entrances cannot disagree");
        } finally {
            CarrierStore.install(previous);
        }
        helper.succeed();
    }
}
