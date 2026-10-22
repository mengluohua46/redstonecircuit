package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintDeploy;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The one place a blueprint file is seen whole.
 *
 * <p>Create reads a schematic's file, hands the tag to {@code StructureTemplate#load} and keeps no reference to it
 * afterwards - the printer only ever holds the loaded template, whose four keys are the ones vanilla understands and
 * whose section of ours is therefore gone. So the tag is taken here, as an argument, rather than by reading the file a
 * second time: what Create read is what gets restored, even if the file changes underneath.
 *
 * <p>Vanilla's method, gated by the same plugin as the rest of this config: with Create absent no hook is applied and
 * this class is never loaded. {@code require = 0} because a mixin that cannot be applied must leave an ordinary
 * blueprint behind, never a failed game start.
 */
@Mixin(StructureTemplate.class)
public abstract class CreateSchematicSectionMixin {

    @Inject(method = "load", at = @At("HEAD"), require = 0)
    private void redstonecircuit$keepSection(HolderGetter<Block> blocks, CompoundTag tag, CallbackInfo callback) {
        BlueprintDeploy.sectionRead(tag);
    }
}
