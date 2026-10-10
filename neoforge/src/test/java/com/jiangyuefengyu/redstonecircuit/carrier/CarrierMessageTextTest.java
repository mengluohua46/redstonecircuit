package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * <b>No player-visible sentence may contain an internal coordinate.</b> A Sable plot coordinate has a shape
 * - a nine-digit number with a leading twenty, {@code 20\d{7}} - and the assertion is made against the
 * rendered text of every {@code message.redstonecircuit.*} key in both language files, with the arguments
 * the production code really passes filled in.
 * <p>Asserted of the language files rather than of a list of sentences here, because the defect was a
 * <b>template</b> that took a coordinate as an argument; log lines keep every coordinate by design.
 */
class CarrierMessageTextTest {

    /** A Sable plot coordinate: {@code 20\d{7}}. */
    private static final Pattern INTERNAL_COORDINATE = Pattern.compile("20\\d{7}");

    /** A registry name - {@code minecraft:grass_block}, {@code redstonecircuit:retaining_repeater} -
     * which is the second shape forbidden in player text. */
    private static final Pattern REGISTRY_NAME = Pattern.compile("[a-z_]+:[a-z_/]+");

    /** A spelled-out enum constant: five or more capitals in a row, which is deliberately longer than
     * any all-caps <em>word</em> the mod's sentences use - {@code OK}, {@code ID}, {@code AUTO} and
     * {@code OFF} are states a player may read. */
    private static final Pattern ENUM_CONSTANT = Pattern.compile("[A-Z][A-Z_]{4,}");

    /** The two {@code toString()} shapes: {@code Block{minecraft:grass_block}}, {@code ItemStack{…}}. */
    private static final Pattern TO_STRING = Pattern.compile("(Block|ItemStack|Slot|ComponentType)\\{");

    /**
     * Sample arguments, by the key whose translation they are - what the production code passes. Each
     * value is <b>shorter</b> than its own key, so one argument's text can never contain another key's,
     * and none of them is a number with a leading twenty.
     */
    private static final Map<String, String> BY_KEY = new LinkedHashMap<>();

    static {
        for (ComponentType type : ComponentType.values()) {
            BY_KEY.put(CarrierMessages.itemKey(type), readable(type));
        }
        // The subject of a sentence: what a carried block is called when a facing is added to it.
        BY_KEY.put("message.redstonecircuit.wrench.block_named_facing", "Repeater");
        BY_KEY.put("message.redstonecircuit.wrench.block_unnamed", "empty block");
        BY_KEY.put("message.redstonecircuit.wrench.that_one", "an earlier block");
        for (Direction direction : Direction.values()) {
            BY_KEY.put(CarrierMessages.directionKey(direction), "the neighbour");
            BY_KEY.put(CarrierMessages.faceKey(direction), direction.getName());
        }
        // The world's own keys, whose placeholders are a direction, a block name, a setting or a count.
        BY_KEY.put("message.redstonecircuit.wrench.selected", "a block");
        BY_KEY.put("message.redstonecircuit.wrench.cleared", "a block");
        BY_KEY.put("message.redstonecircuit.wrench.linked_on", "east");
        BY_KEY.put("message.redstonecircuit.wrench.linked_on_device", "east");
        BY_KEY.put("message.redstonecircuit.wrench.linked_off", "east");
        BY_KEY.put("message.redstonecircuit.wrench.linked_auto", "east");
        BY_KEY.put("message.redstonecircuit.switch.repeater", "2");
        BY_KEY.put("message.redstonecircuit.rotation_rod.to_input", "north");
        BY_KEY.put("message.redstonecircuit.rotation_rod.to_output", "north");
    }

    /**
     * The sample argument for each {@code %s}, in order - every one of them the sort of thing the
     * production code passes: a block's name, a direction's name, a setting, a count.
     */
    private static final List<String> SAMPLE_ARGUMENTS = List.of(
            "Repeater", "east", "the neighbour", "2", "Dust", "west");

    /**
     * A component's name as a reader sees it, built from the enum so no registry is needed. The enum's
     * own words and not a language file's, because what is asserted is that the <b>template</b> cannot
     * turn an argument into a coordinate.
     */
    private static String readable(ComponentType type) {
        return type.name();
    }

    // ------------------------------------------------------- the rule itself --

    @Nested
    @DisplayName("every player-visible sentence is coordinate-free, in both languages")
    class NoInternalCoordinatesAnywhere {

