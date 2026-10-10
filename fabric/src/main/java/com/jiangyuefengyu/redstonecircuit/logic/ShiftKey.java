package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * Whether the player is holding [Shift], as one boolean the shared code may read.
 *
 * <h2>Why this is a value and not a query</h2>
 * Our tooltips are built by {@code Item#appendHoverText} / {@code Block#appendHoverText}, and those live in
 * classes a dedicated server loads. The answer - "is [Shift] down" - is a client's keyboard, and reaching for
 * it from the shared build is exactly the mistake this mod has a rule against: a client-only class named by a
 * shared one is a server that refuses to start the moment that line runs.
 *
 * <p>The flag vanilla hands to those methods cannot answer it either. {@link net.minecraft.world.item.TooltipFlag}
 * reports whether <em>advanced tooltips</em> are on (F3+H) and whether the inventory is creative; neither is
 * the reading habit the hint asks for, and treating F3+H as [Shift] would make the details appear for players
 * who never pressed anything.
 *
 * <p>So the client publishes the one fact it can see. {@code RedstoneCircuitClient} refreshes this once per
 * client tick - a tooltip is rebuilt on every frame it is drawn, so a value at most one tick old is
 * indistinguishable from a live check, and the shared half stays free of client types.
 */
public final class ShiftKey {

    /** Read from a render thread, written from the client thread: volatile rather than merely static. */
    private static volatile boolean held;

    private ShiftKey() {
    }

    /** Called once per client tick with the keyboard's own answer. */
    public static void set(boolean down) {
        held = down;
    }

    /** Whether [Shift] was held as of the last client tick. Always false on a dedicated server. */
    public static boolean held() {
        return held;
    }
}
