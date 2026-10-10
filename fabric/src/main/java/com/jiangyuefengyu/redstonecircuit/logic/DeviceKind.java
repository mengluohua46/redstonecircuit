package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.util.StringRepresentable;

/**
 * Which 带界面的红石元件 a device message is about.
 *
 * <p>One kind per device, and it travels on the wire rather than being inferred from the coordinate: the same
 * {@code DeviceViewPayload}/{@code DeviceEditPayload} pair serves every device, and this is what tells the decoder and
 * the client which of the four numbers mean what. It is also what lets one screen message address either a device
 * standing in the world or a device stored inside a host block (可放入方块内), so it is not the block's class.
 *
 * <p>红石变压器, 时钟源 and 信号延长器 extend this list, each bringing its own settings - so the numbers in the payloads
 * stay four wide and their meaning stays this enum's business.
 */
public enum DeviceKind implements StringRepresentable {

    /** 控制器 - 主档位, 副档位, 输入档位 and 输出强度; see {@link ControllerRule}. */
    CONTROLLER("controller"),

    /** 红石变压器 - one number, the strength its 背面 answers with while anything reaches 正面; see {@link TransformerRule}. */
    TRANSFORMER("transformer"),

    /**
     * 时钟源 - three numbers: 秒 (a), tick (b) and 强度 (c); see {@link ClockRule}.
     *
     * <p>The first device whose 0 秒 0 tick is a <b>setting</b> rather than a refusal - it means "switched off" - which is
     * why its decoder refuses a field that is out of range instead of clamping it: 3601 is not a period this clock has,
     * and turning it into 3600 or into "off" would be the screen lying about what the player set.
     */
    CLOCK("clock"),

    /**
     * 信号延长器 - two numbers: 秒 (a) and 刻 (b); see {@link ExtenderRule}.
     *
     * <p>Its reading is what is left of a running hold, in ticks (0 when nothing is holding), which is the one number
     * the screen cannot derive from the settings.
     */
    EXTENDER("extender");

    /**
     * The largest number any device's settings can hold - 时钟源's 秒 field, the widest on the wire; the payload
     * decoders check against it, so a corrupt packet cannot make a device store something it has no meaning for.
     */
    public static final int MAX_SETTING = Math.max(ControllerRule.MAX,
            Math.max(TransformerRule.MAX, ClockRule.MAX_SECONDS));

    /**
     * The largest number a device's live reading can be - which is not {@link #MAX_SETTING}: a reading is what the
     * device is seeing now, and for 时钟源 that is how many ticks until its output next changes, up to a whole period
     * (72,019), while 控制器 and 红石变压器 read a strength, 15.
     *
     * <p>A wire field must be as wide as the widest value any device can put in it, so the two bounds are named
     * separately.
     */
    public static final int MAX_READING = Math.max(MAX_SETTING, ClockRule.MAX_PERIOD);

    private final String name;

    DeviceKind(String name) {
        this.name = name;
    }

    @Override
    public String getSerializedName() {
        return this.name;
    }

    /** The key suffix the screen's own labels are built from. */
    public String key() {
        return this.name;
    }

    /** The kind an ordinal names, or {@code null} when it names none - the check every decoder makes. */
    public static DeviceKind byOrdinal(int ordinal) {
        DeviceKind[] kinds = values();
        return ordinal >= 0 && ordinal < kinds.length ? kinds[ordinal] : null;
    }
}
