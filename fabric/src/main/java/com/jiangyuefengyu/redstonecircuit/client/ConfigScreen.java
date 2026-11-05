package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * 配置界面: 一行一个设置, 点一下就改, 改完立刻写进 {@code redstonecircuit.json}。
 *
 * <h2>为什么这一页不自己列设置</h2>
 * 行是 {@link RCConfig#keys()} 给的, 和 {@code RCConfig.DEFAULTS} 是同一张表: NeoForge 那边由
 * {@code ModConfigSpec} 生成界面, 换到 Fabric 之后如果这里自己再写一份清单, 新加一个设置就要记得改三处
 * (默认值表、语言文件、这里), 而漏掉一处只在玩家打开界面时看得出来。走 {@code keys()} 就不会漏: 多出来的
 * 设置自己就出现在列表里。
 *
 * <h2>控件按默认值的类型选, 不按文件里现在的值选</h2>
 * 文件是可以手改的: 一个本该是布尔的地方写成了字符串, {@link RCConfig#current(String)} 会退回默认值, 但如果
 * 界面照着"现在读出来的东西"决定画什么, 同一台机器会因为文件里某一行而换一套控件。所以这一页看的是
 * {@link RCConfig#defaultValue(String)} 的类型, 屏幕上永远只有这一套。
 *
 * <h2>哪些设置没有控件</h2>
 * 布尔、整数和下面 {@link #CYCLES} 里点名的那几个字符串各有各的控件; 其余的<b>不画</b>: 列表型的选项(结构捕获
 * 的黑名单)是一串方块 id, 浮点型的两个距离不是"点一下往前走一格"的东西, 而一个点不动的开关只是一行占地方的
 * 文字。
 *
 * <h2>卷动的做法</h2>
 * 一屏放不下七十多个设置, 所以只给<b>看得见的那几行</b>建控件, 滚轮一动就 {@link #rebuildWidgets()} 重新摆一遍。
 * 这样做的好处不止是省: Tab 键只在看得见的控件之间走, 焦点不会跑到屏幕外面去。
 */
@Environment(EnvType.CLIENT)
public final class ConfigScreen extends Screen {

    /** 一行的宽度; 窗口比这窄的时候用窗口宽度减掉两边的空。 */
    private static final int WIDTH = 320;

    private static final int ROW_HEIGHT = 20;

    /** 行距: 行高再加两像素的空, 免得相邻两行的按钮粘成一块。 */
    private static final int ROW_PITCH = 22;

    /** 左右两个箭头的宽度: 二十像素刚好放下一个"<"。 */
    private static final int ARROW_WIDTH = 20;

    /** 标题和分区标题占掉的高度。 */
    private static final int HEADER = 38;

    /** 底部那一行: Done 按钮和"第几行到第几行"。 */
    private static final int FOOTER = 34;

    private static final int TITLE = 0xFFFFFF;
    private static final int LABEL = 0xE0E0E0;
    private static final int DIM = 0xA0A0A0;

    /**
     * 一个键能被点成的那几个值, 按点击的顺序。
     *
     * <p>只有"值的个数是有限的"设置才在表里: 显示模式是三个常量, alpha 是玩家看得出来的几个档, 颜色是一块
     * 调色板, cascade 是 2 的幂。表外的整数用左右箭头一格格加, 表外的字符串没有控件。
     *
     * <p>表里的每一个键都必须在 {@code RCConfig.DEFAULTS} 里存在, 这条由 {@code ConfigScreenWiringTest} 钉住:
     * 拼错的键在这里看着没有任何问题, 只是那一行永远不出现。
     */
    private static final Map<String, List<Object>> CYCLES = new LinkedHashMap<>();

    /** 颜色是可以直接写十六进制的, 所以调色板是字符串; 渲染器读的也是这个字符串。 */
    private static final List<Object> PALETTE = values(
            "9FB6FF", "E03020", "FF8C00", "7FE070", "F0E060", "FFFFFF", "000000");

    /**
     * 显示模式那三个常量名。
     *
     * <p>它们的标签在语言文件里各有自己的键, 不是一个数字也不是一个颜色, 所以要认出来; 认错的那一半(颜色)会是
     * 一串没人翻译的键名画在屏幕上。
     */
    private static final Set<String> MODE_LABELS = Set.of("GOGGLES", "ALWAYS", "OFF");

    static {
        CYCLES.put("hostOverlayMode", values("GOGGLES", "ALWAYS", "OFF"));
        CYCLES.put("hostOverlayAlpha", values(0, 50, 100, 150, 170, 200, 255));
        CYCLES.put("hostOverlayColor", PALETTE);
        CYCLES.put("hostOverlayFrameAlpha", values(0, 50, 100, 150, 170, 200, 255));
        CYCLES.put("hostOverlayFrameColor", PALETTE);
        CYCLES.put("superconductorFrameColor", PALETTE);
        CYCLES.put("solverCascadeLimit", values(1, 2, 4, 8, 16, 32));
    }

    /** 一行怎么点: 开/关、走表、还是左右加减。 */
    private enum Kind {
        TOGGLE,
        CYCLE,
        STEP
    }

    private record Row(String key, Kind kind) {
    }

    /** 打开这一页的那块界面: Done 回到它, 而不是把玩家扔回游戏里。 */
    private final Screen parent;

    /** 屏幕要画的行, 在构造时算一次: 它只跟默认值有关, 不跟窗口大小有关。 */
    private final List<Row> rows;

    /** 每个控件对应的提示, 键盘选中那一行时用的是同一份。 */
    private final Map<GuiEventListener, Component> hints = new HashMap<>();

    /** 列表里第一行的行号; 滚轮和窗口变化之后都夹回合法范围。 */
    private int scroll;

    public ConfigScreen(Screen parent) {
        super(Component.translatable("redstonecircuit.configuration.title"));
        this.parent = parent;
        this.rows = collect();
    }

    /**
     * 这一页要画的行, 按 {@link RCConfig#keys()} 的顺序。
     *
     * <p>{@link RCConfig#current(String)} 读不出来({@code null})的键直接跳过: 那正是列表型选项的样子, 它没有
     * 一个能放在按钮上的值。
     */
    private static List<Row> collect() {
        List<Row> rows = new ArrayList<>();
        for (String key : RCConfig.keys()) {
            if (RCConfig.current(key) == null) {
                continue;
            }
            Object fallback = RCConfig.defaultValue(key);
            if (fallback instanceof Boolean) {
                rows.add(new Row(key, Kind.TOGGLE));
            } else if (CYCLES.containsKey(key)) {
                rows.add(new Row(key, Kind.CYCLE));
            } else if (fallback instanceof Integer) {
                rows.add(new Row(key, Kind.STEP));
            }
            // 其余的类型没有控件, 也就不占一行: 见类注释.
        }
        return List.copyOf(rows);
    }

    /** {@code List<Object>} 的简写: 表里既有数字也有字符串, 不写这个每一行都要带一次类型参数。 */
    private static List<Object> values(Object... entries) {
        return List.of(entries);
    }

    // ------------------------------------------------------------------ setup --

    @Override
    protected void init() {
        this.hints.clear();
        this.scroll = Math.max(0, Math.min(this.scroll, maxScroll()));
        int left = listLeft();
        int width = rowWidth();
        for (int index = 0; index < shownRows(); index++) {
            int row = this.scroll + index;
            if (row >= this.rows.size()) {
                break;
            }
            Row entry = this.rows.get(row);
            int top = rowTop(index);
            switch (entry.kind()) {
                case TOGGLE -> addToggle(entry.key(), left, top, width);
                case CYCLE -> addCycle(entry.key(), left, top, width);
                default -> addStep(entry.key(), left, top, width);
            }
        }
        // 每次改动在点下去的那一刻就存了盘, 所以 Done 只是关: 一个按钮在关界面的同时再存一遍, 只会让人以为
        // 不按它就不算数.
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), button -> this.onClose())
                .bounds(this.width / 2 - 60, this.height - 28, 120, ROW_HEIGHT).build());
    }

    /** 开/关: 整行一个按钮, 按下去的标题里就写着现在是什么。 */
    private void addToggle(String key, int x, int y, int width) {
        Button button = addRenderableWidget(Button.builder(rowLabel(key), pressed -> {
            Object now = RCConfig.current(key);
            RCConfig.set(key, !(now instanceof Boolean value && value));
            pressed.setMessage(rowLabel(key));
        }).bounds(x, y, width, ROW_HEIGHT).build());
        hint(button, key);
    }

    /** 走表: 整行一个按钮, 点一下走到表里的下一个值。 */
    private void addCycle(String key, int x, int y, int width) {
        Button button = addRenderableWidget(Button.builder(rowLabel(key), pressed -> {
            RCConfig.set(key, next(key));
            pressed.setMessage(rowLabel(key));
        }).bounds(x, y, width, ROW_HEIGHT).build());
        hint(button, key);
    }

    /**
     * 加减: 两头各一个小箭头, 中间那行字由 {@link #render} 画。
     *
     * <p>表外的整数是次数、上限、tick, 一格一格走才有意义: 一个只能往上加的按钮, 加过头就只能去手改文件。
     */
    private void addStep(String key, int x, int y, int width) {
        Button down = addRenderableWidget(Button.builder(Component.literal("<"), pressed -> step(key, -1))
                .bounds(x, y, ARROW_WIDTH, ROW_HEIGHT).build());
        hint(down, key);
        Button up = addRenderableWidget(Button.builder(Component.literal(">"), pressed -> step(key, 1))
                .bounds(x + width - ARROW_WIDTH, y, ARROW_WIDTH, ROW_HEIGHT).build());
        hint(up, key);
    }

    /** 提示挂在这一行的每个控件上: 悬停在箭头或按钮上都问的是同一个问题。 */
    private void hint(AbstractWidget widget, String key) {
        Component hint = Component.translatable(tooltipKey(key));
        widget.setTooltip(Tooltip.create(hint));
        this.hints.put(widget, hint);
    }

    // --------------------------------------------------------------- the rows --

    /** 设置名与提示的键: 语言文件里每一个键都有这两条, 名字由 {@link #nameKey} 拼。 */
    private static String nameKey(String key) {
        return "redstonecircuit.configuration." + key;
    }

    private static String tooltipKey(String key) {
        return nameKey(key) + ".tooltip";
    }

    /** 一行上写着的东西: 名字, 加冒号, 加现在这个值。 */
    private static Component rowLabel(String key) {
        return Component.translatable(nameKey(key)).append(": ").append(display(RCConfig.current(key)));
    }

    /**
     * 一个值怎么写出来。
     *
     * <p>显示模式那三个是常量名, 语言文件里各有一句人话; 其余——数字、颜色的十六进制串——就是它本身。
     */
    private static Component display(Object value) {
        if (value instanceof String text && MODE_LABELS.contains(text)) {
            return Component.translatable("redstonecircuit.configuration.hostOverlayMode." + text);
        }
        return Component.literal(String.valueOf(value));
    }

    /**
     * 点一下走到的下一个值。
     *
     * <p>当前值不在表里(手改过的 alpha、别人配的颜色)时不算错: 走到第一个比它大的那一档; 一个都不比它大就回到
     * 开头。这样一按总是落在玩家看得出来的那一档上, 而不是从 128 直接掉到 0。
     */
    private static Object next(String key) {
        List<Object> table = CYCLES.get(key);
        Object value = RCConfig.current(key);
        for (int index = 0; index < table.size(); index++) {
            if (String.valueOf(table.get(index)).equals(String.valueOf(value))) {
                return table.get((index + 1) % table.size());
            }
        }
        for (Object entry : table) {
            if (entry instanceof Number higher && value instanceof Number now
                    && higher.doubleValue() > now.doubleValue()) {
                return entry;
            }
        }
        return table.get(0);
    }

    /** 箭头按一下: 新值立刻落盘, 屏幕上的读数下一次重画就跟着走。 */
    private static void step(String key, int delta) {
        Object value = RCConfig.current(key);
        int now = value instanceof Integer number ? number : 0;
        // 这些数没有一个能是负的, 所以 0 就是底; 要更小的值只有手改文件一种办法, 那是另一件事.
        RCConfig.set(key, Math.max(0, now + delta));
    }

    // -------------------------------------------------------------- scrolling --

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int room = maxScroll();
        if (room <= 0 || mouseY < HEADER || mouseY > HEADER + shownRows() * ROW_PITCH) {
            // 列表外面滚轮什么也不做: 这一页只有列表一个能滚的东西.
            return false;
        }
        int moved = Math.max(0, Math.min(room, this.scroll - (int) Math.signum(scrollY)));
        if (moved != this.scroll) {
            this.scroll = moved;
            // 只有看得见的那几行有控件, 所以滚动就是照着新的起点重新摆一遍.
            this.rebuildWidgets();
        }
        return true;
    }

    private int rowWidth() {
        return Math.min(WIDTH, this.width - 24);
    }

    private int listLeft() {
        return (this.width - rowWidth()) / 2;
    }

    private int rowTop(int index) {
        return HEADER + index * ROW_PITCH;
    }

    /** 一屏能站几行; 窗口矮到一行都站不下时也站一行, 免得整个列表凭空消失。 */
    private int shownRows() {
        return Math.max(1, (this.height - HEADER - FOOTER) / ROW_PITCH);
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() - shownRows());
    }

    // ------------------------------------------------------------------ 画 --

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 10, TITLE);
        // 分区标题是文件里那条 section 键: NeoForge 的界面把它画在顶上, 这里也照做, 玩家看到的是同一个名字.
        graphics.drawCenteredString(this.font,
                Component.translatable("redstonecircuit.configuration.section.redstonecircuit.common.toml.title"),
                this.width / 2, 22, DIM);
        for (int index = 0; index < shownRows(); index++) {
            int row = this.scroll + index;
            if (row >= this.rows.size()) {
                break;
            }
            Row entry = this.rows.get(row);
            if (entry.kind() == Kind.STEP) {
                // 加减那两行中间的读数: 它是这个设置此刻的值, 不是一个能点的东西, 所以画出来.
                graphics.drawString(this.font, rowLabel(entry.key()),
                        listLeft() + ARROW_WIDTH + 4, rowTop(index) + 6, LABEL);
            }
        }
        if (maxScroll() > 0) {
            graphics.drawString(this.font, pageLine(), listLeft(), this.height - 24, DIM);
        }
        hintForFocusedRow(mouseX, mouseY);
    }

    /** 现在看到的是第几行到第几行: 列表比一屏长的时候, 玩家得知道下面还有。 */
    private Component pageLine() {
        int first = this.scroll + 1;
        int last = Math.min(this.rows.size(), this.scroll + shownRows());
        return Component.literal(first + "-" + last + " / " + this.rows.size());
    }

    /**
     * 键盘选中的那一行也说一句话。
     *
     * <p>鼠标停在哪个控件上, 原版自己会画那一条提示; Tab 选中的行没有鼠标停在上面, 不补这一手, 只用键盘的玩家就
     * 只剩下一行"名字: 值"。悬停时不动: 那一格原版已经在画了, 两处都写就是同一个提示抢同一帧。
     */
    private void hintForFocusedRow(int mouseX, int mouseY) {
        GuiEventListener focused = this.getFocused();
        Component hint = focused == null ? null : this.hints.get(focused);
        if (hint != null && this.getChildAt(mouseX, mouseY).isEmpty()) {
            this.setTooltipForNextRenderPass(hint);
        }
    }

    @Override
    public void onClose() {
        // 回到打开它的那块界面(ModMenu 的模组列表), 而不是 minecraft.setScreen(null) 把玩家扔回游戏.
        if (this.minecraft != null) {
            this.minecraft.setScreen(this.parent);
        }
    }
}
