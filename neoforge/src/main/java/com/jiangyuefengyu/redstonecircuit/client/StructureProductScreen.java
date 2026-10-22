package com.jiangyuefengyu.redstonecircuit.client;

import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PanelGrid;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * <b>The screen of a structure that has been put down.</b>
 *
 * <p>已放置的结构带着它<em>自己那份</em>面板副本 - 每个方块有自己的按键，而不是引用控制器的 - 而改这些按键的地方是
 * 捕获，因为副本是在那里写下的。所以这个界面负责另一半: <b>看</b>它的名字和按键，以及<b>按</b>它们；后者与快捷键发出的
 * 请求相同，因此遵守同样的距离和冷却规则。
 *
 * <p>按键排成编辑器里那张一样的 6×6 格: 一页 {@link PanelGrid#PER_PAGE} 个，一个按键就是一个按钮，名字就是玩家在
 * 编辑器里给它起的名字。没有按键的面板因此是一张空白的界面，而不是一行"没有东西"的提示。
 *
 * <p>只读是有意的: 能改按键的界面会让"面板跟着结构走"变成假的，按键属于捕获那一步。
 */
@OnlyIn(Dist.CLIENT)
public final class StructureProductScreen extends Screen {

    private static final int WIDTH = 340;
    private static final int HEIGHT = 240;

    /** The grid: one button per key, sized so a name fits and the rows clear the hint line below them. */
    private static final int GRID_X = 8;
    private static final int GRID_Y = 52;
    private static final int COLUMN_PITCH = 54;
    private static final int ROW_PITCH = 20;
    private static final int CELL_WIDTH = 50;
    private static final int CELL_HEIGHT = 18;

    /** Where the two page arrows sit, right of the count line. */
    private static final int ARROWS_Y = 28;

    private final BlockPos pos;
    private int left;
    private int top;
    private int page;

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
        List<ActivationTable.ManualEntry> entries = entries();
        int count = entries.size();
        int page = page(count);
        // A structure in face mode has no keys, so it gets no key buttons: the server refuses such a press anyway, and
        // the rule is the same on the screen as it is in the block.
        if (manualKeys()) {
            if (PanelGrid.pages(count) > 1) {
                addRenderableWidget(Button.builder(Component.literal("<"), button -> turn(-1))
                        .bounds(this.left + 292, this.top + ARROWS_Y, 18, 16).build());
                addRenderableWidget(Button.builder(Component.literal(">"), button -> turn(1))
                        .bounds(this.left + 314, this.top + ARROWS_Y, 18, 16).build());
            }
            int first = PanelGrid.firstIndex(page);
            int shown = PanelGrid.count(count, page);
            for (int cell = 0; cell < shown; cell++) {
                int index = first + cell;
                ActivationTable.ManualEntry entry = entries.get(index);
                Button button = Button.builder(Component.literal(fit(displayName(entry), CELL_WIDTH - 6)),
                                pressed -> pressEntry(index))
                        .bounds(this.left + GRID_X + (cell % PanelGrid.COLUMNS) * COLUMN_PITCH,
                                this.top + GRID_Y + (cell / PanelGrid.COLUMNS) * ROW_PITCH,
                                CELL_WIDTH, CELL_HEIGHT).build();
                // A key is a name and nothing else on the face of it; the cell it pushes into is what pressing it has
                // to be checked against, so it rides on the tooltip.
                button.setTooltip(Tooltip.create(Component.literal((index + 1) + ". " + displayName(entry) + " -> "
                        + entry.localPos().toShortString() + "@" + entry.strength())));
                addRenderableWidget(button);
            }
        }
        addRenderableWidget(Button.builder(Component.translatable("redstonecircuit.structure.screen.close"),
                        button -> this.onClose())
                .bounds(this.left + 122, this.top + HEIGHT - 24, 96, 18).build());
    }

    /**
     * Whether this structure is driven by its own keys - the one question that decides what this screen shows.
     *
     * <p>An <b>empty</b> panel is still a key panel: it draws no buttons, which is what an empty grid is, and it must
     * not be explained as a face-driven structure it is not.
     */
    private boolean manualKeys() {
        StructureBlockEntity found = product();
        return found != null && found.ioMode() == IoMode.MANUAL;
    }

    /** The page showing, clamped to the pages there are: a panel that shrank under the player keeps a valid page. */
    private int page(int count) {
        return Math.min(this.page, PanelGrid.pages(count) - 1);
    }

    private void turn(int delta) {
        this.page = Math.max(0, Math.min(page(entries().size()) + delta, PanelGrid.pages(entries().size()) - 1));
        this.rebuildWidgets();
    }

    /** The same request the key binding sends, so the rules a press meets are the same ones. */
    private void pressEntry(int index) {
        StructureBlockEntity found = product();
        if (found == null || index >= found.manual().size()) {
            return;
        }
        ActivationTable.ManualEntry entry = found.manual().get(index);
        PacketDistributor.sendToServer(new RCNetwork.ManualTriggerPayload(this.pos, index));
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
        List<ActivationTable.ManualEntry> entries = entries();
        int count = entries.size();
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.keys",
                        count, RCConfig.structureManualEntryLimit(), page(count) + 1, PanelGrid.pages(count)),
                this.left + 4, this.top + 28, 0xA0A0A0);
        if (found != null && !manualKeys()) {
            // Face mode: say so, in place of the keys the structure does not have.
            graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.faces"),
                    this.left + 4, this.top + 52, 0xA0A0A0);
            graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.nokeys"),
                    this.left + 4, this.top + 66, 0x808080);
        }
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.product.hint"),
                this.left + 4, this.top + HEIGHT - 40, 0x808080);
    }

    /** The name the player gave this button, with the stored key as the fallback for rows captured by an older build. */
    private static String displayName(ActivationTable.ManualEntry entry) {
        if (!entry.label().isEmpty()) {
            return entry.label();
        }
        return entry.key();
    }

    /** The same clamp every other line on these screens uses: text may not run out of its own button. */
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
