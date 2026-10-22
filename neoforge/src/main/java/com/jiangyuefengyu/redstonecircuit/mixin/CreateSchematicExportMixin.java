package com.jiangyuefengyu.redstonecircuit.mixin;

import java.nio.file.Path;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintExport;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Create's one blueprint export: after the file is written, the server adds what it has that the file cannot know.
 *
 * <p>{@code SchematicExport#saveSchematic} is the only place a blueprint file is produced, and both the quill and the
 * instant-build packet end here - the packet does not ship a file, it asks the server to export the region again. That
 * makes this single hook enough for the whole "the server makes the file" half; the half where a client ships bytes
 * is {@link CreateSchematicUploadMixin}'s.
 *
 * <p>By name rather than by descriptor: the descriptor's return type is Create's own result record, which this source
 * set may not name. The handler takes that result as {@code Object} and reads its accessors reflectively for the same
 * reason. {@code require = 0} because the target is another mod's internal method - a rename there must leave an
 * ordinary working blueprint, not a failed game start.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.SchematicExport")
public abstract class CreateSchematicExportMixin {

    @Inject(method = "saveSchematic", at = @At("RETURN"), require = 0)
    private static void redstonecircuit$innerRedstone(Path folder, String name, boolean permanent, Level level,
                                                      BlockPos first, BlockPos second,
                                                      CallbackInfoReturnable<Object> result) {
        BlueprintExport.afterExport(result.getReturnValue(), level, first, second);
    }
}
