package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Pins "was any geometry actually emitted, and if not, which gate refused it".</b>
 *
 * <h2>The defect this exists for</h2>
 * The author's Create bearing reported {@code carrier frame drew 1, carriedHosts=1} on every frame and
 * showed nothing at all. {@code drew} counted <em>hosts that were collected</em>, so a host whose every
 * quad was refused by a colour of zero - or by the shader pack's solid-frame rule, or by
 * {@code showInnerComponents} - was counted exactly like a host that filled the screen. There was no
 * number anywhere in the log that could tell the two apart, and the reason is that the counting and the
 * drawing were two different pieces of code.
 *
 * <p>They are one now: {@link CarrierGeometry#shellQuadArgb} is the single implementation of "is this
 * quad drawn", called by both shell paths and by the diagnostics, and the tests below count quads through
 * it exactly as the renderer does. A gate that stops anything from being emitted is asserted to say so in
 * as many words, because the sentence - not the count - is what a reader of a log needs.
 */
class CarrierGeometryTest {

    private static final int TINT = 0x96_9FB6FF;
    private static final int FRAME = 0xAA_E03020;
    private static final int WIRE_FRAME = 0xAA_FF8C00;

    private static Slot torch() {
        return new Slot(ComponentType.TORCH);
    }

    private static Slot superconducting() {
        return new Slot(ComponentType.SUPERCONDUCTOR);
    }

    private static CarrierGeometry.Gates gates(RCConfig.OverlayMode mode, boolean goggles, int tint,
                                               int frame, int wireFrame, boolean showInner,
                                               boolean solidFrames, boolean force) {
        return new CarrierGeometry.Gates(mode, goggles, showInner, tint, frame, wireFrame, solidFrames,
                force);
    }

    /** The default configuration with the goggles on: everything on, no shader pack. */
    private static CarrierGeometry.Gates normal() {
        return gates(RCConfig.OverlayMode.GOGGLES, true, TINT, FRAME, WIRE_FRAME, true, false, false);
    }

    /** How many quads the shell loop actually emits for one host, by the same rule the loop uses. */
    private static int shellQuadsPerHost(CarrierGeometry.Gates gates, Slot slot) {
        int tint = gates.effectiveTint();
        int hostFrame = gates.effectiveFrame(slot);
        boolean withFrame = hostFrame != 0;
        int quads = 0;
        for (int face = 0; face < HostShell.FACE_COUNT; face++) {
            for (int quad = 0; quad < HostShell.quadsPerFace(withFrame); quad++) {
                if (CarrierGeometry.shellQuadArgb(withFrame, quad, tint, hostFrame,
                        gates.solidFrames() && !gates.forceEmit()) != 0) {
                    quads++;
                }
            }
        }
        return quads;
    }

    // -------------------------------------------------------------- the shell --

    @Test
    @DisplayName("a bordered shell is five quads a face, and a borderless one is a single quad")
    void shellQuadCountsFollowTheBorder() {
        assertEquals(30, shellQuadsPerHost(normal(), torch()),
                "a shell with a border is a centre panel plus four rims per face");
        CarrierGeometry.Gates noBorder = gates(RCConfig.OverlayMode.GOGGLES, true, TINT, 0, 0, true,
                false, false);
        assertEquals(6, shellQuadsPerHost(noBorder, torch()),
                "with no border colour each face is one panel, not five quads");
    }

    @Test
    @DisplayName("a shader pack drops the centre panel and keeps the border")
    void solidFramesLeaveTheBorderOnly() {
        CarrierGeometry.Gates shaders = gates(RCConfig.OverlayMode.GOGGLES, true, TINT, FRAME,
                WIRE_FRAME, true, true, false);
        assertEquals(24, shellQuadsPerHost(shaders, torch()),
                "six centre panels are left out and all twenty-four rims stay");
        assertEquals(0, CarrierGeometry.shellQuadArgb(true, CarrierGeometry.CENTRE_PANEL, TINT, FRAME,
                true), "the centre panel is the quad the rule refuses");
        assertTrue(CarrierGeometry.shellQuadArgb(true, 1, TINT, FRAME, true) != 0,
                "the rims are still drawn, which is what keeps a block marked under a shader pack");
    }

    @Test
    @DisplayName("the debug override draws the whole tile set even with every colour at zero")
    void forceEmitOverridesThePerHostGates() {
        CarrierGeometry.Gates forced = gates(RCConfig.OverlayMode.GOGGLES, true, 0, 0, 0, true, true,
                true);
        // The override is about whether geometry reaches the buffer, so it substitutes unmistakable
        // colours rather than obeying a zero that means "draw nothing"; the gates still report the zero,
        // so the reader can see what the unforced frame would have done.
        assertNotNull(forced.shellEmptiness(torch()),
                "the gates still report that every colour is zero");
        assertTrue(forced.effectiveTint() != 0, "the override has to substitute a panel colour");
        assertTrue(forced.effectiveFrame(torch()) != 0, "the override has to substitute a border");
        assertEquals(30, shellQuadsPerHost(forced, torch()),
                "with the override on, the whole tile set is emitted whatever the colours say");
    }

    @Test
    @DisplayName("with the override off, the substituted colours are not used")
    void forceEmitDoesNotChangeANormalFrame() {
        CarrierGeometry.Gates normal = normal();
        assertEquals(TINT, normal.effectiveTint());
        assertEquals(FRAME, normal.effectiveFrame(torch()));
        assertEquals(0, gates(RCConfig.OverlayMode.GOGGLES, true, 0, 0, 0, true, false, false)
                .effectiveTint(), "an unforced frame keeps its configured zero");
    }

    @Test
    @DisplayName("all colours at zero is reported as 'nothing to draw', not as a silent zero")
    void allColoursZeroIsNamed() {
        CarrierGeometry.Gates dark = gates(RCConfig.OverlayMode.GOGGLES, true, 0, 0, 0, true, false,
                false);
        assertEquals(0, shellQuadsPerHost(dark, torch()), "nothing can be emitted");
        String reason = dark.shellEmptiness(torch());
        assertNotNull(reason, "a zero has to carry its reason");
        assertTrue(reason.contains("shell colours"), reason);
        assertTrue(reason.contains("hostOverlayAlpha"), reason);
    }

    @Test
    @DisplayName("a panel with no border still leaves the panel, and is not reported as empty")
    void transparentBorderIsNotAnEmptyShell() {
        CarrierGeometry.Gates noFrame = gates(RCConfig.OverlayMode.GOGGLES, true, TINT, 0, 0, true,
                false, false);
        assertEquals(6, shellQuadsPerHost(noFrame, torch()));
        assertNull(noFrame.shellEmptiness(torch()),
                "a shell with a panel and no border is drawn, so it must not be called empty");
    }

    @Test
    @DisplayName("a superconducting host uses its own border colour")
    void superconductorHasItsOwnFrame() {
        CarrierGeometry.Gates g = normal();
        assertEquals(WIRE_FRAME, g.frameFor(superconducting()));
        assertEquals(FRAME, g.frameFor(torch()));
        assertEquals(30, shellQuadsPerHost(g, superconducting()),
                "the extra border quads are emitted in orange");
    }

    // --------------------------------------------------------------- the gates --

    @Test
    @DisplayName("the pass is off with the goggles off in GOGGLES mode, and says which of the two")
    void inactiveReasonNamesTheConfiguration() {
        CarrierGeometry.Gates noGoggles = gates(RCConfig.OverlayMode.GOGGLES, false, TINT, FRAME,
                WIRE_FRAME, true, false, false);
        assertFalse(noGoggles.active());
        assertTrue(noGoggles.inactiveReason().contains("NOT wearing"), noGoggles.inactiveReason());

        CarrierGeometry.Gates off = gates(RCConfig.OverlayMode.OFF, true, TINT, FRAME, WIRE_FRAME, true,
                false, false);
        assertFalse(off.active());
        assertTrue(off.inactiveReason().contains("hostOverlayMode=OFF"), off.inactiveReason());

        assertTrue(normal().active());
        CarrierGeometry.Gates always = gates(RCConfig.OverlayMode.ALWAYS, false, TINT, FRAME,
                WIRE_FRAME, true, false, false);
        assertTrue(always.active(), "ALWAYS does not ask about the goggles");
    }

    @Test
    @DisplayName("the frame summary line carries every switch that decides whether geometry exists")
    void gatesDescribeCarriesTheSwitches() {
        String text = normal().describeWithVerdict();
        for (String expected : new String[] { "overlayMode=GOGGLES", "goggles=true", "active=true",
                "showInnerComponents=true", "forceOverlayDraw=false", "tint=0x", "frame=0x",
                "wireFrame=0x", "solidFrames=false" }) {
            assertTrue(text.contains(expected), "the gates line is missing " + expected + ": " + text);
        }
        assertTrue(gates(RCConfig.OverlayMode.GOGGLES, false, TINT, FRAME, WIRE_FRAME, true, false,
                false).describeWithVerdict().contains("THE OVERLAY IS OFF"),
                "an inactive pass has to say so on the line the reader is looking at");
        assertTrue(gates(RCConfig.OverlayMode.GOGGLES, true, TINT, FRAME, WIRE_FRAME, true, false, true)
                .describe().contains("forceOverlayDraw=true"),
                "the switch has to be visible on the line whether it is on or off");
    }

    @Test
    @DisplayName("the component inside is a separate gate from the shell")
    void contentsAreGatedSeparately() {
        CarrierGeometry.Gates noContents = gates(RCConfig.OverlayMode.GOGGLES, true, TINT, FRAME,
                WIRE_FRAME, false, false, false);
        assertTrue(noContents.active(), "the shell still draws when the inside is switched off");
        assertFalse(noContents.drawsContents());
        assertNotNull(noContents.contentsEmptiness());
        assertTrue(noContents.contentsEmptiness().contains("showInnerComponents=false"));
        assertNull(noContents.shellEmptiness(torch()), "the shell is unaffected");
        assertEquals(30, shellQuadsPerHost(noContents, torch()));
    }

    // ------------------------------------------------------- the frame verdict --

    @Test
    @DisplayName("a collected host with no geometry says NOTHING EMITTED, in as many words")
    void nothingEmittedIsSpelledOut() {
        CarrierGeometry.Gates dark = gates(RCConfig.OverlayMode.GOGGLES, true, 0, 0, 0, false, false,
                false);
        CarrierGeometry.Emitted emitted = CarrierGeometry.Emitted.of(0, 0, 0, 0, 1,
                dark.shellEmptiness(torch()), dark.contentsEmptiness());
        assertTrue(emitted.nothing());
        String line = emitted.describe();
        assertTrue(line.contains("NOTHING EMITTED"), line);
        assertTrue(line.contains("the host was collected but every quad was skipped"), line);
        assertTrue(line.contains("shellQuads=0"), line);
        assertTrue(line.contains("contentQuads=0"), line);
        assertTrue(line.contains("hostOverlayAlpha"), "the reason has to be in the message: " + line);
        assertTrue(line.contains("showInnerComponents=false"), line);
    }

    @Test
    @DisplayName("a frame that emitted something counts the hosts it drew, not the hosts it met")
    void hostsDrawnCountsGeometryNotCollection() {
        CarrierGeometry.Emitted drawn = CarrierGeometry.Emitted.of(30, 44, 2, 2, 5, null, null);
        assertEquals(2, drawn.hostsDrawn(), "only two of the five collected hosts emitted anything");
        assertFalse(drawn.nothing());
        assertTrue(drawn.describe().contains("shellQuads=30"), drawn.describe());
    }

    @Test
    @DisplayName("the hex helper prints the ARGB a reader compares against the config")
    void hexIsArgb() {
        assertEquals("0x969FB6FF", CarrierGeometry.hex(TINT));
        assertEquals("0x00000000", CarrierGeometry.hex(0));
    }

    @Test
    @DisplayName("no host at all still names its gates, so an empty frame is not a silent zero")
    void noneCarriesTheGates() {
        CarrierGeometry.Emitted none = CarrierGeometry.Emitted.none("all shell colours are 0",
                "showInnerComponents=false");
        assertTrue(none.nothing());
        assertTrue(none.describe().contains("NOTHING EMITTED"), none.describe());
        assertEquals(0, none.hostsDrawn());
    }
}
