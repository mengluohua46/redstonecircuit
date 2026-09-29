package com.jiangyuefengyu.redstonecircuit.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.jiangyuefengyu.redstonecircuit.carrier.CarrierProjection;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierStore;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.BlockSignalSources;
import com.jiangyuefengyu.redstonecircuit.logic.InnerRedstoneNetwork;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
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
 * Before the vanilla implementations of {@code getSignal} and {@code getDirectSignal} run, this asks
 * {@link BlockSignalSources} whether a component is stored inside the queried position and, if so,
 * reports its power. Every redstone consumer - pistons, lamps, wire, comparators, observers - asks
 * exactly these two methods, so they all see the inner component.
 *
 * <p>The mixin is deliberately only three things: two injections, the two lookups the decision needs
 * (the world store and the carrier tables), and the strong-power question's extra neighbour lookup.
 * <b>Which</b> answer wins is {@link BlockSignalSources}' business, so that rule is pinned by offline
 * tests instead of only being reachable behind a running server with mixins applied.
 *
 * <p>Since P4 there is a second place a component can be "at" a world position: a structure that is
 * carrying its host block has that block at a world coordinate which the world itself sees as air, so
 * the query can also be answered from {@code CarrierStore}'s projection of the machine. That is
 * read-only - the world's blocks are never touched - and it is what lets a machine light the lamp it
 * flies over.
 *
 * <p>Since P1.36 there is a third: the same carried host, asked about at its coordinate <em>inside</em>
 * the structure. A vanilla device the machine carries is a real block of the level (a Sable sub-level's
 * blocks live at their plot coordinates, which are level coordinates), so the author's piston asks the
 * host block beside it at the structure's own coordinate and is answered with the same rule, in the
 * same frame the question was asked in.
 *
 * <p>Since P1.38 the suppression window hides the world store and nothing else. It used to answer zero
 * to every query while the mod's own solver was reading the world, which also hid the projection and the
 * structure frame. Every carrier-side read runs inside that window, so a component inside one physicalised
 * structure could never read one inside its neighbour: the seam was dark by construction. The world store
 * still goes quiet there - a component must not read its own output back - but the two carrier sources are
 * other components. See {@link BlockSignalSources}.
 *
 * <p><b>Since P1.44 the answer a vanilla redstone wire receives is charged its own hop.</b> Vanilla
 * charges a wire-to-wire hop to the wire that <em>receives</em> it, but its scan only recognises a
 * neighbour whose block state is a redstone wire, so a component hidden inside a host block entered that
 * arithmetic as a source at full strength: the wire read the host at fifteen while the host read the wire
 * at fourteen, and a line of them held each other up. This is the one place the reader's identity is
 * known - it is the block one step back along the query direction - so it is where the charge is applied;
 * the arithmetic itself is {@link BlockSignalSources#hopChargedByReader} and {@code PowerSolver#afterHop}.
 *
 * <p><b>Since P1.46 the projection-sourced answer is charged too.</b> That gate names the reader by its
 * <em>block state</em>, which works for a vanilla wire and for nothing else: the reader of a seam is a
 * carried host, whose world cell is air (P1.38). The projection is answered by <b>two</b> halves of one
 * merged read, and P1.45 charged only the explicit one, so this half handed the neighbour over at full
 * strength in both directions and two independently physicalised blocks held each other at fifteen with no
 * supply at all - a loop with a gain of exactly one. The charge here is that same seam rule, with the
 * reader found the way P1.45 finds it ({@code InnerRedstoneNetwork#seamReaderAt}): see
 * {@link BlockSignalSources#projectionChargedByReader}.
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
 * asked</em>, so the signal being answered travels the other way. Everything in {@code PowerSolver} is
 * written in terms of where the signal goes, which is why the direction is flipped once, inside the
 * rule, before any rule is consulted. Getting this wrong is invisible to a weak-power test - weak power
 * is asked about one block at a time - so it only showed up as a diode charging the block behind it.
 *
 * <h2>Weak and strong are answered separately</h2>
 * Vanilla has two signal queries and they are not interchangeable: <b>weak</b> power
 * ({@code getSignal}) is only ever seen by the block it is asked about, while <b>strong</b> power
 * ({@code getDirectSignal}) is carried onwards by a solid block to everything around it. A lever has
 * no strong power at all, and only a diode charges the block in front of it. Answering both queries
 * with the inner power - which is what this mixin used to do - turned every host block into a
 * redstone block: a lever inside one charged the stone beside it, and that stone then lit whatever
 * touched it. {@code PowerSolver#directSignalToward} now keeps the two apart.
 *
 * <p>The wrench's {@code forcedOn}/{@code forcedOff} overrides apply to the emitted (weak) directions,
 * which is how a connection is pinned open or cut from the outside.
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Uses {@link Inject} with an early return, never {@code @Overwrite}, so vanilla logic is left
 *       intact and co-exists with other mixins touching these methods.</li>
 *   <li>Returns immediately when the level is not a {@link ServerLevel} (the store is
 *       server-authoritative), or when the query happens while {@code InnerRedstoneNetwork} is
 *       evaluating its own power - the world store stays quiet there; see above.</li>
 *   <li>Allocates nothing on the ordinary paths: a query the world store answers builds no object at
 *       all beyond the store lookup it always did.</li>
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
     * Shared body: gather what the three sources hold at this position, ask
     * {@link BlockSignalSources} which one answers, and report that.
     *
     * <p>This method used to carry the whole rule, including which frame each source answers in. It is
     * now two lookups and one question to the rule, so that the rule - the part that decides - is
     * reachable from an offline test. Nothing about the answer changed with the move; what changed with
     * P1.38 is the suppression window, which is passed in as one boolean and hides one source.
     *
     * @param strong whether the caller is the strong-power query ({@code getDirectSignal})
     */
    private static int redstonecircuit$innerPower(@Nullable BlockGetter level, BlockPos pos,
                                                  @Nullable Direction direction, boolean strong) {
        if (!(level instanceof ServerLevel serverLevel) || direction == null) {
            return 0;
        }
        // P1.38: the suppression window hides the WORLD STORE only. The two carrier sources are other
        // components, not this solve's own output, and they are the only route by which a component
        // inside one physicalised structure reads one inside its neighbour - every carrier-side read
        // runs inside this window (CarrierWorldIo -> CarrierWorldSignals -> Level#getSignal), so a
        // window that answered nothing everywhere made the seam permanently dark. See
        // BlockSignalSources, which owns the rule and the argument.
        boolean worldStoreSuppressed = BlockSignalSources.suppressWorldStore();

        InnerRedstoneStore store = InnerRedstoneStore.get(serverLevel);
        if (store == null) {
            return 0;
        }

        Slot slot = worldStoreSuppressed || store.isEmpty() ? null : store.slotAt(pos);
        CarrierProjection.ProjectedHost projected = null;
        CarrierProjection.ProjectedHost inStructure = null;
        boolean structureFrameDeclined = false;
        if (slot == null) {
            try {
                projected = CarrierStore.projectedHostAt(serverLevel, pos);
                if (projected == null) {
                    // P1.36: and the second position a carried host answers at - its coordinate inside
                    // the structure, which for a Sable sub-level is a real level position. This is the
                    // query a vanilla device the machine is carrying makes: the piston above the
                    // redstone asks about the host block beside it, in the structure's own frame. The
                    // world position answered above and this one are different numbers and never the
                    // same question; see CarrierProjection#atStructure. The lookup is ownership-guarded
                    // (P1.37): a coordinate belonging to another live sub-level is refused there and
                    // left to the world projection source, which is what lets two independently
                    // physicalised blocks keep reading each other.
                    inStructure = CarrierStore.structureHostAt(serverLevel, pos);
                    structureFrameDeclined = CarrierStore.lastStructureFrameDeclined();
                }
            } catch (RuntimeException | LinkageError failed) {
                // The bridge is a bonus over the frozen behaviour: a failure in it must look like a jar
                // without P4, not like a broken world.
                return 0;
            }
        }

        // Strong power asks one question more: whether the block doing the asking holds a component of
        // its own, because a host block is the component inside it and not a block of stone. A carried
        // host counts, whether or not the block asking is one - at its world position or at the
        // structure's own coordinate. The rule only consumes the answer, so the lookup lives here.
        boolean neighbourHoldsComponent = false;
        if (strong) {
            BlockPos asker = pos.relative(direction.getOpposite());
            neighbourHoldsComponent = store.slotAt(asker) != null
                    || CarrierStore.isProjectedAt(serverLevel, asker)
                    || CarrierStore.isStructureHostAt(serverLevel, asker);
        }

        BlockSignalSources.Read read = BlockSignalSources.select(slot, projected, inStructure,
                structureFrameDeclined, worldStoreSuppressed, direction, strong,
                neighbourHoldsComponent);
        int answer = read.answer();
        // P1.44: THE HOP THE READER CHARGES, AND WHO THE READER IS. Everything above decided which
        // source answers and with what; this decides what the *asking block* does with it. A vanilla
        // redstone wire charges one step for a neighbouring wire, but its own scan only recognises a
        // neighbour whose block state is a redstone wire - so a component inside a host block was read
        // as a source at full strength and a line of them held each other at fifteen. See
        // `BlockSignalSources#hopChargedByReader`, which owns the arithmetic, and `PowerSolver#afterHop`,
        // which is the one rule behind it.
        //
        // The extra block read is behind the gate that can make it matter at all: a weak query, an
        // answer above zero, and a component that is itself a wire. An ordinary query - most of the
        // hottest path in the game - never reaches it, and neither does the strong question (a wire
        // gives no direct signal, so the strong branch is not part of a wire-to-wire hop).
        //
        // P1.46: AND THE ONE SOURCE WHOSE READER IS NOT A BLOCK STATE. P1.44 could charge the hop
        // because the reader was a vanilla redstone wire, i.e. a block of the level, and `getBlockState`
        // names it. The projection source is read by a component of ours as well: two independently
        // physicalised blocks one world cell apart read each other through this very source (P1.38), and
        // the reader's world cell is air - so the gate above answers "not a wire", no hop is charged, and
        // `Math.max(vanilla, projected)` in `InnerRedstoneNetwork#worldSignalAt` keeps this uncharged copy
        // over the one P1.45 charged in the explicit projected half. The pair then reads each other at
        // full strength in BOTH directions, which is a loop with a gain of exactly one: every value is a
        // fixed point, so it held fifteen after the supply was taken away and only came down when the
        // blocks were moved apart. The charge is P1.45's own seam rule, with the reader found the same
        // way P1.45 finds it - the bridge names it inside a structure, and the asker is otherwise one
        // step back along the query direction. See `BlockSignalSources#projectionChargedByReader`.
        if (!strong && answer > 0 && read.slot() != null && read.slot().type.isWire()) {
            BlockPos reader = pos.relative(direction.getOpposite());
            boolean readerIsRedstoneWire = serverLevel.getBlockState(reader).is(Blocks.REDSTONE_WIRE);
            if (read.source() == BlockSignalSources.Source.WORLD_PROJECTION) {
                answer = BlockSignalSources.projectionChargedByReader(read.host(), answer,
                        InnerRedstoneNetwork.seamReaderAt(serverLevel, pos, direction),
                        readerIsRedstoneWire);
            } else {
                answer = BlockSignalSources.hopChargedByReader(read.slot(), answer, readerIsRedstoneWire);
            }
        }
        if (read.fromCarrier() || read.structureFrameDeclined() || read.projectionHit()) {
            CarrierStore.noteSignalRead(serverLevel, read, pos, direction, answer);
        }
        return answer;
    }
}
