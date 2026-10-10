package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Locale;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

/**
 * One quad of a shell face, decided once and drawn once.
 *
 * <p>The decision is here rather than inline in the drawing loop, so the loop, the frame counter and the tests
 * all consult the same function: a counter beside the loop reports what it counted, never whether geometry was
 * really emitted. Three things skip a quad - every colour resolving to zero (nothing is drawn at all), the
 * centre panel under a shader pack's opaque rule, and this quad's own colour being zero - and only the first is
 * the "a host was collected and emitted nothing" verdict the diagnostics exist to name.
 */
public final class CarrierGeometry {

    /** Quad 0 of a face is the centre panel; the other four are the border. */
    public static final int CENTRE_PANEL = 0;

    /**
     * The colours the debug switch substitutes, and they are deliberately ugly: a half-opaque magenta cube with
     * a white border is a shape no configuration produces by accident.
     */
    public static final int FORCED_TINT = 0x80FF00FF;
    public static final int FORCED_FRAME = 0xFFFFFFFF;

    /**
     * The ARGB a shell quad is drawn with, or {@code 0} when this quad is skipped - the single implementation of
     * the rule, which is what makes {@code shellQuads=0} a fact rather than an opinion. {@code quad} is one of
     * the face's quads, {@link #CENTRE_PANEL} for the panel; {@code solidFrames} leaves the panel out.
     */
    public static int shellQuadArgb(boolean withFrame, int quad, int tint, int frame,
                                    boolean solidFrames) {
        int argb = withFrame && quad > CENTRE_PANEL ? frame : tint;
        if (argb == 0 || (solidFrames && quad == CENTRE_PANEL)) {
            return 0;
        }
        return argb;
    }

    /**
     * Every gate that decides whether a host's geometry may be emitted at all - {@code tint}, {@code frame} and
     * {@code wireFrame} are the resolved colours, {@code overlay} and {@code goggles} the visibility gates, and
     * {@code forceEmit} the debug switch that overrides every <em>per-host</em> gate. One record rather than eight
     * loose fields, because the frame summary, {@code /rc carrier} and the tests all need the same set.
     */
    public record Gates(RCConfig.OverlayMode overlay, boolean goggles, boolean showInnerComponents,
                        int tint, int frame, int wireFrame, boolean solidFrames, boolean forceEmit) {

        /** The gates as the live config and the live player describe them. */
        public static Gates of(RCConfig.OverlayMode overlay, boolean goggles) {
            return new Gates(overlay, goggles, RCConfig.showInnerComponents(),
                    RCConfig.hostOverlayColor(), RCConfig.hostOverlayFrameColor(),
                    RCConfig.superconductorFrameColor(), ShaderCompat.vanillaGeometry(),
                    RCConfig.forceOverlayDraw());
        }

        /** True when the pass runs at all for this player: the mode, or the debug override. */
        public boolean active() {
            if (forceEmit) {
                return true;
            }
            return overlay == RCConfig.OverlayMode.ALWAYS
                    || (overlay == RCConfig.OverlayMode.GOGGLES && goggles);
        }

        /**
         * Whether the component inside is drawn. {@code showInnerComponents} is the only gate: switching the shell
         * off does not take the component with it.
         */
        public boolean drawsContents() {
            return forceEmit || showInnerComponents;
        }

        /**
         * Why the pass drew nothing at all, or {@code null} when it runs: {@code OFF} is a configuration, and
         * {@code GOGGLES} without the goggles is a player who has not put them on.
         */
        public String inactiveReason() {
            if (active()) {
                return null;
            }
            if (overlay == RCConfig.OverlayMode.OFF) {
                return "hostOverlayMode=OFF (the config, not a bug)";
            }
            return "hostOverlayMode=GOGGLES and the player is NOT wearing the redstone goggles "
                    + "(wearing=" + goggles + ")";
        }

        /** The border colour this host is drawn with: its own if it is a superconductor. */
        public int frameFor(Slot slot) {
            return slot != null && slot.type == ComponentType.SUPERCONDUCTOR ? wireFrame : frame;
        }

        /**
         * The panel colour actually used, which is the configured one unless the debug switch is on - and it
         * substitutes an obvious magenta rather than obeying a colour that means "draw nothing". {@link #shellEmptiness}
         * keeps reporting the <em>configured</em> zero.
         */
        public int effectiveTint() {
            return forceEmit && tint == 0 ? FORCED_TINT : tint;
        }

        /** The border colour actually used, with the debug switch's substitute when there is none. */
        public int effectiveFrame(Slot slot) {
            int configured = frameFor(slot);
            return forceEmit && configured == 0 ? FORCED_FRAME : configured;
        }

        /**
         * Why nothing can be emitted for this host, or {@code null} when something can - the sentence a host that
         * was collected and emitted nothing has to say so that it cannot be mistaken for "drawn".
         */
        public String shellEmptiness(Slot slot) {
            int hostFrame = frameFor(slot);
            if (tint == 0 && hostFrame == 0) {
                return "all shell colours are 0x00000000 (hostOverlayAlpha and "
                        + "hostOverlayFrameAlpha both resolved to 0 - nothing to draw)";
            }
            return null;
        }

        /** Why no component can be emitted for this host, or {@code null} when one can. */
        public String contentsEmptiness() {
            if (!drawsContents()) {
                return "showInnerComponents=false (the component inside is switched off; the shell is "
                        + "still drawn)";
            }
            return null;
        }

        /** One compact line for the frame summary: every switch that decides whether geometry exists. */
        public String describe() {
            return "overlayMode=" + overlay + " goggles=" + goggles + " active=" + active()
                    + " showInnerComponents=" + showInnerComponents
                    + " forceOverlayDraw=" + forceEmit
                    + " tint=" + hex(tint) + " frame=" + hex(frame) + " wireFrame=" + hex(wireFrame)
                    + " solidFrames=" + solidFrames;
        }

        /** The same, with the one sentence a reader needs when the pass drew nothing. */
        public String describeWithVerdict() {
            String reason = inactiveReason();
            return describe() + (reason == null ? "" : " *** THE OVERLAY IS OFF: " + reason + " ***");
        }
    }

