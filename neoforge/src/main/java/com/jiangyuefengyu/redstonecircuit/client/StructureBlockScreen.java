package com.jiangyuefengyu.redstonecircuit.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.network.RCNetwork;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.PanelGrid;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.RegionEditor;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;



/**
 * 结构方块屏幕: the region, mode B's key panel and mode A's six face tables, as two pages.
 *
 * <p>Every button here sends one {@code StructureEditPayload} and nothing else: this screen has <b>no rules</b>.
 * Whether a cell is inside the region, whether a strength is legal, whether a release may overwrite what is there - all
 * of that is decided on the server ({@code StructureEdits}), by the same functions the command uses.
 *
 * <p>The screen reads the block entity the client already has, so it needs no state of its own beyond the text
 * currently in the boxes. The relative/absolute switch is <b>client-side only</b>: it chooses how the six boxes are
 * read, and the rectangle they describe is what is sent (see {@code RegionEditor}).
 */
@OnlyIn(Dist.CLIENT)
public final class StructureBlockScreen extends Screen {

    private static final int WIDTH = 340;
    private static final int HEIGHT = 240;

    /** How many panel rows fit on the panel page: four, so the row captions and fields below them have room. */
    private static final int ROWS = 4;

    private final BlockPos controller;

    /**
     * This save's structure directory and export directory, as the server named them when it opened this screen.
     *
     * <p>They are the server's own paths, shown so a player can copy a file between saves by hand. Nothing else about
     * the server's filesystem travels.
     */
    private final String structuresPath;
    private final String exportPath;

    /** Which page is showing: the region, mode B's key panel, mode A's six face tables, or the file hint. */
    private int page;

    private static final int PAGE_REGION = 0;
    private static final int PAGE_PANEL = 1;
    private static final int PAGE_FACES = 2;
    private static final int PAGE_FILES = 3;
    /**
     * The output table's page - the third table, and the only one that is <b>never locked</b>.
     *
     * <p>Appended after the files page so the four numbers the screen has always used keep their meaning: a page
     * remembered from an older session is still the page it was.
     */
    private static final int PAGE_OUTPUT = 4;

    /** Which face a new table entry goes on, cycled by the button on the face page. */
    private Direction face = Direction.EAST;

    /**
     * True while the panel's key box is waiting for a key to be pressed, the arrangement the game's own controls screen
     * uses: a button that arms itself, the next key (or mouse button) binding, Escape cancelling. What it produces is
     * the <b>key mapping name</b> ({@code key.keyboard.g}) rather than a keycode, because a structure is shared with
     * players whose keyboards are not this one.
     */

    /**
     * The input mode the player just asked for, until the block entity agrees - see {@link #manualMode}.
     *
     * <p>Null is the ordinary state: read the block. It is cleared in {@link #refreshLabels} the moment the block's own
     * value matches, so nothing is remembered longer than the round trip it exists for.
     */
    @Nullable
    private Boolean pendingManual;

    /** The mode the player just asked for, until the block agrees - see {@link #isRelease}. */
    @Nullable
    private Boolean pendingRelease;

    /** The mode this page's widgets were built for, so {@link #tick} knows when to rebuild. */
    private boolean builtRelease;

    /** How many rows the page on screen had when it was built - see {@link #tick}. */
    private int builtRows;

    /** The fingerprint of those rows, recorded with {@link #builtRows} - see {@link #tick}. */
    private long builtSignature;

    /** Whether the player edited the region's own fields on this screen - see {@link #sendWhatChanged}. */
    private boolean editedRegion;

    /** Whether the player edited the name box on this screen - see {@link #sendWhatChanged}. */
    private boolean editedName;

    /** Whether this screen has already put the remembered page, reading and boxes back (see {@link #init}). */
    private boolean restored;
    private boolean boxesRestored;

    /** Whether the six boxes are read from the controller (relative) or as world coordinates. */
    private boolean relative = true;

    /** The first row on screen, so a panel of 32 entries can be walked with two buttons. */
    private int scroll;

    private int left;
    private int top;

    private Button pageRegion;
    private Button pagePanel;
    private Button pageFaces;
    private Button pageFiles;
    private Button pageOutput;
    private Button modeButton;
    private Button tierButton;
    private Button ioButton;
    private Button faceButton;
    private Button relativeButton;
    private Button operateButton;
    private Button cancelButton;
    private Button endPreviewButton;
    /**
     * The "add" button of whichever table page is showing. A cell is named <b>inside the region</b> the border
     * draws - an entry stores a local coordinate, so the region is its frame of reference - which is why this
     * button is disabled while no region is up ({@link #refreshLabels()}) and each page says so in place of its
     * hint.
     */
    private Button addButton;
    /** The other direction of the structure-item door: see {@link #buildRegionPage}. */
    private Button takeOutButton;
    /** The apply row's own buttons, relabelled when the mode changes under them. */
    private Button applyButton;
    private Button hereButton;

    private EditBox nameBox;
    private EditBox offsetX;
    private EditBox offsetY;
    private EditBox offsetZ;
    private EditBox sizeX;
    private EditBox sizeY;
    private EditBox sizeZ;

    /** The one name box the key panel has: a button's name <b>is</b> what identifies it, so there is nothing else. */
    private EditBox buttonName;
    private EditBox cellX;
    private EditBox cellY;
    private EditBox cellZ;
    private EditBox strengthBox;

    private final List<Button> removeButtons = new ArrayList<>();

    /**
     * Which page of the 6×6 key grid is showing, and which entry the row below it is editing.
     *
     * <p>The selection is an index into the panel - the same number a press travels as - and -1 means "nothing
     * selected": the boxes then describe an entry that does not exist yet, and the button under the grid adds it.
     */
    private int panelPage;
    private int selected = -1;

    /** The button that drops the selected entry. Live only while something is selected. */
    private Button removeButton;

    public StructureBlockScreen(BlockPos controller, String structuresPath, String exportPath) {
        super(Component.translatable("redstonecircuit.structure.screen.title", nameOf(controller)));
        this.controller = controller;
        this.structuresPath = structuresPath;
        this.exportPath = exportPath;
    }

    // ------------------------------------------------------------------ setup --

    private static RedstoneStructureBlockEntity controller(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null
                && minecraft.level.getBlockEntity(pos) instanceof RedstoneStructureBlockEntity found
                        ? found
                        : null;
    }

    private static String nameOf(BlockPos pos) {
        RedstoneStructureBlockEntity found = controller(pos);
        return found == null ? "?" : found.structureName();
    }

