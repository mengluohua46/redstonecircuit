package com.jiangyuefengyu.redstonecircuit.compat.jade;

import com.jiangyuefengyu.redstonecircuit.HostRules;
import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.RedstoneCircuit;
import com.jiangyuefengyu.redstonecircuit.block.VariableRepeaterBlock;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.client.ClientHostCache;
import com.jiangyuefengyu.redstonecircuit.data.ComparatorMode;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
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
     */
    private static final class InnerRepeaterProvider implements IBlockComponentProvider {

        @Override
        public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
            if (!visibleTo(Minecraft.getInstance().player)) {
                return;
            }
            Slot slot = ClientHostCache.slotAt(accessor.getLevel().dimension(), accessor.getPosition());
            if (slot == null) {
                return;
            }
            tooltip.add(Component.translatable("jade.redstonecircuit.inner_component",
                            HostRules.itemFor(slot.type).getHoverName(), state(slot))
                    .withStyle(ChatFormatting.GRAY));
        }

        @Override
        public ResourceLocation getUid() {
            return INNER_COMPONENT;
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
         * <p>Every type stores its output in {@link Slot#power}, but the same number means different
         * things: a wire's strength is worth reading off (that is the whole point of the type), while a
         * repeater only ever answers nothing or fifteen, so "has output" says everything "15" would and
         * does it in the same words the mod uses elsewhere.
         */
        private static MutableComponent state(Slot slot) {
            return switch (slot.type) {
                case DUST, SUPERCONDUCTOR -> Component.translatable(K_DUST, slot.power);
                case REPEATER, RETAINING_REPEATER, DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER ->
                        Component.translatable(K_REPEATER, output(slot), slot.delay,
                                slot.type.delayTicks(slot.delay));
                // A comparator has no delay setting - it is always one redstone tick - so only the mode
                // and the output are reported, rather than a setting of one that could not be changed.
                // The mode is named on its own ("compare mode") rather than through the switch message,
                // which would repeat the word "comparator" the name at the front of the line has said.
                case COMPARATOR -> Component.translatable(K_COMPARATOR, output(slot),
                        Component.translatable(slot.mode == ComparatorMode.SUBTRACT
                                ? K_MODE_SUBTRACT
                                : K_MODE_COMPARE));
                // Lit is "carrying anything": the renderer colours the torch by the same field, so the
                // line and the picture say the same thing.
                case TORCH -> Component.translatable(slot.power > 0 ? K_LIT : K_UNLIT);
                case LEVER -> Component.translatable(K_LEVER, switchWord(slot));
                case BUTTON -> Component.translatable(K_BUTTON, switchWord(slot));
                // A plate is the one switch that is not flipped by hand, and it is also the one whose
                // powered flag nobody maintains: the solver writes its strength and nothing else. Its
                // strength is therefore what is reported, which is both true and more use than a flag
                // that would read "off" under a player's feet.
                case PRESSURE_PLATE -> Component.translatable(K_PLATE, slot.power);
            };
        }

        /** "Has output" / "no output", from the strength the solver left behind. */
        private static MutableComponent output(Slot slot) {
            return Component.translatable(slot.power > 0 ? K_OUTPUT : K_NO_OUTPUT);
        }

        private static MutableComponent switchWord(Slot slot) {
            return Component.translatable(slot.powered ? K_ON : K_OFF);
        }
    }
}
