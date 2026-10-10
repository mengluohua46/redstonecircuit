package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Both doors that change a component's state by hand ask the goggles first.
 *
 * <p>"Global" is what these guards check: the rule is asked at the two gestures there are, not on the components. A
 * world host is worked by {@code InnerRedstoneInteraction#tryToggle}, a host riding a machine by
 * {@code CarrierInteraction#handleSwitchClick}, and those two are the only places any component's state is changed by
 * hand - so a component added later is gated because it is worked through them, not because somebody remembered to
 * list it.
 *
 * <p>What has to stay true: the check stands <b>above</b> the state change, since gating after
 * {@code InnerSwitches.toggle} or {@code CarriedSwitch.apply} would refuse the message and keep the change; the
 * decision is the shared {@code logic.InnerAccess} rule, the shared {@code RCRegistry#isWearingGoggles} question the
 * renderer asks, and the shared whitelist {@code InnerSwitches#isHandSwitch}, so "cannot see inside" and "cannot work
 * inside" never disagree; and it is <b>silent</b>, writing no message at all - the lever and the button never reach
 * it, and for the rest a player who cannot read the value is not told about it.
 */
class InnerAdjustGogglesTest {

    @Test
    @DisplayName("a bare hand on a world host is refused before the component is touched, and silently")
    void theWorldHostAsksFirst() throws IOException {
        String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                "InnerRedstoneInteraction.java");
        String toggle = bodyOf(source, "private boolean tryToggle(");

        int gate = toggle.indexOf("refuseInnerAdjust(player, InnerSwitches.isHandSwitch(slot.type))");
        int change = toggle.indexOf("InnerSwitches.toggle(level, pos)");
        assertTrue(gate > 0 && change > gate,
                "the goggles gate stands above the state change: below it, the refusal would arrive after the delay "
                        + "had already stepped");
        assertTrue(toggle.contains("InnerSwitches.isWorkable(slot.type)"),
                "and only a component with a bare-hand action is gated at all: a host holding a wire has to stay "
                        + "\"not our click\", or another mod's interaction on it would be eaten by a rule about a delay "
                        + "setting that is not there");

        String refuse = bodyOf(source, "public static boolean refuseInnerAdjust(");
        assertTrue(refuse.contains("InnerAccess.mayAdjust(RCConfig.innerAdjustNeedsGoggles(), wearsGoggles(player), "
                        + "handSwitch)"),
                "the decision is the one shared rule: the config setting, the same wearer question the renderer and the "
                        + "Jade overlay ask, and the whitelist as its third term");
        assertTrue(!refuse.contains("message(") && !refuse.contains("displayClientMessage"),
                "and the refusal says nothing at all - there is no message and no feedback of any kind");
    }

    @Test
    @DisplayName("the carried host is refused by the same rule and the same whitelist")
    void theCarriedHostAsksTheSameRule() throws IOException {
        String source = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierInteraction.java");
        String switchClick = bodyOf(source, "private static boolean handleSwitchClick(");

        int gate = switchClick.indexOf("refuseInnerAdjust(player, InnerSwitches.isHandSwitch(before.type))");
        int apply = switchClick.indexOf("CarriedSwitch.apply(");
        assertTrue(gate > 0 && apply > gate,
                "a host riding a machine is worked by the same gesture as one standing on the ground, so it is refused "
                        + "before CarriedSwitch writes the record - and because the one-gesture guard has already "
                        + "claimed this click, nothing below may run either");
        assertTrue(switchClick.contains("InnerSwitches.isHandSwitch(before.type)"),
                "with the same whitelist: a lever on a machine works without the goggles, exactly as it does on the "
                        + "ground");

        String refuse = bodyOf(source, "private static boolean refuseInnerAdjust(");
        assertTrue(refuse.contains("InnerAccess.mayAdjust(RCConfig.innerAdjustNeedsGoggles(), "
                        + "wearsGoggles(player), handSwitch)"),
                "and through the one shared decision itself - the config switch, the same wearer question the "
                        + "renderer asks, and the whitelist as its third term - so the carried half cannot drift from "
                        + "the world's own bare-hand door");
        assertTrue(switchClick.contains("CarrierEntryTrace.Outcome.REFUSED"),
                "and the trace says so even though the player is told nothing: the log is where 'why did my click do "
                        + "nothing' is answered");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        Path direct = Path.of("", parts);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
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
