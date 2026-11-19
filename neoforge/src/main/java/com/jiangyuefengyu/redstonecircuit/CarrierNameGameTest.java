package com.jiangyuefengyu.redstonecircuit;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierMessages;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * <b>What the wrench calls a component is the name of the item that component turns back into - asserted
 * against the registry, not against a second copy of the same list.</b>
 *
 * <p>An item that places a block is a {@code BlockItem}, and a {@code BlockItem} answers with its
 * <b>block's</b> description id: {@code block.minecraft.repeater}, never {@code item.minecraft.repeater}.
 * Vanilla keeps no {@code item.} entry for those seven, and there is no fallback at render time, so the
 * wrong half is the raw key in a player's chat line - a defect a language file cannot show, because the
 * missing key lives in vanilla's file and not in ours.
 *
 * <p>The message layer keeps its keys in a pure table so its rules can be read without a game; this is the
 * test that keeps that table from drifting away from {@code HostRules#itemFor}. A game test, because the
 * registry is not bootstrapped in the headless unit-test JVM.
 */
@GameTestHolder(RedstoneCircuit.MODID)
@PrefixGameTestTemplate(false)
public final class CarrierNameGameTest {

    @GameTest(template = "empty")
    public void everyComponentIsNamedByItsOwnItem(GameTestHelper helper) {
        for (ComponentType type : ComponentType.values()) {
            Item item = HostRules.itemFor(type).getItem();
            String id = item.getDescriptionId();
            helper.assertTrue(id.startsWith("item.") || id.startsWith("block."),
                    type + ": a name has to be an item or block translation key, got " + id);
            helper.assertTrue(id.equals(CarrierMessages.itemKey(type)),
                    type + ": the wrench shows '" + CarrierMessages.itemKey(type)
                            + "' where the item it turns back into is registered as '" + id
                            + "' - a player would be shown the raw key");
        }
        helper.succeed();
    }
}
