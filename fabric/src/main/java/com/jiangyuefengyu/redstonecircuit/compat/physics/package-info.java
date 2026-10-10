/**
 * Optional compatibility with mods that move blocks around as <em>physics structures</em>:
 * Sable ({@code sable}) and, through it, Create ({@code create}) and Create Aeronautics ({@code aeronautics_bundled}).
 * <p>Inner redstone is keyed by world {@link net.minecraft.core.BlockPos}, and a block assembled into a moving
 * structure leaves that position, so the key stops meaning anything. Hence the split: this package is compiled always
 * and names no Sable or Create type - it reaches {@code src/compat/java}, which is compiled only when Sable's
 * companion jar and Create's catnip jar were found, by name ({@code Class.forName}) after a {@code ModList} check.
 * <p>Nothing is registered unless {@code ModList} says the other mod is loaded, and nothing on an unconditional path
 * may name one of its types, or a missing mod would throw {@code NoClassDefFoundError}. Diagnostics only.
 */
package com.jiangyuefengyu.redstonecircuit.compat.physics;
