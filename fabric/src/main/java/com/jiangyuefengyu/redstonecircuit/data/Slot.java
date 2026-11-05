package com.jiangyuefengyu.redstonecircuit.data;

import java.util.EnumSet;
import java.util.Set;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;

/**
 * The single redstone component stored inside one host block.
 *
 * <p>A host block holds <b>one</b> component, not one per face; it connects towards any of the six neighbours,
 * derived from what surrounds it and overridable per direction with the wrench. {@code forcedOn} is locked,
 * {@code forcedOff} is explicitly cut, and the rest are automatic - connected if the neighbour holds inner
 * redstone or a piece of vanilla wire sits against this block on that side.
 *
 * <p>Any locked direction makes the routing explicit: every side not locked counts as cut ({@link #isClosed}),
 * which is what makes a lock mean "this line and nothing else" and keeps it to one click per direction.
 */
public final class Slot {

    /** What is installed inside this block. */
    public ComponentType type;
    /** Signal strength for {@link ComponentType#DUST}, 0-15. Ignored by the other types. */
    public int power;
    /** On/off state for torches, repeaters and comparators. */
    public boolean powered;
    /** Orientation for diodes (repeaters/comparators) and directional parts. */
    public Direction facing;
    /** Repeater delay in redstone ticks, 1-4. */
    public int delay = 1;
    /** Comparator output mode. */
    public ComparatorMode mode = ComparatorMode.COMPARE;
    /** 控制器's four settings; every other type ignores this. See {@code ControllerRule.Setting}. */
    public com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting controller =
            com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting.DEFAULT;
    /**
     * 红石变压器's one setting: the strength its 背面 answers with while anything arrives at 正面, 1-15. See
     * {@link com.jiangyuefengyu.redstonecircuit.logic.TransformerRule}; {@link #power} is still what the
     * solver leaves behind as the component's own output, as for 控制器.
     */
    public int transformer = com.jiangyuefengyu.redstonecircuit.logic.TransformerRule.DEFAULT;
    /**
     * 时钟源's four settings: 秒 0-3600, tick 0-19, 强度 1-15 and 脉宽 1-2 tick - see
     * {@link com.jiangyuefengyu.redstonecircuit.logic.ClockRule}. One record rather than four fields because
     * they always travel together: a save tag, a payload, a screen and a rule all want exactly this value.
     */
    public com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting clock =
            com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting.DEFAULT;
    /**
     * 信号延长器's two settings: 秒 0-3600 and 刻 0-19 - see
     * {@link com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule}. The same unit pair as 时钟源's own two
     * time fields, and saved in its own compound for the same reason.
     */
    public com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting extender =
            com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting.DEFAULT;
    /**
     * 同步火把的频道, or {@link com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule#UNBOUND} for one that belongs to no group.
     *
     * <p>存在槽位上而不是别处, 是因为频道是<b>元件自己的</b>: 活塞把方块连着里面的元件搬到另一个格子时
     * ({@code PistonHostTransfer} 搬的是整个 {@code Stored}), 频道跟着一起走, 不需要谁去修那张位置表。世界里那个
     * 立着的方块形态则只能把频道存在 store 的 {@code syncTorch} 表里 - 它没有槽位。
     */
    public int syncGroup = com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule.UNBOUND;
    /**
     * 同步火把: 哪几面此刻有输入, 一位一面.
     *
     * <p>不存档, 与 {@code extenderDeadline} 同一类东西: "此刻"的事实, 由拥有这个世界的 tick 每趟写一次。存下来只会在
     * 重载后给出一个过期的答案, 而过期的输入掩码意味着某面明明有电却还在往外输出。
     */
    public byte syncInputs;
    /**
     * 红石触发器's edge memory: whether the face it reads ({@link #facing}) was powered the last time it looked, which is
     * what tells a <b>rising edge</b> apart from a powered input that is merely still powered.
     *
     * <p>Saved, unlike the two "此刻" fields above: the switch state alone cannot answer "was that a rising edge or a
     * hold", so the previous reading is a fact the component itself has to keep. Losing it across a reload would turn a
     * hold into a fresh rising edge, and a trigger that was on would silently switch itself off on the first tick after
     * the world loads - the one thing the edge memory exists to prevent.
     */
    public boolean triggerInput;
    /**
     * 信号延长器's hold: the tick its output stops on, or {@link com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule#NO_DEADLINE}.
     *
     * <p>Not saved, and not part of the component: it is a fact about when the input that started the hold left, which a
     * reload has no way to know. A loaded slot therefore starts with no hold at all, which is what the default says.
     */
    public long extenderDeadline = com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.NO_DEADLINE;

