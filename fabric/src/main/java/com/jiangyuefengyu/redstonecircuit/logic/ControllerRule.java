package com.jiangyuefengyu.redstonecircuit.logic;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.StringRepresentable;

/**
 * 控制器 - the four comparison sentences, as one pure rule.
 *
 * <p>等于 把边界算进来（输入 ≥ 档位）；不等于 是严格大于/小于，所以四种设置互不重复；只等于 与 只不等于 不看主档位。
 * 输入档位 0 配 等于 恒成立（"没有输入"没有例外），所以薄板的默认是档位 1，含义是"任何真信号"。
 *
 * <p>规则全是关于数字的: 到达的强度、玩家设的 输入档位 与 输出强度，以及出来什么，不涉及 level、方块状态和面，
 * 因此世界里的薄板和方块里的元件共用它。
 */
public final class ControllerRule {

    /** 输入档位 goes 0-15, like every other strength in the mod. */
    public static final int MAX = 15;

    /** 输出强度 goes 1-15: 0 is not a setting here, it is the absence of an output. */
    public static final int MIN_OUTPUT = 1;

    /** 主档位: the direction the input is compared in. */
    public enum Main implements StringRepresentable {
        /** 大于. */
        GREATER("greater"),
        /** 小于. */
        LESS("less");

        private final String name;

        Main(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** The key suffix the screen's label is built from. */
        public String key() {
            return this.name;
        }
    }

    /** 副档位: what happens at the boundary, and whether 主档位 is in the sentence at all. The order is the screen's
     * order, left to right. */
    public enum Sub implements StringRepresentable {
        /** 等于: 主档位, with the boundary included. */
        EQUAL("equal"),
        /** 不等于: 主档位, with the boundary excluded - the strictly greater/less reading. */
        NOT_EQUAL("not_equal"),
        /** 只等于: equality, and 主档位 does not take part. */
        ONLY_EQUAL("only_equal"),
        /** 只不等于: inequality, and 主档位 does not take part. */
        ONLY_NOT_EQUAL("only_not_equal");

        private final String name;

        Sub(String name) {
            this.name = name;
        }

        @Override
        public String getSerializedName() {
            return this.name;
        }

        /** The key suffix the screen's label is built from. */
        public String key() {
            return this.name;
        }

        /** True for the two 只 settings, which are the same sentence in both columns of the table. */
        public boolean ignoresMain() {
            return this == ONLY_EQUAL || this == ONLY_NOT_EQUAL;
        }
    }

    private ControllerRule() {
    }

    /** Whether the input satisfies the condition - the whole rule, as one question. {@code main} and {@code sub} may be
     * {@code null}, read as 大于 / 等于; {@code input} and {@code threshold} are 0-15 and are clamped. */
    public static boolean holds(Main main, Sub sub, int input, int threshold) {
        Main direction = main == null ? Main.GREATER : main;
        Sub comparison = sub == null ? Sub.EQUAL : sub;
        int reached = clamp(input);
        int level = clamp(threshold);
        return switch (comparison) {
            case EQUAL -> direction == Main.GREATER ? reached >= level : reached <= level;
            case NOT_EQUAL -> direction == Main.GREATER ? reached > level : reached < level;
            case ONLY_EQUAL -> reached == level;
            case ONLY_NOT_EQUAL -> reached != level;
        };
    }

    /** What the back face answers: 输出强度 while the condition holds, and nothing while it does not. A device that is
     * not satisfying its condition is not a device that outputs something else, so the answer is 0. */
    public static int answer(Main main, Sub sub, int input, int threshold, int output) {
        return holds(main, sub, input, threshold) ? clampOutput(output) : 0;
    }

