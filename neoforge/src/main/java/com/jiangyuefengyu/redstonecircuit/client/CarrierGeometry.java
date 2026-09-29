package com.jiangyuefengyu.redstonecircuit.client;

import java.util.Locale;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

/**
 * <b>One quad of a shell face, decided once and drawn once.</b>
 *
 * <p>The decision is here rather than inline in the drawing loop for the reason the whole diagnostics
 * round exists: {@code carrier frame drew 1} counted <em>hosts</em>, so a host that was collected and
 * then emitted no geometry at all - every quad refused by a colour of zero, by the shader pack's
 * solid-frame rule, by the layer table - read exactly like a host that was drawn. The way to make that
 * impossible is not another counter beside the loop; it is for the loop and the counter to consult the
 * same function.
 *
 * <h2>The three skips, and what each one means on screen</h2>
 * <ul>
 *   <li>{@code tint == 0 && frame == 0 && wireFrame == 0} - <b>nothing is drawn at all</b>. Every
 *       colour resolved to zero, which is what {@code hostOverlayAlpha=0} plus
 *       {@code hostOverlayFrameAlpha=0} means. There is no shell to see and no bug in the renderer.</li>
 *   <li>{@code solidFrames && quad == 0} - the centre panel is left out. A shader pack is drawing, our
 *       shell has to use vanilla's opaque cutout type, and a translucent centre panel drawn opaquely
 *       would be a plate that hides the component inside it. The four rims are still drawn, so the
 *       block is still marked.</li>
 *   <li>{@code argb == 0} - this particular quad's colour is zero: the panel when the tint alpha is
 *       zero, the rims when the frame alpha is. The other quads are unaffected.</li>
 * </ul>
 *
 * <p>Only the first of those three produces the failure this round is about - a collected host with
 * zero geometry - which is why {@link Shape#NOTHING_EMITTED} exists as a verdict rather than just as a
 * sum.
 */
public final class CarrierGeometry {

    /** Quad 0 of a face is the centre panel; the other four are the border. */
    public static final int CENTRE_PANEL = 0;

    /**
     * The colours the debug switch substitutes, and they are deliberately ugly.
     *
     * <p>A frame drawn with {@code forceOverlayDraw=true} must be unmistakable on screen: a half-opaque
     * magenta cube with a white border is a shape no configuration produces by accident, so "I turned
     * the debug switch on and now I can see it" and "I turned the debug switch on and I still cannot"
     * are two different observations rather than two readings of the same colour.
     */
    public static final int FORCED_TINT = 0x80FF00FF;
    public static final int FORCED_FRAME = 0xFFFFFFFF;

    /**
     * The ARGB a shell quad is drawn with, or {@code 0} when this quad is skipped.
     *
     * <p>The single implementation of the rule, called by both the world shell and the carried shell -
     * and by the diagnostics, which is what makes {@code shellQuads=0} a fact rather than an opinion.
     *
     * @param withFrame whether this host has a border colour at all
     * @param quad which of the face's quads, {@link #CENTRE_PANEL} for the panel
     * @param tint the panel colour, {@code 0} when the shell has no panel
     * @param frame the border colour, {@code 0} when there is no border
     * @param solidFrames true while a shader pack is drawing, which leaves the panel out
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
     * Every gate that decides whether a host's geometry may be emitted at all.
     *
     * <p>One record rather than eight loose fields because the same set is needed by the renderer's
     * frame summary, by {@code /rc carrier} and by the tests, and a set that has to be kept in step by
     * hand is how a diagnostic starts reporting on something other than what was drawn.
     *
     * @param overlay what {@code hostOverlayMode} resolved to, before the goggles are considered
     * @param goggles whether the local player is wearing the redstone goggles
     * @param showInnerComponents {@code showInnerComponents}: whether the component inside is drawn
     * @param tint the shell panel colour, or {@code 0} when it resolves to nothing
     * @param frame the ordinary border colour, or {@code 0}
     * @param wireFrame the superconducting border colour, or {@code 0}
     * @param solidFrames true while a shader pack is drawing, i.e. {@code ShaderCompat.vanillaGeometry()}
     * @param forceEmit {@code forceOverlayDraw}: the debug switch that overrides every <em>per-host</em>
     *     gate, so "the config switched it off" and "it was drawn and not seen" can be told apart in one
     *     run
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
         * Whether the component inside is drawn.
         *
         * <p>{@code showInnerComponents} is the only gate: the shell being switched off does not take
         * the component with it, which is exactly how a player ends up with a block whose inside is
         * visible and whose frame is missing.
         */
        public boolean drawsContents() {
            return forceEmit || showInnerComponents;
        }

