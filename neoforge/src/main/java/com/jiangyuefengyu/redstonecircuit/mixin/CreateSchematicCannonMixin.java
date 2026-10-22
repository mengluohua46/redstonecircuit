package com.jiangyuefengyu.redstonecircuit.mixin;

import com.jiangyuefengyu.redstonecircuit.blueprint.BlueprintDeploy;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A 蓝图加农炮's last pass, which is two moments rather than one.
 *
 * <p>{@code finishedPrinting} means the printer has placed everything - not that everything has arrived: the cannon's
 * projectiles are still travelling, and a block that is still in the air is not a block a component can be written
 * into. So that hook only records where the blueprint was anchored, and it has to run at {@code HEAD} because Create
 * clears its printer, and with it the anchor, before the method returns.
 *
 * <p>{@code tickFlyingBlocks} is then what says the last projectile has landed, which together with a finished print
 * is the only honest meaning of "everything is in place". This is also the hook that reaches the cells the cannon
 * skipped because the world already matched the blueprint.
 *
 * <p>{@code require = 0} as everywhere in this package: both are another mod's methods.
 */
@Mixin(targets = "com.simibubi.create.content.schematics.cannon.SchematicannonBlockEntity")
public abstract class CreateSchematicCannonMixin {

    @Inject(method = "finishedPrinting", at = @At("HEAD"), require = 0)
    private void redstonecircuit$printingFinished(CallbackInfo callback) {
        BlueprintDeploy.printingFinished(this);
    }

    @Inject(method = "tickFlyingBlocks", at = @At("TAIL"), require = 0)
    private void redstonecircuit$flyingBlocksTicked(CallbackInfo callback) {
        BlueprintDeploy.flyingBlocksTicked(this);
    }
}