    /** Directions the wrench locked. */
    public final Set<Direction> forcedOn = EnumSet.noneOf(Direction.class);
    /** Directions the wrench explicitly cut. */
    public final Set<Direction> forcedOff = EnumSet.noneOf(Direction.class);

    /**
     * A power supply planted by hand (debug command and game tests), 0-15, or {@code -1} when the component
     * has none.
     *
     * <p>An <em>input</em> to the solver, like a lever touching the host block: the component holds at least
     * this strength whatever surrounds it. Power gained through propagation never sets it, which is what lets
     * a chain drain once its real supply is removed. Not saved - it seeds experiments rather than persisting.
     */
    public int injectedPower = -1;

    /** True when {@link #injectedPower} is an actual supply rather than "none". */
    public boolean hasInjectedPower() {
        return injectedPower >= 0;
    }

    /**
     * The strength this component read at its input face on the last solve, or {@code -1} when it has not been
     * solved yet.
     *
     * <p>A screen cannot re-derive this with a level query: a component inside a machine is read through the
     * structure's own view, so the number is recorded where it was read ({@code PowerSolver#desiredOutput}).
     * Like {@link #injectedPower} it is not saved - it is a fact about the last solve, and a freshly loaded
     * world has not solved anything, which is what {@code -1} says.
     */
    public int lastInput = -1;

    public Slot(ComponentType type) {
        this.type = type;
        this.facing = Direction.NORTH;
    }

    private Slot() {
    }

    public Slot copy() {
        Slot copy = new Slot(this.type);
        copy.power = this.power;
        copy.powered = this.powered;
        copy.facing = this.facing;
        copy.delay = this.delay;
        copy.mode = this.mode;
        copy.controller = this.controller;
        copy.transformer = this.transformer;
        copy.clock = this.clock;
        copy.extender = this.extender;
        copy.syncGroup = this.syncGroup;
        // Copied because it is saved: a broadcast or a capture carries the component, and an edge memory that did not
        // travel with it would make the copy read the next tick's input as a rising edge.
        copy.triggerInput = this.triggerInput;
        // Copied although it is not saved: a broadcast carries a copy of the component, and the screen shows what is
        // left of the hold - a copy without the deadline would answer "nothing is holding" to its own player.
        copy.extenderDeadline = this.extenderDeadline;
        copy.forcedOn.addAll(this.forcedOn);
        copy.forcedOff.addAll(this.forcedOff);
        copy.injectedPower = this.injectedPower;
        copy.lastInput = this.lastInput;
        return copy;
    }

    // ---------------------------------------------------------- connectivity --

    /** Whether the wrench has locked this component anywhere; when it has, the routing is explicit ({@link #isClosed}). */
    public boolean isRoutingLocked() {
        return !forcedOn.isEmpty();
    }

    /** True when this side was locked: the signal goes out here, whatever the neighbours say. */
    public boolean isOpen(Direction direction) {
        return forcedOn.contains(direction);
    }

    /** True when this side is cut: the wrench cut it, or it was left out of the lock. */
    public boolean isClosed(Direction direction) {
        if (forcedOff.contains(direction)) {
            return true;
        }
        return isRoutingLocked() && !isOpen(direction);
    }

    /** True when this side was cut by hand, as opposed to being left out of a lock. */
    public boolean isExplicitlyCut(Direction direction) {
        return forcedOff.contains(direction);
    }