    /** The condition in symbols - "≥ 7", "&gt; 7", "= 7", "≠ 7" - for the screen's own line. Symbols rather than a
     * sentence, because the line is about the numbers the player typed and the mod's screens are drawn in one font. */
    public static String condition(Main main, Sub sub, int threshold) {
        Main direction = main == null ? Main.GREATER : main;
        Sub comparison = sub == null ? Sub.EQUAL : sub;
        String symbol = switch (comparison) {
            case EQUAL -> direction == Main.GREATER ? "≥" : "≤";
            case NOT_EQUAL -> direction == Main.GREATER ? ">" : "<";
            case ONLY_EQUAL -> "=";
            case ONLY_NOT_EQUAL -> "≠";
        };
        return symbol + " " + clamp(threshold);
    }

    /** Whether {@code value} is a 输入档位 a player may set. */
    public static boolean isThreshold(int value) {
        return value >= 0 && value <= MAX;
    }

    /** Whether {@code value} is a 输出强度 a player may set. */
    public static boolean isOutput(int value) {
        return value >= MIN_OUTPUT && value <= MAX;
    }

    /** The 输入档位 a number means, clamped into range rather than refused - a wire cannot carry an invalid setting. */
    public static int clamp(int value) {
        return Math.max(0, Math.min(MAX, value));
    }

    /** The 输出强度 a number means, clamped into range. */
    public static int clampOutput(int value) {
        return Math.max(MIN_OUTPUT, Math.min(MAX, value));
    }

    /** <b>The four things a controller is told, wherever it lives.</b> The plate carries them in its block state and a
     * controller stored inside a host block carries them in its {@code Slot}; both then ask {@link #answer}, so the two
     * forms cannot disagree. A value, not a device: it knows nothing about a level, a face or a host. */
    public record Setting(Main main, Sub sub, int threshold, int output) {

        /** 大于 + 等于 + 档位 1 + 输出 15 - the plate's own default, i.e. "any real signal comes out at 15". 档位 0 was
         * the literal choice and is not it: with 等于 it is satisfied by 输入 ≥ 0, that is, always. */
        public static final Setting DEFAULT = new Setting(Main.GREATER, Sub.EQUAL, 1, MAX);

        /** The answer this setting gives for one reading at 正面. */
        public int answer(int input) {
            return ControllerRule.answer(this.main, this.sub, input, this.threshold, this.output);
        }

        /** The condition in symbols ("≥ 7"), for the screen's own line. */
        public String condition() {
            return ControllerRule.condition(this.main, this.sub, this.threshold);
        }

        /** The same setting with every field inside the range the rule has meanings for. Read back from a save file or
         * from a component filed by an older version: a 档位 of 90, an 输出强度 of 0 or a missing 主档位 is not a setting
         * this rule can answer, and refusing to read it would be worse than reading the nearest setting that exists. */
        public Setting clamped() {
            return new Setting(this.main == null ? Main.GREATER : this.main,
                    this.sub == null ? Sub.EQUAL : this.sub, clamp(this.threshold), clampOutput(this.output));
        }

        /** Writes the four fields into {@code tag} under their own names - see {@code Slot#save}. */
        public void save(CompoundTag tag) {
            Setting setting = clamped();
            tag.putString("main", setting.main().getSerializedName());
            tag.putString("sub", setting.sub().getSerializedName());
            tag.putInt("threshold", setting.threshold());
            tag.putInt("output", setting.output());
        }

        /** Reads a setting back; a tag that is not there at all (an older save) reads as {@link #DEFAULT}. */
        public static Setting load(CompoundTag tag) {
            if (tag == null || tag.isEmpty()) {
                return DEFAULT;
            }
            return new Setting(readMain(tag.getString("main")), readSub(tag.getString("sub")),
                    tag.contains("threshold") ? tag.getInt("threshold") : DEFAULT.threshold(),
                    tag.contains("output") ? tag.getInt("output") : DEFAULT.output()).clamped();
        }

        private static Main readMain(String name) {
            for (Main main : Main.values()) {
                if (main.getSerializedName().equalsIgnoreCase(name)) {
                    return main;
                }
            }
            return DEFAULT.main();
        }

        private static Sub readSub(String name) {
            for (Sub sub : Sub.values()) {
                if (sub.getSerializedName().equalsIgnoreCase(name)) {
                    return sub;
                }
            }
            return DEFAULT.sub();
        }
    }
}