    /** What one host's two halves actually emitted, and why not when they emitted nothing. */
    public record Emitted(int shellQuads, int contentQuads, String shellReason, String contentReason,
                          int hostsWithShell, int hostsWithContent, int hostsCollected,
                          String zeroReason) {

        /** The verdict for a frame, with the host counts the renderer measured. */
        public static Emitted of(int shellQuads, int contentQuads, int hostsWithShell,
                                 int hostsWithContent, int hostsCollected, String shellReason,
                                 String contentReason) {
            return new Emitted(shellQuads, contentQuads, shellReason, contentReason, hostsWithShell,
                    hostsWithContent, hostsCollected, "");
        }

        /**
         * The same, with the renderer's own answer to "zero shell quads, and why" - a sentence rather than another
         * counter, because a zero has several causes: a colour of zero, a host with no slot yet, a refused quad.
         */
        public static Emitted of(int shellQuads, int contentQuads, int hostsWithShell,
                                 int hostsWithContent, int hostsCollected, String shellReason,
                                 String contentReason, String zeroReason) {
            return new Emitted(shellQuads, contentQuads, shellReason, contentReason, hostsWithShell,
                    hostsWithContent, hostsCollected, zeroReason == null ? "" : zeroReason);
        }

        /**
         * The verdict for a pass that collected nothing, which still has to name its gates: an empty world and a
         * switched-off overlay look identical on screen.
         */
        public static Emitted none(String shellReason, String contentReason) {
            return new Emitted(0, 0, shellReason, contentReason, 0, 0, 0, "");
        }

        /** True when this host was collected and not one vertex came out of it. */
        public boolean nothing() {
            return shellQuads == 0 && contentQuads == 0;
        }

        /** Hosts that emitted something, counting a host that emitted only one of the two once. */
        public int hostsDrawn() {
            return Math.min(hostsCollected, Math.max(hostsWithShell, hostsWithContent));
        }

        /**
         * The alarming sentence: the host was collected and every quad was skipped. A reader who greps this log for
         * the word NOTHING has to find it here.
         */
        public String describe() {
            if (nothing()) {
                return "*** NOTHING EMITTED - the host was collected but every quad was skipped: "
                        + "shellQuads=0 (" + shellReason + "), contentQuads=0 (" + contentReason + ")"
                        + zeroReason;
            }
            StringBuilder sb = new StringBuilder("shellQuads=").append(shellQuads);
            if (shellQuads == 0 && shellReason != null) {
                sb.append(" (").append(shellReason).append(')');
            }
            sb.append(" contentQuads=").append(contentQuads);
            if (contentQuads == 0 && contentReason != null) {
                sb.append(" (").append(contentReason).append(')');
            }
            return sb.append(zeroReason).toString();
        }
    }

