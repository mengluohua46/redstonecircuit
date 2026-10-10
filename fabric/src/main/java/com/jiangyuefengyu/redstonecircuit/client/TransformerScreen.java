package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
import com.jiangyuefengyu.redstonecircuit.logic.TransformerRule;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * 红石变压器's own screen: one number, and what the plate is doing about it.
 *
 * <p>There is one thing to set and one thing to read, and this is the smallest screen of the four: a field for 输出强度,
 * the rule in words, and the two numbers the block is living with right now (输入 and 输出).
 *
 * <p>The screen decides nothing: every number on it came from the server ({@link RCNetwork.DeviceViewPayload}) and every
 * change goes back as an edit ({@link RCNetwork.DeviceEditPayload}). What the screen owns is the two things only a
 * client knows - what the player is typing (a field half-way through "15" is not the number 1) and how often to ask.
 *
 * <p>The strength is 1-15, not 0-15: a setting of 0 would mean "swallow whatever arrives", which is a switch, and a
 * transformer with nothing at its input already answers 0. A field holding anything else is marked red and <b>not
 * sent</b>. The rule line above the readings is a fixed sentence ({@code redstonecircuit.transformer.rule}), because
 * this device's rule is the same whatever the number is. The screen does not pause the game: the refresh is for watching
 * the input move while it is open.
 */
@Environment(EnvType.CLIENT)
public final class TransformerScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 120;
    private static final int BUTTON_HEIGHT = 18;
    private static final int FIELD_WIDTH = 40;
    private static final int FIELD_HEIGHT = 18;

    /** How often the screen asks the server what the block reads now. Twice a second, and the server answers slower. */
    private static final int REFRESH_TICKS = 10;

    /** How long the screen waits after the last keystroke before it sends what was typed. */
    private static final int SEND_DELAY_TICKS = 4;

    private final BlockPos pos;
    private EditBox settingField;
    private int left;
    private int top;

    /** The server's answer, field for field: the setting and the two live readings. */
    private int setting = TransformerRule.DEFAULT;
    private int reading;
    private int power;

    /** True while this screen is writing the field itself, so filling it in is not read as the player typing. */
    private boolean filling;

    /** Ticks left before what was typed is sent, or 0 when nothing is waiting. */
    private int sendIn;

    /** Ticks left before the screen asks again. */
    private int refreshIn = REFRESH_TICKS;

    public TransformerScreen(RCNetwork.DeviceViewPayload message) {
        super(Component.translatable("redstonecircuit.transformer.title"));
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
        if (this.settingField != null) {
            // A focused field keeps what is being typed into it: the answer to the "1" of a half-typed "15" must not
            // replace the "15" the player is on their way to writing.
            this.filling = true;
            try {
                if (!this.settingField.isFocused()) {
                    this.settingField.setValue(Integer.toString(this.setting));
                }
            } finally {
                this.filling = false;
            }
            colourField();
        }
    }

    private void store(RCNetwork.DeviceViewPayload message) {
        this.setting = message.a();
        this.reading = message.reading();
        this.power = message.power();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        this.filling = true;
        try {
            this.settingField = addRenderableWidget(new EditBox(this.font, this.left + 4, this.top + 40,
                    FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("redstonecircuit.device.output")));
            this.settingField.setMaxLength(2);
            this.settingField.setValue(Integer.toString(this.setting));
            this.settingField.setResponder(typed -> edited());
        } finally {
            this.filling = false;
        }

        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.device.close"),
                        button -> this.onClose())
                .bounds(this.left + WIDTH / 2 - 30, this.top + HEIGHT - 24, 60, BUTTON_HEIGHT).build());
        colourField();
    }

    /** The field changed under the player's fingers: sent once they stop, so a two-digit number is one edit. */
    private void edited() {
        if (this.filling) {
            return;
        }
        this.sendIn = SEND_DELAY_TICKS;
        colourField();
    }

    @Override
    public void tick() {
        super.tick();
        if (this.sendIn > 0 && --this.sendIn == 0) {
            send();
        }
        if (--this.refreshIn <= 0) {
            this.refreshIn = REFRESH_TICKS;
            ClientPlayNetworking.send(RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.TRANSFORMER));
        }
    }

    /** Sends what the player has set - or nothing at all, while the field holds something that is not one of the numbers. */
    private void send() {
        this.sendIn = 0;
        Integer typed = typed(this.settingField);
        if (typed == null) {
            colourField();
            return;
        }
        ClientPlayNetworking.send(RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.TRANSFORMER,
                typed, 0, 0, 0));
    }

    /** The strength a field holds, or {@code null} when it is not one the device could have. */
    private static Integer typed(EditBox field) {
        if (field == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(field.getValue().trim());
            return TransformerRule.isOutput(value) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void colourField() {
        if (this.settingField != null) {
            this.settingField.setTextColor(typed(this.settingField) == null ? 0xFF5555 : 0xE0E0E0);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, this.title, this.left + 4, this.top + 8, 0xE0E0E0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.output"),
                this.left + 4, this.top + 28, 0xA0A0A0);

        graphics.drawString(this.font, Component.translatable("redstonecircuit.transformer.rule"),
                this.left + 4, this.top + 68, 0x909090);
        graphics.drawString(this.font, live(), this.left + 4, this.top + 80,
                this.power > 0 ? 0x55FF55 : 0x808080);
    }

    /** What the block is reading and answering right now - the two numbers the server just sent. */
    private Component live() {
        return Component.translatable("redstonecircuit.transformer.live", this.reading, this.power);
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
