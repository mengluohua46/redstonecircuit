package com.jiangyuefengyu.redstonecircuit.data;

/**
 * The kinds of redstone component that can live inside a host block.
 *
 * <p>Every one of them stores its <b>output strength</b> in {@link Slot#power}, so a host block can report "what is
 * inside me" to the outside world without knowing which kind it is ({@code InnerRedstoneStore#getSignal}). What
 * differs between the types is where that output goes, what drives it, and how long it takes to follow a change - all
 * decided by {@code PowerSolver}.
 */
public enum ComponentType {
    /** Redstone dust (wire). The only type that behaves like a wire rather than like a device. */
    DUST,
    /**
     * 超导红石粉 - superconducting dust: a wire whose hops cost nothing.
     *
     * <p>Vanilla charges every wire hop to the wire that receives it ({@code max(sources, best neighbouring wire - 1)}),
     * which is why a normal line fades. A superconductor is the same wire with a hop cost of zero, so a run of it
     * carries whatever strength it was given all the way to the end; reading and emitting on every side, and giving no
     * strong power, are ordinary dust's rules.
     */
    SUPERCONDUCTOR,
    /** Redstone repeater. Reads {@link Slot#facing}, delays by {@link Slot#delay}. */
    REPEATER,
    /**
     * 维持中继器 - a repeater that keeps the strength it was given instead of restoring it: seven in comes out as seven,
     * two as two, nothing as nothing.
     *
     * <p>A vanilla repeater is a <em>restorer</em>, so any signal at all comes out at fifteen. Everything else here is
     * a repeater's: it reads one side, drives the other, has the four delay settings, and pushes strong power into the
     * block in front.
     */
    RETAINING_REPEATER,
    /** 双倍红石中继器 - a repeater whose every delay setting lasts twice as long. */
    DOUBLE_REPEATER,
    /** 双倍维持中继器 - both at once: twice the delay, and no strength restored. */
    DOUBLE_RETAINING_REPEATER,
    /** Redstone comparator. Reads {@link Slot#facing} and its two sides, uses {@link Slot#mode}. */
    COMPARATOR,
    /** Redstone torch: lit unless whatever it is attached to is powered. Inverts. */
    TORCH,
    /** Lever: a manual source. */
    LEVER,
    /** Button: a lever that releases itself. */
    BUTTON,
    /** Pressure plate: a source that answers to whatever stands on the host block. */
    PRESSURE_PLATE,
    /**
     * 控制器 - the four comparison settings as a component inside a host block. It reads the one face it was mounted on
     * and answers on the opposite one, like the plate standing in the world, and its four settings live on the slot
     * ({@link com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting}).
     *
     * <p>It is <b>not</b> a diode: a diode pushes strong power into the block in front of it, while this device answers
     * with the ordinary weak signal, which is what keeps it from charging the block it is buried in.
     */
    CONTROLLER,
    /**
     * 红石变压器 - a level shifter as a component inside a host block: whatever the input strength, the output becomes
     * the adjusted strength, and no input gives 0. It reads the face it was mounted on (its 正面, the convention a diode
     * uses) and answers the strength stored on the slot
     * ({@link com.jiangyuefengyu.redstonecircuit.logic.TransformerRule}).
     *
     * <p>Like 控制器 it is <b>not</b> a diode: it answers with the ordinary weak signal, so it cannot charge the block
     * it is buried in.
     */
    TRANSFORMER,
    /**
     * 时钟源 - a component that outputs a given strength once every X ticks or seconds. Every other type here answers to
     * something in the world - a wire, a lever, a neighbour, a face - while this one answers to the <b>world clock</b>
     * ({@link com.jiangyuefengyu.redstonecircuit.logic.ClockRule}).
     *
     * <p>It has no input face, so nothing ever notifies it and nothing in the solve re-derives it: its output is written
     * by the tick that owns the world it lives in ({@code InnerRedstoneNetwork#tickTimedDevices} for a host standing in
     * the world, {@code CarrierNetworks} for one riding a machine), from the four settings on the slot.
     */
    CLOCK,
    /**
     * 信号延长器 - 正面读一个面, 背面答出同一个强度, 并在输入走了之后继续答这么久.
     *
     * <p>两件事各由一处负责: <b>跟随</b>是组合的, 由求解器做（输入在, 输出就是输入, 一 tick 都不等）; <b>保持</b>由拥有
     * 这个世界的 tick 走查推进（{@code InnerRedstoneNetwork#tickTimedDevices} / {@code CarrierNetworks}）, 因为只有它
     * 知道时间过去了多久。所以这个类型的输出不能由 {@code PowerSolver} 重新推导: 没有输入时它必须原样留着上一次的答案,
     * 那是保持本身。设定在槽位上（{@link com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting}）, 保持的截止
     * 时刻在槽位的 {@code extenderDeadline} 上 - 那是"此刻的事", 不进存档。
     *
     * <p>像 控制器 和 红石变压器 一样它 <b>不是</b> 二极管: 它答的是普通的弱信号, 不会给自己埋着的方块充能。
     */
    EXTENDER,
    /**
     * 同步火把 - 和红石火把同一个形状、同一套方向, 极性反过来, 而亮灭由一个<b>频道</b>上所有成员共同决定.
     *
     * <p>红石火把是"没信号才亮", 它是"有信号才亮"; 除了这一条, 方向与红石火把逐字相同: 只读吸附面 (它粘着的那个方块),
     * 除吸附面以外的五个方向都输出, 答的是普通弱信号, 所以不会给埋着自己的方块充能。
     *
     * <p>它的输出不是自己算出来的, 所以 {@link #isDriven()} 必须是 <b>false</b>: 求解器看不见它、也永远不会推导它
     * ({@code PowerSolver#desiredOutput} 没有这个类型的分支, 落到"原样保留"那一档)。写 {@link Slot#power} 的只有那一趟
     * 组 pass ({@code SyncGroups#tick}) - 组内是 OR, 任一成员在自己吸附面收到信号, 全组同 tick 一起亮; 所以逐个元件
     * 的延迟在这里没有位置, {@link #delayTicks} 就是 0。
     */
    SYNC_TORCH,
    /**
     * 红石触发器 - 一条上升沿切换一次开关的元件.
     *
     * <p>方向与 控制器 / 红石变压器 / 信号延长器 完全同一套: 读 {@link Slot#facing} 那一面 (正面), 在
     * {@code facing.getOpposite()} 那一面 (背面) 答 15 或 0; 而它 <b>不是</b> 二极管 - 答的是普通弱信号, 不会给自己
     * 埋着的方块充能。
     *
     * <p>它的输出不是输入的强度, 而是输入的<b>边沿</b>: 从没电变有电的那一次翻一档, 之后保持通电不再翻, 断电也不翻。
     * 所以它不可能由 {@link com.jiangyuefengyu.redstonecircuit.logic.PowerSolver#desiredOutput} 推导 - 那是一个"此刻的
     * 输入 ⇒ 此刻的输出"的函数, 而这一步要的是<b>上一次</b>的输入。推导它的只有拥有这个世界的那一趟 tick
     * ({@code InnerRedstoneNetwork#tickTriggers}), 读的是槽位上的 {@link Slot#triggerInput}。
     *
     * <p>于是 {@link #isDriven()} 仍是 false (没有延迟、不跟随输入), 而 {@link #hasInputSide()} 是 true: 它有一个属于
     * 自己的输入面 - 扳手锁一条线不该把它读的那一面锁掉 - 但没有需要等的延迟。
     */
    TRIGGER;

