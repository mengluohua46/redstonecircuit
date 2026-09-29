package com.jiangyuefengyu.redstonecircuit.compat.physics;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierId;
import com.jiangyuefengyu.redstonecircuit.carrier.CarrierPose;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The compat half's entry point: the class {@code PhysicsCompat} reaches by name.
 *
 * <h2>What it is for</h2>
 * This is where the compat diagnostics actually live. It exists as a
 * separate class - in a separate Gradle source set, compiled only when the optional mods' nested jars
 * are available - for one reason: <b>every Sable and Create type is named in this source set and
 * nowhere else</b>, so a machine that has never installed those mods compiles and runs the mod
 * without ever resolving them.
 *
 * <h2>What it deliberately does not do</h2>
 * <p>It registers nothing with Sable, subscribes to no event, adds no mixin, and changes no data. The
 * one thing it writes into another mod's registry is a {@code MovementBehaviour} whose only body is a
 * log line (see {@link MovementHostHook}) - the diagnostic P1 needs and nothing more.
 *
 * <h2>The two rules from P0 that still hold</h2>
 * <ol>
 *   <li>Nothing here extends or implements a Sable or Create type. {@link MovementHostHook} has one
 *       anonymous {@code MovementBehaviour}, but it is an anonymous class inside a class that is only
 *       loaded when Create is, so the eager supertype resolution P0 warned about cannot happen when
 *       Create is absent.</li>
 *   <li>Nothing here runs unless {@code ModList} says the mod is there. Every public entry point
 *       checks first.</li>
 * </ol>
 */
public final class PhysicsCompatBackend {

    private PhysicsCompatBackend() {
    }

    /**
     * Called by {@code PhysicsCompat.init()} once a mod-list check has passed.
     *
     * <p>Registers the contraption diagnostic if Create is present. Sable alone needs nothing
     * registered: everything on that side is a query, and {@code /rc physics} runs it on demand.
     */
    public static void init() {
        boolean sable = PhysicsCompat.sableLoaded();
        boolean create = PhysicsCompat.createLoaded();

        if (create) {
            // MovementBehaviour.REGISTRY is pure Create - it does not need Sable - and P0 established
            // it has no freeze check, so a per-block registration later on is legitimate. There is
            // nothing to register yet: the hook is attached the first time a block type actually
            // holds a component (see hostBlockStored).
            RCConfig.LOGGER.info("[redstonecircuit] contraption diagnostics armed: a MovementBehaviour "
                    + "will be registered per host block type on first placement");
        }
        RCConfig.LOGGER.info("[redstonecircuit] physics compat backend loaded: sable={} create={} "
                + "aeronautics={}", sable, create, PhysicsCompat.aeronauticsLoaded());

        // The carrier layer: the half of P1 that actually moves data. Installed only once a source can
        // answer "where is this structure", so with neither mod present none of it is ever reached -
        // CarrierStore.isActive() stays false and the hooks below were never registered.
        if (RCConfig.carrierEnabled()) {
            CarrierResolver.install();
            if (sable) {
                // P1 only ever learned about a Sable sub-level through the orphan sweep. This is the
                // accounting that replaces it: the container's own add/remove observer, the
                // container-ready event, and Sable's physics tick as P2's sub-level solve driver.
                SableSubLevelAccounting.install();
            }
        } else {
            RCConfig.LOGGER.info("[redstonecircuit] carrierEnabled=false: inner redstone will stay "
                    + "behind when a structure picks its block up");
        }
    }

    /**
     * Called from the placement paths the first time a block type holds a component.
     *
     * <p>Does nothing unless Create is installed, because both hooks are Create's: the movement hook that
     * makes the component travel with the block, and the interaction hook that lets a carried host be
     * operated with the wrench and the rotation rod from a contraption. They are registered together
     * because they are wanted under exactly the same condition - a block type that has ever held a
     * component - and a block type that travels but cannot be operated would be a gap nobody could
     * explain from the log.
     */
    public static void hostBlockStored(ServerLevel level, BlockPos pos, Block block) {
        if (!PhysicsCompat.createLoaded()) {
            return;
        }
        MovementHostHook.register(block);
        CarrierToolHook.register(block);
    }

    /**
     * <b>P1.57: the pose Sable is drawing a sub-level at, in this frame - the entry point the carried
     * render pass asks through {@code PhysicsCompat}.</b>
     *
     * <p><b>This method existing is the whole point.</b> {@code PhysicsCompat} reaches this class by name
     * and reflects on it, so a name that is only on {@code CarrierResolver} is a name that is never found:
     * the first version of this round added the query there and not here, and the author's log carried
     * <b>3625</b> "physics compat: …renderPoseOf failed" warnings on the render thread - one per host per
     * frame - while the render silently kept using the network pose. Every gated call needs its entry
     * point here, and {@code PhysicsCompatBackendWiringTest} now asserts exactly that, for every name.
     *
     * <p>Gated on Sable because only Sable can answer it; a Create contraption has no such pose and the
     * caller keeps the network one.
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
            out.add("carried-tool hooks registered for " + CarrierToolHook.registeredCount()
                    + " block type(s)   (a right-click on a contraption block of one of those types"
                    + " reaches the carrier-side wrench / rotation rod)");
            // P1.23: the count above is the number of block types the registration was *attempted* for,
            // because a refused type is deliberately never retried (see CarrierHookLedger). The ledger
            // below separates accepted from refused and names every refused type, which is the difference
            // between "no block of this type has ever held a component" and "another mod owns the
            // behaviour for it, so a contraption click on it can never reach us".
            out.addAll(CarrierToolHook.hookReport());

            BlockState state = level.getBlockState(pos);
            out.add("worldState = " + state + (state.isAir() ? "   (AIR)" : ""));

            if (!PhysicsCompat.sableLoaded()) {
                out.add("Sable is not installed: no sub-level queries available.");
                out.addAll(SableReport.describeStore(level));
                return out;
            }

            // --- 2. getContaining semantics ------------------------------------------------------
            out.add(section("getContaining semantics (P0 open question 3)"));
            out.addAll(SableReport.describeContaining(level, pos));

            // --- 3. sub-level enumeration + coordinate round trip ---------------------------------
            out.add(section("sub-levels, poses and the world->local->world round trip (P0 open questions 2 and 4)"));
            out.add(SableReport.summary(level, pos));
            out.addAll(SableReport.describeSubLevels(level, pos));

            // --- 4. store -------------------------------------------------------------------------
            out.add(section("store (what P1 has to move)"));
            out.addAll(SableReport.describeStore(level));

            // --- 5. carriers (what P1 does move) --------------------------------------------------
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
            out.add(section("sable accounting (P2)"));
            out.addAll(accountingReport(level));
            return out;
        } catch (RuntimeException | LinkageError failed) {
            RCConfig.LOGGER.warn("[redstonecircuit] /rc carrier failed", failed);
            return List.of("carrier diagnostics failed: " + failed);
        }
    }

    /**
     * P2's Sable accounting, as lines ready to print.
     *
     * <p>Separate from {@link #carrierReport} because it is about a different question: not "what is
     * being carried" but "is the mod hearing about sub-levels being created and destroyed". The P1
     * answer to the second was "only through the orphan sweep", which is why the brief asks for it
     * explicitly.
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
