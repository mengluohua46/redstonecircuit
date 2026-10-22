package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintDeploy;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The read point: the moment a blueprint is loaded, everything it carries is turned into world coordinates once.
 *
 * <p>{@code SchematicItem#loadSchematic} is where both placement paths meet - the instant build asks it through a
 * printer of its own, the cannon through {@code initializePrinter} - and it is the only moment at which the file, the
 * item's rotation and mirror, and the anchor are all in hand. Doing the transform here rather than where a block
 * lands is what keeps a per-block loop free of the maths, and of the half-cell error a float rotation would add.
 *
 * <p>By name rather than by descriptor, and {@code require = 0}, as everywhere in this package: a rename there has to
 * mean "the blueprint carries no components", not a game that does not start.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.SchematicItem")
public abstract class CreateSchematicDeployMixin {

    @Inject(method = "loadSchematic", at = @At("RETURN"), require = 0)
    private static void redstonecircuit$budget(Level level, ItemStack stack,
                                               CallbackInfoReturnable<StructureTemplate> result) {
        BlueprintDeploy.afterSchematicLoad(level, stack);
    }
}
