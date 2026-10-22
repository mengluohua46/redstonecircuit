package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * The one place a blueprint's inner-redstone section is written and read: a plain NBT codec with no level, no loader
 * and no Create type in it.
 *
 * <p>Create's blueprint is a vanilla {@code StructureTemplate}, and {@code StructureTemplate#load} reads four keys and
 * ignores everything else, so a section of our own at the top level is invisible to it. That is the whole reason this
 * format exists; it also means a blueprint written by this mod stays a blueprint for anyone who does not have it.
 *
 * <p>The section carries the component tables and nothing the blueprint already carries: no block states, no palette,
 * no indices, no anchor, no rotation - the file's own block list is the only answer to "what stands here", and the two
 * item components answer the rest. Positions are relative to the box's minimum corner and <b>unrotated</b>, which is
 * the same frame the file's own {@code blocks[].pos} uses.
 *
 * <p>Each entry reuses the shape that component's own save already writes, so the world save, the wire and this file
 * cannot drift apart: a node is {@code InnerRedstoneNode#save}'s {@code component} compound, and a 时钟源 or 信号延长器
 * is its {@code Setting#save} fields, which do not collide with the entry's own {@code p}.
 */
public final class BlueprintComponents {

    /** The top-level key this mod owns. Namespaced so no other add-on is likely to pick the same one. */
    public static final String KEY = "redstonecircuit";

    /** Layout version of the section, independent of the file's own {@code DataVersion}. */
    public static final int VERSION = 1;

    static final String VERSION_KEY = "v";
    static final String SIZE = "size";
    static final String NODES = "nodes";
    static final String CLOCKS = "clocks";
    static final String EXTENDERS = "extenders";
    static final String POS = "p";
    static final String COMPONENT = "component";

    /** One host block's component: {@code p} plus the shape {@link InnerRedstoneNode#save()} already writes. */
    public record Node(BlockPos pos, InnerRedstoneNode node) {
    }

    /** One 时钟源, as its own tag shape {@link ClockRule.Setting#save} already writes. */
    public record Clock(BlockPos pos, ClockRule.Setting setting) {
    }

    /** One 信号延长器, as {@link ExtenderRule.Setting#save} already writes it. */
    public record Extender(BlockPos pos, ExtenderRule.Setting setting) {
    }

    /** A decoded section: the box it was taken from and everything stored inside it. */
    public record Section(Vec3i size, List<Node> nodes, List<Clock> clocks, List<Extender> extenders) {

        public int entryCount() {
            return nodes.size() + clocks.size() + extenders.size();
        }
    }

    private BlueprintComponents() {
    }

    /** Writes one section. Positions must already be relative to the box's minimum corner. */
    public static CompoundTag write(Vec3i size, List<Node> nodes, List<Clock> clocks, List<Extender> extenders) {
        CompoundTag section = new CompoundTag();
        section.putInt(VERSION_KEY, VERSION);
        section.putIntArray(SIZE, new int[] {size.getX(), size.getY(), size.getZ()});

        ListTag nodeList = new ListTag();
        for (Node entry : nodes) {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray(POS, xyz(entry.pos()));
            tag.merge(entry.node().save());
            nodeList.add(tag);
        }
        section.put(NODES, nodeList);

        ListTag clockList = new ListTag();
        for (Clock entry : clocks) {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray(POS, xyz(entry.pos()));
            entry.setting().save(tag);
            clockList.add(tag);
        }
        section.put(CLOCKS, clockList);

        ListTag extenderList = new ListTag();
        for (Extender entry : extenders) {
            CompoundTag tag = new CompoundTag();
            tag.putIntArray(POS, xyz(entry.pos()));
            entry.setting().save(tag);
            extenderList.add(tag);
        }
        section.put(EXTENDERS, extenderList);
        return section;
    }

    /**
     * Reads one section back, or {@code null} for every reason a reader may not use it: an unknown version, a size that
     * disagrees with the file's own, a missing key, a wrong tag type, a position outside the box.
     *
     * <p>{@code null} rather than an exception is the whole contract: this is other people's data, arriving in a file
     * that has to stay usable whether or not we understand it, so every failure is a value. It is also why the checks
     * are written out instead of relying on the NBT accessors' own tolerance - a list of integers where a list of
     * compounds belongs reads as an empty list, and an empty list of components is a different statement from a
     * damaged one.
     */
    @Nullable
    public static Section read(CompoundTag section, Vec3i fileSize) {
        if (section == null || fileSize == null) {
            return null;
        }
        if (!(section.get(VERSION_KEY) instanceof IntTag version) || version.getAsInt() != VERSION) {
            return null;
        }
        if (!(section.get(SIZE) instanceof IntArrayTag declared)) {
            return null;
        }
        int[] size = declared.getAsIntArray();
        if (size.length != 3) {
            return null;
        }
        if (size[0] != fileSize.getX() || size[1] != fileSize.getY() || size[2] != fileSize.getZ()) {
            return null;
        }
        Vec3i box = new Vec3i(size[0], size[1], size[2]);
        ListTag nodeList = list(section, NODES);
        ListTag clockList = list(section, CLOCKS);
        ListTag extenderList = list(section, EXTENDERS);
        if (nodeList == null || clockList == null || extenderList == null) {
            return null;
        }

        List<Node> nodes = new ArrayList<>(nodeList.size());
        for (int i = 0; i < nodeList.size(); i++) {
            CompoundTag entry = entryAt(nodeList, i, box);
            if (entry == null || !(entry.get(COMPONENT) instanceof CompoundTag)) {
                return null;
            }
            InnerRedstoneNode node = InnerRedstoneNode.load(entry);
            if (node.isEmpty()) {
                return null;
            }
            nodes.add(new Node(positionOf(entry), node));
        }

        List<Clock> clocks = new ArrayList<>(clockList.size());
        for (int i = 0; i < clockList.size(); i++) {
            CompoundTag entry = entryAt(clockList, i, box);
            if (entry == null) {
                return null;
            }
            clocks.add(new Clock(positionOf(entry), ClockRule.Setting.load(entry)));
        }

        List<Extender> extenders = new ArrayList<>(extenderList.size());
        for (int i = 0; i < extenderList.size(); i++) {
            CompoundTag entry = entryAt(extenderList, i, box);
            if (entry == null) {
                return null;
            }
            extenders.add(new Extender(positionOf(entry), ExtenderRule.Setting.load(entry)));
        }
        return new Section(box, nodes, clocks, extenders);
    }

    /** Reads the section out of a whole blueprint, checking it against that file's own {@code size}. */
    @Nullable
    public static Section readFile(CompoundTag blueprint) {
        if (blueprint == null || !(blueprint.get(KEY) instanceof CompoundTag section)) {
            return null;
        }
        ListTag sizeTag = blueprint.getList(SIZE, Tag.TAG_INT);
        if (sizeTag.size() != 3) {
            return null;
        }
        return read(section, new Vec3i(sizeTag.getInt(0), sizeTag.getInt(1), sizeTag.getInt(2)));
    }

    // ---------------------------------------------------------------- helpers --

    /** One list, or {@code null} when the key is absent or is anything else. */
    @Nullable
    private static ListTag list(CompoundTag section, String key) {
        return section.get(key) instanceof ListTag list ? list : null;
    }

    /**
     * One entry of a table: a compound carrying a position inside the box. {@code null} for anything else, including a
     * position outside it - a cell that does not exist in this blueprint could only ever be a write into nothing.
     */
    @Nullable
    private static CompoundTag entryAt(ListTag list, int index, Vec3i box) {
        if (!(list.get(index) instanceof CompoundTag entry) || !(entry.get(POS) instanceof IntArrayTag array)) {
            return null;
        }
        int[] position = array.getAsIntArray();
        if (position.length != 3) {
            return null;
        }
        if (position[0] < 0 || position[0] >= box.getX() || position[1] < 0 || position[1] >= box.getY()
                || position[2] < 0 || position[2] >= box.getZ()) {
            return null;
        }
        return entry;
    }

    /** The position of an entry already validated by {@link #entryAt}. */
    private static BlockPos positionOf(CompoundTag entry) {
        int[] position = ((IntArrayTag) entry.get(POS)).getAsIntArray();
        return new BlockPos(position[0], position[1], position[2]);
    }

    private static int[] xyz(BlockPos pos) {
        return new int[] {pos.getX(), pos.getY(), pos.getZ()};
    }
}
