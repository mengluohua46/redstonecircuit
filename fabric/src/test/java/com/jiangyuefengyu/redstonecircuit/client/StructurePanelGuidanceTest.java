package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The panel page says why its 添加 button cannot be pressed - and asks the right side whether a region is up.
 *
 * <h2>What went wrong, twice</h2>
 * A cell is stored <b>selection-local</b>: the boxes name a world position and the block keeps it relative to the
 * region's corner, so with no region up there is no coordinate the entry could mean. The two table pages said so in
 * red in place of their hint; the key panel did not - it filled its cell boxes with 0/0/0 and left the refusal to the
 * action bar, which reads as "adding keys does not work" rather than "pick a region first".
 *
 * <p>Then the first fix disabled the button by asking the <em>client's block entity</em> whether it was previewing -
 * and a controller's preview is deliberately <b>not</b> in its sync tag (a border is visible to everyone in the
 * dimension through its own message), so on a client that question is always answered "no" and the button stayed
 * locked with the border right there in front of the player. The border cache is the client's copy of the same fact,
 * and it is what the button must ask.
 *
 * <p>So four things are pinned here, because each of them alone leaves one of those doors open: the button follows
 * the border, no page asks the block entity, the page draws the reason where its hint goes, and both languages carry
 * that reason.
 *
 * <p>Two of the four are about wording as much as wiring, since the key panel submits a <b>6×6 grid of buttons</b>: its
 * one button is labelled with the verb it would do (add, or update the selected entry), and the hint's colour says what
 * the button can do - red with no region up, blue on a selected entry, grey otherwise.
 */
class StructurePanelGuidanceTest {

    @Test
    @DisplayName("with no region the add button is disabled and the page says why")
    void theAddButtonNeedsARegion() throws IOException {
        String screen = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "StructureBlockScreen.java");

        assertTrue(screen.contains("this.addButton.active = border() != null;"),
                "an add button is only usable while a region is showing, and the border cache is where a client "
                        + "learns that");
        assertFalse(screen.contains("isPreviewing()"),
                "and never from the client's own block entity: a controller's preview is not in its sync tag, so "
                        + "there it is always false - the button was locked for ever once already that way");

        for (String page : List.of("faces.add", "output.add")) {
            assertTrue(screen.contains("this.addButton = addRenderableWidget(Button.builder(text(\"" + page + "\")"),
                    "every page that names a cell owns the one button: " + page);
        }
        assertTrue(screen.contains("this.addButton = addRenderableWidget(Button.builder(commitLabel()"),
                "and the key panel owns the same one button, labelled with the verb it would do: on a 6x6 grid of "
                        + "buttons the add button is the one that either adds a new entry or writes the selected one back");

        assertTrue(screen.contains("hint = text(\"panel.noregion\").getString();"),
                "and the key panel replaces its hint with the reason, the way the two table pages do");
        assertTrue(screen.contains("colour = 0xFF5555;") && screen.contains("colour = 0x808080;"),
                "in the same red the other pages use, because it is the same refusal - and grey while it does work, "
                        + "which is what \"the colour follows whether the button can work\" means");
    }

    @Test
    @DisplayName("both languages carry that reason")
    void theReasonIsTranslated() throws IOException {
        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src", "main", "resources", "assets", "redstonecircuit", "lang", lang);
            assertTrue(text.contains("\"redstonecircuit.structure.screen.panel.noregion\""),
                    "a missing key shows as its own name in the game, which is not an explanation: " + lang);
        }
    }

    /** One of the mod's own files, addressed from this project's directory. */
    private static String read(String... parts) throws IOException {
        Path relative = Path.of("", parts);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}
