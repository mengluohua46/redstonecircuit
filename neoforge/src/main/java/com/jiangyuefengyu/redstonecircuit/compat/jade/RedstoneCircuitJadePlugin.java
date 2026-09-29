package com.jiangyuefengyu.redstonecircuit.compat.jade;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierIndex;
import com.jiangyuefengyu.redstonecircuit.client.ClientHostCache;
import com.jiangyuefengyu.redstonecircuit.client.InnerComponentLine;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.state.BlockState;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.IWailaClientRegistration;
import snownee.jade.api.IWailaPlugin;
import snownee.jade.api.WailaPlugin;
import snownee.jade.api.config.IPluginConfig;

import org.jetbrains.annotations.Nullable;

/**
 * Optional Jade (玉) integration: the same readouts on our blocks that Jade gives vanilla's.
 *
 * <h2>Why this is needed at all</h2>
 * Jade's delay line for a vanilla repeater comes from its own built-in provider, which is registered
 * against that one block instance. Our three repeaters are different blocks - extending
 * {@link RepeaterBlock} in Java is invisible to a registry lookup - so nothing was shown for them.
 * There is no data-driven way to join in: a mod that wants an overlay has to register one.
 *
 * <h2>What it costs</h2>
 * Nothing to anyone who does not use Jade. Jade's API is a {@code compileOnly} dependency, so this jar
 * contains none of it and does not ask for it; Jade itself finds this class by scanning for
 * {@link WailaPlugin} annotations, which only happens when Jade is installed.
 *
 * <p>Nothing here is sent over the network. The in-world repeaters carry their setting as a block
 * state property, and a repeater hidden inside a block is already known to the client - that is the
 * same synced data the goggles draw with, see {@link ClientHostCache}.
 *
 * <h2>Two kinds of line, two different rules</h2>
 * <ul>
 *   <li><b>The world's own blocks</b> - a repeater's delay setting, the weather detector's mode. These
 *       are public facts about the block in front of you, the same line Jade itself prints for a
 *       vanilla repeater without any goggles, so they are shown whenever Jade is looking at one.</li>
 *   <li><b>What is hidden inside a block</b> - the component in {@code inner_component}. This is what
 *       the goggles exist for, so it is shown on exactly the terms the renderer draws the shell on:
 *       {@link RCConfig#overlayMode()} decides. {@code GOGGLES} (the default) means the line appears
 *       only while the local player is wearing the {@link RCRegistry#isWearingGoggles} goggles;
 *       {@code ALWAYS} shows it to everyone, which is the setting to use while testing; {@code OFF}
 *       never shows it, exactly as if this overlay did not exist. The renderer reads the same config
 *       and calls the same goggle check, so the text and the picture can never disagree.</li>
 * </ul>
 */
@WailaPlugin(RedstoneCircuit.MODID)
public class RedstoneCircuitJadePlugin implements IWailaPlugin {

