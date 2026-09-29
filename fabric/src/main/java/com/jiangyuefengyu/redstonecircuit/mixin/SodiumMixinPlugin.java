package com.jiangyuefengyu.redstonecircuit.mixin;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.loader.api.FabricLoader;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

/**
 * Applies the Sodium-targeting mixin only when Sodium is actually there, and says so in the log.
 *
 * <h2>Why the classpath, and not just the loader's mod list</h2>
 * The target is named as a string ({@code @Mixin(targets = "...")}) so the mod never compiles against
 * Sodium - which means Mixin cannot be told to be quiet about an absent class by a {@code require}
 * count, and the config has to decide. The first version of this class asked only
 * {@code FabricLoader.isModLoaded("sodium")} and fell back to a classpath lookup; a {@code false} from
 * here is <em>undetectable</em> in game - the mixin is simply skipped, silently, and the only symptom
 * is the one this class cannot report: blocks that stay in the chunk mesh and hide their own contents.
 *
 * <p>So the order is reversed. The classpath is asked first (finding a {@code .class} resource does
 * not load it, which is what makes it safe this early), the loader's mod list is the second opinion,
 * the answer is cached, and <b>both outcomes are logged</b>. If the next run shows the "Sodium found"
 * line and the mixin still does not run, the gate is not the fault and the search moves to Mixin
 * itself; if it shows the "not found" line on a machine that has Sodium, the lookup is the fault -
 * either way the guesswork is gone.
 *
 * <p>The classpath is written as a resource string rather than a compile-time reference for the same
 * reason the mixin is: nothing in this mod may hard-depend on Sodium.
 */
public class SodiumMixinPlugin implements IMixinConfigPlugin {

    private static final String SODIUM = "sodium";

    private static final String BLOCK_RENDERER =
            "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer.class";

    private static Boolean present;
    private static String foundBy;

    /** Mixin classes already reported as applied, so a second transform does not repeat the line. */
    private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

    /** True when Sodium (or a fork keeping its package) is present. Cached: this is asked per mixin. */
    public static boolean sodiumPresent() {
        if (present != null) {
            return present;
        }
        boolean onClasspath = SodiumMixinPlugin.class.getClassLoader().getResource(BLOCK_RENDERER)
                != null;
        boolean onModList = FabricLoader.getInstance().isModLoaded(SODIUM);
        present = onClasspath || onModList;
        foundBy = onClasspath ? "classpath" : (onModList ? "mod list" : null);
        return present;
    }

    /**
     * Records once how the answer above was reached.
     *
     * <p>Logged from {@link #onLoad} on purpose: that runs for this config whether or not any mixin is
     * then applied, so the line is present in exactly the case where the mod needs it - a machine where
     * Sodium is installed and the hook did not run.
     */
    private static void report() {
        if (sodiumPresent()) {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] Sodium found via the {}; the Sodium mesh hook will be applied",
                    foundBy);
        } else {
            RCConfig.LOGGER.info(
                    "[redstonecircuit] Sodium not found; the Sodium mesh hook is skipped - and the "
                            + "vanilla mesh hook does not run under Sodium, so host blocks would keep "
                            + "their own cube in the mesh and hide what is inside them");
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
        try {
            report();
        } catch (Throwable ignored) {
            // A log line must never be the reason a client fails to start.
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return sodiumPresent();
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                         IMixinInfo mixinInfo) {
    }

    /**
     * Says, once per mixin, that Mixin really did merge it into the Sodium target.
     *
     * <p>This is the third of the three things that have to happen for a host block to disappear from
     * Sodium's mesh - the gate opens ({@link #onLoad}), the mixin is applied to the target (here), and
     * the injected body is reached ({@code ShaderCompat.noteHookEntered}). Written from the plugin
     * rather than from inside the mixin on purpose: it is produced whether or not the annotation
     * processor's method spec resolves, so "the mixin was never applied" and "the mixin was applied and
     * its injection point did not resolve" stop looking the same. It logs nothing for a mixin the gate
     * skipped, and it cannot throw into the transformer.
     */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
        try {
            if (APPLIED.add(mixinClassName)) {
                int cut = mixinClassName.lastIndexOf('.');
                RCConfig.LOGGER.info(
                        "[redstonecircuit] Sodium mixin {} was applied to {} (the class was transformed; "
                                + "whether its injection point was reached is a separate line)",
                        cut < 0 ? mixinClassName : mixinClassName.substring(cut + 1), targetClassName);
            }
        } catch (Throwable ignored) {
            // A log line must never be the reason a client fails to start.
        }
    }
}
