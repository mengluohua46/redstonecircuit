package com.jiangyuefengyu.redstonecircuit.logic;

/**
 * <b>同步火把 - 极性反过来的一支火把, 亮灭由整个频道共同决定.</b>
 *
 * <p>红石火把是"吸附面没有信号就亮", 这一支是"吸附面有信号就亮"; 剩下的规则逐字相同: 只读吸附面, 除吸附面以外五个方向
 * 输出 15 级弱信号, 放下时是灭的。所以这一类比 {@link ClockRule} 与 {@link ExtenderRule} 都薄: 它没有设置、没有时间、
 * 没有相位, 只有两条真值表和一个颜色。
 *
 * <p>为什么颜色也算在这里: 频道号是存档里唯一的身份, 颜色是它的一条纯函数, 所以两端各自算一遍永远一致, 不必上线, 也不
 * 必担心客户端那一份和服务器那一份漂开。色相用黄金角 137.508 度一档: 相邻频道号之间差得足够远, 而任何两个频道号在
 * 环形色相上都几乎不会靠近 - 一列火把排在墙上时, 这是"一眼看出它们是不是一组"的全部。
 */
public final class SyncTorchRule {

    /** 不属于任何频道. 与 {@code InnerRedstoneStore#syncTorchChannelAt} 的 0 分工: 那里 0 表示"这里没有火把",
     *  -1 表示"有火把, 但还没绑频道". */
    public static final int UNBOUND = -1;

    /** 组 pass 一次 tick 里最多迭代几轮. 上限而不是"迭代到不动"的理由: 两个组的成员互相当对方的输入时, 每轮都有变化,
     *  而"同步"要的是同一 tick 里一起亮, 所以宁可停在这个上限上并接受那一 tick 的答案, 也不要为了收敛把 tick 拖长。 */
    public static final int MAX_PASS_ITERATIONS = 4;

    /** 色相的黄金角, 度. 用它一步一档, 任何两个频道号都不会撞在同一条色相上。 */
    public static final double HUE_STEP = 137.508D;

    /** 饱和度: 高而不满, 于是同一色相上的火把仍然看得出是一支发光的方块, 而不是一块纯色塑料。 */
    public static final float SATURATION = 0.85F;

    /** 明度: 满, 于是同一个频道在任何一侧都是同一个不透明颜色。 */
    public static final float VALUE = 1.0F;

    /** 未绑定的火把的颜色: 中性灰, 不和任何一个频道号撞, 玩家一眼看得出它还不属于任何组。 */
    public static final int UNBOUND_COLOUR = 0xFF808080;

    private SyncTorchRule() {
    }

    /** Whether a number names a channel at all - anything a torch may actually be bound to. */
    public static boolean isChannel(int channel) {
        return channel > 0;
    }

    /**
     * 全组的输入合成一个答案: OR, 也就是"任一成员在它的吸附面收到信号".
     *
     * <p>写成方法而不是让调用方自己 {@code anyMemberInput ||} 一次, 是因为这句话就是这件设备的语义本身, 而它只该有
     * 一处表述: 集中式的中继器可以有别的一种组合 (AND 就是一个"全部到位才亮"的变体), 换它的时候改这里。
     */
    public static boolean lit(boolean anyMemberInput) {
        return anyMemberInput;
    }

    /** 亮起时答多少: 满强度, like every other source in this mod; 灭时 0. */
    /** 一个方向在那个"哪几面有输入"的掩码里的那一位. 用 {@link net.minecraft.core.Direction#ordinal()} - 六个方向的
     * 顺序在 1.21.1 里是固定的 (DOWN, UP, NORTH, SOUTH, WEST, EAST). */
    public static byte bit(net.minecraft.core.Direction side) {
        return (byte) (1 << side.ordinal());
    }

    /** 这一面此刻是不是有输入. */
    public static boolean hasInput(byte inputs, net.minecraft.core.Direction side) {
        return (inputs & bit(side)) != 0;
    }