        /**
         * Why the pass drew nothing at all, or {@code null} when it runs.
         *
         * <p>The sentence a reader needs when the frame summary says zero: {@code OFF} is a
         * configuration, {@code GOGGLES} without the goggles is a player who has not put them on, and
         * either one looks identical to a bug from inside the game.
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
         * The panel colour actually used, which is the configured one unless the debug switch is on.
         *
         * <p>{@code forceOverlayDraw} has to be able to draw something even when every colour is zero -
         * that is the whole question it exists to answer, "does geometry reach the buffer at all" - so
         * it substitutes an obvious magenta rather than obeying a colour that means "draw nothing".
         * {@link #shellEmptiness} deliberately keeps reporting the <em>configured</em> zero, so the
         * reader can still see what the unforced frame would have done.
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
         * Why nothing can be emitted for this host, or {@code null} when something can.
         *
         * <p>Used for one host at a time in the frame report, and the wording is deliberately the
         * sentence the acceptance procedure looks for: a host that was collected and emitted nothing
         * has to say so in a way that cannot be mistaken for "drawn".
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
         * The same, with the renderer's own answer to "zero shell quads, and why".
         *
         * <p>An extra field rather than another two counters in {@link #describe()}, because it is the
         * one question a reader asks about a zero and the answer is a sentence, not a number: a colour
         * of zero, a host with no slot yet, or a colour rule that refused every quad. See
         * {@code HostRenderPass.Counter#zeroShellReason}.
         */
        public static Emitted of(int shellQuads, int contentQuads, int hostsWithShell,
                                 int hostsWithContent, int hostsCollected, String shellReason,
                                 String contentReason, String zeroReason) {
            return new Emitted(shellQuads, contentQuads, shellReason, contentReason, hostsWithShell,
                    hostsWithContent, hostsCollected, zeroReason == null ? "" : zeroReason);
        }

        /**
         * The verdict for a pass that collected nothing, which still has to name its gates.
         *
         * <p>An empty world and a switched-off overlay look identical on screen; this is the value
         * that makes the frame summary say which one it was.
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
         * The alarming sentence: the host was collected, and every quad was skipped.
         *
         * <p>Written the long way round on purpose. {@code drew=1, shellQuads=0, contentQuads=0} is the
         * exact shape of the author's report - the log says a host was handled and the screen shows
         * nothing - and a reader who greps this log for the word NOTHING has to find it here.
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
     * <b>WHICH STAGE THE CARRIED GEOMETRY IS FLUSHED AT, as a function of the shader path.</b>
     *
     * <h2>The defect this decision exists for</h2>
     * With a shader pack drawing, a physicalised block showed its frame and <b>not</b> the component
     * inside it; without one, the same block was whole. The world path - the same vanilla
     * {@code cutout} type and the same depth-ignoring contents - was <b>not</b> affected, and the only
     * thing the two paths did not share was the moment they were submitted:
     *
     * <pre>
     *   world path   -&gt; AFTER_ENTITIES           (before the structure draws, under a pack or not)
     *   carried path -&gt; AFTER_TRANSLUCENT_BLOCKS (after everything, including the structure)
     * </pre>
     *
     * <p>The late stage exists for a reason that is real and must be kept <b>without</b> a pack:
     * everything the structure draws - Flywheel's visual pass at {@code blockentities}, Sable's
     * sub-level pass even earlier - is already in the frame by then, so geometry submitted earlier for a
     * host riding a machine is covered by the machine's own opaque blocks. See {@link #Stage}.
     *
     * <p>Under a pack the same lateness is what hides the component: our contents are written with a
     * vanilla type, which <em>tests</em> depth (that is what makes Iris light them), and by
     * {@code AFTER_TRANSLUCENT_BLOCKS} the carried block's own faces are long since in the depth buffer.
     * The frame survives because the shell is pushed a hair outside the block
     * ({@code HostShell.EXPAND = 0.005}); the component is inside it, so every one of its fragments fails
     * the depth test. Submitting at the world stage instead puts the component into the frame
     * <b>before</b> Create's contraption pass and in the same window the working world path uses.
     *
     * <h2>Why this is a pure value and not two {@code if}s in the renderer</h2>
     * "Which stage" is a property of the build, and the diagnostic has to print <b>the stage that was
     * really used</b> - a reader comparing {@code carriedQuadsFlushedAt} against the world stage is
     * reading the answer to this very question. Keeping it here means the renderer's dispatch, the
     * flush line and the tests all consult one function, exactly as {@link #shellQuadArgb} does for the
     * colour rule.
     *
     * <p><b>Without a pack nothing here changes anything:</b> {@link #LATE} is the value it has always
     * had, the stages, types, buffers and submission order are the ones that were there before, and
     * {@link #sameAsBeforeShaders} exists so that is an assertion rather than a claim.
     */
    public enum StagePlan {