    @Override
    protected void init() {
        this.left = (this.width - WIDTH) / 2;
        this.top = (this.height - HEIGHT) / 2;
        this.removeButtons.clear();
        // The remembered page is put back only on the first build: showPage sets the page and then rebuildWidgets runs
        // init, so restoring it every time would undo the page the player just asked for.
        StructureScreenMemory.Fields remembered = StructureScreenMemory.get(this.controller);
        if (!this.restored && remembered != null) {
            this.restored = true;
            this.page = remembered.page();
            this.scroll = remembered.scroll();
            this.relative = remembered.relative();
            this.face = Direction.values()[Math.floorMod(remembered.faceOrdinal(), Direction.values().length)];
        }

        // The header: five page tabs and the three switches, sized so that all eight fit one row. The numbers are
        // measured against the widest label in each group (区域/按键/面表/输出/文件 and 模式：捕获/档位：复制/输入：面表):
        // 26px holds two CJK glyphs (18px) plus the button's own border, and 62px holds five. 4 + 4*28 = 116 is the last
        // tab and the switches start at 146, so nothing overlaps and the row still ends inside the panel.
        int tabWidth = 26;
        int tabPitch = 28;
        int switchWidth = 62;
        int switchPitch = 64;
        int tabY = this.top + ly("header.row");
        this.pageRegion = addRenderableWidget(Button.builder(tab("page.region"), button -> showPage(PAGE_REGION))
                .bounds(this.left + 4, tabY, tabWidth, 18).build());
        // The two table tabs are disabled together below, since one of them is always the locked one.
        this.pagePanel = addRenderableWidget(Button.builder(tab("page.panel"), button -> showPage(PAGE_PANEL))
                .bounds(this.left + 4 + tabPitch, tabY, tabWidth, 18).build());
        this.pageFaces = addRenderableWidget(Button.builder(tab("page.faces"), button -> showPage(PAGE_FACES))
                .bounds(this.left + 4 + 2 * tabPitch, tabY, tabWidth, 18).build());
        // The output table sits beside the face table, because they are one table read from its two ends.
        this.pageOutput = addRenderableWidget(Button.builder(tab("page.output"), button -> showPage(PAGE_OUTPUT))
                .bounds(this.left + 4 + 3 * tabPitch, tabY, tabWidth, 18).build());
        this.pageFiles = addRenderableWidget(Button.builder(tab("page.files"), button -> showPage(PAGE_FILES))
                .bounds(this.left + 4 + 4 * tabPitch, tabY, tabWidth, 18).build());
        this.modeButton = addRenderableWidget(Button.builder(modeLabel(), button -> {
            // Rebuilt against what the player just asked for, not against the block's answer, which is still in flight.
            boolean toRelease = !isRelease();
            this.pendingRelease = toRelease;
            send(RCNetwork.StructureEditPayload.flag(this.controller,
                    RCNetwork.StructureEditPayload.Verb.SET_MODE, toRelease));
            this.builtRelease = toRelease;
            this.rebuildWidgets();
        }).bounds(this.left + 146, tabY, switchWidth, 18).build());
        this.tierButton = addRenderableWidget(Button.builder(tierLabel(),
                        button -> send(RCNetwork.StructureEditPayload.flag(this.controller,
                                RCNetwork.StructureEditPayload.Verb.SET_TIER, !isCopy())))
                .bounds(this.left + 146 + switchPitch, tabY, switchWidth, 18).build());
        this.ioButton = addRenderableWidget(Button.builder(ioLabel(), button -> {
            // the input mode decides which of the two tables is live, so switching it takes the player to the page that
            // belongs to the new mode - and the new mode is remembered locally until the block agrees, so the page the
            // player lands on is never the locked one.
            boolean toManual = !manualMode();
            this.pendingManual = toManual;
            send(RCNetwork.StructureEditPayload.flag(this.controller,
                    RCNetwork.StructureEditPayload.Verb.SET_IO, toManual));
            showPage(toManual ? PAGE_PANEL : PAGE_FACES);
        }).bounds(this.left + 146 + 2 * switchPitch, tabY, switchWidth, 18).build());
        // The tab of the table the current input mode does not use is locked: it is not "another view", it is a table
        // the block will never read while the other one is selected. The two tables are exclusive, and the button on the
        // locked page goes to the table that <em>is</em> live.
        this.pagePanel.active = !locked(PAGE_PANEL);
        this.pageFaces.active = !locked(PAGE_FACES);

        // Each build below assigns the one add button it has, so a page with none leaves the field empty rather
        // than pointing at a widget that is no longer on screen.
        this.addButton = null;
        switch (this.page) {
            case PAGE_PANEL -> buildPanelPage();
            case PAGE_FACES -> buildFacePage();
            case PAGE_OUTPUT -> buildOutputPage();
            case PAGE_FILES -> buildFilesPage();
            default -> buildRegionPage();
        }
        // The locked page's one button takes the player to the table the block is actually reading: a page with no
        // widgets at all looks broken, and one button is the difference.
        if (locked(this.page)) {
            int other = this.page == PAGE_PANEL ? PAGE_FACES : PAGE_PANEL;
            addRenderableWidget(Button.builder(text("locked.goto"), button -> showPage(other))
                    .bounds(this.left + lx("region.locked.button"), this.top + ly("region.locked.button"),
                            lw("region.locked.button"), lh("region.locked.button")).build());
        }
        // Only on the first build: after that the live boxes are the truth, and re-applying a stale memory would undo
        // what the player has typed since.
        if (!this.boxesRestored) {
            this.boxesRestored = true;
            applyRemembered(remembered);
        }
        // Restoring the boxes is not the player editing them: nothing is sent on close until something is typed or
        // the region or the name is changed by hand.
        this.editedRegion = false;
        this.editedName = false;
        // One button for every page: what the next operation does is the same question wherever it is asked.
        this.operateButton = addRenderableWidget(Button.builder(operateLabel(), button -> send(
                        RCNetwork.StructureEditPayload.of(this.controller,
                                RCNetwork.StructureEditPayload.Verb.PRESS)))
                .bounds(this.left + lx("region.operate.button"), this.top + ly("region.operate.button"),
                        lw("region.operate.button"), lh("region.operate.button")).build());
        // This button closes the screen's preview, which is what a button in that place reads as.
        this.endPreviewButton = addRenderableWidget(Button.builder(text("cancel"), button -> send(
                        RCNetwork.StructureEditPayload.of(this.controller,
                                RCNetwork.StructureEditPayload.Verb.CANCEL)))
                .bounds(this.left + lx("region.cancel.button"), this.top + ly("region.cancel.button"),
                        lw("region.cancel.button"), lh("region.cancel.button")).build());
        this.cancelButton = addRenderableWidget(Button.builder(text("close"), button -> this.onClose())
                .bounds(this.left + lx("region.close.button"), this.top + ly("region.close.button"),
                        lw("region.close.button"), lh("region.close.button")).build());
        this.endPreviewButton.visible = border() != null;
        // What this page was built from, so a tick can tell whether the block has changed it since (see #tick). Recorded
        // here and nowhere else: every rebuild runs init, so these two are exactly "the data this page is showing".
        this.builtRows = pageRows();
        this.builtSignature = pageSignature();
    }

    /**
     * Puts back everything the player typed, box by box, and only the boxes that exist on this page.
     *
     * <p>The cell row is left out: it is <em>derived</em> from the region, and {@link #fillCellFields} fills it with the
     * region's own first cell. A remembered input is a convenience; a remembered <em>derived</em> value is a bug that
     * waits to happen - a cell remembered from an earlier session would be added to the controller's position and sent.
     */
    private void applyRemembered(@Nullable StructureScreenMemory.Fields fields) {
        if (fields == null) {
            return;
        }
        set(this.nameBox, fields.name());
        set(this.offsetX, fields.offsetX());
        set(this.offsetY, fields.offsetY());
        set(this.offsetZ, fields.offsetZ());
        set(this.sizeX, fields.sizeX());
        set(this.sizeY, fields.sizeY());
        set(this.sizeZ, fields.sizeZ());
        set(this.buttonName, fields.key());
        set(this.strengthBox, fields.strength());
    }

    private static void set(@Nullable EditBox box, @Nullable String value) {
        if (box != null && value != null) {
            box.setValue(value);
        }
    }

    /** A text box's current text, or null when the box is not on this page - see {@link #rememberBoxes}. */
    @Nullable
    private static String live(@Nullable EditBox box) {
        return box == null ? null : box.getValue();
    }

    /** What the screen is holding right now, merged with what a page that is not showing had. */
    private StructureScreenMemory.Fields remembered(@Nullable StructureScreenMemory.Fields previous) {
        return new StructureScreenMemory.Fields(
                keep(live(this.nameBox), previous == null ? null : previous.name()),
                keep(live(this.offsetX), previous == null ? null : previous.offsetX()),
                keep(live(this.offsetY), previous == null ? null : previous.offsetY()),
                keep(live(this.offsetZ), previous == null ? null : previous.offsetZ()),
                keep(live(this.sizeX), previous == null ? null : previous.sizeX()),
                keep(live(this.sizeY), previous == null ? null : previous.sizeY()),
                keep(live(this.sizeZ), previous == null ? null : previous.sizeZ()),
                keep(live(this.buttonName), previous == null ? null : previous.key()),
                keep(live(this.cellX), previous == null ? null : previous.cellX()),
                keep(live(this.cellY), previous == null ? null : previous.cellY()),
                keep(live(this.cellZ), previous == null ? null : previous.cellZ()),
                keep(live(this.strengthBox), previous == null ? null : previous.strength()),
                this.relative, this.face.ordinal(), this.page, this.scroll);
    }

    /** The live value when there is one, otherwise what was remembered, otherwise an empty string. */
    private static String keep(@Nullable String live, @Nullable String remembered) {
        if (live != null) {
            return live;
        }
        return remembered == null ? "" : remembered;
    }

    private void showPage(int page) {
        // The boxes of the page being left are written to memory first, so a page switch is not a way to lose what was
        // typed in them.
        rememberBoxes();
        this.page = page;
        this.scroll = 0;
        // The selection belongs to the grid it was made on: leaving the page is not a way to keep editing an entry
        // that is no longer on screen.
        this.selected = -1;
        this.panelPage = 0;
        this.rebuildWidgets();
    }

    /**
     * Writes what the boxes hold into the screen's memory, <b>merging</b> rather than replacing.
     *
     * <p>A page only has its own boxes (the region fields exist on the region page, the row fields on the two table
     * pages), and a value of {@code null} means "not on this page" - not "empty". Writing the merged record is what
     * lets a value typed on one page survive a visit to another.
     */
    private void rememberBoxes() {
        StructureScreenMemory.Fields previous = StructureScreenMemory.get(this.controller);
        StructureScreenMemory.put(this.controller, remembered(previous));
    }

    /**
     * Whether this page's table is the one the current input mode uses.
     *
     * <p>{@code ioMode} decides which, so the other page is not a second view of the same thing, it is the table this
     * block will never read. Editing it would look like it worked and change nothing, so it is locked and the tab that
     * leads to it is disabled.
     */
    private boolean locked(int page) {
        if (page == PAGE_PANEL) {
            return !manualMode();
        }
        if (page == PAGE_FACES) {
            return manualMode();
        }
        return false;
    }

    /**
     * The input mode, <b>including a switch the server has not answered yet</b>.
     *
     * <p>The mode comes from the block entity, and a switch takes a round trip, so for the tick between the click and
     * the answer the page the player had just been sent to was still the locked one - and a locked page builds no
     * widgets, so it looked like an unknown, empty screen. The override is what the player just asked for, honoured
     * until the block agrees.
     */
    private boolean manualMode() {
        return this.pendingManual != null ? this.pendingManual : isManual();
    }

    /** Shorthand for the layout table - every position on this screen comes from {@link StructureLayout}. */
    private static int lx(String id) {
        return StructureLayout.x(id);
    }

    private static int ly(String id) {
        return StructureLayout.y(id);
    }

    private static int lw(String id) {
        return StructureLayout.w(id);
    }

    private static int lh(String id) {
        return StructureLayout.h(id);
    }