    /**
     * 这一面此刻输不输出 - <b>有输入的那一面不输出</b>, 这是这副器件不自锁的全部机制.
     *
     * <p>为什么它成立: 灭着的火把什么都不输出, 所以它读到的任何信号都不可能是自己造的; 一旦某面读到了信号, 那一面就
     * 被记进掩码并不再输出, 于是外面那份 15 不会被我们的输出续上; 撤掉外面的信号, 那一面归零, 火把灭, 灭着又什么都不
     * 输出 —— 环在这里断掉。吸附面同理 (它是我们的来源, 输出回去就是自己给自己供电), 只不过它是静态的。
     *
     * <p>这条"记住哪几面有输入"必须由 tick pass 写下来, 不能每次现算: 现算的那一瞬间, 我们刚打出去的 15 已经躺在邻居
     * 的读数里了, 于是永远判成"有输入"。
     */
    public static boolean emitsOn(byte inputs, net.minecraft.core.Direction facing,
                                  net.minecraft.core.Direction direction) {
        return direction != facing && !hasInput(inputs, direction);
    }

    /**
     * 这一面此刻<b>要不要听</b>. 上一 tick 正在往它输出的那一面不听 —— 它答的 15 可能正是我们自己打过去的那一份。
     *
     * <p>少这一条就会每两 tick 抖一次: 我们把 15 打给红石粉 → 粉亮 → 那一面读到 15 → 记成输入 → 我们停手 → 粉灭 →
     * 那一面读不到 → 我们重新输出 ⇒ 亮灭锁在某个相位上, 玩家看到的就是"两支一起自锁"。加这一条之后, 自己的输出永远
     * 不会被自己听成输入, 而真正的外部电源仍然听得见: 我们只在"本来就不往那一面输出"的时候才需要它。
     */
    public static boolean listensTo(byte inputs, net.minecraft.core.Direction facing, boolean lit,
                                    net.minecraft.core.Direction side) {
        return !(lit && emitsOn(inputs, facing, side));
    }

    /**
     * 一个邻居这一刻给了我们多少: 导体读它的弱信号(里面只有被强充能的那一份), 其余把弱信号与 direct 都算上。
     *
     * <p>为什么会读的方块(红石粉、中继器、比较器)现在也敢读: 它们的答案一旦非零, 那一面就被记成输入并且不再输出, 我们
     * 的 15 不会被送回来 —— 见 {@link #emitsOn}。
     */
    public static int ask(boolean conductor, int signal, int directSignal) {
        return conductor ? signal : Math.max(signal, directSignal);
    }

    public static int output(boolean lit) {
        return lit ? PowerSolver.MAX_POWER : 0;
    }

    /**
     * 一个频道的颜色, 由频道号派生, 不存档.
     *
     * <p>HSV: {@code hue = (channel * 137.508) mod 360}, {@code S = 0.85}, {@code V = 1.0}, 转成不透明 ARGB。
     * {@link #UNBOUND} 拿到一支中性灰 - 它同样是一个确定的答案, 所以两端仍然一致, 只是不参与色相那一族。
     */
    public static int colour(int channel) {
        if (!isChannel(channel)) {
            return UNBOUND_COLOUR;
        }
        double hue = channel * HUE_STEP;
        hue -= 360.0D * Math.floor(hue / 360.0D);
        return hsv(hue, SATURATION, VALUE);
    }

    /** HSV 转不透明 ARGB; 色相 0-360, 饱和度与明度 0-1. */
    private static int hsv(double hue, float saturation, float value) {
        float sector = (float) ((hue % 360.0D) / 60.0D);
        int index = (int) Math.floor(sector) % 6;
        float fraction = sector - (float) Math.floor(sector);
        float p = value * (1.0F - saturation);
        float q = value * (1.0F - saturation * fraction);
        float t = value * (1.0F - saturation * (1.0F - fraction));
        float red;
        float green;
        float blue;
        switch (index) {
            case 0 -> {
                red = value;
                green = t;
                blue = p;
            }
            case 1 -> {
                red = q;
                green = value;
                blue = p;
            }
            case 2 -> {
                red = p;
                green = value;
                blue = t;
            }
            case 3 -> {
                red = p;
                green = q;
                blue = value;
            }
            case 4 -> {
                red = t;
                green = p;
                blue = value;
            }
            default -> {
                red = value;
                green = p;
                blue = q;
            }
        }
        return 0xFF000000 | (byteOf(red) << 16) | (byteOf(green) << 8) | byteOf(blue);
    }

    /** 一个 0-1 的分量换算成 0-255 的一字节; 四舍五入并夹紧, 所以极值也不会溢出到相邻分量上。 */
    private static int byteOf(float component) {
        return Math.max(0, Math.min(255, Math.round(component * 255.0F)));
    }
}