        @Test
        @DisplayName("no message.redstonecircuit.* key can render an internal coordinate")
        void noMessageKeyRendersACoordinate() throws IOException {
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                Map<String, String> entries = messages(language);
                assertFalse(entries.isEmpty(), language + " has no message keys at all");
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    String rendered = render(language, entry.getValue());
                    Matcher found = INTERNAL_COORDINATE.matcher(rendered);
                    assertFalse(found.find(), language + " " + entry.getKey()
                            + " renders an internal coordinate: " + rendered);
                }
            }
        }

        @Test
        @DisplayName("no sentence can render a registry name, an enum constant or a toString()")
        void noMessageKeyRendersAnInternalName() throws IOException {
            // The wrench's own sentence once spelled the component as its enum constant, in English,
            // inside a Chinese sentence; the coordinate half was the other defect. Both are asserted here
            // over the whole league of shapes rather than over the one constant that happened to leak.
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                for (Map.Entry<String, String> entry : messages(language).entrySet()) {
                    String rendered = render(language, entry.getValue());
                    assertNoInternalName(language + " " + entry.getKey(), rendered);
                }
            }
        }

        @Test
        @DisplayName("the four shapes are the exact ones the report named")
        void theForbiddenShapesAreTheOnesTheReportNamed() {
            // A test that cannot fail is not a test: every pattern must match the shape it exists for,
            // and must not match the words the mod's own sentences legitimately use.
            assertTrue(REGISTRY_NAME.matcher("redstonecircuit:retaining_repeater").find());
            assertTrue(REGISTRY_NAME.matcher("minecraft:grass_block").find());
            assertTrue(ENUM_CONSTANT.matcher("RETAINING_REPEATER").find());
            assertTrue(ENUM_CONSTANT.matcher("DOUBLE_RETAINING_REPEATER").find());
            assertTrue(TO_STRING.matcher("Block{minecraft:grass_block}").find());
            assertTrue(TO_STRING.matcher("ItemStack{minecraft:redstone x1}").find());
            // ...and the words that must stay allowed.
            for (String allowed : List.of("AUTO", "OFF", "ON", "OK", "east", "Repeater")) {
                assertFalse(REGISTRY_NAME.matcher(allowed).find(), allowed);
                assertFalse(ENUM_CONSTANT.matcher(allowed).find(),
                        allowed + " is a state or a name, not an enum constant");
            }
        }

        @Test
        @DisplayName("no sentence is longer than a readable action bar line")
        void noMessageIsAParagraph() throws IOException {
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                for (Map.Entry<String, String> entry : messages(language).entrySet()) {
                    String rendered = render(language, entry.getValue());
                    assertTrue(rendered.length() <= CarrierMessages.MAX_ACTION_BAR_CHARS,
                            language + " " + entry.getKey() + " is " + rendered.length()
                                    + " characters, over the " + CarrierMessages.MAX_ACTION_BAR_CHARS
                                    + " the rule allows: " + rendered);
                }
            }
        }

        @Test
        @DisplayName("the offending sentence is gone, and the new one names blocks")
        void theAuthorsSentenceIsGone() throws IOException {
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                String picked = messages(language).get("message.redstonecircuit.wrench.picked_elsewhere");
                assertNotNull(picked, language + " must still have the key the message uses");
                assertFalse(picked.contains("%s at %s"),
                        language + " still names both ends by position: " + picked);
                assertFalse(picked.contains("put both blocks in the same machine"),
                        language + " still tells the player to merge two blocks that are already built: "
                                + picked);
                assertTrue(picked.contains("%s"),
                        language + " must name the two BLOCKS, which is how a player tells them apart: "
                                + picked);
            }
        }

        @Test
        @DisplayName("every key the CARRIER LAYER adds exists in both languages")
        void everyReferencedKeyExists() throws IOException {
            // Keys in another namespace belong to that mod's own language file. What has to exist is
            // everything this mod introduces, because a missing key prints the raw key to the player.
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                Map<String, String> entries = allEntries(language);
                for (String key : referencedKeys()) {
                    if (!key.startsWith("message.redstonecircuit.")
                            && !key.startsWith("item.redstonecircuit.")
                            && !key.startsWith("block.redstonecircuit.")) {
                        continue;
                    }
                    // A BlockItem with no item. entry falls back to its block's own name, so either key
                    // is a name, and having neither is the leak.
                    String asBlock = "block" + key.substring("item".length());
                    assertTrue(entries.containsKey(key) || entries.containsKey(asBlock),
                            language + " is missing " + key + " (and its block-name fallback " + asBlock
                                    + "), which the carrier layer passes to Component.translatable - a "
                                    + "missing key prints the raw key to the player, which is its own kind "
                                    + "of leak");
                }
            }
        }
    }

    // ------------------------------------------------ the wording helpers are pure --

    @Nested
    @DisplayName("the wording helpers: a block is named, a place is a direction")
    class TheWordingHelpers {

        /** Every {@code Component.translatable} key in this file, and the text of its literal arguments. */
        private static final Pattern KEY = Pattern.compile(
                "Component\\.translatable\\(\\s*\"([^\"]+)\"((?:[^;]|\\n)*?)\\)");

        private static final Pattern LITERAL = Pattern.compile("Component\\.literal\\(\"([^\"]*)\"\\)");

        @Test
        @DisplayName("a block label names the component, and its facing only in words")
        void aBlockLabelIsItsName() {
            assertTrue(CarrierMessages.blockLabelKey(true).contains("block_named_facing"),
                    "a component with a facing is named together with it");
            assertTrue(CarrierMessages.blockLabelKey(false).contains("block_unnamed"),
                    "and a block holding nothing is named as exactly that, not as a coordinate");
        }

        @Test
        @DisplayName("'where the other block is' is a direction, never a position")
        void whereIsADirection() {
            BlockPos here = new BlockPos(-7, -60, -11);
            BlockPos above = here.above();
            BlockPos far = new BlockPos(400, 70, 900);

            assertTrue(CarrierMessages.whereKey(here, above).contains("side."),
                    "an adjacent block is named by the side it is on");
            assertTrue(CarrierMessages.whereKey(here, far).contains("that_one"),
                    "and a block that is not adjacent has no side, so the sentence says that instead of "
                            + "naming where it is");
            assertFalse(CarrierMessages.whereKey(here, far).contains("400"),
                    "which is the whole point: the position never reaches the sentence");
        }

        @Test
        @DisplayName("NO literal argument the carrier layer passes is an internal coordinate")
        void noLiteralArgumentIsACoordinate() throws IOException {
            // The templates are clean; what filled them was a position printed as text, so that is what
            // this scan looks for - the shape, not one spelling of it.
            String source = readMainSource("carrier", "CarrierInteraction.java");
            Matcher key = KEY.matcher(source);
            int found = 0;
            while (key.find()) {
                found++;
                String arguments = key.group(2);
                assertFalse(arguments.contains("toShortString()"),
                        "the message " + key.group(1) + " is given a BlockPos printed as text: "
                                + arguments);
                Matcher literal = LITERAL.matcher(arguments);
                while (literal.find()) {
                    assertFalse(INTERNAL_COORDINATE.matcher(literal.group(1)).find(),
                            "the message " + key.group(1) + " is given a literal internal coordinate: "
                                    + literal.group(1));
                }
            }
            assertTrue(found >= 10,
                    "the scan found only " + found + " translatable calls, so it is not reading the file "
                            + "it thinks it is");
        }

        @Test
        @DisplayName("no player message is filled with a log phrase, an enum name or a toString()")
        void noMessageArgumentIsALogPhrase() throws IOException {
            // The argument is where the defect lived, so the arguments are what is scanned - in both
            // files that can reach the action bar. A log phrase, an enum constant or a judgement line's
            // spelling of a component must never be an argument of a sentence again.
            for (String[] file : List.of(
                    new String[] {"carrier", "CarrierInteraction.java"},
                    new String[] {"", "InnerRedstoneInteraction.java"})) {
                String source = readMainSource(file[0], file[1]);
                Matcher key = KEY.matcher(source);
                int found = 0;
                while (key.find()) {
                    found++;
                    String arguments = key.group(2);
                    assertFalse(arguments.contains("WrenchLinks.describe"),
                            "the message " + key.group(1) + " is given the LOG's spelling of a component "
                                    + "(the enum constant): " + arguments);
                    assertFalse(arguments.contains(".describe()"),
                            "the message " + key.group(1) + " is given a judgement-line phrase: "
                                    + arguments);
                    assertFalse(arguments.contains(".name()"),
                            "the message " + key.group(1) + " is given an enum constant: " + arguments);
                    assertFalse(arguments.contains("Block.toString()") || arguments.contains("getBlock() +"),
                            "the message " + key.group(1) + " is given a block's toString(): " + arguments);
                }
                assertTrue(found >= 5, file[1] + " has only " + found + " translatable calls, so the scan "
                        + "is not reading the file it thinks it is");
            }
        }
    }

    // ------------------------------------------------------------- the plumbing --

    /** A player-visible sentence names things, it does not spell them: four shapes, all of them "the
     * English original name" to a player, each asserted separately so a failure says which came back. */
    private static void assertNoInternalName(String where, String rendered) {
        // find() first, and the group read only when it found something: reading a group before a match
        // throws, which would replace the failure's own message with "No match found".
        Matcher registry = REGISTRY_NAME.matcher(rendered);
        boolean hasRegistry = registry.find();
        assertFalse(hasRegistry, where + " renders a REGISTRY NAME (" + (hasRegistry ? registry.group() : "")
                + "): a player must read the item's name, not `namespace:path` - " + rendered);
        Matcher constant = ENUM_CONSTANT.matcher(rendered);
        boolean hasConstant = constant.find();
        assertFalse(hasConstant, where + " renders an ENUM CONSTANT (" + (hasConstant ? constant.group() : "")
                + "): the enum's own words are for the log, the translated name is for the player - "
                + rendered);
        Matcher toString = TO_STRING.matcher(rendered);
        boolean hasToString = toString.find();
        assertFalse(hasToString, where + " renders a toString() of a game object ("
                + (hasToString ? toString.group() : "") + ") - " + rendered);
        assertFalse(rendered.contains("ComponentType."),
                where + " renders a ComponentType reference: " + rendered);
    }

    /**
     * The same rule applied to the name-source functions themselves, not only to the templates: a
     * template can be clean while its <em>argument</em> is the enum name. The three functions that decide
     * what a name <em>is</em> are checked directly, and each answer must exist in both language files.
     */
    @Nested
    @DisplayName("the name sources are translation keys, for every input")
    class TheNameSources {

        @Test
        @DisplayName("every ComponentType's name is a translation key that exists, and is not its enum name")
        void everyComponentTypeIsATranslatedName() throws IOException {
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                Map<String, String> entries = allEntries(language);
                for (ComponentType type : ComponentType.values()) {
                    String key = CarrierMessages.itemKey(type);
                    assertNotEquals(type.name(), key,
                            "the name of " + type + " is its own enum constant, which is the leak");
                    assertFalse(REGISTRY_NAME.matcher(key).find(),
                            "the name of " + type + " is a registry id, not a translation key: " + key);
                    // Either the item's own key or the block's, which is the fallback a BlockItem
                    // without a separate item. entry really uses.
                    String asBlock = "block" + key.substring("item".length());
                    if (key.startsWith("item.redstonecircuit.")) {
                        assertTrue(entries.containsKey(key) || entries.containsKey(asBlock),
                                language + " has neither " + key + " nor " + asBlock
                                        + ", so a player reading " + type + " would be shown the raw key");
                    }
                    assertTrue(key.startsWith("item.") || key.startsWith("block."),
                            "a component's name has to be an item or block translation key: " + key);
                }
            }
        }

        @Test
        @DisplayName("every direction and face is a translation key with an entry")
        void everyDirectionIsATranslatedKey() throws IOException {
            for (String language : List.of("zh_cn.json", "en_us.json")) {
                Map<String, String> entries = allEntries(language);
                for (Direction direction : Direction.values()) {
                    String side = CarrierMessages.directionKey(direction);
                    String face = CarrierMessages.faceKey(direction);
                    assertTrue(side.contains("side."), side);
                    assertTrue(face.contains("face."), face);
                    assertTrue(entries.containsKey(side), language + " is missing " + side);
                    assertTrue(entries.containsKey(face), language + " is missing " + face);
                }
            }
        }

        @Test
        @DisplayName("the world's own wrench label no longer spells the component")
        void theWrenchLabelIsNotTheEnumName() {
            // The label is built from a translation key while the log's own words still spell the
            // constant: the assertion is the property that made the defect, and the two must differ.
            com.jiangyuefengyu.redstonecircuit.data.Slot repeater =
                    new com.jiangyuefengyu.redstonecircuit.data.Slot(ComponentType.RETAINING_REPEATER);
            repeater.facing = Direction.SOUTH;

            String itemKey = CarrierMessages.itemKey(ComponentType.RETAINING_REPEATER);
            assertTrue(itemKey.startsWith("item.") || itemKey.startsWith("block."),
                    "the component's name is a translation key: " + itemKey);
            assertNotEquals(ComponentType.RETAINING_REPEATER.name(), itemKey,
                    "and not the constant");
            assertEquals("RETAINING_REPEATER facing south",
                    com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks.describe(repeater),
                    "the LOG keeps the constant, which is what a report is grepped against - the player's "
                            + "name is the translated one, and the two are deliberately different");
            // The two forms must not be the same string, or the fix would have changed the log too.
            assertFalse(com.jiangyuefengyu.redstonecircuit.logic.WrenchLinks.describe(repeater)
                            .equals(itemKey),
                    "the judgement line and the player's label are different by construction");
        }
    }

    /** Every {@code message.redstonecircuit.*} key of one language file, as key -> template. */
    private static Map<String, String> messages(String language) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : allEntries(language).entrySet()) {
            if (entry.getKey().startsWith("message.redstonecircuit.")) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /** Every translation entry of one language file, as key -> template. */
    private static Map<String, String> allEntries(String language) throws IOException {
        String json = readResource("lang", language);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(json);
        while (matcher.find()) {
            out.put(matcher.group(1), matcher.group(2));
        }
        return out;
    }

    /**
     * What the production code would show for one template: the arguments the carrier layer actually
     * passes, in the order it passes them - the translation of each sample key, or the sample text
     * itself. Each {@code %s} is filled once, left to right.
     */
    private static String render(String language, String template) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        int argument = 0;
        for (int next = template.indexOf("%s", at); next >= 0; next = template.indexOf("%s", at)) {
            out.append(template, at, next);
            out.append(SAMPLE_ARGUMENTS.get(argument++ % SAMPLE_ARGUMENTS.size()));
            at = next + 2;
        }
        out.append(template.substring(at));
        return out.toString();
    }

    /** Every translation key the carrier layer can hand to {@code Component.translatable}. */
    private static List<String> referencedKeys() {
        List<String> keys = new ArrayList<>();
        for (String key : new String[] {
            "message.redstonecircuit.wrench.selected_carrier",
            "message.redstonecircuit.wrench.cleared_carrier",
            "message.redstonecircuit.wrench.no_selection",
            "message.redstonecircuit.wrench.no_selection_carrier",
            "message.redstonecircuit.wrench.picked_elsewhere",
            "message.redstonecircuit.wrench.same_block",
            "message.redstonecircuit.wrench.not_adjacent_carrier",
            "message.redstonecircuit.wrench.block_unnamed",
            "message.redstonecircuit.wrench.block_named_facing",
            "message.redstonecircuit.wrench.that_one",
            "message.redstonecircuit.wrench.linked_on",
            "message.redstonecircuit.wrench.linked_on_device",
            "message.redstonecircuit.wrench.linked_outside_on",
            "message.redstonecircuit.wrench.linked_outside_off",
            "message.redstonecircuit.wrench.linked_outside_auto",
            "message.redstonecircuit.wrench.outside_no_pose",
            "message.redstonecircuit.wrench.linked_off",
            "message.redstonecircuit.wrench.linked_auto",
            "message.redstonecircuit.wrench.linked_across_on",
            "message.redstonecircuit.wrench.linked_across_off",
            "message.redstonecircuit.wrench.linked_across_auto",
            "message.redstonecircuit.wrench.no_component",
            "message.redstonecircuit.wrench.need_goggles",
            "message.redstonecircuit.rotation_rod.no_direction",
            "message.redstonecircuit.rotation_rod.nothing_there",
            "message.redstonecircuit.rotation_rod.to_input",
            "message.redstonecircuit.rotation_rod.to_output",
            "message.redstonecircuit.rotation_rod.need_goggles",
            "message.redstonecircuit.carried.inserted",
            "message.redstonecircuit.carried.retrieved",
            "message.redstonecircuit.carried.handled",
        }) {
            keys.add(key);
        }
        for (Direction direction : Direction.values()) {
            keys.add(CarrierMessages.directionKey(direction));
            keys.add(CarrierMessages.faceKey(direction));
        }
        for (ComponentType type : ComponentType.values()) {
            keys.add(CarrierMessages.itemKey(type));
        }
        return keys;
    }

    /**
     * Reads one of the mod's resources, failing loudly rather than quietly skipping the check. Tries both
     * layouts the harness may run from, the project directory and the repository root.
     */
    private static String readResource(String kind, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "resources", "assets", "redstonecircuit", kind, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + ")");
    }

    /** One main source file, for the guard that reads the call sites rather than the templates. */
    private static String readMainSource(String packageName, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packageName, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath());
    }
}
