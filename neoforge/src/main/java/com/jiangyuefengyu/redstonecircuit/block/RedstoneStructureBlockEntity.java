package com.jiangyuefengyu.redstonecircuit.block;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.ActivationTable;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PayloadHeader;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 红石结构方块 - the controller: which mode it is in, what region it is showing, and what it is holding.
 * <p>The region, the mode and the three tables live in the block entity rather than in a menu, because
 * closing the screen, walking away or a chunk unload must <b>not</b> end a preview; the preview is one box
 * plus one flag, and it is not in the sync tag because a border is visible to everyone in the dimension.
 * <p>{@link #getUpdateTag} carries only this small state: the player edits the tables here and a capture
 * writes them into the payload, and the product block reads them back out.
 */
public class RedstoneStructureBlockEntity extends BlockEntity {

    /** The controller's two jobs. */
    public enum Mode {
        CAPTURE,
        RELEASE
    }

    /** Whether a capture takes the region away or copies it. Survival is forced to {@link #CUT}. */
    public enum Tier {
        CUT,
        COPY
    }

    private Mode mode = Mode.CAPTURE;
    private Tier tier = Tier.CUT;
    private IoMode ioMode = IoMode.ACTIVATION_FACES;
    /** The box this controller is showing, or null when it is showing nothing. One box, not two
     * corners: every edit is a new rectangle with the same meaning, so it cannot disagree with itself. */
    @Nullable
    private PreviewRegion preview;
    /** Whether the preview has been confirmed once - the difference between the first and the second
     * operation. Not persisted: a preview that comes back after a chunk unload comes back unarmed. */
    private boolean previewArmed;
    private String name = PayloadHeader.DEFAULT_NAME;
    @Nullable
    private StructureProductData pending;
    /** Mode A: which cells each energized face pushes into. A face that is absent does nothing. */
    private Map<Direction, List<ActivationTable.FaceEntry>> faces = Map.of();
    /** Mode B: which cell each key pushes into. An empty panel is a structure with no keys. */
    private List<ActivationTable.ManualEntry> manual = List.of();
    /** The output table: which cell makes which face answer with how much. Edited here like the other
     * two, and it is the only table whose direction is "inside tells the outside". */
    private List<ActivationTable.OutputEntry> output = List.of();

    public RedstoneStructureBlockEntity(BlockPos pos, BlockState state) {
        super(RCRegistry.REDSTONE_STRUCTURE_BLOCK_ENTITY.get(), pos, state);
    }

    /** The structure this controller is holding - the product stays in the controller's slot until the
     * player takes it out by hand. The id and description rather than an {@code ItemStack}, so there is
     * only ever one copy of these facts to disagree with the file. */
    @Nullable
    public StructureProductData pending() {
        return this.pending;
    }

    public void setPending(@Nullable StructureProductData data) {
        this.pending = data;
        this.markDirtyAndSync();
    }

    public Mode mode() {
        return this.mode;
    }

    /** The six activation tables, as the capture will write them. Immutable, so a caller cannot edit
     * the controller's state by accident; a face with no entries is simply absent. */
    public Map<Direction, List<ActivationTable.FaceEntry>> faces() {
        return this.faces;
    }

    /** Adds or replaces one cell on one face. Replaces, because two entries for one cell would be two
     * strengths for it, and the ledger's "the stronger one" is a rule the player cannot see. */
    public void setFaceEntry(Direction face, BlockPos localPos, int strength) {
        List<ActivationTable.FaceEntry> entries = new ArrayList<>(ActivationTable.entries(this.faces, face));
        entries.removeIf(entry -> entry.localPos().equals(localPos));
        entries.add(new ActivationTable.FaceEntry(localPos, strength));
        Map<Direction, List<ActivationTable.FaceEntry>> updated = copyOfFaces();
        updated.put(face, List.copyOf(entries));
        this.faces = Map.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Empties one face. The design's "不填的面无效": an empty face is a face that does nothing. */
    public void clearFace(Direction face) {
        if (ActivationTable.entries(this.faces, face).isEmpty()) {
            return;
        }
        Map<Direction, List<ActivationTable.FaceEntry>> updated = copyOfFaces();
        updated.remove(face);
        this.faces = Map.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Removes one cell from one face, by the cell rather than by an index: other edits reorder the
     * list, and a player who clicks a row means that row's cell. */
    public void clearFaceEntry(Direction face, BlockPos localPos) {
        List<ActivationTable.FaceEntry> entries = new ArrayList<>(ActivationTable.entries(this.faces, face));
        if (!entries.removeIf(entry -> entry.localPos().equals(localPos))) {
            return;
        }
        Map<Direction, List<ActivationTable.FaceEntry>> updated = copyOfFaces();
        if (entries.isEmpty()) {
            updated.remove(face);
        } else {
            updated.put(face, List.copyOf(entries));
        }
        this.faces = Map.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Mode B's panel: one entry per key, each naming a cell and a strength. */
    public List<ActivationTable.ManualEntry> manual() {
        return this.manual;
    }

    /** Adds or replaces one panel entry, matched on the <b>key and the cell</b> - the pair a player
     * means. Two entries on one key and two on one cell are both legal, so neither alone identifies it. */
    public void setManualEntry(String key, BlockPos localPos, int strength, String label) {
        ActivationTable.ManualEntry entry = new ActivationTable.ManualEntry(key, localPos, strength, label);
        List<ActivationTable.ManualEntry> updated = new ArrayList<>(this.manual);
        updated.removeIf(existing -> existing.key().equals(entry.key())
                && existing.localPos().equals(entry.localPos()));
        updated.add(entry);
        this.manual = List.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Removes one panel entry by the identity an entry has: the key <b>and</b> the cell. Not by index,
     * which is a position in a list that other edits reorder. */
    public void clearManualEntry(String key, BlockPos localPos) {
        List<ActivationTable.ManualEntry> updated = new ArrayList<>(this.manual);
        if (!updated.removeIf(existing -> existing.key().equals(key) && existing.localPos().equals(localPos))) {
            return;
        }
        this.manual = List.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Empties the panel: a structure with no keys. */
    public void clearManual() {
        if (this.manual.isEmpty()) {
            return;
        }
        this.manual = List.of();
        this.markDirtyAndSync();
    }

    /** The output table, as a capture will write it. A list rather than a map keyed by face: several
     * cells may watch one face, and the order the player added the rows in is the order shown back. */
    public List<ActivationTable.OutputEntry> output() {
        return this.output;
    }

    /** Adds or replaces one row, matched on <b>the cell and the face</b>: one cell watching two faces is
     * legal, and two strengths on one face is what the "stronger one wins" rule resolves. */
    public void setOutputEntry(BlockPos localPos, Direction face, int strength) {
        ActivationTable.OutputEntry entry = new ActivationTable.OutputEntry(localPos, face, strength);
        List<ActivationTable.OutputEntry> updated = new ArrayList<>(this.output);
        updated.removeIf(existing -> existing.localPos().equals(entry.localPos())
                && existing.face() == entry.face());
        updated.add(entry);
        this.output = List.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Removes one row by the pair that identifies it: the cell and the face. */
    public void clearOutputEntry(BlockPos localPos, Direction face) {
        List<ActivationTable.OutputEntry> updated = new ArrayList<>(this.output);
        if (!updated.removeIf(existing -> existing.localPos().equals(localPos) && existing.face() == face)) {
            return;
        }
        this.output = List.copyOf(updated);
        this.markDirtyAndSync();
    }

    /** Empties the output table: a structure that answers with nothing on any face. */
    public void clearOutput() {
        if (this.output.isEmpty()) {
            return;
        }
        this.output = List.of();
        this.markDirtyAndSync();
    }

    /** A mutable copy of the table to edit. Built by class, because {@code new EnumMap<>(Map)} refuses
     * an <b>empty</b> source map - which is exactly what the first cell a player fills starts from. */
    private EnumMap<Direction, List<ActivationTable.FaceEntry>> copyOfFaces() {
        EnumMap<Direction, List<ActivationTable.FaceEntry>> copy = new EnumMap<>(Direction.class);
        copy.putAll(this.faces);
        return copy;
    }

    public Tier tier() {
        return this.tier;
    }

    public IoMode ioMode() {
        return this.ioMode;
    }

    public String structureName() {
        return this.name;
    }

    /** True while a region is being previewed - the state the border is drawn from. */
    public boolean isPreviewing() {
        return this.preview != null;
    }

    /** The box being previewed, or null. */
    @Nullable
    public PreviewRegion preview() {
        return this.preview;
    }

    /** Whether the preview has had its first operation and is one operation away from happening. */
    public boolean isPreviewArmed() {
        return this.preview != null && this.previewArmed;
    }

    /** The selection's first cell, or null while there is no preview. */
    @Nullable
    public BlockPos cornerA() {
        return this.preview == null ? null : this.preview.min();
    }

    /** The selection's last cell, or null while there is no preview. */
    @Nullable
    public BlockPos cornerB() {
        return this.preview == null ? null : this.preview.max();
    }

    /** The selection's size, or 1×1×1 while there is no selection. */
    public Vec3i regionSize() {
        return this.preview == null ? new Vec3i(1, 1, 1) : this.preview.size();
    }

    public void setMode(Mode mode) {
        if (this.mode == mode) {
            return;
        }
        this.mode = mode;
        // Switching the mode ends a preview: a border left up for a capture would lie about the next operation.
        if (this.preview != null) {
            endPreview();
        }
        this.markDirtyAndSync();
    }

    public void setTier(Tier tier) {
        this.tier = tier;
        this.markDirtyAndSync();
    }

    public void setIoMode(IoMode ioMode) {
        this.ioMode = ioMode;
        this.markDirtyAndSync();
    }

    public void setName(String name) {
        this.name = name == null || name.isEmpty() ? PayloadHeader.DEFAULT_NAME : name;
        this.markDirtyAndSync();
    }

    /** Shows {@code region}, or stops showing anything when it is null - the one way in, for the
     * commands and the screen alike. Editing the region <b>disarms</b> the preview, so "生效" can never
     * be reached from a rectangle the player has not looked at since it changed. */
    public void setPreview(@Nullable PreviewRegion region) {
        this.preview = region;
        this.previewArmed = false;
        if (region == null) {
            StructurePreviews.withdraw(this.level, this.worldPosition);
        } else {
            StructurePreviews.publish(this.level, this.worldPosition);
        }
        this.markDirtyAndSync();
    }

    /** The first operation's effect: this preview may now be the one that happens. */
    public void armPreview() {
        if (this.preview == null || this.previewArmed) {
            return;
        }
        this.previewArmed = true;
        StructurePreviews.publish(this.level, this.worldPosition);
        this.markDirtyAndSync();
    }

    /** Stops showing the border. A capture or release that really happened is the other way a preview
     * ends, and this one name covers all of them. */
    public void endPreview() {
        this.preview = null;
        this.previewArmed = false;
        StructurePreviews.withdraw(this.level, this.worldPosition);
        this.markDirtyAndSync();
    }

    /** Moves one corner of the selection; both have to be set before the preview starts. Kept as the
     * two-corner spelling the tests and the older commands use. */
    public void setCorner(boolean second, BlockPos pos) {
        if (!second) {
            setPreview(PreviewRegion.one(pos));
        } else {
            BlockPos min = this.preview == null ? pos : this.preview.min();
            setPreview(PreviewRegion.of(min, pos));
        }
    }

    /** Stops previewing. Named for what the capture path does with it: the selection is gone with the take. */
    public void clearSelection() {
        endPreview();
    }

    private void markDirtyAndSync() {
        this.setChanged();
        if (this.level != null && !this.level.isClientSide) {
            BlockState state = this.getBlockState();
            this.level.sendBlockUpdated(this.worldPosition, state, state, 3);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("mode", this.mode.name());
        tag.putString("tier", this.tier.name());
        tag.putString("io_mode", this.ioMode.getSerializedName());
        tag.putString("name", this.name);
        if (this.preview != null) {
            tag.put("preview", this.preview.save());
        }
        putPending(tag);
        putFaces(tag);
        putManual(tag);
        putOutput(tag);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.mode = readEnum(Mode.class, tag.getString("mode"), Mode.CAPTURE);
        this.tier = readEnum(Tier.class, tag.getString("tier"), Tier.CUT);
        this.ioMode = readIoMode(tag.getString("io_mode"));
        this.name = tag.contains("name") ? tag.getString("name") : PayloadHeader.DEFAULT_NAME;
        this.preview = tag.contains("preview")
                ? PreviewRegion.load(tag.getCompound("preview"))
                : legacySelection(tag);
        this.previewArmed = false;
        this.pending = tag.contains("pending")
                ? StructureProductData.CODEC.parse(NbtOps.INSTANCE, tag.get("pending")).result().orElse(null)
                : null;
        this.faces = tag.contains("faces")
                ? ActivationTable.TABLE_CODEC.parse(NbtOps.INSTANCE, tag.get("faces")).result().orElse(Map.of())
                : Map.of();
        this.manual = tag.contains("manual")
                ? ActivationTable.ManualEntry.CODEC.listOf().parse(NbtOps.INSTANCE, tag.get("manual"))
                        .result().orElse(List.of())
                : List.of();
        this.output = tag.contains("output")
                ? ActivationTable.OUTPUT_CODEC.parse(NbtOps.INSTANCE, tag.get("output"))
                        .result().orElse(List.of())
                : List.of();
    }

    /** What a client is told: the mode, the tier, the io mode, the name and the tables. Small on
     * purpose - a preview, not a structure, is what travels between the server and a client. */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("mode", this.mode.name());
        tag.putString("tier", this.tier.name());
        tag.putString("io_mode", this.ioMode.getSerializedName());
        tag.putString("name", this.name);
        putPending(tag);
        putFaces(tag);
        putManual(tag);
        putOutput(tag);
        return tag;
    }

    /** The activation tables, written the way the payload writes them: one codec for both, so a table
     * cannot be a table in a file and something else in a block entity, and the screen phase can read
     * what it has to draw straight out of the sync tag. */
    private void putFaces(CompoundTag tag) {
        if (this.faces.isEmpty()) {
            return;
        }
        ActivationTable.TABLE_CODEC.encodeStart(NbtOps.INSTANCE, this.faces).result()
                .ifPresent(encoded -> tag.put("faces", encoded));
    }

    /** The manual panel, in the payload's own format - see {@link #putFaces}. */
    private void putManual(CompoundTag tag) {
        if (this.manual.isEmpty()) {
            return;
        }
        ActivationTable.ManualEntry.CODEC.listOf().encodeStart(NbtOps.INSTANCE, this.manual).result()
                .ifPresent(encoded -> tag.put("manual", encoded));
    }

    /** The output table, in the payload's own format - see {@link #putFaces}. */
    private void putOutput(CompoundTag tag) {
        if (this.output.isEmpty()) {
            return;
        }
        ActivationTable.OUTPUT_CODEC.encodeStart(NbtOps.INSTANCE, this.output).result()
                .ifPresent(encoded -> tag.put("output", encoded));
    }

    /** The pending structure is a handful of fields, so it goes in the sync tag as well as in the save. */
    private void putPending(CompoundTag tag) {
        if (this.pending != null) {
            StructureProductData.CODEC.encodeStart(NbtOps.INSTANCE, this.pending).result()
                    .ifPresent(encoded -> tag.put("pending", encoded));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** A controller that is loaded again and is still previewing has to be visible again: a chunk
     * unloading or a player re-logging in does not end a preview, which is why the region is in the save
     * file at all. {@link #setRemoved()} is the other half - while the chunk is gone the controller
     * leaves the per-dimension list the clients are told from. */
    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (this.preview != null) {
            StructurePreviews.publish(level, this.worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        StructurePreviews.withdraw(this.level, this.worldPosition);
        super.setRemoved();
    }

    /** The selection as an earlier test package wrote it: two corners, no region. */
    @Nullable
    private static PreviewRegion legacySelection(CompoundTag tag) {
        BlockPos a = readPos(tag, "corner_a");
        BlockPos b = readPos(tag, "corner_b");
        return a == null || b == null ? null : PreviewRegion.of(a, b);
    }

    @Nullable
    private static BlockPos readPos(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        return BlockPos.CODEC.parse(NbtOps.INSTANCE, tag.get(key)).result().orElse(null);
    }

    /** An unknown name is the default rather than a crash: a save from a build that knew more. */
    private static <E extends Enum<E>> E readEnum(Class<E> type, String name, E fallback) {
        for (E value : type.getEnumConstants()) {
            if (value.name().equals(name)) {
                return value;
            }
        }
        return fallback;
    }

    private static IoMode readIoMode(String name) {
        for (IoMode mode : IoMode.values()) {
            if (mode.getSerializedName().equals(name)) {
                return mode;
            }
        }
        return IoMode.ACTIVATION_FACES;
    }
}