    /** Jade plugin uids; shown in Jade's own plugin list and used to switch each overlay off. */
    private static final ResourceLocation REPEATER_DELAY =
            ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID, "repeater_delay");
    private static final ResourceLocation WEATHER_DETECTOR =
            ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID, "weather_detector");
    private static final ResourceLocation INNER_COMPONENT =
            ResourceLocation.fromNamespaceAndPath(RedstoneCircuit.MODID, "inner_component");

    /** Language keys, named here so the switch and the translation file cannot drift apart. */
    private static final String K_DUST = "jade.redstonecircuit.inner.dust";
    private static final String K_REPEATER = "jade.redstonecircuit.inner.repeater";
    private static final String K_COMPARATOR = "jade.redstonecircuit.inner.comparator";
    private static final String K_LIT = "jade.redstonecircuit.inner.lit";
    private static final String K_UNLIT = "jade.redstonecircuit.inner.unlit";
    private static final String K_LEVER = "jade.redstonecircuit.inner.lever";
    private static final String K_BUTTON = "jade.redstonecircuit.inner.button";
    private static final String K_PLATE = "jade.redstonecircuit.inner.pressure_plate";
    /** Reused rather than duplicated: the same two words describe a lever, a button and a plate. */
    private static final String K_ON = "message.redstonecircuit.switch.on";
    private static final String K_OFF = "message.redstonecircuit.switch.off";
    /** A diode only ever answers nothing or fifteen, so its output is reported as a state. */
    private static final String K_OUTPUT = "jade.redstonecircuit.inner.output";
    private static final String K_NO_OUTPUT = "jade.redstonecircuit.inner.no_output";
    /** A comparator's mode, without the component's name in front of it. */
    private static final String K_MODE_COMPARE = "jade.redstonecircuit.inner.mode.compare";
    private static final String K_MODE_SUBTRACT = "jade.redstonecircuit.inner.mode.subtract";

    @Override
    public void registerClient(IWailaClientRegistration registration) {
        registration.registerBlockComponent(new RepeaterDelayProvider(), VariableRepeaterBlock.class);
        registration.registerBlockComponent(new WeatherModeProvider(), WeatherDetectorBlock.class);
        // Any block can hold a component, so this one cannot be narrowed to a class of ours: it checks
        // the synced host data instead, and says nothing at all unless a repeater is hidden there.
        registration.registerBlockComponent(new InnerRepeaterProvider(), Block.class);
    }

    /** The setting and what it costs in ticks, for the three repeaters this mod adds. */
    private static final class RepeaterDelayProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockState state = accessor.getBlockState();
            if (!(state.getBlock() instanceof RepeaterBlock repeater)) {
                return;
            }
            int setting = state.getValue(RepeaterBlock.DELAY);
            int ticks = repeater instanceof VariableRepeaterBlock variable
                    ? variable.ticksFor(state)
                    // Vanilla's own two ticks per setting, in case this ever gets registered wider.
                    : setting * 2;
            tooltip.add(Component.translatable("jade.redstonecircuit.repeater", setting, ticks)
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public ResourceLocation getUid() {
            return REPEATER_DELAY;
        }
    }

    /** Which weather the detector is watching, and what it is answering right now. */
    private static final class WeatherModeProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            BlockState state = accessor.getBlockState();
            Component mode = Component.translatable("jade.redstonecircuit.weather."
                    + state.getValue(WeatherDetectorBlock.WEATHER).key());
            Component output = Component.literal(state.getValue(WeatherDetectorBlock.ACTIVE)
                    ? "15"
                    : "0");
            tooltip.add(Component.translatable("jade.redstonecircuit.weather", mode, output)
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public ResourceLocation getUid() {
            return WEATHER_DETECTOR;
        }
    }

    /**
     * The component stored inside a block: what it is, and what it is doing right now.
     *
     * <p>The host block's own state says nothing about this, which is exactly why the mod syncs the
     * contents to the client; the goggles read the same cache, and so does this. The line is a private
     * view of a hidden thing, so it is gated on the goggles precisely as the rendering is - see the
     * class comment for what each {@link RCConfig.OverlayMode} means here.
     *
     * <h2>Two places a component can be, and this provider must answer both</h2>
     * <b>In the world</b> the position Jade reports is a world position and {@code HostIndex} has the
     * contents. <b>Riding a moving structure</b> it is not: a physicalised block's position in the level
     * is the structure's <em>own</em> coordinate (a Sable sub-level's blocks are the level's blocks inside
     * a reserved plot region - see {@code CarrierStructureAccess}), and the ordinary host sync has
     * nothing at any such coordinate. That is why the author saw an empty overlay over a physicalised
     * block even though the goggles drew the component perfectly well inside it: the two readers were
     * asking different stores, and only one of them had been told about the carrier payload.
     *
     * <p>So the carried store is consulted <b>first</b>, by the coordinate the block is named by.
     *
     * <h2>P1.29: a carried host is authoritative, even when it holds nothing</h2>
     * The carried branch used to fall through to the world store whenever it held nothing
     * ({@code host.slot() != null} in the condition). That fall-through is a leak, and the author's own
     * log is what it leaked: {@code placed RETAINING_REPEATER at 20481032, 127, 20481032} - a plot
     * coordinate, from a build in which the world path still wrote there. A <em>stale</em> world node at a
     * coordinate a structure now owns is not the block in front of the player; it is a leftover from
     * before the block was physicalised, and reading it makes Jade print a component that exists nowhere:
     * the block itself holds nothing (so the overlay draws nothing), and breaking the block drops
     * something out of thin air.
     *
     * <p>So the question is asked in the right order: <b>is this coordinate one a structure carries?</b>
     * If yes, the carried answer is the answer - including "nothing" - and the world store is not
     * consulted at all. A coordinate no structure carries is answered by the world store exactly as
     * before, which is every ordinary block.
     */
    private static final class InnerRepeaterProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!visibleTo(Minecraft.getInstance().player)) {
                return;
            }
            BlockPos position = accessor.getPosition();
            ResourceKey<Level> dimension = accessor.getLevel().dimension();

            if (RCConfig.carriedJade()) {
                CarrierIndex.CarriedView host =
                        ClientHostCache.carriedHostAtLocal(dimension, position);
                if (host != null) {
                    // A coordinate a structure carries: this branch is the whole answer, and an empty host
                    // is a real answer ("nothing is inside this block"), not a reason to read a store that
                    // describes a different time.
                    noteCarried(dimension, host, accessor.getLevel().getGameTime());
                    if (host.slot() != null) {
                        tooltip.add(slotLine(host.slot()));
                    }
                    return;
                }
            }

            Slot slot = ClientHostCache.slotAt(dimension, position);
            if (slot != null) {
                tooltip.add(slotLine(slot));
            }
        }

        @Override
        public ResourceLocation getUid() {
            return INNER_COMPONENT;
        }

        /**
         * The one line both branches print, so the world overlay and the carried overlay can never say
         * different things about the same component.
         */
        private static Component slotLine(Slot slot) {
            return Component.translatable("jade.redstonecircuit.inner_component",
                            HostRules.itemFor(slot.type).getHoverName(), state(slot))
                    .withStyle(ChatFormatting.GRAY);
        }

        /**
         * How often the carried-host judgement line may be written again, in ticks.
         *
         * <p>One second - the same ceiling the client's three render lines use, so one log has one notion
         * of "how often is often enough".
         */
        private static final int JADE_LOG_INTERVAL_TICKS = 20;

        /** The answer the last judgement line carried, so an unchanged overlay writes nothing. */
        @Nullable
        private static String lastCarriedLine;

        /** When the last judgement line was written, in client ticks, and whether it ever was. */
        private static long lastCarriedTick = Long.MIN_VALUE;
        private static boolean carriedEverWritten;

        /**
         * <b>The judgement line: a carried host was resolved and its fields were handed to Jade.</b>
         *
         * <p>Written at the moment the line is produced rather than on any tick, and gated on
         * {@code debugLog} - an overlay is drawn once per frame while the player looks at something, so
         * this is the one place a per-frame log line would be affordable only because it is behind a
         * switch. It is the evidence that the carried branch was reached at all, which is otherwise
         * indistinguishable from "Jade shows nothing here".
         *
         * <p><b>The author's log had 268 of these for one look at one block.</b> The question Jade asks is
         * a per-frame question, so the line was per-frame too, and it says the same thing every time the
         * component inside the block does not change. It is now written <b>when the answer changes</b>, and
         * otherwise at most once per {@link #JADE_LOG_INTERVAL_TICKS} ticks: an operator sees a line the
         * moment the overlay starts showing a component, and then gets on with the session. The first line
         * of a session is always written, so "Jade never asked" and "Jade asked and the mod said nothing"
         * stay distinguishable - and {@code verboseRenderLog=true} restores the per-frame behaviour for
         * when the query rate itself is what is being investigated.
         *
         * <p><b>The host's coordinate is deliberately NOT on the line (P1.27).</b> It was, and it was the
         * author's question - "why does a prompt show a very long number?" - one reader removed from the
         * action bar: a plot coordinate printed into a screenshot, a bug report or a chat message is a
         * number that means nothing to the player and everything about the mod's own bookkeeping. What the
         * line keeps is the carrier's short id and the component's own state, which is what a report has to
         * be matched against; the coordinate is one {@code /rc} diagnostic away when it is genuinely
         * needed.
         *
         * @param nowTicks the client level's game time, used only to space the lines out
         */
        private static void noteCarried(ResourceKey<Level> dimension, CarrierIndex.CarriedView host,
                                        long nowTicks) {
            if (!RCConfig.debugLog()) {
                return;
            }
            String signature = host.carrier().shortId() + "/" + host.localPos().asLong() + "/"
                    + dimension.location() + "/" + stateText(host.slot());
            boolean verbose = nowTicks < 0 || RCConfig.verboseRenderLog();
            if (!verbose && carriedEverWritten) {
                // The same answer as the last line: an overlay that has not changed carries no news.
                if (signature.equals(lastCarriedLine)) {
                    return;
                }
                // A different answer, but this frame is inside the window: deliberately NOT remembered as
                // printed, so the next query at or after the boundary still reports the new answer.
                if (nowTicks - lastCarriedTick < JADE_LOG_INTERVAL_TICKS) {
                    return;
                }
            }
            lastCarriedLine = signature;
            carriedEverWritten = true;
            if (nowTicks >= 0) {
                lastCarriedTick = nowTicks;
            }
            RCConfig.LOGGER.info("[redstonecircuit] jade: carried host {} in {} -> {} "
                            + "(the block is a physicalised block, so the world host sync has nothing at "
                            + "its own coordinate and this line comes from the carrier payload; the "
                            + "coordinate itself is left to the /rc diagnostics, never printed here)",
                    host.carrier().shortId(), dimension.location(), stateText(host.slot()));
        }

        /**
         * Whether the inside of a block is the local player's business right now.
         *
         * <p>This is the renderer's rule, not a second opinion: same config, same goggle check on the
         * same player ({@link RCRegistry#isWearingGoggles}, which is what the shell is drawn under).
         */
        private static boolean visibleTo(Player player) {
            return switch (RCConfig.overlayMode()) {
                case ALWAYS -> true;
                // No player means no world worth reading the cache for, so nothing to say either way.
                case GOGGLES -> RCRegistry.isWearingGoggles(player);
                case OFF -> false;
            };
        }

        /**
         * The state half of the line, worded by component type.
         *
         * <p>The <b>fields</b> come from {@link InnerComponentLine}, which is the one place that decides
         * what a component says - so the carried overlay and the world overlay cannot drift apart. What
         * is here is only the wording.
         *
         * <p>Every type stores its output in {@link Slot#power}, but the same number means different
         * things: a wire's strength is worth reading off (that is the whole point of the type), while a
         * repeater only ever answers nothing or fifteen, so "has output" says everything "15" would and
         * does it in the same words the mod uses elsewhere.
         */
        private static MutableComponent state(Slot slot) {
            InnerComponentLine line = InnerComponentLine.of(slot);
            if (line == null) {
                return Component.empty();
            }
            return switch (line.kind()) {
                case STRENGTH -> Component.translatable(K_DUST, line.power());
                case REPEATER -> Component.translatable(K_REPEATER, output(line), line.delay(),
                        line.type().delayTicks(line.delay()));
                // A comparator has no delay setting - it is always one redstone tick - so only the mode
                // and the output are reported, rather than a setting of one that could not be changed.
                // The mode is named on its own ("compare mode") rather than through the switch message,
                // which would repeat the word "comparator" the name at the front of the line has said.
                case COMPARATOR -> Component.translatable(K_COMPARATOR, output(line),
                        Component.translatable(line.subtrahend() ? K_MODE_SUBTRACT : K_MODE_COMPARE));
                // Lit is "carrying anything": the renderer colours the torch by the same field, so the
                // line and the picture say the same thing.
                case TORCH -> Component.translatable(line.hasOutput() ? K_LIT : K_UNLIT);
                case SWITCH -> Component.translatable(
                        line.type() == ComponentType.LEVER ? K_LEVER : K_BUTTON, switchWord(line));
                // A plate is the one switch that is not flipped by hand, and it is also the one whose
                // powered flag nobody maintains: the solver writes its strength and nothing else. Its
                // strength is therefore what is reported, which is both true and more use than a flag
                // that would read "off" under a player's feet.
                case PLATE -> Component.translatable(K_PLATE, line.power());
            };
        }

        /** "Has output" / "no output", from the strength the solver left behind. */
        private static MutableComponent output(InnerComponentLine line) {
            return Component.translatable(line.hasOutput() ? K_OUTPUT : K_NO_OUTPUT);
        }

        private static MutableComponent switchWord(InnerComponentLine line) {
            return Component.translatable(line.powered() ? K_ON : K_OFF);
        }

        /** The game-free rendering of the same fields, for the judgement line. */
        private static String stateText(Slot slot) {
            InnerComponentLine line = InnerComponentLine.of(slot);
            return line == null ? "-" : line.describe();
        }
    }
}