        /**
         * The stage the carried path has always used: submitted after everything the structure draws.
         *
         * <p>The only value without a shader pack, and the value this build printed before this decision
         * existed.
         */
        LATE("minecraft:after_translucent_blocks", "the late stage (after every structure the game draws)"),

        /**
         * With a shader pack: submitted with the world path, at {@code after_entities}.
         *
         * <p>Before Create's contraption pass (Flywheel's {@code afterEntities}, which runs immediately
         * after {@code AFTER_ENTITIES} is dispatched) and in the same window the world path's contents
         * reach the frame in.
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
         * True when this plan is the one this build used <b>before the shader decision existed</b>.
         *
         * <p>Only {@link #LATE} is, and that is the whole of the "without a shader pack nothing changed"
         * claim - the stage, and therefore the type, the buffer and the submission order with it. Named
         * rather than written as {@code plan == LATE} at the test, so the assertion says what it means.
         */
        public boolean sameAsBeforeShaders() {
            return this == LATE;
        }
    }

    /**
     * <b>Which render type each half of the carried picture is written with</b>, as a function of the
     * shader path - and the second half of the same defect as {@link StagePlan}.
     *
     * <h2>Why the shell and the contents differ</h2>
     * Under a pack both halves used to be vanilla's {@code cutout}, because Iris selects its program
     * from the render type it is handed and a type of ours is a stranger to it. That is right for the
     * <b>shell</b>, whose quad at {@code 1.005} is in front of the block and therefore always visible.
     * It is wrong for the <b>contents</b>: the world path's contents are <b>depth-ignoring</b>
     * ({@code HostRenderTypes#CONTENTS} / {@code #CARRIED_CONTENTS} - {@code NO_DEPTH_TEST} with depth
     * <em>written</em>, so a deferred pack still has a surface to light), and the no-shader carried path
     * is depth-ignoring too. The shader path was the only place the component was asked to pass a depth
     * test against a block that is drawn <em>around</em> it, which is precisely the thing the depth
     * bypass exists to make impossible.
     *
     * <p>So the decision is deliberately split: the shell keeps vanilla's identity where a pack is
     * drawing, and the <b>contents never change type</b> - the carried contents use
     * {@code CARRIED_CONTENTS} on both paths, which is one behaviour to look at instead of two and the
     * one the author has always seen work without a pack.
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
         * Whether the carried shell uses vanilla's {@code cutout} rather than this build's own type.
         *
         * <p>True only where a pack is drawing, and it exists so no call site has to re-ask the shader
         * question and get a different answer than the plan.
         */
        public boolean vanillaShellType() {
            return this == SHADER_SHELL;
        }

        /**
         * Whether the carried <b>contents</b> use vanilla's {@code cutout}.
         *
         * <p>Always false, on purpose, and it is a method rather than a constant so that the one place
         * this rule lives is nameable: the contents ignore depth on both paths, because that is what
         * makes a component inside a block visible at all. See the enum's own comment.
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