    /**
     * True when signal may pass across this side: locked sides, and automatic sides of a component
     * that has not been locked anywhere.
     */
    public boolean isConnected(Direction direction) {
        return !isClosed(direction);
    }

    /** True when the wrench has pinned this direction either way. */
    public boolean isLocked(Direction direction) {
        return forcedOn.contains(direction) || forcedOff.contains(direction);
    }

    /** Sets an explicit connection state, or clears the override for {@link ConnectionState#AUTO}. */
    public void setConnection(Direction direction, ConnectionState state) {
        forcedOn.remove(direction);
        forcedOff.remove(direction);
        switch (state) {
            case ON -> forcedOn.add(direction);
            case OFF -> forcedOff.add(direction);
            case AUTO -> {
            }
        }
    }

    public void clearConnections() {
        forcedOn.clear();
        forcedOff.clear();
    }

    // ------------------------------------------------------------------ NBT --

    public void save(CompoundTag tag) {
        tag.putString("type", type.name());
        tag.putInt("power", power);
        tag.putBoolean("powered", powered);
        tag.putString("facing", facing.getName());
        tag.putInt("delay", delay);
        tag.putString("mode", mode.name());
        // 控制器's four settings travel in their own compound, so a slot's flat keys keep their meaning.
        CompoundTag controllerTag = new CompoundTag();
        controller.save(controllerTag);
        tag.put("controller", controllerTag);
        // The transformer's single strength, as a flat key: a missing key reads as the default (see Slot#load).
        tag.putInt("transformer", transformer);
        // 时钟源's four numbers in their own compound, for the same reason (see ClockRule).
        CompoundTag clockTag = new CompoundTag();
        clock.save(clockTag);
        tag.put("clock", clockTag);
        // 信号延长器's two numbers in their own compound as well. The hold's deadline is deliberately not written.
        CompoundTag extenderTag = new CompoundTag();
        extender.save(extenderTag);
        tag.put("extender", extenderTag);
        tag.putInt("syncGroup", syncGroup);
        // 红石触发器's edge memory. A save written before this device existed has no such key and reads back as false,
        // which is the honest "I have never seen this face powered" for a slot that is not a trigger anyway.
        tag.putBoolean("triggerInput", triggerInput);
        tag.putIntArray("forcedOn", encode(forcedOn));
        tag.putIntArray("forcedOff", encode(forcedOff));
    }

    public static Slot load(CompoundTag tag) {
        Slot slot = new Slot();
        slot.type = ComponentType.byName(tag.getString("type"), ComponentType.DUST);
        slot.power = tag.getInt("power");
        slot.powered = tag.getBoolean("powered");
        slot.facing = readDirection(tag.getString("facing"), Direction.NORTH);
        slot.delay = Math.max(1, Math.min(4, tag.getInt("delay") == 0 ? 1 : tag.getInt("delay")));
        slot.mode = ComparatorMode.byName(tag.getString("mode"), ComparatorMode.COMPARE);
        slot.controller = com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting
                .load(tag.getCompound("controller"));
        int transformer = tag.getInt("transformer");
        slot.transformer = com.jiangyuefengyu.redstonecircuit.logic.TransformerRule.isOutput(transformer)
                ? transformer
                : com.jiangyuefengyu.redstonecircuit.logic.TransformerRule.DEFAULT;
        slot.clock = com.jiangyuefengyu.redstonecircuit.logic.ClockRule.Setting.load(tag.getCompound("clock"));
        slot.extender = com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule.Setting
                .load(tag.getCompound("extender"));
        // A key that is not there at all is a save written before 同步火把 existed, which is exactly "no channel" -
        // so no data version bump is needed for the field, and 0 is not a channel either (channels start at 1).
        int syncGroup = tag.getInt("syncGroup");
        slot.syncGroup = syncGroup > 0 ? syncGroup : com.jiangyuefengyu.redstonecircuit.logic.SyncTorchRule.UNBOUND;
        // Read back with the switch state: the two together are what says "this is a hold, not a new edge" (see the
        // field's own comment). Absent means false, which is exactly what a fresh trigger starts with.
        slot.triggerInput = tag.getBoolean("triggerInput");
        decode(tag.getIntArray("forcedOn"), slot.forcedOn);
        decode(tag.getIntArray("forcedOff"), slot.forcedOff);
        return slot;
    }