    /** True when this component is a power source rather than a conductor/relay. */
    public boolean isSource() {
        return this == TORCH || this == LEVER || this == BUTTON || this == PRESSURE_PLATE;
    }

    /**
     * True when this component propagates like a wire: it reads and emits on every side, and a hop
     * between two of them is charged by the receiver.
     */
    public boolean isWire() {
        return this == DUST || this == SUPERCONDUCTOR;
    }

    /**
     * What one hop into this wire costs: one for ordinary dust - vanilla's rule, and the reason a line fades - and
     * nothing for a superconductor.
     */
    public int hopCost() {
        return this == SUPERCONDUCTOR ? 0 : 1;
    }

    /** True when this component relays a signal and therefore has a direction. */
    public boolean isDiode() {
        return this == REPEATER || this == RETAINING_REPEATER || this == DOUBLE_REPEATER
                || this == DOUBLE_RETAINING_REPEATER || this == COMPARATOR;
    }

    /** True for the four repeater variants, which share every rule except delay and restoration. */
    public boolean isRepeater() {
        return this == REPEATER || this == RETAINING_REPEATER || this == DOUBLE_REPEATER
                || this == DOUBLE_RETAINING_REPEATER;
    }

    /**
     * True when this repeater hands its output back at fifteen whatever reached it.
     *
     * <p>That is what a vanilla repeater is for - it cleans a fading line back up to full strength -
     * and it is the one thing the "维持" (retaining) family does not do: those pass on the strength
     * they were given. It is also what makes a retaining repeater's output expensive to compute: a
     * vanilla one only ever answers "nothing" or "fifteen".
     */
    public boolean restoresStrength() {
        return this == REPEATER || this == DOUBLE_REPEATER;
    }

