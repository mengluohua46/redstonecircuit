package com.jiangyuefengyu.redstonecircuit.mixin;

import java.util.List;
import java.util.Set;

import net.neoforged.fml.ModList;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the Sodium-targeting mixins only when Sodium is actually installed.
 *
 * <p>Their target is named as a string ({@code @Mixin(targets = "...")}) so the mod never compiles
 * against Sodium, and Mixin has to be told not to complain when the class is simply absent - which is
 * the ordinary case, since Sodium is optional. The check is the loader's own mod list rather than a
 * class lookup so it also answers correctly before Sodium's classes are loaded.
 */
public class SodiumMixinPlugin implements IMixinConfigPlugin {

    private static final String SODIUM = "sodium";

    /** True when Sodium (or a fork keeping its mod id) is present. */
    public static boolean sodiumPresent() {
        if (ModList.get() != null && ModList.get().isLoaded(SODIUM)) {
            return true;
        }
        // Mixin configs can be read before the mod list is populated, so the classpath is asked too.
        // A resource lookup does not load the class, which is what makes it safe this early.
        return SodiumMixinPlugin.class.getClassLoader().getResource(
                "net/caffeinemc/mods/sodium/client/render/chunk/compile/pipeline/BlockRenderer.class")
                != null;
    }

    @Override
    public void onLoad(String mixinPackage) {
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

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName,
                          IMixinInfo mixinInfo) {
    }
}
