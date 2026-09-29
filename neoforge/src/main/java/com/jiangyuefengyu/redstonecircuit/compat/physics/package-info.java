/**
 * Optional compatibility with mods that move blocks around as <em>physics structures</em>:
 * Sable ({@code sable}) and, through it, Create ({@code create}) and Create Aeronautics
 * ({@code aeronautics_bundled}).
 *
 * <h2>Why this package is split across two source sets</h2>
 * Inner redstone is keyed by world {@link net.minecraft.core.BlockPos}. A block that has been
 * assembled into a moving structure leaves its old position and keeps moving, so that key stops
 * meaning anything. Everything that will ever have to deal with that lives either here or in
 * {@code src/compat/java}, and the split is the whole trick:
 *
 * <ul>
 *   <li><b>{@code src/main/java/.../compat/physics} - this package, compiled always.</b> It contains
 *       exactly one class, {@link com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat},
 *       which names no Sable or Create type at all. It is the only member of the compat layer the
 *       rest of the mod may mention, and it reaches the other half <em>by name</em>
 *       ({@code Class.forName}), after a real {@code ModList} check.</li>
 *   <li><b>{@code src/compat/java} - a second Gradle source set, compiled only when Sable's nested
 *       companion jar and Create's nested catnip jar could be found on this machine.</b> Everything
 *       that names a Sable or Create type lives there: the {@code /rc physics} report, the
 *       {@code MovementBehaviour} diagnostic, and the {@code dev.ryanhcode.sable.companion}
 *       accessor. On a machine without those mods the source set is skipped entirely and the mod
 *       still builds.</li>
 * </ul>
 *
 * <h2>The two rules this layer obeys</h2>
 * <ol>
 *   <li><b>Installed and not installed behave identically.</b> Nothing in the compat source set
 *       registers an event listener, a mixin, a network channel or a data structure unless
 *       {@link net.neoforged.fml.ModList#get()}{@code .isLoaded(...)} says the other mod is there.
 *       With none of them installed, the only thing that happens is one
 *       {@link com.jiangyuefengyu.redstonecircuit.compat.physics.PhysicsCompat#init()} call, and
 *       even that loads no other class in this package.</li>
 *   <li><b>A missing mod must never be able to throw {@code NoClassDefFoundError}.</b> Sable's
 *       classes are not on the runtime classpath when Sable is absent, so nothing reachable from an
 *       unconditional path may extend, implement, or even name one of them. The one interface
 *       implementation in the compat half (a Create {@code MovementBehaviour}) lives in a class that
 *       is only resolved when Create is present.</li>
 * </ol>
 *
 * <h2>What is here now</h2>
 * Diagnostics only. Nothing moves data, changes a solve, or draws
 * anything. The point of this stage is to settle, on a real machine, the four questions
 * bytecode alone could not answer; the API research behind them is
 */
package com.jiangyuefengyu.redstonecircuit.compat.physics;