    /**
     * True when this component has an input side: its output is decided by what reaches it, not by
     * hand. These are the components whose output lags behind their input.
     */
    public boolean isDriven() {
        return this == TORCH || isDiode();
    }

    /**
     * True when this component answers on one face and reads on the other, so the face it reads is <b>its own</b> rather
     * than one of the lines the wrench routes.
     *
     * <p>Read by {@code PowerSolver#readsToward}: locking a repeater's output sideways must not switch the repeater off,
     * and a controller's 正面 is the same kind of side - its settings say it reads there, so a routing lock cannot take
     * its feed away.
     */
    public boolean hasInputSide() {
        // 红石触发器 has one too: it reads exactly one face - its own - and a routing lock must not be able to take that
        // feed away. It is not driven, though: nothing here decides its output (see TRIGGER above).
        return isDriven() || this == CONTROLLER || this == TRANSFORMER || this == EXTENDER
                || this == TRIGGER;
    }

    /**
     * True when what this component answers is decided by the <b>world clock</b> rather than by what reaches it
     * (时钟源, and nothing else).
     *
     * <p>A predicate of its own rather than a wider {@link #hasInputSide}, because the two answer different questions and
     * two places care about the difference: {@code readsToward} asks whether the component reads a face of its own (the
     * clock reads nothing, so a routing lock must not be told otherwise), while the tick that owns a world asks whether
     * the component has to be re-derived even though no notification will ever arrive (the clock does, every tick).
     */
    public boolean isTimeDriven() {
        return this == CLOCK;
    }

    /**
     * True for the one component whose answer belongs to a <b>group</b> rather than to itself: 同步火把.
     *
     * <p>谓词单独一个, 因为"它自己不看输入"和"它的答案由别人决定"是两件事: 前面每一档都是"我这一个元件的输入是多少",
     * 而这一档的输入是同一频道上所有成员输入的最大值 (语义上的 OR)。只有一趟 pass 能同时看到全组, 所以只有那一趟
     * 可以写它的输出 - 这就是它不能由求解器推导的全部理由。
     */
    public boolean isGroupDriven() {
        return this == SYNC_TORCH;
    }

    /** True when flipping this component needs a player (or the debug command). */
    public boolean isManual() {
        return this == LEVER || this == BUTTON;
    }

    /**
     * How many ticks the output lags behind a change of input. Matches vanilla: a torch needs {@code TOGGLE_DELAY = 2}
     * ticks, a repeater is one redstone tick (2 game ticks) per 1-4 setting, and a comparator runs at the base diode
     * delay of 2 ticks. The 双倍 (double) repeaters stretch every setting to twice that; everything else - dust, lever,
     * button, plate - reacts within the same tick, exactly as it does in vanilla.
     */
    public int delayTicks(int repeaterDelay) {
        int setting = Math.max(1, Math.min(4, repeaterDelay));
        switch (this) {
            case REPEATER, RETAINING_REPEATER:
                return 2 * setting;
            case DOUBLE_REPEATER, DOUBLE_RETAINING_REPEATER:
                return 4 * setting;
            case COMPARATOR:
            case TORCH:
                return 2;
            default:
                return 0;
        }
    }

    /**
     * The output a freshly placed component starts with, before anything has been solved.
     */
    public int initialPower() {
        // A torch is lit the moment it is placed; everything else starts silent and is raised by
        // whatever feeds it (or by the player, for a lever).
        return this == TORCH ? 15 : 0;
    }

    public static ComponentType byName(String name, ComponentType fallback) {
        for (ComponentType type : values()) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return fallback;
    }
}
