package com.jiangyuefengyu.redstonecircuit.mixin;

import net.minecraft.client.resources.language.I18n;
import net.neoforged.neoforgespi.language.IModInfo;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Draws this mod's own name in the mod list in the language the game is running in.
 *
 * <p>FML has no name translation: {@code ModInfo#getDisplayName} returns the string the toml was loaded with,
 * and the mod list wraps it in {@code Component#literal}, so the name cannot follow the language file by
 * editing data. The one place it is read for display is
 * {@code ModListWidget.ModEntry#render} - twice, for the entry's first line and for the version line - and
 * the name there is {@code stripControlCodes(container.getModInfo().getDisplayName())}. Redirecting the
 * <em>lookup</em> rather than the {@code Component} the caller builds keeps the patch to one call site:
 * {@code render} also calls {@code Component#literal} for the version, and redirecting that one would be
 * ambiguous.
 *
 * <p>Only this mod is touched: every other mod's name comes back exactly as FML handed it over.
 *
 * <p>What this deliberately does not do - and cannot - is change what the <em>search box</em> matches: the
 * filter in {@code ModListScreen#getSearchable} runs over the raw {@code getDisplayName()}, so typing the
 * translated name finds nothing. Renaming the search would mean overriding behaviour rather than a lookup,
 * and a mod list that answers a filter with a name it does not display is the worse half of the trade.
 *
 * <p>{@code require = 0}: a future NeoForge reshape moves the call and the list falls back to the English
 * name from the toml, which is what it draws today, rather than crashing the client.
 */
@Mixin(targets = "net.neoforged.neoforge.client.gui.widget.ModListWidget$ModEntry")
public abstract class ModListNameMixin {

    /** The name key both language files carry; absent, the toml's English name is returned unchanged. */
    private static final String NAME_KEY = "redstonecircuit.mod.name";

    @Redirect(
            method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIIIIIZF)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/neoforged/neoforgespi/language/IModInfo;getDisplayName()Ljava/lang/String;"),
            require = 0)
    private String redstonecircuit$localisedName(IModInfo info) {
        if (!"redstonecircuit".equals(info.getModId())) {
            return info.getDisplayName();
        }
        return I18n.exists(NAME_KEY) ? I18n.get(NAME_KEY) : info.getDisplayName();
    }
}