    private static int[] encode(Set<Direction> directions) {
        return directions.stream().mapToInt(Direction::get3DDataValue).toArray();
    }

    private static void decode(int[] values, Set<Direction> out) {
        for (int value : values) {
            for (Direction direction : Direction.values()) {
                if (direction.get3DDataValue() == value) {
                    out.add(direction);
                }
            }
        }
    }

    private static Direction readDirection(String name, Direction fallback) {
        for (Direction direction : Direction.values()) {
            if (direction.getName().equalsIgnoreCase(name)) {
                return direction;
            }
        }
        return fallback;
    }

    /** Human readable summary used by the {@code /rc dump} debug command. */
    public String describe() {
        StringBuilder sb = new StringBuilder(type.name());
        sb.append(" out=").append(power);
        if (type.isManual()) {
            sb.append(" on=").append(powered);
        }
        if (type.hasInputSide()) {
            sb.append(" in=").append(facing.getName());
        } else if (type.isDiode()) {
            sb.append(" facing=").append(facing.getName());
        }
        if (type == ComponentType.CONTROLLER) {
            // The four settings are the whole of what this component does, so a dump has to print them.
            sb.append(" ").append(controller.condition()).append(" -> ").append(controller.output());
        }
        if (type == ComponentType.TRANSFORMER) {
            // And the transformer's one setting is the whole of what it does.
            sb.append(" output=").append(transformer).append(" while the input is live");
        }
        if (type == ComponentType.EXTENDER) {
            // The extender's dump prints its setting and whether a hold is running: neither is visible anywhere else.
            sb.append(" hold ").append(extender.holdTicks()).append(" tick(s) = ").append(extender.seconds())
                    .append("s+").append(extender.ticks()).append("t");
        }
        if (type == ComponentType.SYNC_TORCH) {
            // A 同步火把's dump prints its channel and the face it is stuck to: neither is visible anywhere else.
            sb.append(" channel=").append(syncGroup <= 0 ? "unbound" : String.valueOf(syncGroup))
                    .append(" on=").append(facing.getName());
        }
        if (type == ComponentType.TRIGGER) {
            // A trigger's dump prints its switch and the reading it is stepping from: the second one is the edge memory,
            // and it is the only thing that tells "open and holding" from "open and about to close".
            sb.append(" trigger ").append(powered ? "on" : "off").append(" triggerInput=").append(triggerInput);
        }
        if (type == ComponentType.CLOCK) {
            // A clock's dump prints the period and the strength: neither is visible anywhere else.
            sb.append(" every ").append(clock.period()).append(" tick(s) = ").append(clock.seconds())
                    .append("s+").append(clock.ticks()).append("t at strength ").append(clock.strength());
        }
        if (type == ComponentType.REPEATER) {
            sb.append(" delay=").append(delay).append(" (").append(type.delayTicks(delay)).append(" ticks)");
        } else if (type == ComponentType.COMPARATOR) {
            sb.append(" mode=").append(mode.name());
        }
        if (hasInjectedPower()) {
            sb.append(" injected=").append(injectedPower);
        }
        if (isRoutingLocked()) {
            sb.append(" locked=").append(names(forcedOn)).append(" (every other side cut)");
        }
        if (!forcedOff.isEmpty()) {
            sb.append(" cut=").append(names(forcedOff));
        }
        return sb.toString();
    }

    private static String names(Set<Direction> directions) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (Direction direction : directions) {
            if (!first) {
                sb.append(',');
            }
            sb.append(direction.getName());
            first = false;
        }
        return sb.append(']').toString();
    }
}
