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
 * Optional Jade (玉) integration: the same readouts on our blocks that Jade gives vanilla's. Jade
 * registers its delay line against one block instance, so our repeaters need an overlay of their own.
 *
 * <p>Nothing is sent over the network - the lines read synced data. The world's own settings are always
 * shown; the component hidden inside a block is gated on {@link RCConfig#overlayMode()}, exactly as the
 * shell is.
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
    /** 控制器's answer, at the strength the player chose. */
    private static final String K_CONTROLLER = "jade.redstonecircuit.inner.controller";
    /** 红石变压器's own line: the strength it is answering with. */
    private static final String K_TRANSFORMER = "jade.redstonecircuit.inner.transformer";
    /** 时钟源's own line: the strength it is emitting right now. */
    private static final String K_CLOCK = "jade.redstonecircuit.inner.clock";
    /** 信号延长器's own line: the strength it is holding, or nothing while no hold is running. */
    private static final String K_EXTENDER = "jade.redstonecircuit.inner.extender";
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
        // Any block can hold a component, so this one is registered for every block and checks the synced
        // host data instead.
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
     * The component stored inside a block: what it is, and what it is doing right now. The host block's
     * own state says nothing about it, so the synced cache is the source - gated on the goggles, exactly
     * as the rendering is.
     *
     * <p>A component can be in the world or riding a structure, and the carried store is consulted first:
     * a carried host is authoritative even when it holds nothing, so a stale world node at a coordinate a
     * structure now owns is never read.
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
                    // A coordinate a structure carries: this branch is the whole answer, including an empty
                    // host.
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

        /** The one line both branches print, so the two overlays cannot say different things. */
        private static Component slotLine(Slot slot) {
            return Component.translatable("jade.redstonecircuit.inner_component",
                            HostRules.itemFor(slot.type).getHoverName(), state(slot))
                    .withStyle(ChatFormatting.GRAY);
        }

        /** How often the carried-host judgement line may be written again, in ticks: one second. */
        private static final int JADE_LOG_INTERVAL_TICKS = 20;

        /** The answer the last judgement line carried, so an unchanged overlay writes nothing. */
        @Nullable
        private static String lastCarriedLine;

        /** When the last judgement line was written, in client ticks, and whether it ever was. */
        private static long lastCarriedTick = Long.MIN_VALUE;
        private static boolean carriedEverWritten;

        /**
         * The judgement line: a carried host was resolved and its fields were handed to Jade. It is written
         * when the answer changes, at most once per {@link #JADE_LOG_INTERVAL_TICKS} ticks, and carries no
         * host coordinate - a plot coordinate means nothing to the player.
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
                // A different answer, but this frame is inside the window: not remembered as printed, so
                // the next query at or after the boundary still reports it.
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
         * Whether the inside of a block is the local player's business right now: the renderer's own rule,
         * same config and same goggle check on the same player.
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
         * The state half of the line, worded by component type. The fields come from
         * {@link InnerComponentLine}, so the carried overlay and the world overlay cannot drift apart;
         * only the wording is here.
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
                // A comparator has no delay setting, and its mode is named on its own rather than through
                // the switch message.
                case COMPARATOR -> Component.translatable(K_COMPARATOR, output(line),
                        Component.translatable(line.subtrahend() ? K_MODE_SUBTRACT : K_MODE_COMPARE));
                // Lit is "carrying anything": the renderer colours the torch by the same field.
                case TORCH -> Component.translatable(line.hasOutput() ? K_LIT : K_UNLIT);
                case SWITCH -> Component.translatable(
                        line.type() == ComponentType.LEVER ? K_LEVER : K_BUTTON, switchWord(line));
                // A plate is not flipped by hand, and its powered flag is not maintained: its strength is
                // therefore what is reported.
                case PLATE -> Component.translatable(K_PLATE, line.power());
                // 控制器 answers a strength the player chose, so a diode's "has output" line would report a
                // number nobody set. Its condition and its other settings are on its own screen.
                case CONTROLLER -> line.hasOutput()
                        ? Component.translatable(K_CONTROLLER, line.power())
                        : Component.translatable(K_NO_OUTPUT);
                // 红石变压器 answers a strength the player chose too, so it gets the same shape of line - the
                // number it is answering with, and "no output" while nothing reaches its input.
                case TRANSFORMER -> line.hasOutput()
                        ? Component.translatable(K_TRANSFORMER, line.power())
                        : Component.translatable(K_NO_OUTPUT);
                // 时钟源 answers its own strength while it is in the emitting half of its period, and nothing
                // for the rest of it; the period itself is on the device's screen.
                case CLOCK -> line.hasOutput()
                        ? Component.translatable(K_CLOCK, line.power())
                        : Component.translatable(K_NO_OUTPUT);
                // 信号延长器 answers whatever stands at its input, and keeps answering it for the hold; how long that
                // hold is lives on the device's screen.
                case EXTENDER -> line.hasOutput()
                        ? Component.translatable(K_EXTENDER, line.power())
                        : Component.translatable(K_NO_OUTPUT);
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
