package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;

/**
 * Tests which right-clicks the mod claims, since that answer is shared by both sides.
 *
 * <p>{@link HostRules#claimsClick} is what the server uses to decide whether a click is its business and
 * what the client uses to decide whether to predict a block placement. If the two ever disagreed, the
 * symptom would be either the placement flash coming back or a block appearing a round trip late, so the
 * table is worth pinning even though it is "only" a predicate.
 *
 * <p>It lives in a game test rather than a unit test because it needs real block states and item stacks:
 * vanilla's registries are not bootstrapped in the headless test JVM (see {@code InnerRedstoneDataTest}).
 * The world is never touched, so the empty template is all it needs.
 *
 * <h2>How it is registered on Fabric</h2>
 * NeoForge found these tests through {@code @GameTestHolder}; Fabric has no equivalent annotation, and
 * instead scans the classes named by the {@code fabric-gametest} entrypoint in {@code fabric.mod.json},
 * which is why this class implements {@link FabricGameTest} - that interface is how Fabric recognises a
 * test class. {@code @PrefixGameTestTemplate(false)} has no counterpart and is not needed: with the
 * template named explicitly in every {@code @GameTest}, no name prefixing happens either way.
 */
public final class HostRulesGameTest implements FabricGameTest {

    private static final ItemStack NOTHING = ItemStack.EMPTY;
    private static final ItemStack REDSTONE = new ItemStack(Items.REDSTONE);
    private static final ItemStack REPEATER = new ItemStack(Items.REPEATER);
    private static final ItemStack STICK = new ItemStack(Items.STICK);

    private static Slot component(ComponentType type) {
        return new Slot(type);
    }

    @GameTest(template = "redstonecircuit:empty")
    public void plainClicksBelongToVanillaUnlessThereIsSomethingToWork(GameTestHelper helper) {
        helper.assertFalse(claims(Blocks.STONE, null, NOTHING, false),
                "a bare hand on a plain block is vanilla's click: chests still open, beds still work");
        helper.assertFalse(claims(Blocks.STONE, component(ComponentType.DUST), NOTHING, false),
                "a wire inside the block has no setting to change");
        helper.assertTrue(claims(Blocks.STONE, component(ComponentType.LEVER), NOTHING, false),
                "a lever does");
        helper.assertTrue(claims(Blocks.STONE, component(ComponentType.REPEATER), NOTHING, false),
                "and so does a repeater's delay");
        helper.assertFalse(claims(Blocks.STONE, component(ComponentType.TORCH), NOTHING, false),
                "while a torch follows its input and has nothing to work");
        helper.assertFalse(claims(Blocks.STONE, component(ComponentType.LEVER), REDSTONE, false),
                "and a click with something in hand is never the bare-hand one");
        helper.succeed();
    }

    @GameTest(template = "redstonecircuit:empty")
    public void sneakClicksPlaceIntoTheBlockOrTakeItBackOut(GameTestHelper helper) {
        helper.assertTrue(claims(Blocks.STONE, null, REDSTONE, true),
                "shift-right-clicking a full block with redstone stores it inside");
        helper.assertTrue(claims(Blocks.STONE, null, REPEATER, true),
                "and the same goes for every other component item");
        helper.assertTrue(claims(Blocks.STONE, component(ComponentType.DUST), NOTHING, true),
                "while an empty hand on a block with something inside takes that component back out");
        helper.succeed();
    }

    /** The refusals that keep vanilla's own behaviour, and the placement rules, intact. */
    @GameTest(template = "redstonecircuit:empty")
    public void sneakClicksAreRefusedForNonHostsAndOccupiedBlocks(GameTestHelper helper) {
        helper.assertFalse(claims(Blocks.GLASS, null, REDSTONE, true),
                "a transparent block may not hold redstone, so the click is vanilla's again");
        helper.assertFalse(claims(Blocks.STONE, null, STICK, true),
                "a stick is not a component: vanilla keeps its click");
        helper.assertFalse(claims(Blocks.STONE, component(ComponentType.DUST), REDSTONE, true),
                "one component per block: a second placement falls through to vanilla");
        helper.assertFalse(claims(Blocks.STONE, null, NOTHING, true),
                "an empty hand on a block with nothing inside has nothing to take out");
        helper.assertFalse(claims(Blocks.STONE, component(ComponentType.DUST), NOTHING, false),
                "and a plain click on a wire is not ours either");
        helper.succeed();
    }

    private static boolean claims(net.minecraft.world.level.block.Block block, Slot existing,
                                  ItemStack stack, boolean sneaking) {
        return HostRules.claimsClick(block.defaultBlockState(), existing, stack, sneaking);
    }
}
