package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintExport;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The client's half of the upload announcement: the moment a file is picked for upload, before Create asks the server
 * to open one.
 *
 * <p>Create's upload packets name a file and carry its bytes, so the region the file was exported from never travels
 * with it - and the client cannot put the components in itself, because a client holds no redstone store. What it can
 * do is say which region it exported, and this is the only point where both facts are known at once: the file name
 * from the argument, the region from {@code BlueprintLocalSaves}.
 *
 * <p>Sending at HEAD is what keeps the two packets in order on one connection: the announcement reaches the server
 * before {@code begin}, and the server has it long before the stream is closed.
 *
 * <p>{@code require = 0} as everywhere in this package - a Create update that renames this must leave the upload
 * working, with the blueprint simply carrying what its file already carries.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.client.ClientSchematicLoader")
public abstract class CreateSchematicLoaderMixin {

    @Inject(method = "startNewUpload", at = @At("HEAD"), require = 0)
    private void redstonecircuit$announceSelection(String schematic, CallbackInfo callback) {
        BlueprintExport.announceUpload(schematic);
    }
}