    private void buildRegionPage() {
        // Every position on this page comes from the layout table (`/rc structure uiedit` drags it), so a caption that
        // sits too close to its field is a number to move, not a bug.
        this.nameBox = nameBox(this.left + lx("region.name.field"), this.top + ly("region.name.field"),
                lw("region.name.field"), nameOf(this.controller),
                com.jiangyuefengyu.redstonecircuit.structure.PayloadHeader.MAX_NAME_LENGTH);
        addRenderableWidget(Button.builder(text("rename"), button -> {
            send(RCNetwork.StructureEditPayload.text(this.controller,
                    RCNetwork.StructureEditPayload.Verb.SET_NAME, this.nameBox.getValue()));
            this.editedName = false;
        }).bounds(this.left + lx("region.rename.button"), this.top + ly("region.rename.button"),
                lw("region.rename.button"), lh("region.rename.button")).build());

        this.relativeButton = addRenderableWidget(Button.builder(relativeLabel(), button -> {
            // Flipping the reading refills the boxes, so the rectangle on screen does not move when the meaning of the
            // numbers changes - a toggle that silently moved the region would be a trap.
            this.relative = !this.relative;
            // The row's boxes mean something different in the other reading, so they are refilled rather than
            // converted: a number that was an offset is not a coordinate, and keeping it would name a different cell.
            this.cellEdited = false;
            fillCellFields();
            fillRegionFields();
        }).bounds(this.left + lx("region.reading.button"), this.top + ly("region.reading.button"),
                lw("region.reading.button"), lh("region.reading.button")).build());

        this.offsetX = regionBox(this.left + lx("region.offsetX.field"), this.top + ly("region.offsetX.field"),
                lw("region.offsetX.field"), "0", 12);
        this.offsetY = regionBox(this.left + lx("region.offsetY.field"), this.top + ly("region.offsetY.field"),
                lw("region.offsetY.field"), "0", 12);
        this.offsetZ = regionBox(this.left + lx("region.offsetZ.field"), this.top + ly("region.offsetZ.field"),
                lw("region.offsetZ.field"), "0", 12);
        // The size row exists in capture mode only: the server's release is handed a target position and nothing else,
        // so asking for a number the block ignores is worse than not asking.
        if (!isRelease()) {
            this.sizeX = regionBox(this.left + lx("region.sizeX.field"), this.top + ly("region.sizeX.field"),
                    lw("region.sizeX.field"), "1", 4);
            this.sizeY = regionBox(this.left + lx("region.sizeY.field"), this.top + ly("region.sizeY.field"),
                    lw("region.sizeY.field"), "1", 4);
            this.sizeZ = regionBox(this.left + lx("region.sizeZ.field"), this.top + ly("region.sizeZ.field"),
                    lw("region.sizeZ.field"), "1", 4);
        }
        fillRegionFields();

        // The apply row is the one place the two modes differ in <em>widgets</em>, and they differ by name only: a
        // capture applies the region it describes, a release asks where the structure would be put down.
        this.applyButton = addRenderableWidget(Button.builder(applyLabel(), button -> applyRegion())
                .bounds(this.left + lx("region.apply.button"), this.top + ly("region.apply.button"),
                        lw("region.apply.button"), lh("region.apply.button")).build());
        this.hereButton = addRenderableWidget(Button.builder(text(isRelease() ? "here.release" : "here"),
                        button -> fromMe())
                .bounds(this.left + lx("region.here.button"), this.top + ly("region.here.button"),
                        lw("region.here.button"), lh("region.here.button")).build());
        this.takeOutButton = addRenderableWidget(Button.builder(text("takeout"), button -> send(
                        RCNetwork.StructureEditPayload.of(this.controller,
                                RCNetwork.StructureEditPayload.Verb.TAKE_OUT)))
                .bounds(this.left + lx("region.takeout.button"), this.top + ly("region.takeout.button"),
                        lw("region.takeout.button"), lh("region.takeout.button")).build());
        this.takeOutButton.visible = false;
    }

    /** What the region button says: the same edit, named for what the mode does with the rectangle. */
    private Component applyLabel() {
        return text(isRelease() ? "apply.release" : "apply");
    }

    /** The operate button's label, which is the anti-mistouch rule in words, in the mode's own vocabulary. */
    private Component operateLabel() {
        StructurePreviews.Entry border = border();
        String mode = isRelease() ? ".release" : ".capture";
        if (border == null) {
            return text("operate.preview" + mode);
        }
        return text((border.armed() ? "operate.apply" : "operate.confirm") + mode);
    }

    /**
     * The file page: where this save's structures live, and where an export goes.
     *
     * <p>A hint and not a file manager: a player copies a file between saves by hand, and what the screen owes them is
     * the path.
     */
    private void buildFilesPage() {
        // Nothing to press: the two paths and the command that writes/reads them.
    }

    /**
     * <b>The key panel</b>: a 6×6 grid of named buttons, one per entry, and the row that edits whichever of them is
     * selected.
     *
     * <p>The panel is drawn as the grid it <em>is</em> rather than as a list of rows, because a structure's keys are a
     * row of buttons on the placed block: what the player arranges here is what they will be pressing later. A page
     * holds {@link PanelGrid#PER_PAGE} buttons, and a panel with four entries draws four of them - never a page of
     * empty rows standing in for the ones it does not have.
     */
    private void buildPanelPage() {
        if (locked(PAGE_PANEL)) {
            // Locked: no widgets at all, and the page says why. See {@link #locked}.
            return;
        }
        addRenderableWidget(Button.builder(Component.literal("<"), button -> turnPanel(-1))
                .bounds(this.left + lx("panel.scroll.back"), this.top + ly("panel.scroll.back"),
                        lw("panel.scroll.back"), lh("panel.scroll.back")).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> turnPanel(1))
                .bounds(this.left + lx("panel.scroll.forward"), this.top + ly("panel.scroll.forward"),
                        lw("panel.scroll.forward"), lh("panel.scroll.forward")).build());

        // Only the entries this page holds get a button: an empty panel is an empty grid, and a lattice of greyed
        // cells would be thirty-six buttons saying nothing.
        List<ActivationTable.ManualEntry> manual = panelEntries();
        int page = page();
        int first = PanelGrid.firstIndex(page);
        int count = PanelGrid.count(manual.size(), page);
        int cellWidth = lw("panel.grid") - StructureLayout.GRID_GAP_X;
        int cellHeight = lh("panel.grid") - StructureLayout.GRID_GAP_Y;
        for (int cell = 0; cell < count; cell++) {
            int index = first + cell;
            ActivationTable.ManualEntry entry = manual.get(index);
            Button button = Button.builder(Component.literal(fit(markSelection(index) + displayName(entry),
                            cellWidth - 6)), pressed -> select(index))
                    .bounds(this.left + lx("panel.grid") + (cell % PanelGrid.COLUMNS) * lw("panel.grid"),
                            this.top + ly("panel.grid") + (cell / PanelGrid.COLUMNS) * lh("panel.grid"),
                            cellWidth, cellHeight).build();
            // A button is only a name, so what the old row printed - the cell and the strength - rides on the tooltip:
            // hiding it behind a click would make the grid less informative than the list it replaced.
            button.setTooltip(Tooltip.create(Component.literal(rowText(index, entry))));
            addRenderableWidget(button);
        }

