package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintDeploy;

import net.minecraft.server.level.ServerPlayer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An instant build, after it has placed everything.
 *
 * <p>{@code SchematicPlacePacket#handle} walks the whole blueprint in one call and puts a block down wherever the
 * world does not already match it. The cells it skips are the reason this hook exists: those blocks are already
 * standing, so no landing hook ever fires for them, and their components would be lost with a blueprint that has
 * otherwise been built in full. There is nothing in the air at this point, so this pass is the blueprint's last.
 *
 * <p>{@code require = 0} as everywhere in this package: the injection point is another mod's class.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.packet.SchematicPlacePacket")
public abstract class CreateSchematicPacketMixin {

    @Inject(method = "handle", at = @At("TAIL"), require = 0)
    private void redstonecircuit$finish(ServerPlayer player, CallbackInfo callback) {
        BlueprintDeploy.packetFinished(this, player);
    }
}
