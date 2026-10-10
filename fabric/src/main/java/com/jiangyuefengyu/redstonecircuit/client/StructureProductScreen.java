package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * <b>The screen of a structure that has been put down.</b>
 *
 * <p>已放置的结构带着它<em>自己那份</em>面板副本 - 每个方块有自己的按键，而不是引用控制器的 - 而改这些按键的地方是
 * 捕获，因为副本是在那里写下的。所以这个界面负责另一半: <b>看</b>它的名字和按键，以及<b>按</b>它们；后者与快捷键发出的
 * 请求相同，因此遵守同样的距离和冷却规则。
 *
 * <p>只读是有意的: 能改按键的界面会让"面板跟着结构走"变成假的，按键属于捕获那一步。
 */
@Environment(EnvType.CLIENT)
public final class StructureProductScreen extends Screen {

    private static final int WIDTH = 340;
    private static final int HEIGHT = 240;
    private static final int ROWS = 6;

    private final BlockPos pos;
    private int left;
    private int top;
    private int scroll;
    private Button scrollBack;
    private Button scrollForward;

    public StructureProductScreen(BlockPos pos) {
        super(Component.translatable("redstonecircuit.structure.product.title"));
        this.pos = pos;
    }

    private StructureBlockEntity product() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        return minecraft.level.getBlockEntity(this.pos) instanceof StructureBlockEntity found ? found : null;
    }

    private List<ActivationTable.ManualEntry> entries() {
        StructureBlockEntity found = product();
        return found == null ? List.of() : found.manual();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;
        StructureBlockEntity found = product();
        // A structure in face mode has no keys, so it gets no key rows: the server refuses such a press anyway, and
        // the rule is the same on the screen as it is in the block.
        if (manualKeys()) {
            this.scrollBack = addRenderableWidget(Button.builder(Component.literal("<"), button -> scrollBy(-ROWS))
                    .bounds(this.left + 4, this.top + 24, 18, 16).build());
            this.scrollForward = addRenderableWidget(Button.builder(Component.literal(">"), button -> scrollBy(ROWS))
                    .bounds(this.left + 24, this.top + 24, 18, 16).build());
            for (int row = 0; row < ROWS; row++) {
                int y = this.top + 52 + row * 22;
                int index = this.scroll + row;
                Button press = addRenderableWidget(Button.builder(
                                Component.translatable("redstonecircuit.structure.screen.panel.press"),
                                button -> pressEntry(index))
                        .bounds(this.left + 280, y, 52, 18).build());
                press.active = index < entries().size();
            }
            refreshScrollButtons();
        }
        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.structure.screen.close"),
                        button -> this.onClose())
                .bounds(this.left + 122, this.top + HEIGHT - 24, 96, 18).build());
    }

    /** Whether this structure is driven by its own keys - the one question that decides what this screen shows. */
    private boolean manualKeys() {
        StructureBlockEntity found = product();
        return found != null && found.ioMode() == IoMode.MANUAL && !found.manual().isEmpty();
    }



    private void refreshScrollButtons() {
        boolean room = entries().size() > ROWS;
        this.scrollBack.visible = room;
        this.scrollForward.visible = room;
    }

    private void scrollBy(int delta) {
        int max = Math.max(0, entries().size() - ROWS);
        this.scroll = Math.min(Math.max(0, this.scroll + delta), max);
        this.rebuildWidgets();
    }

    /** The same request the key binding sends, so the rules a press meets are the same ones. */
    private void pressEntry(int index) {
        StructureBlockEntity found = product();
        if (found == null || index >= found.manual().size()) {
            return;
        }
        ActivationTable.ManualEntry entry = found.manual().get(index);
        ClientPlayNetworking.send(new RCNetwork.ManualTriggerPayload(this.pos, index));
        if (RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] product screen pressed entry {} ({}) on the structure at {}",
                    index, entry.key(), this.pos.toShortString());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        StructureBlockEntity found = product();
        int nameColour = 0xA0A0A0;
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.screen.name"),
                this.left + 4, this.top + 8, nameColour);
        String name = found == null ? "" : found.structureName();
        graphics.drawString(this.font, name, this.left + 46, this.top + 8, 0xE0E0E0);
        if (found != null) {
            graphics.drawString(this.font, found.size().getX() + "x" + found.size().getY() + "x"
                            + found.size().getZ(), this.left + 200, this.top + 8, 0x808080);
        }
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.keys",
                        entries().size(), RCConfig.structureManualEntryLimit()),
                this.left + 4, this.top + 28, 0xA0A0A0);
        List<ActivationTable.ManualEntry> entries = entries();
        if (!manualKeys()) {
            // Face mode: say so, in place of the key rows the structure does not have.
            graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.faces"),
                    this.left + 4, this.top + 52, 0xA0A0A0);            graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.nokeys"),
                    this.left + 4, this.top + 66, 0x808080);
        }
        for (int row = 0; row < ROWS; row++) {
            int index = this.scroll + row;
            if (index >= entries.size()) {
                break;
            }
            int y = this.top + 52 + row * 22;
            // The name is the whole row: what this screen is for is pressing a named button - the cell, the strength
            // and the selection-local index are the capture's business and are visible where they are edited.
            String label = (index + 1) + ". " + displayName(entries.get(index));
            graphics.drawString(this.font, fit(label, 268), this.left + 4, y + 5, 0xE0E0E0);
        }
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.hint"),
                this.left + 4, this.top + HEIGHT - 40, 0x808080);
    }

    /** The name the player gave this button, with the free-text note as a fallback for rows without one. */
    private static String displayName(ActivationTable.ManualEntry entry) {
        if (!entry.label().isEmpty()) {
            return entry.label();
        }
        return entry.key();
    }

    /** The same clamp every other line on these screens uses: text may not run under its own row's buttons. */
    private String fit(String text, int maxWidth) {
        if (this.font.width(text) <= maxWidth) {
            return text;
        }
        return this.font.plainSubstrByWidth(text, Math.max(0, maxWidth - this.font.width("..."))) + "...";
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
