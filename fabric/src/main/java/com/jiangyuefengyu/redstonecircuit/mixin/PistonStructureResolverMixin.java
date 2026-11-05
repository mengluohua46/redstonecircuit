package com.jiangyuefengyu.redstonecircuit.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.logic.PistonHostTransfer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.piston.PistonStructureResolver;

/**
 * The one place where a piston's movement is known <b>before</b> the blocks move: this resolver.
 *
 * <p>NeoForge's {@code PistonEvent} cannot carry it. Its {@code getStructureHelper()} builds a <em>second</em> resolver,
 * and calling {@code resolve()} on that one is not a read: for a retraction vanilla deletes the piston head as part of
 * resolving. Asking it therefore both changes the world behind vanilla's back and answers {@code false} for a pull that
 * then succeeds anyway - measured, not assumed: a sticky piston reported {@code extend=false resolved=false} and still
 * pulled its block back. What this injects into is the resolution vanilla itself is about to use.
 *
 * <p>{@code pushDirection} is the direction the blocks <b>travel</b> for both moves (a retraction resolves with the
 * opposite of the piston's facing), which is exactly what the transfer needs - so nothing here has to flip anything.
 */
@Mixin(PistonStructureResolver.class)
public abstract class PistonStructureResolverMixin {

    @Shadow private Level level;

    @Shadow private BlockPos pistonPos;

    @Shadow private Direction pushDirection;

    @Inject(method = "resolve", at = @At("RETURN"))
    private void redstonecircuit$carryComponentsWithTheirBlocks(CallbackInfoReturnable<Boolean> callback) {
        if (!callback.getReturnValueZ() || !PistonHostTransfer.insideAMove()) {
            // Either nothing is going to move, or this is the yes/no resolution {@code checkIfExtend} asks before any
            // move is decided. Both leave every block where it is, so there is nothing to carry.
            return;
        }
        PistonHostTransfer.blocksAreAboutToMove(this.level, this.pistonPos, this.pushDirection,
                ((PistonStructureResolver) (Object) this).getToPush());
    }
}
