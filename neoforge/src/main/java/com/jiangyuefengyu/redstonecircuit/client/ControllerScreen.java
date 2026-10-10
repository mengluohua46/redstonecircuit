package com.jiangyuefengyu.redstonecircuit.client;

import com.jiangyuefengyu.redstonecircuit.logic.ControllerRule;
import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;
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
 * 控制器's own screen: 主档位, 副档位, 输入档位, 输出强度, and what the plate is doing about them.
 *
 * <p>The screen decides nothing: numbers come from the server and changes go back as edits, and the condition
 * line is built from the server's settings through {@link ControllerRule} - the same table the block runs - so
 * it cannot disagree with the circuit. All the screen owns is the text being typed (a field half-way through
 * "15" is not the number 1) and how often to ask.
 *
 * <p>输入档位 is 0-15 and 输出强度 is 1-15, and they are not the same range: 0 is a 档位 a player may want
 * ("the input must be exactly nothing") but not an 输出强度, since a device that answers 0 answers nothing.
 * A field holding anything else is marked red and not sent.
 */
@OnlyIn(Dist.CLIENT)
public final class ControllerScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 190;
    private static final int BUTTON_HEIGHT = 18;
    private static final int MAIN_BUTTON_WIDTH = 60;
    private static final int SUB_BUTTON_WIDTH = 52;
    private static final int SUB_BUTTON_GAP = 2;
    private static final int FIELD_WIDTH = 40;
    private static final int FIELD_HEIGHT = 18;

    /** How often the screen asks the server what the block reads now. Twice a second, and the server answers slower. */
    private static final int REFRESH_TICKS = 10;

    /** How long the screen waits after the last keystroke before it sends what was typed. */
    private static final int SEND_DELAY_TICKS = 4;

    private final BlockPos pos;
    private Button[] mainButtons;
    private Button[] subButtons;
    private EditBox xField;
    private EditBox yField;
    private int left;
    private int top;

    /** The server's answer, field for field: the two 档位, the two numbers and the two live readings. */
    private int main;
    private int sub;
    private int x = 1;
    private int y = ControllerRule.MAX;
    private int reading;
    private int power;

    /** True while this screen is writing the fields itself, so filling them in is not read as the player typing. */
    private boolean filling;

    /** Ticks left before what was typed is sent, or 0 when nothing is waiting. */
    private int sendIn;

    /** Ticks left before the screen asks again. */
    private int refreshIn = REFRESH_TICKS;

    public ControllerScreen(RCNetwork.DeviceViewPayload message) {
        super(Component.translatable("redstonecircuit.controller.title"));
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
        if (this.xField != null) {
            // A focused field keeps what is being typed: the answer to the "1" of a half-typed "15" must not
            // replace the "15" the player is on their way to writing.
            this.filling = true;
            try {
                if (!this.xField.isFocused()) {
                    this.xField.setValue(Integer.toString(this.x));
                }
                if (!this.yField.isFocused()) {
                    this.yField.setValue(Integer.toString(this.y));
                }
            } finally {
                this.filling = false;
            }
            refreshButtons();
            colourFields();
        }
    }

    private void store(RCNetwork.DeviceViewPayload message) {
        this.main = message.a();
        this.sub = message.b();
        this.x = message.c();
        this.y = message.d();
        this.reading = message.reading();
        this.power = message.power();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        ControllerRule.Main[] mains = ControllerRule.Main.values();
        this.mainButtons = new Button[mains.length];
        for (int index = 0; index < mains.length; index++) {
            int chosen = index;
            this.mainButtons[index] = addRenderableWidget(
                    Button.builder(label(mains[index]), button -> chooseMain(chosen))
                            .bounds(this.left + 4 + index * (MAIN_BUTTON_WIDTH + 2), this.top + 42,
                                    MAIN_BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build());
        }

        ControllerRule.Sub[] subs = ControllerRule.Sub.values();
        this.subButtons = new Button[subs.length];
        for (int index = 0; index < subs.length; index++) {
            int chosen = index;
            this.subButtons[index] = addRenderableWidget(
                    Button.builder(label(subs[index]), button -> chooseSub(chosen))
                            .bounds(this.left + 4 + index * (SUB_BUTTON_WIDTH + SUB_BUTTON_GAP), this.top + 80,
                                    SUB_BUTTON_WIDTH, BUTTON_HEIGHT)
                            .build());
        }

        this.filling = true;
        try {
            this.xField = addRenderableWidget(new EditBox(this.font, this.left + 4, this.top + 116,
                    FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("redstonecircuit.device.threshold")));
            this.yField = addRenderableWidget(new EditBox(this.font, this.left + 112, this.top + 116,
                    FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("redstonecircuit.device.output")));
            this.xField.setMaxLength(2);
            this.yField.setMaxLength(2);
            this.xField.setValue(Integer.toString(this.x));
            this.yField.setValue(Integer.toString(this.y));
            for (EditBox field : new EditBox[] {this.xField, this.yField}) {
                field.setResponder(typed -> edited());
            }
        } finally {
            this.filling = false;
        }

        // The button sits on its own bottom row, 10 pixels lower than the panel's old height, so it is clear
        // of the two status lines above it.
        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.device.close"),
                        button -> this.onClose())
                .bounds(this.left + WIDTH / 2 - 30, this.top + 168, 60, BUTTON_HEIGHT).build());
        refreshButtons();
        colourFields();
    }

    /** A 主档位 was picked: the screen's own field is the new answer, and the server hears about it at once. */
    private void chooseMain(int ordinal) {
        this.main = ordinal;
        refreshButtons();
        send();
    }

    /** A 副档位 was picked; same as above, because both are one click and not a half-typed number. */
    private void chooseSub(int ordinal) {
        this.sub = ordinal;
        refreshButtons();
        send();
    }

    /** The fields changed under the player's fingers: sent once they stop, so a two-digit number is one edit. */
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
            PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.CONTROLLER));
        }
    }

    /** Sends what the player has set - or nothing at all, while a field holds something that is not one of the numbers. */
    private void send() {
        this.sendIn = 0;
        Integer typedX = typed(this.xField);
        Integer typedY = typed(this.yField);
        if (typedX == null || typedY == null) {
            colourFields();
            return;
        }
        PacketDistributor.sendToServer(RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.CONTROLLER,
                this.main, this.sub, typedX, typedY));
    }

    /** The number a field holds, or {@code null} when it is not one the device could have. */
    private static Integer typed(EditBox field) {
        if (field == null) {
            return null;
        }
        String text = field.getValue().trim();
        try {
            int value = Integer.parseInt(text);
            return value >= 0 && value <= ControllerRule.MAX ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private void colourFields() {
        Integer typedX = typed(this.xField);
        Integer typedY = typed(this.yField);
        if (this.xField != null) {
            this.xField.setTextColor(typedX == null ? 0xFF5555 : 0xE0E0E0);
        }
        if (this.yField != null) {
            this.yField.setTextColor(typedY == null || !ControllerRule.isOutput(typedY) ? 0xFF5555 : 0xE0E0E0);
        }
    }

    private void refreshButtons() {
        ControllerRule.Main[] mains = ControllerRule.Main.values();
        for (int index = 0; index < mains.length; index++) {
            this.mainButtons[index].setMessage(marked(index == this.main, label(mains[index])));
        }
        ControllerRule.Sub[] subs = ControllerRule.Sub.values();
        for (int index = 0; index < subs.length; index++) {
            this.subButtons[index].setMessage(marked(index == this.sub, label(subs[index])));
        }
    }

    /** The chosen setting is boxed rather than ticked, which needs no second colour to be readable. */
    private static Component marked(boolean chosen, Component label) {
        return chosen ? Component.translatable("redstonecircuit.device.chosen", label) : label;
    }

    private static Component label(ControllerRule.Main main) {
        return Component.translatable("redstonecircuit.controller.main." + main.key());
    }

    private static Component label(ControllerRule.Sub sub) {
        return Component.translatable("redstonecircuit.controller.sub." + sub.key());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, this.title, this.left + 4, this.top + 8, 0xE0E0E0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.main"),
                this.left + 4, this.top + 28, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.sub"),
                this.left + 4, this.top + 66, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.threshold"),
                this.left + 4, this.top + 104, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.device.output"),
                this.left + 112, this.top + 104, 0xA0A0A0);

        graphics.drawString(this.font, conditionLine(), this.left + 4, this.top + 140, 0x909090);
        graphics.drawString(this.font, live(), this.left + 4, this.top + 152,
                this.power > 0 ? 0x55FF55 : 0x808080);
    }

    /**
     * The setting in words, with the two numbers in it: "条件：输入 ≥ 7 ⇒ 输出 15". Built from the server's
     * settings through {@link ControllerRule#condition}, the same table the block runs - so the sentence cannot
     * describe a condition the plate is not using.
     */
    private Component conditionLine() {
        String condition = ControllerRule.condition(ControllerRule.Main.values()[this.main],
                ControllerRule.Sub.values()[this.sub], this.x);
        return Component.translatable("redstonecircuit.controller.condition", condition, this.y);
    }

    /** What the block is reading and answering right now - the two numbers the server just sent. */
    private Component live() {
        return Component.translatable("redstonecircuit.controller.live", this.reading, this.power);
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
