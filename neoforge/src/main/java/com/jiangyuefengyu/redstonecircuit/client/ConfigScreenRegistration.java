package com.jiangyuefengyu.redstonecircuit.client;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * What turns the mod list's Config button on, on the client only.
 *
 * <p>The button asks the mod whether it has a config <em>screen</em>, not whether it has a config: the list enables it
 * only when {@code IConfigScreenFactory.getForMod(...)} finds an extension point, so registering a {@code ModConfigSpec}
 * alone leaves the button grey. The extension point is that answer.
 *
 * <p>{@link ConfigurationScreen} is the generic editor NeoForge already ships - it walks every section of our
 * {@code ModConfigSpec} on its own - so there is no screen of ours to write and none to keep in step with the keys.
 *
 * <p>{@code ConfigurationScreen} is a client class, and {@link #register}'s parameter is a plain {@code ModContainer},
 * so a dedicated server never has to resolve it: this class is the only place the two meet, and the caller guards it.
 */
@OnlyIn(Dist.CLIENT)
public final class ConfigScreenRegistration {

    /** Registers the config screen for {@code modContainer}. Must only be called on the client. */
    public static void register(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
                (container, parent) -> new ConfigurationScreen(container, parent));
    }

    private ConfigScreenRegistration() {
    }
}
