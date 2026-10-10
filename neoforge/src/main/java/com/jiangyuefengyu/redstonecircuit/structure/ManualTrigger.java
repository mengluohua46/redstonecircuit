package com.jiangyuefengyu.redstonecircuit.structure;

/**
 * The rules a manual press is checked against - the design's "服务端校验（距离 / 维度 / 持有者、限流）", as one pure
 * decision over values the caller has already looked up, so the rules are testable without a running server.
 *
 * <p>The four refusals are separate: the mode (an activation-face structure has no keys), the entry (an index the
 * table does not have - the one a hand-written packet attacks, so it is checked before the table is read), the
 * distance (measured from the <b>player's eyes</b>, because that is the reach a player has) and the cooldown (one press
 * per {@code structureManualCooldownTicks}; a pulse lasts {@code structureManualPulseTicks}, so a held key must not
 * drive a machine at tick speed).
 *
 * <p>Every refusal carries a sentence rather than a code, because it goes into the log and into the player's chat.
 */
public final class ManualTrigger {

    /** The answer to a press: allowed, or refused with the reason. */
    public record Verdict(boolean allowed, String refusal) {

        /**
         * The two answers; named {@code permit}/{@code refuse} because a record's component already generates
         * {@code allowed()}, and a static method of the same name would collide with it.
         */
        public static Verdict permit() {
            return new Verdict(true, "");
        }

        public static Verdict refuse(String reason) {
            return new Verdict(false, reason);
        }
    }

    /**
     * The check. Only {@link IoMode#MANUAL} accepts a key; {@code distance} is measured in blocks from the player's
     * eyes and {@code range} is {@code structureManualRange}; {@code lastPressTick} is -1 when nothing was pressed yet,
     * and {@code cooldownTicks} is {@code structureManualCooldownTicks}.
     */
    public static Verdict check(IoMode ioMode, int entryCount, int entry, double distance, double range,
                                long lastPressTick, long nowTick, int cooldownTicks) {
        if (ioMode != IoMode.MANUAL) {
            return Verdict.refuse("this structure is in activation-face mode, so it has no keys to press");
        }
        if (entry < 0 || entry >= entryCount) {
            return Verdict.refuse("this structure has no manual entry " + entry + " (it has " + entryCount
                    + ")");
        }
        if (!(distance <= range)) {
            // Negated so a NaN distance (a player with a broken position) is refused rather than passing the check.
            return Verdict.refuse("pressing from " + describe(distance) + " block(s) away: the limit is "
                    + describe(range));
        }
        if (lastPressTick >= 0 && nowTick - lastPressTick < cooldownTicks) {
            return Verdict.refuse("pressed again " + (nowTick - lastPressTick) + " tick(s) after the last "
                    + "press: the cooldown is " + cooldownTicks + " tick(s)");
        }
        return Verdict.permit();
    }

    private static String describe(double value) {
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private ManualTrigger() {
    }
}
