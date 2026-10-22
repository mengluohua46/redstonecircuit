package com.jiangyuefengyu.redstonecircuit.mixin;

import java.util.List;
import java.util.Set;

import net.neoforged.fml.ModList;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Applies the blueprint hooks only when Create is actually installed.
 *
 * <p>Their targets are Create's own classes, named as strings ({@code @Mixin(targets = "...")}) so this mod never
 * compiles against Create - which is also why Mixin has to be told not to complain when those classes are simply
 * absent, the ordinary case for a player who does not run Create. The mod list answers that once it exists; before it
 * does, the classpath is asked instead, and a resource lookup does not load the class, which is what makes it safe this
 * early.
 *
 * <p>Nothing else in this mod depends on these hooks: with Create absent no hook is applied, the classes are never
 * loaded, and the mod behaves exactly as it did before they existed.
 */
public class CreateMixinPlugin implements IMixinConfigPlugin {

    private static final String CREATE = "create";

    /** The class whose presence means Create is on the classpath; looked up as a resource, never loaded. */
    private static final String CREATE_RESOURCE = "com/simibubi/create/Create.class";

    /** True when Create is present. */
    public static boolean createPresent() {
        if (ModList.get() != null && ModList.get().isLoaded(CREATE)) {
            return true;
        }
        // Mixin configs can be read before the mod list is populated, so the classpath is asked too.
        return CreateMixinPlugin.class.getClassLoader().getResource(CREATE_RESOURCE) != null;
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
        return createPresent();
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
