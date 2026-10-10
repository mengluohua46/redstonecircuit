package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The compat half's entry point: the class {@code PhysicsCompat} reaches by name.
 *
 * <p>It lives in its own source set, compiled only when the optional mods' nested jars are available, for one reason:
 * <b>every Sable and Create type is named in this source set and nowhere else</b>, so a machine that has never
 * installed those mods compiles and runs without ever resolving them. Nothing here extends or implements a Sable or
 * Create type - the one anonymous {@code MovementBehaviour} lives inside a class that is only loaded when Create is -
 * and no public entry point runs unless {@code ModList} says the mod is there. It registers nothing with Sable,
 * subscribes to no event, adds no mixin and changes no data.
 */
public final class PhysicsCompatBackend {

    private PhysicsCompatBackend() {
    }

    /**
     * Called by {@code PhysicsCompat.init()} once a mod-list check has passed. Registers the contraption diagnostic if
     * Create is present; Sable alone needs nothing registered, because everything on that side is a query.
     */
    public static void init() {
        boolean sable = PhysicsCompat.sableLoaded();
        boolean create = PhysicsCompat.createLoaded();

        if (create) {
            // MovementBehaviour.REGISTRY is pure Create and has no freeze check, so a per-block registration later on
            // is legitimate. Nothing is registered yet: the hook is attached the first time a block type actually
            // holds a component (see hostBlockStored).
            RCConfig.LOGGER.info("[redstonecircuit] contraption diagnostics armed: a MovementBehaviour "
                    + "will be registered per host block type on first placement");
        }
        RCConfig.LOGGER.info("[redstonecircuit] physics compat backend loaded: sable={} create={} "
                + "aeronautics={}", sable, create, PhysicsCompat.aeronauticsLoaded());

        // The carrier layer, installed only once a source can answer "where is this structure": with neither mod
        // present none of it is ever reached - CarrierStore.isActive() stays false and no hook was registered.
        if (RCConfig.carrierEnabled()) {
            CarrierResolver.install();
            if (sable) {
                // The accounting for Sable sub-levels: the container's own add/remove observer, the container-ready
                // event, and Sable's physics tick as the sub-level solve driver.
                SableSubLevelAccounting.install();
            }
        } else {
            RCConfig.LOGGER.info("[redstonecircuit] carrierEnabled=false: inner redstone will stay "
                    + "behind when a structure picks its block up");
        }
    }

    /**
     * <b>The product's carrier registration, at a point where the block registry is full.</b> It cannot happen in
     * {@link #init()}: that runs from the mod constructor, and reaching for our own block there throws
     * "Trying to access unbound value".
     */
    public static void installProductCarrier() {
        if (PhysicsCompat.createLoaded()) {
            MovementProductHook.install();
        }
    }

    /**
     * <b>The placed structures a level does not tick.</b> It is a separate entry point because {@code PhysicsCompat}
     * reaches this class <em>by name</em>: a query implemented only on the class behind it is never found, and the
     * failure is a counted one rather than a loud one.
     */
    public static void tickCarriedProducts(ServerLevel level, ServerLevel pocket) {
        SableProductDriver.tick(level, pocket);
    }