    /**
     * Which stage the carried geometry is flushed at, as a function of the shader path: with a shader pack the
     * carried geometry rides with the world path, and otherwise it is submitted late, after everything the
     * structure draws.
     *
     * <p>Under a pack our contents are written with a vanilla type, which <em>tests</em> depth, so by the late stage
     * the carried block's own faces are already in the depth buffer and every fragment of the component inside it
     * fails the test; the shell survives only because it is pushed a hair outside the block. Without a pack nothing
     * changes: {@link #LATE} is the value it has always had.
     */
    public enum StagePlan {

        /**
         * The stage the carried path has always used: submitted after everything the structure draws, and the only
         * value without a shader pack.
         */
        LATE("minecraft:after_translucent_blocks", "the late stage (after every structure the game draws)"),

        /**
         * With a shader pack: submitted with the world path, at {@code after_entities} - before Create's contraption
         * pass, and in the same window the world path's contents reach the frame in.
         */
        WITH_WORLD("minecraft:after_entities", "the world stage (with the world path, under a shader pack)");

        private final String stageName;
        private final String why;

        StagePlan(String stageName, String why) {
            this.stageName = stageName;
            this.why = why;
        }

        /** The stage as the log prints it, namespace and all; see {@code HostRenderPass.WORLD_STAGE_NAME}. */
        public String stageName() {
            return stageName;
        }

        /** Why this stage, in a few words, for the acceptance line. */
        public String why() {
            return why;
        }

        /** True when this is the one stage that also collects and draws the hosts standing in the world. */
        public boolean withWorld() {
            return this == WITH_WORLD;
        }

        /** <b>The decision.</b> With a shader pack the carried geometry rides with the world path. */
        public static StagePlan of(boolean vanillaGeometry) {
            return vanillaGeometry ? WITH_WORLD : LATE;
        }

        /**
         * True when this plan is the one the build used before the shader decision existed - the whole of the
         * "without a shader pack nothing changed" claim, since the type, the buffer and the submission order all
         * follow the stage.
         */
        public boolean sameAsBeforeShaders() {
            return this == LATE;
        }
    }

    /**
     * Which render type each half of the carried picture is written with, as a function of the shader path.
     *
     * <p>Under a pack both halves were vanilla's {@code cutout}, which is right for the <b>shell</b> - its
     * quad sits in front of the block and is therefore always visible - and wrong for the <b>contents</b>, whose
     * type ignores depth so that a component inside a block can be seen at all. So the shell takes vanilla's
     * identity where a pack is drawing, and the carried contents use the depth-ignoring type on both paths.
     */
    public enum RenderPlan {

        /** No shader pack: the build's own types, exactly as before. */
        OWN,

        /**
         * A pack is drawing: the shell takes vanilla's {@code cutout} identity for Iris, the contents
         * keep the depth-ignoring carried type.
         */
        SHADER_SHELL;

        /** The decision. Named for the half it is about, so a call site cannot read it as the other. */
        public static RenderPlan of(boolean vanillaGeometry) {
            return vanillaGeometry ? SHADER_SHELL : OWN;
        }

        /**
         * Whether the carried shell uses vanilla's {@code cutout} rather than this build's own type: true only
         * where a pack is drawing, so no call site has to re-ask the shader question.
         */
        public boolean vanillaShellType() {
            return this == SHADER_SHELL;
        }

        /**
         * Whether the carried <b>contents</b> use vanilla's {@code cutout}: always false, because the contents
         * ignore depth on both paths, which is what makes a component inside a block visible at all.
         */
        public boolean vanillaContentsType() {
            return false;
        }

        /** One phrase for the acceptance line. */
        public String describe() {
            return this == SHADER_SHELL
                    ? "shell=vanilla cutout (Iris identity), contents=depth-ignoring carried type"
                    : "shell=carried shell type, contents=depth-ignoring carried type";
        }
    }

    private CarrierGeometry() {
    }

    /** {@code 0x9FB6FFAA} for a log line: the ARGB a reader compares against the config. */
    public static String hex(int argb) {
        return String.format(Locale.ROOT, "0x%08X", argb);
    }
}
