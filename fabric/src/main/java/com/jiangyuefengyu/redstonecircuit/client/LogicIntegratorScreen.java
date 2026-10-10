package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.logic.LogicGate;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * 逻辑整合器's own screen: 档位, X, Y, and what the block is seeing right now.
 *
 * <p>Three jobs: pick one of the seven gates, type the two strengths, and <b>say what the block is doing about
 * it</b> - including the case where it does nothing because the wrong number of faces carries a signal. The
 * screen decides nothing, so it owns only the text being typed and how often to ask. An empty field is not
 * zero: unfilled travels as {@link LogicGate#UNSET}, while {@code 0} is a decision.
 */
@Environment(EnvType.CLIENT)
public final class LogicIntegratorScreen extends Screen {

    private static final int WIDTH = 220;
    private static final int HEIGHT = 180;
    private static final int MODE_COLUMNS = 4;
    private static final int MODE_BUTTON_WIDTH = 52;
    private static final int MODE_BUTTON_HEIGHT = 18;
    private static final int FIELD_WIDTH = 40;
    private static final int FIELD_HEIGHT = 18;

    /** How often the screen asks the server what the block sees now. Twice a second, and the server answers slower. */
    private static final int REFRESH_TICKS = 10;

    /** How long the screen waits after the last keystroke before it sends what was typed. */
    private static final int SEND_DELAY_TICKS = 4;

    private final BlockPos pos;
    private Button[] modeButtons;
    private EditBox xField;
    private EditBox yField;
    private int left;
    private int top;

    /** The server's answer, field for field: the gate, the two numbers, the readings, the inputs and the verdict. */
    private int mode;
    private int x = LogicGate.UNSET;
    private int y = LogicGate.UNSET;
    private int facing;
    private int[] readings = new int[Direction.values().length];

    /** Which faces count as inputs, one bit per {@link Direction#ordinal()}: a face with a wire on it counts. */
    private int inputs;
    private int power;
    private int verdict;

    /** True while this screen is writing the fields itself, so filling them in is not read as the player typing. */
    private boolean filling;

    /** Ticks left before what was typed is sent, or 0 when nothing is waiting. */
    private int sendIn;

    /** Ticks left before the screen asks again. */
    private int refreshIn = REFRESH_TICKS;

    public LogicIntegratorScreen(RCNetwork.LogicViewPayload message) {
        super(Component.translatable("redstonecircuit.logic.title"));
        this.pos = message.pos();
        store(message);
    }

    /** Whether this screen is the screen of the block in {@code pos} - the one question the message router asks. */
    public boolean shows(BlockPos pos) {
        return this.pos.equals(pos);
    }

    /** The server answered again: everything on this screen is that answer. */
    public void accept(RCNetwork.LogicViewPayload message) {
        store(message);
        if (this.xField != null) {
            // A focused field keeps what is being typed: the answer to the "1" of a half-typed "15" must not
            // replace the "15" the player is on their way to writing.
            this.filling = true;
            try {
                if (!this.xField.isFocused()) {
                    this.xField.setValue(text(this.x));
                }
                if (!this.yField.isFocused()) {
                    this.yField.setValue(text(this.y));
                }
            } finally {
                this.filling = false;
            }
            refreshModeButtons();
        }
    }

    private void store(RCNetwork.LogicViewPayload message) {
        this.mode = message.mode();
        this.x = message.x();
        this.y = message.y();
        this.facing = message.facing();
        this.readings = message.readings();
        this.inputs = message.inputs();
        this.power = message.power();
        this.verdict = message.verdict();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;

        LogicGate.Mode[] modes = LogicGate.Mode.values();
        this.modeButtons = new Button[modes.length];
        for (int index = 0; index < modes.length; index++) {
            int column = index % MODE_COLUMNS;
            int row = index / MODE_COLUMNS;
            int chosen = index;
            this.modeButtons[index] = addRenderableWidget(
                    Button.builder(label(modes[index]), button -> choose(chosen))
                            .bounds(this.left + 4 + column * (MODE_BUTTON_WIDTH + 2),
                                    this.top + 24 + row * (MODE_BUTTON_HEIGHT + 2),
                                    MODE_BUTTON_WIDTH, MODE_BUTTON_HEIGHT)
                            .build());
        }

        this.filling = true;
        try {
            this.xField = addRenderableWidget(new EditBox(this.font, this.left + 16, this.top + 72,
                    FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("redstonecircuit.logic.x")));
            this.yField = addRenderableWidget(new EditBox(this.font, this.left + 78, this.top + 72,
                    FIELD_WIDTH, FIELD_HEIGHT, Component.translatable("redstonecircuit.logic.y")));
            for (EditBox field : List.of(this.xField, this.yField)) {
                field.setMaxLength(2);
                field.setValue(text(field == this.xField ? this.x : this.y));
                field.setResponder(typed -> edited());
            }
        } finally {
            this.filling = false;
        }

        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.logic.close"),
                        button -> this.onClose())
                .bounds(this.left + WIDTH / 2 - 30, this.top + HEIGHT - 24, 60, FIELD_HEIGHT).build());
        refreshModeButtons();
    }

    /** A gate was picked: the screen's own field is the new answer, and the server hears about it at once. */
    private void choose(int ordinal) {
        this.mode = ordinal;
        refreshModeButtons();
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
            ClientPlayNetworking.send(RCNetwork.LogicEditPayload.probe(this.pos));
        }
    }

    /**
     * Sends what the player has set - or nothing at all, when a field holds something that is not a strength.
     *
     * <p>A field is only ever sent when it is either empty ({@link LogicGate#UNSET}) or a number 0-15; anything else is
     * marked red and left alone, because the block has no setting that "16" or "abc" could mean and silently turning it
     * into one would be the screen lying about what it saw.
     */
    private void send() {
        this.sendIn = 0;
        Integer typedX = typed(this.xField);
        Integer typedY = typed(this.yField);
        if (typedX == null || typedY == null) {
            colourFields();
            return;
        }
        ClientPlayNetworking.send(RCNetwork.LogicEditPayload.settings(this.pos,
                LogicGate.Mode.values()[this.mode], typedX, typedY));
    }

    /** The number a field holds, {@link LogicGate#UNSET} for an empty one, or {@code null} for neither. */
    private static Integer typed(EditBox field) {
        if (field == null) {
            return LogicGate.UNSET;
        }
        String text = field.getValue().trim();
        if (text.isEmpty()) {
            return LogicGate.UNSET;
        }
        try {
            int value = Integer.parseInt(text);
            return LogicGate.isStrength(value) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** The text a strength is shown as: nothing at all while it is unset - an empty field is meant to look blank. */
    private static String text(int value) {
        return value == LogicGate.UNSET ? "" : Integer.toString(value);
    }

    private void colourFields() {
        for (EditBox field : List.of(this.xField, this.yField)) {
            if (field != null) {
                field.setTextColor(typed(field) == null ? 0xFF5555 : 0xE0E0E0);
            }
        }
    }

    private void refreshModeButtons() {
        LogicGate.Mode[] modes = LogicGate.Mode.values();
        for (int index = 0; index < modes.length; index++) {
            this.modeButtons[index].setMessage(index == this.mode
                    ? Component.translatable("redstonecircuit.logic.mode.chosen", label(modes[index]))
                    : label(modes[index]));
        }
    }

    private static Component label(LogicGate.Mode mode) {
        return Component.translatable("redstonecircuit.logic.mode." + mode.key());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawString(this.font, this.title, this.left + 4, this.top + 8, 0xE0E0E0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.logic.x"),
                this.left + 4, this.top + 77, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.logic.y"),
                this.left + 66, this.top + 77, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.logic.blank"),
                this.left + 124, this.top + 77, 0x808080);
        graphics.drawString(this.font, inputsLine(), this.left + 4, this.top + 98, 0xA0A0A0);
        graphics.drawString(this.font, Component.translatable("redstonecircuit.logic.facing", face(this.facing)),
                this.left + 4, this.top + 112, 0xA0A0A0);
        graphics.drawString(this.font, fit(verdictLine().getString(), WIDTH - 8), this.left + 4, this.top + 126,
                this.verdict == LogicGate.Verdict.OK.ordinal() ? 0x55FF55 : 0xFF5555);
        graphics.drawString(this.font, fit(ruleLine(), WIDTH - 8), this.left + 4, this.top + 146, 0x909090);
    }

    /**
     * The selected gate's own sentence, so X and Y mean something while they are being typed: X is the value the
     * input is compared against in five of the seven gates and the <em>answer</em> in the two that do not read Y,
     * so two unlabelled numbers would make the player look up which gate they picked.
     */
    private String ruleLine() {
        return Component.translatable("redstonecircuit.logic.rule."
                + LogicGate.Mode.values()[this.mode].key()).getString();
    }

    /**
     * Which faces count as inputs, and what each of them is giving. The list is the server's own mask and not
     * "the readings above zero": a face with a wire on it counts while the wire is off, and that 0 is exactly
     * what the player has to be able to see there.
     */
    private Component inputsLine() {
        List<String> counting = new ArrayList<>();
        for (Direction side : Direction.values()) {
            if ((this.inputs & (1 << side.ordinal())) != 0) {
                counting.add(face(side.ordinal()) + " " + this.readings[side.ordinal()]);
            }
        }
        if (counting.isEmpty()) {
            return Component.translatable("redstonecircuit.logic.inputs.none");
        }
        return Component.translatable("redstonecircuit.logic.inputs", counting.size(),
                String.join("、", counting));
    }

    /** What the block is doing: the strength it answers, or the reason it answers nothing. */
    private Component verdictLine() {
        LogicGate.Mode gate = LogicGate.Mode.values()[this.mode];
        LogicGate.Verdict reason = LogicGate.Verdict.values()[this.verdict];
        return switch (reason) {
            case OK -> Component.translatable("redstonecircuit.logic.output", this.power);
            case WRONG_INPUT_COUNT -> Component.translatable("redstonecircuit.logic.notworking.count",
                    gate.neededInputs(), Integer.bitCount(this.inputs));
            case X_UNSET -> Component.translatable("redstonecircuit.logic.notworking.x");
            case Y_UNSET -> Component.translatable("redstonecircuit.logic.notworking.y");
        };
    }

    /** A face, in the words the rest of the mod uses for directions. */
    private static String face(int ordinal) {
        Direction side = Direction.values()[ordinal];
        return Component.translatable("message.redstonecircuit.wrench.face." + side.getName()).getString();
    }

    /** The same clamp every other line on this mod's screens uses: text may not run off its own panel. */
    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("..."))) + "...";
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
