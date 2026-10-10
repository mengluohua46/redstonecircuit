package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

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
 * 信号延长器's screen: the two numbers 时钟源 also has - 秒 and 刻 - and the hold they set.
 *
 * <p>It is {@link ClockScreen} without the 强度 field: the same two time fields, the same units, and so the same rule
 * ({@link ExtenderRule}). What 背面 outputs is not a strength of its own but whatever arrives at 正面, so there is no
 * third number to set.
 *
 * <p>Unlike the clock, nothing here is computed locally: a hold depends on when the input last went away, and only the
 * server sees the input, so every number shown is one the server just sent ({@link RCNetwork.DeviceViewPayload}). The
 * screen owns the two things only a client knows - what the player is typing (a field half-way through "3600" is not
 * the number 3) and how often to ask.
 */
@OnlyIn(Dist.CLIENT)
public final class ExtenderScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 140;
    private static final int BUTTON_HEIGHT = 18;
    private static final int FIELD_WIDTH = 64;
    private static final int FIELD_HEIGHT = 18;
    private static final int LABEL_TOP = 28;
    private static final int FIELD_TOP = 40;

    /** The two columns the fields stand in: 秒, 刻. */
    private static final int COLUMN_SECONDS = 4;
    private static final int COLUMN_TICKS = 76;

    /** How often the screen asks the server what the device is set to now. */
    private static final int REFRESH_TICKS = 10;

    /** How long the screen waits after the last keystroke before it sends what was typed. */
    private static final int SEND_DELAY_TICKS = 4;

    private final BlockPos pos;
    private EditBox secondsField;
    private EditBox ticksField;
    private int left;
    private int top;

    /** The server's answer, field for field: the two settings and the two numbers that go with them. */
    private int seconds = ExtenderRule.DEFAULT_SECONDS;
    private int ticks = ExtenderRule.DEFAULT_TICKS;
    private int input;
    private int power;
    private int serverTicksLeft;

    /** True while this screen is writing the fields itself, so filling them in is not read as the player typing. */
    private boolean filling;

    /** Ticks left before what was typed is sent, or 0 when nothing is waiting. */
    private int sendIn;

    /** Ticks left before the screen asks again. */
    private int refreshIn = REFRESH_TICKS;

    public ExtenderScreen(RCNetwork.DeviceViewPayload message) {
        super(Component.translatable("redstonecircuit.extender.title"));
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
        this.input = message.c();
        this.power = message.power();
        this.serverTicksLeft = message.reading();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        this.filling = true;
        try {
            this.secondsField = field(COLUMN_SECONDS, 4, "redstonecircuit.device.seconds", this.seconds);
            this.ticksField = field(COLUMN_TICKS, 2, "redstonecircuit.device.ticks", this.ticks);
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
            PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.EXTENDER));
        }
    }

    /** Sends what the player has set - or nothing at all, while any field holds something that is not one of the numbers. */
    private void send() {
        this.sendIn = 0;
        Integer seconds = typedSeconds();
        Integer ticks = typedTicks();
        if (seconds == null || ticks == null) {
            colourFields();
            return;
        }
        PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.EXTENDER,
                seconds, ticks, 0, 0));
    }

    /** The number a field holds, or {@code null} when it is not a number at all. 0 is legal in both fields. */
    private static Integer typed(EditBox field) {
        if (field == null) {
            return null;
        }
        try {
            return Integer.parseInt(field.getValue().trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The 秒 field, which this device accepts from 0 to 3600 - the same range 时钟源's 秒 field has. */
    private Integer typedSeconds() {
        Integer value = typed(this.secondsField);
        return value != null && ExtenderRule.isSeconds(value) ? value : null;
    }

    /** The 刻 field is the one field with a range of its own: 0-19, because 20 ticks is a second. */
    private Integer typedTicks() {
        Integer value = typed(this.ticksField);
        return value != null && ExtenderRule.isTicks(value) ? value : null;
    }

    private void colourFields() {
        if (this.secondsField != null) {
            this.secondsField.setTextColor(typedSeconds() == null ? 0xFF5555 : 0xE0E0E0);
            this.ticksField.setTextColor(typedTicks() == null ? 0xFF5555 : 0xE0E0E0);
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

        graphics.drawString(this.font, Component.translatable("redstonecircuit.extender.rule"),
                this.left + 4, this.top + 64, 0x909090);
        graphics.drawString(this.font, holdLine(), this.left + 4, this.top + 80, 0xC0C0C0);
        graphics.drawString(this.font, nowLine(), this.left + 4, this.top + 92,
                this.power > 0 ? 0x55FF55 : 0x808080);
    }

    /** The hold the two fields ask for, from the settings the server last sent. */
    private Component holdLine() {
        int hold = ExtenderRule.holdTicks(this.seconds, this.ticks);
        return hold == 0
                ? Component.translatable("redstonecircuit.extender.off")
                : Component.translatable("redstonecircuit.extender.hold", hold, this.seconds, this.ticks);
    }

    /** Whether the input is still arriving, whether a hold is standing in for it, or neither. */
    private Component nowLine() {
        if (this.power <= 0) {
            return Component.translatable("redstonecircuit.extender.silent");
        }
        if (this.input > 0) {
            return Component.translatable("redstonecircuit.extender.passing", this.input, this.power);
        }
        return Component.translatable("redstonecircuit.extender.holding", this.power, this.serverTicksLeft);
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
