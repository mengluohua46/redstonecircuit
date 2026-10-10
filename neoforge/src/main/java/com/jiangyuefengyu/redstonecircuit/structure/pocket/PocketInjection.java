package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * <b>What is being pushed into a plot right now</b> - the design's "注入账本" (injection ledger): a cell and the
 * strength the world is currently pushing into it.
 *
 * <p>被注入的格子对 {@code getSignal} 答出注入强度，等价于在该格放了一个这么强的电源，所以注入格旁边的粉尘拿到这个
 * 强度、再远一格照常减一；不模拟、不碰方块状态，只是信号查询上的一层覆盖。{@link #update} 报的是变化的格子，同一格
 * 被两个面点名时取更强的那个，强度 0 合法（范围 0-15）但等于不注入，因此不存。
 */
public final class PocketInjection {

    /** Keyed by {@link BlockPos#asLong()}: the signal query that reads this is a hot path and must not allocate. */
    private final Map<Long, Integer> strengths = new HashMap<>();

    /** Keyed the same way: the game time a cell's injected strength last changed, for the rate limit. */
    private final Map<Long, Long> changedAt = new HashMap<>();

    /** Keyed the same way: the strength a cell is owed but which the rate limit has not let through yet. The
     * newest value wins and goes out when its window opens, so a fast input is thinned out, never left stale. */
    private final Map<Long, Integer> waiting = new HashMap<>();

    /** Whether any cell is being held back right now - the caller re-offers the sources while this is true. */
    public boolean hasWaiting() {
        return !this.waiting.isEmpty();
    }

    /** How many cells are being held back, for the diagnostics line. */
    public int waitingCells() {
        return this.waiting.size();
    }

    /** The strength a cell in the selection is being pushed with, or 0 when nothing pushes it. */
    public int strengthAt(BlockPos localPos) {
        return strengthAt(localPos.getX(), localPos.getY(), localPos.getZ());
    }

    /** The same, for the reader that has the three numbers already - the signal query must not allocate. */
    public int strengthAt(int x, int y, int z) {
        return this.strengths.getOrDefault(BlockPos.asLong(x, y, z), 0);
    }

    /** True when this plot is not being driven at all - the state every plot is in by default. */
    public boolean isEmpty() {
        return this.strengths.isEmpty();
    }

    /** How many cells are being pushed: for the diagnostics line and for the tests. */
    public int size() {
        return this.strengths.size();
    }

    /** <b>Everything that can push into a plot at one moment</b>: the structure's six-face table with what each
     * face is receiving, and its manual table with which entries are being pressed. One type for both because
     * {@link #update} is one rule - the two modes differ in who presses the button, not in what a press does. */
    public record Sources(Map<Direction, List<ActivationTable.FaceEntry>> faces, int[] faceSignals,
                          List<ActivationTable.ManualEntry> manual, Set<Integer> pressed) {

        public static final Sources NONE = new Sources(Map.of(), noneEnergized(), List.of(), Set.of());

        static int[] noneEnergized() {
            return new int[Direction.values().length];
        }

        /** The activation-face half alone - what mode A drives a plot with. */
        public static Sources faces(Map<Direction, List<ActivationTable.FaceEntry>> faces, int[] faceSignals) {
            return new Sources(faces, faceSignals, List.of(), Set.of());
        }

        /** The manual half alone - what mode B drives a plot with. */
        public static Sources manual(List<ActivationTable.ManualEntry> manual, Set<Integer> pressed) {
            return new Sources(Map.of(), noneEnergized(), manual, pressed);
        }
    }

    /** Rebuilds the ledger from a face table and the strength each face is receiving now: the whole of the
     * activation-face mode, kept so the call sites that only ever have that half read better. */
    public List<BlockPos> update(Map<Direction, List<ActivationTable.FaceEntry>> faces, int[] faceSignals) {
        return update(Sources.faces(faces, faceSignals));
    }

    /** Rebuilds the ledger from everything that is pushing at this moment, and returns every cell whose injected
     * strength changed - newly pushed, pushed harder or softer, or no longer pushed - and nothing else. */
    public List<BlockPos> update(Sources sources) {
        return update(sources, 0L, 0);
    }

    /** <b>The same, with the rate limit on the injected cell itself</b> - two edges closer together than a machine
     * can act on strand a piston's moving block. A change inside the window is <b>held, newest winning</b> and applied
     * when the window opens, because dropping it would leave the cell powered for ever. {@code limitTicks} &le; 0
     * turns the limit off, and the caller has to offer the sources again for a held change to go out. */
    public List<BlockPos> update(Sources sources, long gameTime, int limitTicks) {
        Map<Long, Integer> next = wanted(sources);

        java.util.Set<Long> cells = new java.util.HashSet<>(next.keySet());
        cells.addAll(this.strengths.keySet());
        cells.addAll(this.waiting.keySet());

        List<BlockPos> changed = new ArrayList<>();
        for (long cell : cells) {
            int now = next.getOrDefault(cell, 0);
            int have = this.strengths.getOrDefault(cell, 0);
            if (now == have) {
                // Nothing is owed any more: whatever was waiting for this cell has been overtaken by the value
                // it already holds.
                Integer owed = this.waiting.get(cell);
                if (owed == null || owed == have) {
                    this.waiting.remove(cell);
                    continue;
                }
                // A pulse shorter than the window is not "overtaken" - it is still owed. A nested structure's source
                // is a cell of the outer plot, so a rise and fall inside the window arrives here as "rise (waiting),
                // then back to the value the plot already holds"; the owed value goes out when its window opens, and
                // what the source has settled on since is the next request with its own window.
                now = owed;
            }
            Long at = this.changedAt.get(cell);
            if (limitTicks > 0 && at != null && gameTime - at < limitTicks) {
                this.waiting.put(cell, now);
                continue;
            }
            this.waiting.remove(cell);
            this.changedAt.put(cell, gameTime);
            if (now == 0) {
                this.strengths.remove(cell);
            } else {
                this.strengths.put(cell, now);
            }
            changed.add(BlockPos.of(cell));
        }
        if (this.changedAt.size() > 16) {
            // A cell that is neither pushed nor owed anything only matters while its window is still open.
            long stale = gameTime - 200;
            this.changedAt.entrySet().removeIf(entry -> entry.getValue() < stale
                    && !this.strengths.containsKey(entry.getKey())
                    && !this.waiting.containsKey(entry.getKey()));
        }
        return changed;
    }

    /** What the sources add up to for every cell they name: the ledger before any rate limit. */
    private static Map<Long, Integer> wanted(Sources sources) {
        Map<Long, Integer> next = new HashMap<>();
        if (sources != null) {
            for (Direction face : Direction.values()) {
                if (sources.faceSignals() == null || sources.faceSignals()[face.ordinal()] <= 0) {
                    continue;
                }
                for (ActivationTable.FaceEntry entry : ActivationTable.entries(sources.faces(), face)) {
                    push(next, entry.localPos(), entry.strength());
                }
            }
            if (sources.manual() != null && sources.pressed() != null) {
                for (int index : sources.pressed()) {
                    if (index < 0 || index >= sources.manual().size()) {
                        continue;
                    }
                    ActivationTable.ManualEntry entry = sources.manual().get(index);
                    push(next, entry.localPos(), entry.strength());
                }
            }
        }
        return next;
    }

    /** One thing pushing one cell: the stronger push is the one that counts. An entry of strength 0 is legal in
     * a table (the codec's range is 0-15) and means nothing to inject. */
    private static void push(Map<Long, Integer> next, BlockPos localPos, int strength) {
        if (strength <= 0) {
            return;
        }
        next.merge(localPos.asLong(), strength, Math::max);
    }

    /** Forgets everything. Used when a plot is given back, so nothing outlives the structure it drove. */
    public void clear() {
        this.strengths.clear();
        this.changedAt.clear();
        this.waiting.clear();
    }
}