    /**
     * <b>The block a carrier draws at a world position, asked as a signal source.</b> Entry point for
     * {@code PhysicsCompat#carriedSignalAt}, by name like every other one. A failure of the bridge is a world that
     * answers nothing extra, never a broken query.
     */
    public static int carriedSignalAt(ServerLevel level, BlockPos worldPos,
                                      net.minecraft.core.Direction worldDirection, boolean strong) {
        if (!PhysicsCompat.sableLoaded()) {
            return 0;
        }
        try {
            return SableProductDriver.signalAt(level, worldPos, worldDirection, strong);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carried block lookup at {} failed: {}", worldPos.toShortString(),
                    failed.toString());
            return 0;
        }
    }

    /**
     * <b>The block a carrier draws at a world position, asked as a comparator source.</b> The analogue question is what
     * a comparator's back input is made of, and a drawn physicalised block answers 0 to it without this walk - a
     * steering angle is a number only this question can carry.
     */
    public static int carriedAnalogAt(ServerLevel level, BlockPos worldPos,
                                      net.minecraft.core.Direction worldDirection) {
        if (!PhysicsCompat.sableLoaded()) {
            return 0;
        }
        try {
            return SableProductDriver.analogAt(level, worldPos, worldDirection);
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] carried analogue lookup at {} failed: {}",
                    worldPos == null ? "?" : worldPos.toShortString(), failed.toString());
            return 0;
        }
    }

    /**
     * Called from the placement paths the first time a block type holds a component. Does nothing unless Create is
     * installed, because both hooks are Create's: the movement hook that makes the component travel with the block, and
     * the interaction hook that lets a carried host be operated with the wrench and the rotation rod from a contraption.
     * They are registered together because they are wanted under exactly the same condition - a block type that has ever
     * held a component - and a block type that travels but cannot be operated would be a gap nobody could explain.
     */
    /**
     * 方块放置器's physical half: the one block goes into a brand-new sub-level of its own.
     *
     * <p>Returns false when nothing was placed; a structure that was made and then refused is taken back out again.
     */
    public static boolean placeSingleBlockStructure(ServerLevel level, BlockPos drawnTarget, Direction out,
                                                    ItemStack stack) {
        if (!PhysicsCompat.sableLoaded()) {
            return false;
        }
        return SablePlacerPlacement.place(level, drawnTarget, out, stack);
    }

    /**
     * Drop one chunk's saved sub-level record - the repair for a dangling pointer Sable reports as
     * "Failed to load sub_level". Returns what the repair did, in words the player can read, or null when Sable is not
     * there.
     */
    public static String purgeHoldingChunk(ServerLevel level, int chunkX, int chunkZ) {
        if (!PhysicsCompat.sableLoaded()) {
            return null;
        }
        net.minecraft.world.level.ChunkPos chunk = new net.minecraft.world.level.ChunkPos(chunkX, chunkZ);
        return SableStorageRepair.purgeHoldingChunk(level, chunk).describe(chunk);
    }

    public static void hostBlockStored(ServerLevel level, BlockPos pos, Block block) {
        if (!PhysicsCompat.createLoaded()) {
            return;
        }
        MovementHostHook.register(block);
        CarrierToolHook.register(block);
    }

    /**
     * <b>The pose Sable is drawing a sub-level at, in this frame - the entry point the carried render pass asks through
     * {@code PhysicsCompat}.</b>
     *
     * <p>{@code PhysicsCompat} reaches this class by name and reflects on it, so a name that is only on
     * {@code CarrierResolver} is a name that is never found: the query then fails once per host per frame while the
     * render silently keeps the network pose. Gated on Sable, because only Sable can answer it; a Create contraption has
     * no such pose and the caller keeps the network one.
     */
    public static CarrierPose renderPoseOf(Level level, CarrierId carrier, float partialTick) {
        if (!PhysicsCompat.sableLoaded()) {
            return null;
        }
        return CarrierResolver.renderPoseOf(level, carrier, partialTick);
    }

    /**
     * {@code /rc physics [pos]}: the whole diagnostic, as lines ready to print.
     *
     * <p>Read-only. Never throws: a diagnostic that can crash the server is worse than no diagnostic.
     */
    public static List<String> physicsReport(ServerLevel level, BlockPos pos) {
        List<String> out = new ArrayList<>();
        try {
            out.add(PhysicsCompat.describe());
            out.add("target = " + pos.toShortString() + " in " + level.dimension().location());
            out.add("contraption hooks registered for " + MovementHostHook.registeredCount() + " block type(s)");
            // Is a placed structure pickable by a contraption, and is its plot protected while it rides? Both come
            // from the same registration, so one line covers both.
            out.add("placed structure blocks on contraptions: "
                    + (MovementProductHook.installed()
                            ? "MOVABLE + plot protected while travelling"
                            : "not registered (Create missing, or the registration was refused)"));
            out.add("carried-tool hooks registered for " + CarrierToolHook.registeredCount()
                    + " block type(s)   (a right-click on a contraption block of one of those types"
                    + " reaches the carrier-side wrench / rotation rod)");
            // The count above is the number of block types the registration was *attempted* for, because a refused
            // type is never retried. The ledger below separates accepted from refused and names every refused type,
            // which is the difference between "no block of this type has ever held a component" and "another mod owns
            // the behaviour for it, so a contraption click on it can never reach us".
            out.addAll(CarrierToolHook.hookReport());

            BlockState state = level.getBlockState(pos);
            out.add("worldState = " + state + (state.isAir() ? "   (AIR)" : ""));

            if (!PhysicsCompat.sableLoaded()) {
                out.add("Sable is not installed: no sub-level queries available.");
                out.addAll(SableReport.describeStore(level));
                return out;
            }

            // --- 2. getContaining semantics ------------------------------------------------------
            out.add(section("getContaining semantics"));
            out.addAll(SableReport.describeContaining(level, pos));

            // --- 3. sub-level enumeration + coordinate round trip ---------------------------------
            out.add(section("sub-levels, poses and the world->local->world round trip"));
            out.add(SableReport.summary(level, pos));
            out.addAll(SableReport.describeSubLevels(level, pos));

            // --- 4. store -------------------------------------------------------------------------
            out.add(section("store (what the carrier layer has to move)"));
            out.addAll(SableReport.describeStore(level));

            // --- 5. carriers (what the carrier layer moves) ----------------------------------------
            out.add(section("carriers (what is riding a moving structure right now)"));
            out.addAll(CarrierDiagnostics.describe(level, pos));
            return out;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] /rc physics failed at {}", pos, failed);
            out.add("diagnostics failed: " + failed);
            return out;
        }
    }

    /**
     * {@code /rc carrier}: the same carrier section, without needing to point at a block.
     *
     * <p>Exists because the interesting question during an acceptance run - "is the machine's block
     * actually being carried, and where does the mod think the machine is" - is about a structure that
     * is usually somewhere else entirely, so aiming at a block is the wrong gesture.
     */
    public static List<String> carrierReport(ServerLevel level) {
        try {
            List<String> out = new ArrayList<>(CarrierDiagnostics.describe(level, null));
            out.addAll(CarrierDiagnostics.solveFailures());
            out.add(section("sable accounting"));
            out.addAll(accountingReport(level));
            return out;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] /rc carrier failed", failed);
            return List.of("carrier diagnostics failed: " + failed);
        }
    }

    /**
     * Sable accounting, as lines ready to print.
     *
     * <p>Separate from {@link #carrierReport} because it answers a different question: not "what is being carried" but
     * "is the mod hearing about sub-levels being created and destroyed".
     */
    public static List<String> accountingReport(ServerLevel level) {
        List<String> out = new ArrayList<>();
        try {
            if (!PhysicsCompat.sableLoaded()) {
                out.add("sable accounting: not applicable (Sable is not installed; a Create contraption "
                        + "is accounted for by its own MovementBehaviour)");
                return out;
            }
            out.add("sable accounting: containersObserved=" + SableSubLevelAccounting.observedContainerCount()
                    + " levels=" + SableSubLevelAccounting.describedLevels());
            out.add("  onSubLevelAdded reconciles a sub-level that was already on disk when the world "
                    + "loaded; onSubLevelRemoved releases its hosts to their recorded origins");
            out.add("  (the orphan sweep every 40 ticks is unchanged and remains the backstop)");
            // The two-phase identity's own state: a structure claimed before its contraption entity
            // existed waits here until the next level tick re-labels it. A number that does not fall to
            // zero is the one shape of "the claim is safe but the structure is not being drawn".
            out.add("provisional identities waiting for a contraption entity: "
                    + CarrierResolver.provisionalCount()
                    + "   (0 = every claim is filed under a real entity UUID)");
            return out;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] /rc carrier accounting report failed", failed);
            return List.of("sable accounting failed: " + failed);
        }
    }

    /**
     * One line about a carrier that is still filed under a provisional identity, for
     * {@code /rc carrier}.
     *
     * <p>The two-phase identity's own diagnostic: a claim made before the contraption entity existed
     * has no pose to print, so without this line it would read as a broken carrier rather than as a
     * claim waiting to be settled. What it prints is which structure the fallback id belongs to.
     */
    public static String provisionalDescription(
            com.jiangyuefengyu.redstonecircuit.carrier.CarrierId carrier) {
        try {
            return CarrierResolver.describeProvisional(carrier);
        } catch (RuntimeException | LinkageError failed) {
            return "(provisional lookup failed: " + failed + ")";
        }
    }

    private static String section(String title) {
        return "--- " + title + " ---";
    }
}
