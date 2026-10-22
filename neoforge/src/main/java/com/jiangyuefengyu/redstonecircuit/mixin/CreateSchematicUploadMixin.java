package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintExport;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The upload half of Create's blueprints: a client wrote a file, the server adds what only the server knows.
 *
 * <p>The hook sits on the <b>close</b> of the upload stream rather than at the return of the method, because the two
 * statements after it read the file back: {@code finishUpload} ends the progress display and
 * {@code SchematicItem.create} loads the template to stamp its size onto the item. Injecting later would hand Create a
 * copy taken before this mod had written anything.
 *
 * <p>{@code require = 0} for the same reason as everywhere in this package: the target is another mod's internal
 * method, and a rename there has to degrade to "the blueprint carries no components".
 */
@Mixin(targets = "com.simibubi.create.content.schematics.ServerSchematicLoader")
public abstract class CreateSchematicUploadMixin {

    @Inject(
            method = "handleFinishedUpload",
            at = @At(value = "INVOKE", target = "Ljava/io/OutputStream;close()V", shift = At.Shift.AFTER),
            require = 0)
    private void redstonecircuit$innerRedstone(ServerPlayer player, String schematic, CallbackInfo callback) {
        BlueprintExport.afterUpload(player, schematic);
    }
}
