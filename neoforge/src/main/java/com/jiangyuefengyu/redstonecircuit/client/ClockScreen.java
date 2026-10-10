package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 时钟源's screen: three numbers, and a clock you can watch running.
 *
 * <p>There are three fields - 秒, tick and 强度 - and the screen's job is to keep them honest: the period is shown as the
 * sum it is (tick and 秒 added together), and no field is sent while any of the three holds something the device cannot
 * have.
 *
 * <p>One live number is computed here rather than waited for: the clock <b>follows the world clock</b> rather than a
 * countdown it keeps (see {@link ClockRule}), so the screen can say what the device is doing <em>right now</em> and how
 * long until it changes, frame by frame, from the client's own tick - the same pure function the server uses, on the same
 * clock, so the two cannot disagree by more than a tick. Without a level (a screen restored before the world arrived) it
 * falls back to the numbers the server last sent.
 *
 * <p>The pulse is two ticks wide, so the "正在输出" line is a flash rather than a state. The screen decides nothing: every
 * setting came from the server and every change goes back as an edit ({@link RCNetwork.DeviceEditPayload}), leaving the
 * screen with only what the player is typing and how often to ask.
 */
@OnlyIn(Dist.CLIENT)
public final class ClockScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 156;
    private static final int BUTTON_HEIGHT = 18;
    private static final int FIELD_WIDTH = 64;
    private static final int FIELD_HEIGHT = 18;
    private static final int LABEL_TOP = 28;
    private static final int FIELD_TOP = 40;

    /** The three columns the fields stand in: 秒, tick, 强度. */
    private static final int COLUMN_SECONDS = 4;
    private static final int COLUMN_TICKS = 76;
    private static final int COLUMN_STRENGTH = 148;

    /** How often the screen asks the server what the device is set to now. */
    private static final int REFRESH_TICKS = 10;

    /** How long the screen waits after the last keystroke before it sends what was typed. */
    private static final int SEND_DELAY_TICKS = 4;

    private final BlockPos pos;
    private EditBox secondsField;
    private EditBox ticksField;
    private EditBox strengthField;
    private int left;
    private int top;

    /** The server's answer, field for field: the three settings and the two numbers that go with them. */
    private int seconds = ClockRule.DEFAULT_SECONDS;
    private int ticks = ClockRule.DEFAULT_TICKS;
    private int strength = ClockRule.DEFAULT_STRENGTH;
    private int power;
    private int serverTicksToEdge;

    /** True while this screen is writing the fields itself, so filling them in is not read as the player typing. */
    private boolean filling;

    /** Ticks left before what was typed is sent, or 0 when nothing is waiting. */
    private int sendIn;

    /** Ticks left before the screen asks again. */
    private int refreshIn = REFRESH_TICKS;

    public ClockScreen(RCNetwork.DeviceViewPayload message) {
        super(Component.translatable("redstonecircuit.clock.title"));
        this.pos = message.pos();
        store(message);
    }

    /** Whether this screen is the screen of the device in {@code pos} - the one question the message router asks. */
    public boolean shows(BlockPos pos) {
        return this.pos.equals(pos);
    }

    /** The server answered again: everything on this screen is that answer. */
    public void accept(RCNetwork.DeviceViewPayload message) {
        store(message);
        if (this.secondsField != null) {
            // A focused field keeps what is being typed into it: the answer to the "1" of a half-typed "15" must not
            // replace the "15" the player is on their way to writing.
            this.filling = true;
            try {
                fill(this.secondsField, this.seconds);
                fill(this.ticksField, this.ticks);
                fill(this.strengthField, this.strength);
            } finally {
                this.filling = false;
            }
            colourFields();
        }
    }

    private static void fill(EditBox field, int value) {
        if (field != null && !field.isFocused()) {
            field.setValue(Integer.toString(value));
        }
    }

    private void store(RCNetwork.DeviceViewPayload message) {
        this.seconds = message.a();
        this.ticks = message.b();
        this.strength = message.c();
        this.power = message.power();
        this.serverTicksToEdge = message.reading();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        this.filling = true;
        try {
            this.secondsField = field(COLUMN_SECONDS, 4, "redstonecircuit.device.seconds", this.seconds);
            this.ticksField = field(COLUMN_TICKS, 2, "redstonecircuit.device.ticks", this.ticks);
            this.strengthField = field(COLUMN_STRENGTH, 2, "redstonecircuit.device.output", this.strength);
        } finally {
            this.filling = false;
        }

        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.device.close"),
                        button -> this.onClose())
                .bounds(this.left + WIDTH / 2 - 30, this.top + HEIGHT - 24, 60, BUTTON_HEIGHT).build());
        colourFields();
    }

    private EditBox field(int x, int maxLength, String label, int value) {
        EditBox box = addRenderableWidget(new EditBox(this.font, this.left + x, this.top + FIELD_TOP,
                FIELD_WIDTH, FIELD_HEIGHT, Component.translatable(label)));
        box.setMaxLength(maxLength);
        box.setValue(Integer.toString(value));
        box.setResponder(typed -> edited());
        return box;
    }

    /** A field changed under the player's fingers: sent once they stop, so a multi-digit number is one edit. */
    private void edited() {
        if (this.filling) {
            return;
        }
        this.sendIn = SEND_DELAY_TICKS;
        colourFields();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.sendIn > 0 && --this.sendIn == 0) {
            send();
        }
        if (--this.refreshIn <= 0) {
            this.refreshIn = REFRESH_TICKS;
            PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.CLOCK));
        }
    }

    /** Sends what the player has set - or nothing at all, while any field holds something that is not one of the numbers. */
    private void send() {
        this.sendIn = 0;
        Integer seconds = typed(this.secondsField, true);
        Integer ticks = typedTicks();
        Integer strength = typed(this.strengthField, false);
        if (seconds == null || ticks == null || strength == null) {
            colourFields();
            return;
        }
        PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.CLOCK,
                seconds, ticks, strength, 0));
    }

    /**
     * The number a field holds, or {@code null} when it is not one the device could have.
     *
     * <p>{@code allowZero} is the difference between the period's two fields and the strength: 0 秒 and 0 tick are both
     * legal (both together are the off switch), while 0 is not a strength at all.
     */
    private static Integer typed(EditBox field, boolean allowZero) {
        if (field == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(field.getValue().trim());
            if (allowZero ? (value < 0 || value > ClockRule.MAX_SECONDS) : !ClockRule.isStrength(value)) {
                return null;
            }
            return value;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The tick field is the one field with a range of its own: 0-19, because 20 ticks is a second. */
    private Integer typedTicks() {
        Integer value = typed(this.ticksField, true);
        return value != null && ClockRule.isTicks(value) ? value : null;
    }

    private void colourFields() {
        if (this.secondsField != null) {
            this.secondsField.setTextColor(typed(this.secondsField, true) == null ? 0xFF5555 : 0xE0E0E0);
            this.ticksField.setTextColor(typedTicks() == null ? 0xFF5555 : 0xE0E0E0);
            this.strengthField.setTextColor(typed(this.strengthField, false) == null ? 0xFF5555 : 0xE0E0E0);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, this.title, this.left + 4, this.top + 8, 0xE0E0E0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.seconds"),
                this.left + COLUMN_SECONDS, this.top + LABEL_TOP, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.ticks"),
                this.left + COLUMN_TICKS, this.top + LABEL_TOP, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.output"),
                this.left + COLUMN_STRENGTH, this.top + LABEL_TOP, 0xA0A0A0);

        graphics.drawString(this.font, Component.translatable("redstonecircuit.clock.rule"),
                this.left + 4, this.top + 64, 0x909090);
        graphics.drawString(this.font, periodLine(), this.left + 4, this.top + 80, 0xC0C0C0);
        graphics.drawString(this.font, liveLine(), this.left + 4, this.top + 92,
                live() ? 0x55FF55 : 0x808080);
        graphics.drawString(this.font, nextLine(), this.left + 4, this.top + 104, 0x808080);
    }

    /** The period, as the sum of the two fields, from the settings the server last sent. */
    private Component periodLine() {
        return Component.translatable("redstonecircuit.clock.period",
                ClockRule.period(this.seconds, this.ticks), this.seconds, this.ticks);
    }

    /** Whether the clock is emitting at this instant - locally if the client has a world, else the server's own answer. */
    private boolean live() {
        Integer local = localPower();
        return local != null ? local > 0 : this.power > 0;
    }

    /** The strength it is emitting, or nothing at all. */
    private Component liveLine() {
        Integer local = localPower();
        int now = local != null ? local : this.power;
        return Component.translatable(now > 0 ? "redstonecircuit.clock.emitting" : "redstonecircuit.clock.silent",
                now);
    }

    /** How long until the output next changes - the same {@code ticksToEdge} the block schedules itself on. */
    private Component nextLine() {
        Integer local = localCountdown();
        return Component.translatable("redstonecircuit.clock.next",
                local != null ? local : this.serverTicksToEdge);
    }

    /** The client's own tick is the clock the phase follows, so the answer is computed here rather than waited for. */
    private long gameTime() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? -1L : minecraft.level.getGameTime();
    }

    private Integer localPower() {
        long now = gameTime();
        return now < 0 ? null : ClockRule.answer(now, this.seconds, this.ticks, this.strength);
    }

    private Integer localCountdown() {
        long now = gameTime();
        int period = ClockRule.period(this.seconds, this.ticks);
        return now < 0 || period <= 0 ? null : ClockRule.ticksToEdge(now, period);
    }

    @Override
    public void removed() {
        // A last keystroke must not be lost because the player closed the screen before the delay ran out.
        if (this.sendIn > 0) {
            send();
        }
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
