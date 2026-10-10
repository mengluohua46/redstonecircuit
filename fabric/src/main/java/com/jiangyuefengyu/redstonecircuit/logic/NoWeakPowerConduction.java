package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * A block that answers a signal query with what it emits and nothing else.
 *
 * <h2>The rule this opts out of</h2>
 * A signal query about a position is answered by the block standing there <em>plus</em> the strong power its
 * neighbours push into it. That is right for a block of stone: a redstone torch charges the block it is
 * attached to, and dust laid against that block sees the charge.
 *
 * <p>It is wrong for a device whose faces mean different things. Dust at a plate's front would charge the
 * plate, the plate would re-emit that charge on <b>all six faces</b>, and the back - which is the device's
 * <em>output</em> - would be read back as an input one step later. A gate fares no better: with its input
 * and output wired to the same wire, the re-emitted charge reaches the face its input is on, and an inverter
 * whose two settings are equal switches its own input off every tick.
 *
 * <p>A device is the component inside it, not a block of stone, so its answer is only ever its own. Nothing
 * else changes: dust still draws its line to the block, and the device still reads its neighbours' strong
 * power - this is about what it <em>reports</em>, not about what it can see.
 *
 * <h2>How the opt-out is expressed</h2>
 * As a marker rather than as an overridden method, because the query that adds the weak-power term
 * ({@code SignalGetter#getSignal}) is a <em>default method on an interface</em>: a block cannot override it,
 * and vanilla has no hook to opt out of that one term. So the place the term is added is patched instead -
 * see {@link com.jiangyuefengyu.redstonecircuit.mixin.SignalGetterMixin} - and this interface is how that
 * patch recognises the blocks it must not add it for.
 */
public interface NoWeakPowerConduction {
}
