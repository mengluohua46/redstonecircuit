package com.jiangyuefengyu.redstonecircuit.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;

/**
 * Lets inner redstone power the world outside its host block.
 *
 * <h2>Why a mixin is required</h2>
 * NeoForge 21.1.x has no hook that lets a mod make an existing vanilla block emit a redstone signal:
 * there is no {@code Capabilities.RedstoneSignal}, no {@code getSignal} event, and overriding
 * {@code Block#getSignal} only works for blocks you own. Replacing every candidate host block with a
 * modded variant would change block identity across the world, which the design rules out. A mixin
 * into {@code BlockState#getSignal} is therefore the only way to make an inner component
 * indistinguishable from a real signal source.
 *
 * <h2>What it does</h2>
 * Before the vanilla implementations of {@code getSignal} and {@code getDirectSignal} run, this checks
 * whether a component is stored inside the queried position and, if so, reports its power. Every
 * redstone consumer - pistons, lamps, wire, comparators, observers - asks exactly these two methods,
 * so they all see the inner component.
 *
 * <h2>Where it emits</h2>
 * The host emits <b>exactly what its component emits, and exactly where</b> - the same rule the inner
 * network uses, so a block is not a better signal source than the device inside it:
 *
 * <pre>
 *   dust                      every side
 *   torch                     every side except the one it hangs on
 *   repeater / comparator     only the side it points at
 *   lever / button / plate    every side
 * </pre>
 *
 * <p>So a repeater inside a block drives the block in front of it and nothing else, just like a
 * repeater on the ground.
 *
 * <h2>Directions are backwards in these methods</h2>
 * Both vanilla queries receive the direction pointing <em>from the asker towards the block being
 * asked</em>, so the signal being answered travels the other way. Everything in {@link PowerSolver} is
 * written in terms of where the signal goes, which is why the direction is flipped once, here, before
 * any rule is consulted. Getting this wrong is invisible to a weak-power test - weak power is asked
 * about one block at a time - so it only showed up as a diode charging the block behind it.
 *
 * <h2>Weak and strong are answered separately</h2>
 * Vanilla has two signal queries and they are not interchangeable: <b>weak</b> power
 * ({@code getSignal}) is only ever seen by the block it is asked about, while <b>strong</b> power
 * ({@code getDirectSignal}) is carried onwards by a solid block to everything around it. A lever has
 * no strong power at all, and only a diode charges the block in front of it. Answering both queries
 * with the inner power - which is what this mixin used to do - turned every host block into a
 * redstone block: a lever inside one charged the stone beside it, and that stone then lit whatever
 * touched it. {@link PowerSolver#directSignalToward} now keeps the two apart.
 *
 * <p>The wrench's {@code forcedOn}/{@code forcedOff} overrides apply to the emitted (weak) directions,
 * which is how a connection is pinned open or cut from the outside.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Uses {@link Inject} with an early return, never {@code @Overwrite}, so vanilla logic is left
 *       intact and co-exists with other mixins touching these methods.</li>
 *   <li>Returns immediately when the level is not a {@link ServerLevel} (the store is
 *       server-authoritative), or when the query happens while {@link InnerRedstoneNetwork} is
 *       evaluating its own power - otherwise a component would charge itself from its own value and
 *       the network could never settle.</li>
 *   <li>Allocates nothing: the rules are asked through primitive overloads, because this is the hottest
 *       signal path in the game.</li>
 * </ul>
 *
 * <h2>Why the target is {@code BlockStateBase}</h2>
 * {@code getSignal} and {@code getDirectSignal} are declared on
 * {@code BlockBehaviour.BlockStateBase}, not on {@code BlockState} itself, and Mixin's target search
 * does not walk up to superclass declarations. Targeting {@code BlockState} therefore fails at load
 * time with "could not find any targets matching 'getSignal'". Targeting the declaring class is what
 * actually patches every block state, since all of them are instances of it.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateSignalMixin {

    @Inject(method = "getSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerSignal(BlockGetter level, BlockPos pos, Direction direction,
                                             CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction, false);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    @Inject(method = "getDirectSignal", at = @At("HEAD"), cancellable = true)
    private void redstonecircuit$innerDirectSignal(BlockGetter level, BlockPos pos, Direction direction,
                                                   CallbackInfoReturnable<Integer> cir) {
        int power = redstonecircuit$innerPower(level, pos, direction, true);
        if (power > 0) {
            cir.setReturnValue(power);
        }
    }

    /**
     * Shared body: the inner component's power, or 0 when this position should behave normally.
     *
     * @param strong whether the caller is the strong-power query ({@code getDirectSignal})
     */
    private static int redstonecircuit$innerPower(@Nullable BlockGetter level, BlockPos pos,
                                                  @Nullable Direction direction, boolean strong) {
        if (!(level instanceof ServerLevel serverLevel) || direction == null) {
            return 0;
        }
        if (InnerRedstoneNetwork.isSignalSuppressed(serverLevel)) {
            return 0;
        }

        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        if (store == null || store.isEmpty()) {
            return 0;
        }
        Slot slot = store.slotAt(pos);
        if (slot == null) {
            return 0;
        }

        // Signalling methods are backwards: `direction` points from the querier to this block, so the
        // signal this answers travels towards its opposite. Everything in PowerSolver is written in
        // terms of that emission direction, so it is converted once, here.
        Direction emitted = direction.getOpposite();
        if (strong) {
            if (!PowerSolver.directSignalToward(slot.type, slot.facing, emitted,
                    PowerSolver.Locks.of(slot, emitted))) {
                return 0;
            }
        } else {
            if (!PowerSolver.emitsToward(slot, emitted)) {
                return 0;
            }
            // No extra "not upwards" rule here, even though vanilla wire has one: a host block is a
            // signal source, and every direction has to behave the same way or a component inside a
            // block would be the only one in the game that cannot drive the block above it.
        }
        return store.getSignal(pos);
    }
}
