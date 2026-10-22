package com.jiangyuefengyu.redstonecircuit.blueprint;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.ClockRule;
import com.jiangyuefengyu.redstonecircuit.logic.ExtenderRule;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Turns a blueprint's own coordinates and orientations into the world's.
 *
 * <p>A section is stored in the frame the file's {@code blocks[].pos} uses: relative to the box's minimum corner and
 * <b>unrotated</b>. Rotation and mirror are not in the file at all - they are item components on the blueprint stack -
 * so this is the only place the two frames meet, and it happens <b>once</b>, when the blueprint is loaded. Everything
 * downstream (the landing hook, the compensation pass) looks world positions up in a map and never transforms again;
 * that is what keeps the half-cell error of a float transform, and the sign errors of a hand-written one, out of a
 * loop that runs per block.
 *
 * <p>Both halves of the transform are vanilla's own, by name, because both are exactly the mappings a vanilla block
 * state goes through when a structure is placed: {@code StructureTemplate#transform} for positions and
 * {@code Mirror#mirror} followed by {@code Rotation#rotate} for directions - the same order vanilla's
 * {@code placeInWorld} uses, and getting that order backwards is what mirrors the handedness of every repeater.
 */
public final class BlueprintPlacement {

    /**
     * One blueprint's components, already in world coordinates: the only thing the landing hook is allowed to see.
     *
     * <p>Three tables rather than one map of a tagged entry, because the three are written back differently and a
     * table that only ever holds one kind cannot be read as the wrong kind.
     */
    public record Budget(Map<BlockPos, Slot> nodes, Map<BlockPos, ClockRule.Setting> clocks,
                         Map<BlockPos, ExtenderRule.Setting> extenders) {

        public boolean isEmpty() {
            return this.nodes.isEmpty() && this.clocks.isEmpty() && this.extenders.isEmpty();
        }

        public int size() {
            return this.nodes.size() + this.clocks.size() + this.extenders.size();
        }
    }

    private BlueprintPlacement() {
    }

    /** The world position of one relative cell of a blueprint deployed with its minimum corner at {@code anchor}. */
    public static BlockPos toWorld(BlockPos anchor, Rotation rotation, Mirror mirror, BlockPos relative) {
        // The pivot is the origin, not the anchor: vanilla's own place path adds the anchor afterwards, and passing
        // the anchor as the pivot would apply it twice.
        return anchor.offset(StructureTemplate.transform(relative, mirrorOrNone(mirror), rotationOrNone(rotation),
                BlockPos.ZERO));
    }

    /** The world direction of one of a component's own directions, as a placed block state would be turned. */
    public static Direction toWorldDirection(Direction direction, Rotation rotation, Mirror mirror) {
        return rotationOrNone(rotation).rotate(mirrorOrNone(mirror).mirror(direction));
    }

    /**
     * One component's whole stored state in world terms: its orientation, and both sets of wrench locks.
     *
     * <p>The locks are turned rather than dropped. A lock is what the player meant by "and nothing else", so a
     * blueprint that lost it would come back as a differently wired machine - the one failure that looks like the
     * blueprint worked.
     */
    public static Slot toWorldSlot(Slot slot, Rotation rotation, Mirror mirror) {
        Slot out = slot.copy();
        out.facing = toWorldDirection(slot.facing, rotation, mirror);
        out.forcedOn.clear();
        for (Direction direction : slot.forcedOn) {
            out.forcedOn.add(toWorldDirection(direction, rotation, mirror));
        }
        out.forcedOff.clear();
        for (Direction direction : slot.forcedOff) {
            out.forcedOff.add(toWorldDirection(direction, rotation, mirror));
        }
        return out;
    }

    /**
     * A whole section in world coordinates.
     *
     * <p>A cell outside the section's own box is skipped here as well as on the way in: the reader already refuses
     * such a file, so this is the guard for a section built in code - and a cell outside the box has no world
     * position that could be right, since the blueprint has no block there.
     */
    public static Budget budget(BlueprintComponents.Section section, BlockPos anchor, Rotation rotation,
                                Mirror mirror) {
        Vec3i box = section.size();
        Map<BlockPos, Slot> nodes = new LinkedHashMap<>();
        for (BlueprintComponents.Node entry : section.nodes()) {
            if (inside(box, entry.pos())) {
                nodes.put(toWorld(anchor, rotation, mirror, entry.pos()),
                        toWorldSlot(entry.node().slot(), rotation, mirror));
            }
        }
        Map<BlockPos, ClockRule.Setting> clocks = new LinkedHashMap<>();
        for (BlueprintComponents.Clock entry : section.clocks()) {
            if (inside(box, entry.pos())) {
                clocks.put(toWorld(anchor, rotation, mirror, entry.pos()), entry.setting());
            }
        }
        Map<BlockPos, ExtenderRule.Setting> extenders = new LinkedHashMap<>();
        for (BlueprintComponents.Extender entry : section.extenders()) {
            if (inside(box, entry.pos())) {
                extenders.put(toWorld(anchor, rotation, mirror, entry.pos()), entry.setting());
            }
        }
        return new Budget(nodes, clocks, extenders);
    }

    private static boolean inside(Vec3i box, BlockPos pos) {
        return pos.getX() >= 0 && pos.getX() < box.getX()
                && pos.getY() >= 0 && pos.getY() < box.getY()
                && pos.getZ() >= 0 && pos.getZ() < box.getZ();
    }

    private static Rotation rotationOrNone(@Nullable Rotation rotation) {
        return rotation == null ? Rotation.NONE : rotation;
    }

    private static Mirror mirrorOrNone(@Nullable Mirror mirror) {
        return mirror == null ? Mirror.NONE : mirror;
    }
}