        // One name box: a button's name is what the structure shows and what a file names it by, so the panel stores
        // the name itself rather than a key mapping the player would have to think up.
        this.buttonName = rowBox(this.left + lx("panel.key.field"), this.top + ly("panel.key.field"),
                lw("panel.key.field"), "", ActivationTable.MAX_KEY_LENGTH);
        this.cellX = cellBox(this.left + lx("panel.cellX.field"), this.top + ly("panel.cellX.field"),
                lw("panel.cellX.field"));
        this.cellY = cellBox(this.left + lx("panel.cellY.field"), this.top + ly("panel.cellY.field"),
                lw("panel.cellY.field"));
        this.cellZ = cellBox(this.left + lx("panel.cellZ.field"), this.top + ly("panel.cellZ.field"),
                lw("panel.cellZ.field"));
        this.strengthBox = rowBox(this.left + lx("panel.strength.field"), this.top + ly("panel.strength.field"),
                lw("panel.strength.field"), "15", 2);
        fillFromSelection();
        this.addButton = addRenderableWidget(Button.builder(commitLabel(), button -> commitEntry())
                .bounds(this.left + lx("panel.add.button"), this.top + ly("panel.add.button"),
                        lw("panel.add.button"), lh("panel.add.button")).build());
        this.removeButton = addRenderableWidget(Button.builder(text("panel.remove"), button -> removeSelected())
                .bounds(this.left + lx("panel.remove.button"), this.top + ly("panel.remove.button"),
                        lw("panel.remove.button"), lh("panel.remove.button")).build());
        this.removeButton.active = selectedEntry() != null;
    }

    /**
     * One of the three cell boxes: a text field whose typing marks the row as edited, so a page rebuild - a switch, a
     * mode change, a close - no longer overwrites what was just entered (see {@link #fillCellFields}).
     */
    private EditBox cellBox(int x, int y, int width) {
        EditBox edit = box(x, y, width, "0", 12);
        edit.setResponder(text -> this.cellEdited = true);
        return edit;
    }

    /**
     * A box that is part of the row being edited rather than of the page: the name and the strength.
     *
     * <p>The three cell boxes are marked by {@link #cellBox}; these two are the other half of the same row, and a page
     * that refreshes itself has to leave them alone for the same reason - {@link #fillFromSelection} refills a selected
     * row's name, cell and strength together.
     */
    private EditBox rowBox(int x, int y, int width, String value, int maxLength) {
        EditBox edit = box(x, y, width, value, maxLength);
        edit.setResponder(text -> this.cellEdited = true);
        return edit;
    }

    /**
     * Whether the player typed in this page's row since the boxes were last filled from the region or the selection -
     * see {@link #fillCellFields} and {@link #fillFromSelection}.
     *
     * <p>It covers every box of the row, not only the cell: a rebuild refills the name, the cell and the strength from
     * the row that is selected, so a refresh driven by the block's own data (see {@link #tick}) would otherwise eat half
     * of what the player is typing. The flag is cleared when the boxes are refilled and when the player picks another
     * row - the text belonged to the row it was typed for.
     */
    private boolean cellEdited;

    private EditBox box(int x, int y, int width, String value, int maxLength) {
        EditBox edit = new EditBox(this.font, x, y, width, 16, Component.empty());
        edit.setMaxLength(maxLength);
        edit.setValue(value);
        return addRenderableWidget(edit);
    }

    /**
     * A field of the region, which is also what marks the region as <b>touched</b> (see {@link #sendWhatChanged}).
     *
     * <p>The responder is the only place that can tell "the screen filled this in from the border" apart from "the
     * player typed here", and only the second is an edit to send.
     */
    private EditBox regionBox(int x, int y, int width, String value, int maxLength) {
        EditBox edit = box(x, y, width, value, maxLength);
        edit.setResponder(text -> this.editedRegion = true);
        return edit;
    }

    /** Everything the player types in this box is a change to the structure's name - see {@link #sendWhatChanged}. */
    private EditBox nameBox(int x, int y, int width, String value, int maxLength) {
        EditBox edit = box(x, y, width, value, maxLength);
        edit.setResponder(text -> this.editedName = true);
        return edit;
    }

    /**
     * Mode A's six face tables, as one flat list: {@code face -> {cell, strength}} rows.
     *
     * <p>Flattened rather than six sub-lists, because the six tables are read the same way - "which cells does this face
     * push into". The row's own button removes that one cell; the whole face is cleared from the command,
     * {@code /rc structure face <controller> clear <face>}.
     */
    private void buildFacePage() {
        if (locked(PAGE_FACES)) {
            // Locked: see {@link #locked}. No widgets, and the page says why.
            return;
        }
        this.faceButton = addRenderableWidget(Button.builder(faceLabel(), button -> {
            Direction[] faces = Direction.values();
            this.face = faces[(this.face.ordinal() + 1) % faces.length];
            relabel(this.faceButton, faceLabel());
        }).bounds(this.left + lx("faces.face.button"), this.top + ly("faces.face.button"),
                lw("faces.face.button"), lh("faces.face.button")).build());
        this.cellX = cellBox(this.left + lx("faces.cellX.field"), this.top + ly("faces.cellX.field"),
                lw("faces.cellX.field"));;
        this.cellY = cellBox(this.left + lx("faces.cellY.field"), this.top + ly("faces.cellY.field"),
                lw("faces.cellY.field"));;
        this.cellZ = cellBox(this.left + lx("faces.cellZ.field"), this.top + ly("faces.cellZ.field"),
                lw("faces.cellZ.field"));;
        this.strengthBox = rowBox(this.left + lx("faces.strength.field"), this.top + ly("faces.strength.field"),
                lw("faces.strength.field"), "15", 2);
        fillCellFields();

        addRenderableWidget(Button.builder(Component.literal("<"), button -> scrollBy(-ROWS))
                .bounds(this.left + lx("faces.scroll.back"), this.top + ly("faces.scroll.back"),
                        lw("faces.scroll.back"), lh("faces.scroll.back")).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> scrollBy(ROWS))
                .bounds(this.left + lx("faces.scroll.forward"), this.top + ly("faces.scroll.forward"),
                        lw("faces.scroll.forward"), lh("faces.scroll.forward")).build());
        for (int row = 0; row < ROWS; row++) {
            int y = this.top + ly("faces.rows") + row * lh("faces.rows");
            int index = row;
            this.removeButtons.add(addRenderableWidget(Button.builder(Component.literal("x"),
                            button -> removeFaceEntry(this.scroll + index))
                    .bounds(this.left + lx("faces.remove.button"), y, lw("faces.remove.button"),
                            lh("faces.remove.button")).build()));
        }
        this.addButton = addRenderableWidget(Button.builder(text("faces.add"), button -> addFaceEntry())
                .bounds(this.left + lx("faces.add.button"), this.top + ly("faces.add.button"),
                        lw("faces.add.button"), lh("faces.add.button")).build());
    }

    /**
     * The cell the input row names, as the world position the server checks it against.
     *
     * <p>The boxes name a cell the way a table stores one - an offset from the <b>selection's first cell</b> (see
     * {@link RegionEditor#cellIn}) - while every edit is checked against the region in <b>world</b> coordinates. So the
     * translation belongs here, and the defaults come from the region itself: a typed cell is clamped to the region
     * and the substitution is reported in the action bar.
     */
    private BlockPos typedCell() {
        int cellX = parse(this.cellX, 0);
        int cellY = parse(this.cellY, 0);
        int cellZ = parse(this.cellZ, 0);
        // Read in whichever of the two readings the region page offers, and the one the player has just been looking at.
        PreviewRegion shown = shownRegion();
        // With no border up there is no selection to count from: the one-cell stand-in makes the controller the origin,
        // and the server's own refusal in the action bar is what explains it, as on the page that offers no region.
        BlockPos typed = this.relative
                ? RegionEditor.cellIn(shown == null ? PreviewRegion.one(this.controller) : shown,
                        new Vec3i(cellX, cellY, cellZ))
                : new BlockPos(cellX, cellY, cellZ);
        if (shown == null || shown.box().contains(typed)) {
            return typed;
        }
        com.jiangyuefengyu.redstonecircuit.structure.StructureCapture.Box box = shown.box();
        BlockPos snapped = new BlockPos(
                Math.min(Math.max(typed.getX(), box.min().getX()), box.max().getX()),
                Math.min(Math.max(typed.getY(), box.min().getY()), box.max().getY()),
                Math.min(Math.max(typed.getZ(), box.min().getZ()), box.max().getZ()));
        if (this.minecraft != null && this.minecraft.player != null) {
            this.minecraft.player.displayClientMessage(Component.translatable(
                    "redstonecircuit.structure.screen.cell.snapped", snapped.toShortString()), true);
        }
        // The cell the player typed is sent clamped, but the boxes are NOT rewritten to it: what the player typed stays
        // what the player typed, and the action bar above is where the substitution is reported.
        return snapped;
    }

    /**
     * Fills the three cell boxes from the region the border is drawing. A row the player has already typed in is left
     * alone: a rebuilding page must not eat what was just entered (see {@link #cellEdited}).
     */
    private void fillCellFields() {
        if (this.cellX == null || this.cellEdited) {
            return;
        }
        PreviewRegion region = shownRegion();
        // With no border up there is nothing to be inside of, so the row starts at the block itself and the server's
        // refusal in the action bar explains.
        if (region == null) {
            set(this.cellX, "0");
            set(this.cellY, "0");
            set(this.cellZ, "0");
            this.cellEdited = false;
            return;
        }
        BlockPos first = region.min();
        // The row starts on the selection's first cell, which in the relative reading <em>is</em> 0,0,0 - the origin a
        // table stores against (see {@link RegionEditor#cellIn}) - so the default is one cell whether a border is up
        // or not. The absolute reading shows that same cell where it stands in the world.
        set(this.cellX, Integer.toString(this.relative ? 0 : first.getX()));
        set(this.cellY, Integer.toString(this.relative ? 0 : first.getY()));
        set(this.cellZ, Integer.toString(this.relative ? 0 : first.getZ()));
        this.cellEdited = false;
    }

    // ------------------------------------------------------------------ actions --

    /** The six boxes, as the rectangle they describe - the only thing an edit needs from this screen. */
    private PreviewRegion typedRegion() {
        RegionEditor fields = new RegionEditor(this.relative,
                new Vec3i(parse(this.offsetX, 0), parse(this.offsetY, 0), parse(this.offsetZ, 0)),
                new Vec3i(parse(this.sizeX, 1), parse(this.sizeY, 1), parse(this.sizeZ, 1)));
        PreviewRegion typed = fields.resolve(this.controller);
        // A release puts the structure down at the size the structure is - the server's release takes nothing else. So
        // the rectangle this screen sends for a release is the position the player typed with the slot's own size,
        // which also makes the border draw exactly the footprint that will appear.
        StructureProductData pending = controller(this.controller) == null ? null
                : controller(this.controller).pending();
        if (!isRelease() || pending == null) {
            return typed;
        }
        return new PreviewRegion(typed.min(), pending.size());
    }

    /**
     * The region this controller is showing, <b>as the client can know it</b>.
     *
     * <p>The region travels to clients as the border ({@code PreviewSnapshotPayload}) and nowhere else, so on a client
     * {@code controller.preview()} is null and every default read from it falls back to "just this block". Reading the
     * border cache is reading the same fact the player is looking at: the box on the ground.
     */
    @Nullable
    private PreviewRegion shownRegion() {
        StructurePreviews.Entry border = border();
        return border == null ? null : border.region();
    }

    private void applyRegion() {
        PreviewRegion region = typedRegion();
        send(RCNetwork.StructureEditPayload.region(this.controller, region.min(), region.size()));
        // The change is on its way, so a later close does not have to send it again.
        this.editedRegion = false;
    }

    private void fromMe() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }
        BlockPos feet = minecraft.player.blockPosition();
        this.relative = false;
        this.offsetX.setValue(Integer.toString(feet.getX()));
        this.offsetY.setValue(Integer.toString(feet.getY()));
        this.offsetZ.setValue(Integer.toString(feet.getZ()));
        applyRegion();
    }

    /** Fills the boxes from the region the controller is showing, in whichever reading is selected. */
    private void fillRegionFields() {
        if (this.offsetX == null) {
            return;
        }
        // The border is where a client learns the region (see shownRegion); with none up there is nothing yet, and the
        // boxes show the starting point the controller has - one cell, itself.
        PreviewRegion region = shownRegion() == null ? PreviewRegion.one(this.controller) : shownRegion();
        RegionEditor fields = this.relative ? RegionEditor.of(this.controller, region)
                : RegionEditor.absoluteOf(region);
        this.offsetX.setValue(Integer.toString(fields.offset().getX()));
        this.offsetY.setValue(Integer.toString(fields.offset().getY()));
        this.offsetZ.setValue(Integer.toString(fields.offset().getZ()));
        // The size row is capture-only (see buildRegionPage), so these three may not exist.
        set(this.sizeX, Integer.toString(fields.size().getX()));
        set(this.sizeY, Integer.toString(fields.size().getY()));
        set(this.sizeZ, Integer.toString(fields.size().getZ()));
        // Filling the boxes is not the player editing them (see sendWhatChanged): the responder cannot tell the two
        // apart, so the flag is cleared here, after the fill.
        this.editedRegion = false;
    }

    /** One entry as a line of text: which one, its name, the cell the boxes mean by it, and its strength. */
    private String rowText(int index, ActivationTable.ManualEntry entry) {
        return (index + 1) + ". " + displayName(entry) + " -> " + cellAsTyped(entry.localPos()) + "@"
                + entry.strength();
    }

    /** Which page of the key grid is showing, clamped to the pages the panel actually has. */
    private int page() {
        return Math.min(this.panelPage, PanelGrid.pages(panelEntries().size()) - 1);
    }

    /** The entry the row below the grid is editing, or null when nothing is selected. */
    @Nullable
    private ActivationTable.ManualEntry selectedEntry() {
        List<ActivationTable.ManualEntry> manual = panelEntries();
        return this.selected >= 0 && this.selected < manual.size() ? manual.get(this.selected) : null;
    }

    /** What goes before a grid button's name while it is the entry being edited. */
    private String markSelection(int index) {
        return index == this.selected ? "\u00BB " : "";
    }

    /**
     * The grid's arrows: they turn the page, and the selection goes with the page it was on - a selection the player
     * can no longer see is a selection the boxes below are silently editing.
     */
    private void turnPanel(int delta) {
        this.panelPage = Math.max(0, Math.min(page() + delta, PanelGrid.pages(panelEntries().size()) - 1));
        this.selected = -1;
        this.rebuildWidgets();
    }

    /** A grid button: the entry it names becomes the one the row below edits. */
    private void select(int index) {
        this.selected = index;
        // What was typed belonged to the row the boxes were filled from, and this is another one: the row below is
        // filled from the new selection, so that is what "not edited yet" means again.
        this.cellEdited = false;
        this.rebuildWidgets();
    }

    /** The one button under the grid says which of its two verbs it would do - see {@link #commitEntry}. */
    private Component commitLabel() {
        return text(selectedEntry() == null ? "panel.add" : "panel.update");
    }

    /**
     * The one button under the grid: it <b>adds</b> the row to the panel, or <b>writes it back</b> over the selected
     * entry.
     *
     * <p>Two verbs on one button because they are one act - "make the panel hold this" - and the label is also the only
     * place the selection shows: a grid of buttons of equal weight would otherwise leave the row below pointing at
     * nothing the player can see.
     */
    private void commitEntry() {
        String name = this.buttonName.getValue().trim();
        if (selectedEntry() == null) {
            send(RCNetwork.StructureEditPayload.manual(this.controller, name, typedCell(),
                    parse(this.strengthBox, 15), name));
            // An addition is appended, so the page to look at is the one this panel is about to need.
            this.panelPage = PanelGrid.pageOf(panelEntries().size());
            this.rebuildWidgets();
            return;
        }
        send(RCNetwork.StructureEditPayload.manualReplace(this.controller, this.selected, name, typedCell(),
                parse(this.strengthBox, 15)));
    }

    /** Drops the selected entry - by index, which is the identity a press travels as. */
    private void removeSelected() {
        if (selectedEntry() == null) {
            return;
        }
        send(RCNetwork.StructureEditPayload.entry(this.controller,
                RCNetwork.StructureEditPayload.Verb.REMOVE_MANUAL, this.selected));
        this.selected = -1;
        this.rebuildWidgets();
    }

    /**
     * Fills the row from the selected entry - or, with nothing selected, from the region, the way the two table pages
     * do: a cell is stored inside the selection, so with no region up there is nothing a new entry could name.
     */
    private void fillFromSelection() {
        ActivationTable.ManualEntry editing = selectedEntry();
        if (editing == null) {
            fillCellFields();
            return;
        }
        if (this.cellEdited) {
            // The player is typing this row - which a page rebuilt by the block's own data may now happen under (see
            // #tick). Their row stays theirs: the grid above it is redrawn, the boxes below are not.
            return;
        }
        set(this.buttonName, displayName(editing));
        PreviewRegion shown = shownRegion();
        // A stored cell is already what the boxes mean in the relative reading (see {@link RegionEditor#cellIn}), and
        // with no border up there is no world position to place it at: both cases are the stored cell itself, so a row
        // reads the same numbers before and after a capture takes the border away. Only the absolute reading has to be
        // placed back into the world, through the region the row was drawn from.
        if (shown == null || this.relative) {
            set(this.cellX, Integer.toString(editing.localPos().getX()));
            set(this.cellY, Integer.toString(editing.localPos().getY()));
            set(this.cellZ, Integer.toString(editing.localPos().getZ()));
        } else {
            BlockPos world = shown.min().offset(editing.localPos());
            set(this.cellX, Integer.toString(world.getX()));
            set(this.cellY, Integer.toString(world.getY()));
            set(this.cellZ, Integer.toString(world.getZ()));
        }
        set(this.strengthBox, Integer.toString(editing.strength()));
        // Filling the boxes is not the player typing in them (the responders cannot tell the two apart), so the flag is
        // cleared here, after the fill - the same order {@link #fillCellFields} and {@link #fillRegionFields} use.
        this.cellEdited = false;
    }

    /** One cell of the face the button is showing - see {@link #typedCell} for the reading and why it is relative. */
    private void addFaceEntry() {
        send(RCNetwork.StructureEditPayload.face(this.controller, this.face, typedCell(),
                parse(this.strengthBox, 15)));
    }

    /** The row the flat face list is showing, or null when that row is past the end. */
    private FaceRow faceRow(int index) {
        RedstoneStructureBlockEntity found = controller(this.controller);
        if (found == null || index < 0) {
            return null;
        }
        int seen = 0;
        for (Direction direction : Direction.values()) {
            for (ActivationTable.FaceEntry entry : ActivationTable.entries(found.faces(), direction)) {
                if (seen++ == index) {
                    return new FaceRow(direction, entry.localPos(), entry.strength());
                }
            }
        }
        return null;
    }

    /** One row of the flattened face list. */
    private record FaceRow(Direction face, BlockPos localPos, int strength) {
    }

    private void removeFaceEntry(int index) {
        FaceRow row = faceRow(index);
        if (row == null) {
            return;
        }
        // The cell is converted through the border the row was drawn from, not through the controller's own preview,
        // which <b>a client never receives</b> - the region travels as the border and nowhere else. So a cell converted
        // through it is the cell the server will be looking for.
        PreviewRegion region = shownRegion();
        if (region == null) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(text("faces.noregion"), true);
            }
            return;
        }
        send(RCNetwork.StructureEditPayload.removeFaceCell(this.controller, row.face(),
                region.min().offset(row.localPos())));
    }

    /**
     * <b>The output table's page</b>: the face page's widgets, pointing the other way.
     *
     * <p>The boxes, the captions and the row layout are the face page's, and the only thing that differs is what a row
     * means: {@code cell -> face@strength} instead of {@code face -> cell@strength}. The face cycler is the same field,
     * so a player who picked "east" on the face page finds "east" here.
     *
     * <p>Never locked, unlike the two input tables: the output is not a way of driving the structure, it is what the
     * structure answers with, and it works in both input modes.
     */
    private void buildOutputPage() {
        this.faceButton = addRenderableWidget(Button.builder(outputLabel(), button -> {
            Direction[] faces = Direction.values();
            this.face = faces[(this.face.ordinal() + 1) % faces.length];
            relabel(this.faceButton, outputLabel());
        }).bounds(this.left + lx("output.face.button"), this.top + ly("output.face.button"),
                lw("output.face.button"), lh("output.face.button")).build());
        this.cellX = cellBox(this.left + lx("output.cellX.field"), this.top + ly("output.cellX.field"),
                lw("output.cellX.field"));
        this.cellY = cellBox(this.left + lx("output.cellY.field"), this.top + ly("output.cellY.field"),
                lw("output.cellY.field"));
        this.cellZ = cellBox(this.left + lx("output.cellZ.field"), this.top + ly("output.cellZ.field"),
                lw("output.cellZ.field"));
        this.strengthBox = rowBox(this.left + lx("output.strength.field"), this.top + ly("output.strength.field"),
                lw("output.strength.field"), "15", 2);
        fillCellFields();

        addRenderableWidget(Button.builder(Component.literal("<"), button -> scrollBy(-ROWS))
                .bounds(this.left + lx("output.scroll.back"), this.top + ly("output.scroll.back"),
                        lw("output.scroll.back"), lh("output.scroll.back")).build());
        addRenderableWidget(Button.builder(Component.literal(">"), button -> scrollBy(ROWS))
                .bounds(this.left + lx("output.scroll.forward"), this.top + ly("output.scroll.forward"),
                        lw("output.scroll.forward"), lh("output.scroll.forward")).build());
        for (int row = 0; row < ROWS; row++) {
            int y = this.top + ly("output.rows") + row * lh("output.rows");
            int index = row;
            this.removeButtons.add(addRenderableWidget(Button.builder(Component.literal("x"),
                            button -> removeOutputEntry(this.scroll + index))
                    .bounds(this.left + lx("output.remove.button"), y, lw("output.remove.button"),
                            lh("output.remove.button")).build()));
        }
        this.addButton = addRenderableWidget(Button.builder(text("output.add"), button -> addOutputEntry())
                .bounds(this.left + lx("output.add.button"), this.top + ly("output.add.button"),
                        lw("output.add.button"), lh("output.add.button")).build());
    }

    /** One output row in the table's own order, or null when that row is past the end. */
    private OutputRow outputRow(int index) {
        RedstoneStructureBlockEntity found = controller(this.controller);
        if (found == null || index < 0 || index >= found.output().size()) {
            return null;
        }
        ActivationTable.OutputEntry entry = found.output().get(index);
        return new OutputRow(entry.face(), entry.localPos(), entry.strength());
    }

    /** One row of the output table. */
    private record OutputRow(Direction face, BlockPos localPos, int strength) {
    }

    /** Adds or replaces one row: the cell the boxes name, the face the button shows, the strength typed. */
    private void addOutputEntry() {
        send(RCNetwork.StructureEditPayload.output(this.controller, this.face, typedCell(),
                parse(this.strengthBox, 15)));
    }

    /**
     * The row's own button: removes the row it is drawn on.
     *
     * <p>The cell goes back through the border, exactly as the face page's does and for the same reason: the table
     * stores a cell relative to the selection, the boxes and the command name a world position, and the row on screen
     * is the world one.
     */
    private void removeOutputEntry(int index) {
        OutputRow row = outputRow(index);
        if (row == null) {
            return;
        }
        PreviewRegion region = shownRegion();
        if (region == null) {
            if (this.minecraft != null && this.minecraft.player != null) {
                this.minecraft.player.displayClientMessage(text("output.noregion"), true);
            }
            return;
        }
        send(RCNetwork.StructureEditPayload.removeOutput(this.controller, row.face(),
                region.min().offset(row.localPos())));
    }

    private void scrollBy(int rows) {
        int entries = Math.max(panelEntries().size(), Math.max(this.faceEntries(), this.outputEntries()));
        this.scroll = Math.max(0, Math.min(Math.max(0, entries - ROWS), this.scroll + rows));
        this.rebuildWidgets();
    }

    /** The controller's key panel, in the order its grid shows it. */
    private List<ActivationTable.ManualEntry> panelEntries() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found == null ? List.of() : found.manual();
    }

    /** How many rows the flattened face list has: every cell of every face. */
    private int faceEntries() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        if (found == null) {
            return 0;
        }
        int total = 0;
        for (Direction direction : Direction.values()) {
            total += ActivationTable.entries(found.faces(), direction).size();
        }
        return total;
    }

    /** How many rows the output table has - the page's own count, beside {@link #faceEntries()}. */
    private int outputEntries() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found == null ? 0 : found.output().size();
    }

    /**
     * How many rows the page on screen has, read from the block <b>now</b> - the first half of what {@link #tick}
     * compares.
     *
     * <p>A page whose data is not a list of rows - the region, the files - answers zero, and so does a controller that
     * has gone: there is nothing on those pages that the server can change under the screen.
     */
    private int pageRows() {
        return switch (this.page) {
            case PAGE_PANEL -> panelEntries().size();
            case PAGE_FACES -> faceEntries();
            case PAGE_OUTPUT -> outputEntries();
            default -> 0;
        };
    }

    /**
     * The fingerprint of the rows the page on screen has, read from the block <b>now</b> - the second half of what
     * {@link #tick} compares.
     *
     * <p>Each row contributes its name, its cell and its strength, in the order the page draws them (see
     * {@link StructurePageSignature}), so the answer changes when a row is added, renamed, moved, re-powered or
     * reordered, and stays the same - the point of the whole thing - while the block says nothing new.
     */
    private long pageSignature() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        long folded = StructurePageSignature.START;
        int rows = 0;
        if (found == null) {
            return StructurePageSignature.of(0, folded);
        }
        switch (this.page) {
            case PAGE_PANEL -> {
                for (ActivationTable.ManualEntry entry : found.manual()) {
                    BlockPos cell = entry.localPos();
                    folded = StructurePageSignature.fold(folded, displayName(entry), cell.getX(), cell.getY(),
                            cell.getZ(), entry.strength());
                    rows++;
                }
            }
            // The two table pages are one table read from its two ends, and each row of them is a face and a cell.
            case PAGE_FACES -> {
                for (Direction direction : Direction.values()) {
                    for (ActivationTable.FaceEntry entry : ActivationTable.entries(found.faces(), direction)) {
                        BlockPos cell = entry.localPos();
                        folded = StructurePageSignature.fold(folded, direction.getName(), cell.getX(), cell.getY(),
                                cell.getZ(), entry.strength());
                        rows++;
                    }
                }
            }
            case PAGE_OUTPUT -> {
                for (ActivationTable.OutputEntry entry : found.output()) {
                    BlockPos cell = entry.localPos();
                    folded = StructurePageSignature.fold(folded, entry.face().getName(), cell.getX(), cell.getY(),
                            cell.getZ(), entry.strength());
                    rows++;
                }
            }
            default -> {
                // A page with no list of its own: nothing to fold, and nothing that can change under it.
            }
        }
        return StructurePageSignature.of(rows, folded);
    }

    /**
     * Fits the selection, the grid page and the scroll to the list the block has <b>now</b>.
     *
     * <p>A list can get shorter while the screen is open: the player removed a row, the server refused one, or another
     * player did. An index that pointed into it is then out of range - a selection past the end leaves the row below
     * editing nothing while its button still offers 更新, and a scroll past the end draws an empty table with a list
     * above it. Clamped here, at the one moment the screen knows the list moved, rather than on every read.
     */
    private void clampToData() {
        int entries = panelEntries().size();
        if (this.selected >= entries) {
            this.selected = -1;
        }
        this.panelPage = Math.max(0, Math.min(this.panelPage, PanelGrid.pages(entries) - 1));
        int longest = Math.max(entries, Math.max(this.faceEntries(), this.outputEntries()));
        this.scroll = Math.max(0, Math.min(this.scroll, Math.max(0, longest - ROWS)));
    }

    private void send(RCNetwork.StructureEditPayload payload) {
        PacketDistributor.sendToServer(payload);
    }

    // ------------------------------------------------------------------ reading --

    /**
     * Whether the block is in release mode - <b>including a switch the server has not answered yet</b>.
     *
     * <p>The two modes differ in <em>widgets</em> (release has no size row), and the widgets are built in {@code init},
     * so the override makes the switch visible at once and {@link #tick} rebuilds the page when the block's own value
     * differs from the one the page was built with.
     */
    private boolean isRelease() {
        if (this.pendingRelease != null) {
            return this.pendingRelease;
        }
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found != null && found.mode() == RedstoneStructureBlockEntity.Mode.RELEASE;
    }

    /** The block's own answer, ignoring the local override - what {@link #tick} compares against. */
    private boolean blockIsRelease() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found != null && found.mode() == RedstoneStructureBlockEntity.Mode.RELEASE;
    }

    /**
     * Rebuilds the page when what it was built from is no longer what the block has: the mode it was built for, or the
     * list the page shows.
     *
     * <p>In {@code tick} rather than in {@code render}: rebuilding widgets while the screen is drawing is a mutation
     * during iteration, and a tick is exactly the quiet moment for it.
     *
     * <p><b>The list is asked about every tick, because it is the block's data and not the screen's.</b> A key panel is
     * edited by a packet and answered by the server a tick or more later, so the page built when the button was pressed
     * is built from the <em>old</em> panel: without this, adding or renaming a row changes nothing until the player
     * presses the page tab again - the tab is what rebuilt it, not the answer. All three live pages are covered, not
     * only the keys: the two tables are read from the same entity and go stale the same way.
     *
     * <p><b>Its cost, and the bound on it, are one and the same:</b> the row count first, then - only when the count still
     * matches - one {@link StructurePageSignature} fingerprint of the rows. Two integer comparisons and one fold over a
     * handful of rows is what an idle tick pays, and a page is rebuilt only when one of them differs, so a page whose
     * data has not moved cannot be rebuilt however long it is left open.
     *
     * <p><b>What a rebuild must not do is eat what the player is typing.</b> {@code init} refills the row's boxes from
     * the region and from the selected entry, so the ones the player has typed in are left alone: that is the
     * {@link #cellEdited} flag, which this screen already used for a page rebuilt by a switch or a mode change, extended
     * to the name and the strength boxes because a refresh now happens without the player asking for it.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.pendingRelease != null && this.pendingRelease == blockIsRelease()) {
            this.pendingRelease = null;
        }
        boolean stale = this.builtRelease != isRelease();
        if (!stale && this.builtRows != pageRows()) {
            // A list that changed length is a different list: folding its rows to prove it would be work for nothing.
            stale = true;
        }
        if (!stale && this.builtSignature != pageSignature()) {
            stale = true;
        }
        if (!stale) {
            return;
        }
        this.builtRelease = isRelease();
        // The list may have got shorter under the indices the page is holding - see #clampToData.
        clampToData();
        this.rebuildWidgets();
    }

    private boolean isCopy() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found != null && found.tier() == RedstoneStructureBlockEntity.Tier.COPY;
    }

    private boolean isManual() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found != null && found.ioMode() == com.jiangyuefengyu.redstonecircuit.structure.IoMode.MANUAL;
    }

    /** The border the server says is up for this controller, or null - the state the button's label discusses. */
    private StructurePreviews.Entry border() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return null;
        }
        for (StructurePreviews.Entry entry : StructurePreviewClient.borders(minecraft.level.dimension())) {
            if (entry.controller().equals(this.controller)) {
                return entry;
            }
        }
        return null;
    }

    private Component modeLabel() {
        return Component.translatable("redstonecircuit.structure.screen.mode",
                Component.translatable("redstonecircuit.structure.screen.mode."
                        + (isRelease() ? "release" : "capture")));
    }

    private Component tierLabel() {
        return Component.translatable("redstonecircuit.structure.screen.tier",
                Component.translatable("redstonecircuit.structure.screen.tier."
                        + (isCopy() ? "copy" : "cut")));
    }

    private Component ioLabel() {
        return Component.translatable("redstonecircuit.structure.screen.io",
                Component.translatable("redstonecircuit.structure.screen.io."
                        + (isManual() ? "manual" : "faces")));
    }

    private Component relativeLabel() {
        return Component.translatable("redstonecircuit.structure.screen.reading",
                Component.translatable("redstonecircuit.structure.screen.reading."
                        + (this.relative ? "relative" : "absolute")));
    }

    /** The face the add row would fill - the button cycles down, up, north, south, west, east. */
    private Component faceLabel() {
        return Component.translatable("redstonecircuit.structure.screen.faces.label", this.face.getName());
    }

    /** The face the output page's add row would drive - the same cycler and the same field as the face page. */
    private Component outputLabel() {
        return Component.translatable("redstonecircuit.structure.screen.output.label", this.face.getName());
    }

    /**
     * The panel has no key bindings of its own - its rows are fired from the placed structure's screen. Keys still
     * reach the text boxes, which is all this has to leave alone.
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** A panel button's name: what the player typed, with the stored key as the fallback for older entries. */
    private static String displayName(ActivationTable.ManualEntry entry) {
        return entry.label().isEmpty() ? entry.key() : entry.label();
    }

    /**
     * A stored cell as the boxes would name it.
     *
     * <p>The tables store a cell as an offset from the selection's first cell, and so does the relative reading of the
     * boxes (see {@link RegionEditor#cellIn}) - so that reading is the stored value itself and needs no border, which
     * is why a capture, the one action that takes the border away, cannot make a row change what it says. The absolute
     * reading places the cell in the world through the border, and falls back to the stored value when none is up.
     */
    private String cellAsTyped(Vec3i local) {
        if (this.relative) {
            return local.toShortString();
        }
        PreviewRegion shown = shownRegion();
        return shown == null ? local.toShortString() : shown.min().offset(local).toShortString();
    }

    private static Component tab(String key) {
        return Component.translatable("redstonecircuit.structure.screen." + key);
    }

    private static Component text(String key) {
        return Component.translatable("redstonecircuit.structure.screen." + key);
    }

    private static int parse(EditBox box, int fallback) {
        if (box == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(box.getValue().trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /**
     * A string cut down to a width in pixels, with an ellipsis when it had to be.
     *
     * <p>The two things that can be arbitrarily long are a panel entry's label (typed by the player) and a save path
     * (named by the filesystem), so clamping is the difference between a hint and a mess.
     */
    private String fit(String value, int maxWidth) {
        if (value == null || value.isEmpty() || this.font.width(value) <= maxWidth) {
            return value == null ? "" : value;
        }
        String cut = value;
        while (cut.length() > 1 && this.font.width(cut + "...") > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "...";
    }

    /**
     * Re-labels the buttons whose text is a fact about the controller, every frame.
     *
     * <p>Comparing before setting keeps the per-frame cost to a string compare and avoids rebuilding widget text every
     * frame; a label only changes when the block entity's own value does.
     */
    private void refreshLabels() {
        // The local mode override lasts exactly until the block's own value matches it.
        if (this.pendingManual != null && this.pendingManual == isManual()) {
            this.pendingManual = null;
        }
        relabel(this.modeButton, modeLabel());
        relabel(this.tierButton, tierLabel());
        relabel(this.ioButton, ioLabel());
        relabel(this.operateButton, operateLabel());
        relabel(this.relativeButton, relativeLabel());
        relabel(this.faceButton, faceLabel());
        // The end-preview button exists only while there is a preview to end. Its own label doubles as the state line
        // for the operation, which is why it is refreshed here with the rest.
        if (this.endPreviewButton != null) {
            this.endPreviewButton.visible = border() != null;
        }
        // The take-out button exists only while there is something to take out, so it is visible exactly then - a
        // button that is always there and usually refuses teaches nothing.
        if (this.takeOutButton != null) {
            RedstoneStructureBlockEntity found = controller(this.controller);
            this.takeOutButton.visible = found != null && found.pending() != null;
        }
        // A cell is named inside the region the border draws, so an add button with no region up has nothing to add
        // to: it is disabled rather than left to refuse through the action bar, and the page says so in place of its
        // hint.
        if (this.addButton != null) {
            // The border, not the block entity: a controller's preview is not in its sync tag (a border is
            // visible to everyone in the dimension through its own message), so the client's copy of the block
            // never knows about one - asking it would leave this button disabled for ever.
            this.addButton.active = border() != null;
        }
        // The apply row's wording is built with the page, but the mode can change under it (see {@link #isRelease}).
        relabel(this.applyButton, applyLabel());
        relabel(this.hereButton, text(isRelease() ? "here.release" : "here"));
    }

    private static void relabel(Button button, Component label) {
        if (button != null && !button.getMessage().equals(label)) {
            button.setMessage(label);
        }
    }

    // ------------------------------------------------------------------ drawing --

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Before the widgets are drawn, so a click's effect is on screen in the same frame it was made.
        refreshLabels();
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.left + WIDTH / 2, this.top + 6, 0xFFFFFF);

        RedstoneStructureBlockEntity found = controller(this.controller);
        if (found == null) {
            graphics.drawString(this.font, text("gone"), this.left + 8, this.top + 48, 0xFF5555);
            return;
        }
        if (this.page == PAGE_PANEL) {
            renderPanelPage(graphics, found);
        } else if (this.page == PAGE_FACES) {
            renderFacePage(graphics, found);
        } else if (this.page == PAGE_OUTPUT) {
            renderOutputPage(graphics, found);
        } else if (this.page == PAGE_FILES) {
            renderFilesPage(graphics);
        } else {
            renderRegionPage(graphics, found);
        }
        // The two state lines describe the <b>region</b> - what is shown and what the next operation would do - so they
        // belong to the region page and nowhere else.
        if (this.page == PAGE_REGION) {
            renderState(graphics, found);
        }
    }

    /** Where the files are, and the two commands that move them - the whole of the file hint. */
    private void renderFilesPage(GuiGraphics graphics) {
        graphics.drawString(this.font, text("files.structures"), this.left + lx("files.structures.line"),
                this.top + ly("files.structures.line"), 0xA0A0A0);
        graphics.drawString(this.font, fit(this.structuresPath, WIDTH - lx("files.structures.path") - 8),
                this.left + lx("files.structures.path"), this.top + ly("files.structures.path"), 0xE0E0E0);
        graphics.drawString(this.font, text("files.export"), this.left + lx("files.export.line"),
                this.top + ly("files.export.line"), 0xA0A0A0);
        graphics.drawString(this.font, fit(this.exportPath, WIDTH - lx("files.export.path") - 8),
                this.left + lx("files.export.path"), this.top + ly("files.export.path"), 0xE0E0E0);
        graphics.drawString(this.font, text("files.list"), this.left + lx("files.commands.line"),
                this.top + ly("files.commands.line"), 0xA0A0A0);
        graphics.drawString(this.font, fit(text("files.export.cmd").getString(),
                        WIDTH - lx("files.export.cmd.line") - 8), this.left + lx("files.export.cmd.line"),
                this.top + ly("files.export.cmd.line"), 0xA0A0A0);
        graphics.drawString(this.font, fit(text("files.import.cmd").getString(),
                        WIDTH - lx("files.import.cmd.line") - 8), this.left + lx("files.import.cmd.line"),
                this.top + ly("files.import.cmd.line"), 0xA0A0A0);
        graphics.drawString(this.font, fit(text("files.note").getString(), WIDTH - lx("files.note.line") - 8),
                this.left + lx("files.note.line"), this.top + ly("files.note.line"), 0x808080);
    }

    /**
     * The region page's own lines: the slot, the six region fields and their captions.
     *
     * <p>A capture puts a structure <em>into</em> the block and a release takes it <em>out</em>, so the slot is the
     * subject of this page and is drawn in both modes.
     */
    private void renderRegionPage(GuiGraphics graphics, RedstoneStructureBlockEntity found) {
        caption(graphics, "region.name.caption", text("name"));
        StructureProductData pending = found.pending();
        if (pending == null) {
            graphics.drawString(this.font, fit(text("slot.empty").getString(), lx("region.takeout.button") - 12),
                    this.left + lx("region.slot.line"), this.top + ly("region.slot.line"), 0x808080);
            // In release mode an empty slot is the whole reason the button does nothing, so it says how to fill it -
            if (isRelease()) {
                graphics.drawString(this.font, fit(text("slot.need").getString(), lx("region.takeout.button") - 12),
                        this.left + lx("region.slot.line"), this.top + ly("region.slot.line") + 10, 0xFF5555);
            }
        } else {
            graphics.drawString(this.font, fit(Component.translatable(
                            isRelease() ? "redstonecircuit.structure.screen.slot.willPlace"
                                    : "redstonecircuit.structure.screen.slot.holds",
                            pending.name(),
                            pending.size().getX() + "x" + pending.size().getY() + "x" + pending.size().getZ())
                    .getString(), lx("region.takeout.button") - 12), this.left + lx("region.slot.line"),
                    this.top + ly("region.slot.line"), 0x60D0FF);
        }
        caption(graphics, "region.offsetX.caption", text("axis.x"));
        caption(graphics, "region.offsetY.caption", text("axis.y"));
        caption(graphics, "region.offsetZ.caption", text("axis.z"));
        caption(graphics, "region.offset.note", text("region.offset.note"));
        // A release has no size to type (see typedRegion), so its labels exist only in capture mode, with its boxes.
        if (!isRelease()) {
            caption(graphics, "region.sizeX.caption", text("axis.x"));
            caption(graphics, "region.sizeY.caption", text("axis.y"));
            caption(graphics, "region.sizeZ.caption", text("axis.z"));
            caption(graphics, "region.size.note", text("region.size.note"));
        }
    }

    /** One caption, at its slot. */
    private void caption(GuiGraphics graphics, String id, Component translated) {
        graphics.drawString(this.font, translated, this.left + lx(id), this.top + ly(id), 0xA0A0A0);
    }

    /**
     * The key panel's own lines: the count, the captions above the fields, and the one sentence under the buttons - what
     * the cell boxes mean, or why the button refuses, or which entry is being edited.
     *
     * <p>The grid is buttons, so it draws itself: an empty panel draws nothing here, which is what an empty panel is.
     */
    private void renderPanelPage(GuiGraphics graphics, RedstoneStructureBlockEntity found) {
        if (locked(PAGE_PANEL)) {
            graphics.drawString(this.font, fit(text("locked.panel").getString(), WIDTH - 16), this.left + 8,
                    this.top + 48, 0xFF5555);
            return;
        }
        List<ActivationTable.ManualEntry> manual = found.manual();
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.screen.panel.header",
                        manual.size(), RCConfig.structureManualEntryLimit(), page() + 1, PanelGrid.pages(manual.size())),
                this.left + lx("panel.header.line"), this.top + ly("panel.header.line"), 0xA0A0A0);
        caption(graphics, "panel.key.caption", text("panel.key"));
        caption(graphics, "panel.cellX.caption", text("axis.x"));
        caption(graphics, "panel.cellY.caption", text("axis.y"));
        caption(graphics, "panel.cellZ.caption", text("axis.z"));
        caption(graphics, "panel.strength.caption", text("panel.strength"));
        // One sentence, in the only room left under the buttons. Which sentence it is says what the row is: a cell is
        // named inside the region, so with none up that comes first - the same refusal the two table pages make, in
        // the same red - then the selected entry, and otherwise how to read the boxes.
        boolean region = border() != null;
        ActivationTable.ManualEntry editing = selectedEntry();
        String hint;
        int colour;
        if (!region) {
            hint = text("panel.noregion").getString();
            colour = 0xFF5555;
        } else if (editing != null) {
            hint = Component.translatable("redstonecircuit.structure.screen.panel.selected.hint", this.selected + 1)
                    .getString();
            colour = 0x60D0FF;
        } else {
            hint = text(this.relative ? "panel.hint.rel" : "panel.hint.abs").getString();
            colour = 0x808080;
        }
        graphics.drawString(this.font, fit(hint, WIDTH - lx("panel.hint.line") - 8),
                this.left + lx("panel.hint.line"), this.top + ly("panel.hint.line"), colour);
    }

    /** Mode A's tables: one row per cell, showing which face drives it and how hard. */
    private void renderFacePage(GuiGraphics graphics, RedstoneStructureBlockEntity found) {
        if (locked(PAGE_FACES)) {
            graphics.drawString(this.font, fit(text("locked.faces").getString(), WIDTH - 16), this.left + 8,
                    this.top + 48, 0xFF5555);
            return;
        }
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.screen.faces.header",
                faceEntries()), this.left + lx("faces.header.line"), this.top + ly("faces.header.line"), 0xA0A0A0);
        for (int row = 0; row < ROWS; row++) {
            FaceRow entry = faceRow(this.scroll + row);
            if (entry == null) {
                break;
            }
            int y = this.top + ly("faces.rows") + row * lh("faces.rows");
            String label = (this.scroll + row + 1) + ". " + entry.face().getName() + " -> "
                    + cellAsTyped(entry.localPos()) + "@" + entry.strength();
            graphics.drawString(this.font, fit(label, lx("faces.remove.button") - 12), this.left + 8, y + 5,
                    0xE0E0E0);
        }
        // One caption per field, directly above it - see the panel page for the same rule.
        caption(graphics, "faces.cellX.caption", text("axis.x"));
        caption(graphics, "faces.cellY.caption", text("axis.y"));
        caption(graphics, "faces.cellZ.caption", text("axis.z"));
        caption(graphics, "faces.strength.caption", text("panel.strength"));
        // One line, and it says the caveat in place of the hint: a structure's region is what makes a cell nameable.
        if (border() != null) {
            graphics.drawString(this.font, fit(text("faces.hint").getString(), WIDTH - lx("faces.hint.line") - 8),
                    this.left + lx("faces.hint.line"), this.top + ly("faces.hint.line"), 0x808080);
        } else {
            graphics.drawString(this.font, fit(text("faces.noregion").getString(),
                            WIDTH - lx("faces.hint.line") - 8), this.left + lx("faces.hint.line"),
                    this.top + ly("faces.hint.line"), 0xFF5555);
        }
    }

    /**
     * The output table on screen - one row per entry, read as "this face answers this much while that cell carries a
     * signal".
     *
     * <p>The arrow points out of the face rather than into the cell, which is the one visible difference from the face
     * page and the whole of what the table means.
     */
    private void renderOutputPage(GuiGraphics graphics, RedstoneStructureBlockEntity found) {
        graphics.drawString(this.font, Component.translatable("redstonecircuit.structure.screen.output.header",
                outputEntries()), this.left + lx("output.header.line"), this.top + ly("output.header.line"), 0xA0A0A0);
        for (int row = 0; row < ROWS; row++) {
            OutputRow entry = outputRow(this.scroll + row);
            if (entry == null) {
                break;
            }
            int y = this.top + ly("output.rows") + row * lh("output.rows");
            String label = (this.scroll + row + 1) + ". " + entry.face().getName() + " <- "
                    + cellAsTyped(entry.localPos()) + "@" + entry.strength();
            graphics.drawString(this.font, fit(label, lx("output.remove.button") - 12), this.left + 8, y + 5,
                    0xE0E0E0);
        }
        // One caption per field, directly above it - the same rule the other two table pages follow.
        caption(graphics, "output.cellX.caption", text("axis.x"));
        caption(graphics, "output.cellY.caption", text("axis.y"));
        caption(graphics, "output.cellZ.caption", text("axis.z"));
        caption(graphics, "output.strength.caption", text("panel.strength"));
        if (border() != null) {
            graphics.drawString(this.font, fit(text("output.hint").getString(), WIDTH - lx("output.hint.line") - 8),
                    this.left + lx("output.hint.line"), this.top + ly("output.hint.line"), 0x808080);
        } else {
            graphics.drawString(this.font, fit(text("output.noregion").getString(),
                            WIDTH - lx("output.hint.line") - 8), this.left + lx("output.hint.line"),
                    this.top + ly("output.hint.line"), 0xFF5555);
        }
    }

    /** The two lines that say what is on the ground, and why it cannot be used when it cannot. */
    private void renderState(GuiGraphics graphics, RedstoneStructureBlockEntity found) {
        if (this.minecraft.level == null) {
            // The screen can outlive the world by a tick (a disconnect with it open); the background is already drawn
            // by the superclass, and there is nothing true to say about a world that is gone.
            return;
        }
        StructurePreviews.Entry border = border();
        if (border == null) {
            graphics.drawString(this.font, text("state.none"), this.left + 8, this.top + 198, 0xA0A0A0);
            return;
        }
        Component action = Component.translatable("redstonecircuit.structure.screen.action."
                + (border.release() ? "release" : "capture"));
        graphics.drawString(this.font, fit(Component.translatable(
                        border.armed() ? "redstonecircuit.structure.screen.state.armed"
                                : "redstonecircuit.structure.screen.state.shown",
                        action, border.region().describe()).getString(), WIDTH - 16), this.left + 8, this.top + 198,
                border.armed() ? 0xFFC040 : 0x60D0FF);
        Optional<String> refusal = border.region().refusal(RCConfig.structurePayloadLimits(),
                this.minecraft.level.getMinBuildHeight(), this.minecraft.level.getMaxBuildHeight());
        if (refusal.isPresent()) {
            graphics.drawString(this.font, fit(refusal.get(), WIDTH - 16), this.left + 8, this.top + 210,
                    0xFF5555);
        } else if (border.release() && border.overwrite() > 0) {
            graphics.drawString(this.font, fit(Component.translatable(
                            "redstonecircuit.structure.screen.state.overwrite", border.overwrite()).getString(),
                    WIDTH - 16), this.left + 8, this.top + 210, 0xFFC040);
        }
    }

    /**
     * A field the player filled in is work, and losing it is losing work, so the region and the name are sent on the
     * way out when they differ from the block. The server validates them like any other edit (a region that is too big
     * is still refused, and the action bar says so), and the block's own sync carries them back next time.
     */
    @Override
    public void onClose() {
        // The boxes are written to the screen's memory first, so the fields that exist only on this page are not lost
        // when the two edits that belong to the block are computed from it.
        rememberBoxes();
        sendWhatChanged();
        super.onClose();
    }

    /**
     * Sends the two edits that are "a field the player filled in" and belong to the block: the region and the name.
     *
     * <p>Each is sent only when its own fields were edited on their own page (see {@link #editedRegion} /
     * {@link #editedName}); everything that is a button - captures, releases, table rows - was already sent when it was
     * pressed. A close is not an edit.
     */
    private void sendWhatChanged() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        if (found == null) {
            return;
        }
        if (this.editedRegion) {
            PreviewRegion shown = shownRegion();
            PreviewRegion typed = typedRegionFrom(remembered(StructureScreenMemory.get(this.controller)));
            if (typed != null && !typed.equals(shown == null ? PreviewRegion.one(this.controller) : shown)) {
                send(RCNetwork.StructureEditPayload.region(this.controller, typed.min(), typed.size()));
            }
        }
        if (this.editedName) {
            String name = remembered(StructureScreenMemory.get(this.controller)).name();
            if (name != null && !name.equals(found.structureName())) {
                send(RCNetwork.StructureEditPayload.text(this.controller,
                        RCNetwork.StructureEditPayload.Verb.SET_NAME, name));
            }
        }
    }

    /**
     * The region a remembered set of fields describes, in the reading it was typed in.
     *
     * <p>{@link #typedRegion} does this from the live boxes; this does it from a memory record, which is what a close
     * from another page has to work with - the same conversion, so the two cannot disagree.
     */
    private PreviewRegion typedRegionFrom(StructureScreenMemory.Fields fields) {
        RegionEditor editor = new RegionEditor(fields.relative(),
                new Vec3i(intOr(fields.offsetX(), 0), intOr(fields.offsetY(), 0), intOr(fields.offsetZ(), 0)),
                new Vec3i(intOr(fields.sizeX(), 1), intOr(fields.sizeY(), 1), intOr(fields.sizeZ(), 1)));
        PreviewRegion typed = editor.resolve(this.controller);
        if (!isRelease()) {
            return typed;
        }
        StructureProductData pending = pendingOfController();
        // A release has no size to type: its footprint is the structure's own.
        return pending == null ? typed : new PreviewRegion(typed.min(), pending.size());
    }

    @Nullable
    private StructureProductData pendingOfController() {
        RedstoneStructureBlockEntity found = controller(this.controller);
        return found == null ? null : found.pending();
    }

    /** A remembered number, or the fallback when it is missing or was not a number. */
    private static int intOr(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
