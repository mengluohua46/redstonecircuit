package com.jiangyuefengyu.redstonecircuit.client;

import java.util.EnumSet;
import java.util.Set;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;
import com.jiangyuefengyu.redstonecircuit.logic.PowerSolver;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.RedstoneSide;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Vanilla's own model, standing in for a component stored inside a host block: a model is only ever
 * <em>named</em> ({@code minecraft:block/repeater_1tick}) and loaded from the game's own jar.
 *
 * <p>Our own three repeaters draw their own models instead, and the wrench's locked/cut markers stay
 * custom ({@link ConnectionBars}). Vanilla has no standing repeater and no ceiling torch, so those two
 * reuse the flat-authored model with a quarter turn of our own - see {@link #tilt}.
 */
@OnlyIn(Dist.CLIENT)
final class InnerComponentVisuals {

    /** Which block the model comes from: vanilla for all but our own three repeaters. */
    enum Model {
        WIRE,
        REPEATER,
        RETAINING_REPEATER,
        DOUBLE_REPEATER,
        DOUBLE_RETAINING_REPEATER,
        COMPARATOR,
        TORCH,
        WALL_TORCH,
        LEVER,
        BUTTON,
        /** 控制器: our own plate, standing in for itself inside the host block. */
        CONTROLLER,
        /** 红石变压器: our own plate again. */
        TRANSFORMER,
        /** 时钟源: our own plate; its lit face is the pulse. */
        CLOCK,
        /** 信号延长器: our own plate once more; its lit model is the display running. */
        EXTENDER,
        /** No vanilla model fits; the caller falls back to the box shapes. */
        NONE
    }

    /**
     * One component expressed in vanilla's vocabulary, with no registry access anywhere - so the
     * interesting half of the mapping can be checked by a plain unit test.
     * <p>{@code tilt} is degrees about X (0, ±90 for a diode on its back, 180 for a hanging torch);
     * {@code delay} is a repeater delay of 1-4; {@code wireSides} are the sides dust draws a line towards.
     */
    record Placement(Model model, Direction facing, @Nullable AttachFace face, float tilt, boolean lit,
                     boolean locked, int delay, boolean subtract, Set<Direction> wireSides) {
    }

    /** A vanilla model, where to put it inside the host, and the tint to draw it with. */
    record Visual(BlockState state, Matrix4f transform, float red, float green, float blue) {
    }

    /** Identity, shared: a {@link Visual} is only ever read, never mutated. */
    private static final Matrix4f NO_TILT = new Matrix4f();

    private InnerComponentVisuals() {
    }

    /**
     * What to draw for {@code slot}, or {@code null} when no vanilla model fits and the box shapes must do.
     * {@code neighbours} decides where a wire draws its lines.
     */
    @Nullable
    static Visual of(Slot slot, InnerComponentModel.Neighbours neighbours) {
        Placement placement = placementFor(slot, neighbours);
        if (placement == null || placement.model() == Model.NONE) {
            return null;
        }
        BlockState state = stateOf(placement, slot);
        if (state == null) {
            return null;
        }
        float[] tint = tintOf(slot);
        return new Visual(state, tilt(placement.tilt()), tint[0], tint[1], tint[2]);
    }

    // ------------------------------------------------------------------ decisions --

    /** The vanilla vocabulary for one component: pure - no registry, no client state, no world. */
    @Nullable
    static Placement placementFor(Slot slot, InnerComponentModel.Neighbours neighbours) {
        switch (slot.type) {
            case DUST, SUPERCONDUCTOR -> {
                // A side counts when the signal leaves that way and a component stands there to receive it.
                Set<Direction> sides = EnumSet.noneOf(Direction.class);
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    if (PowerSolver.emitsToward(slot, direction)
                            && neighbours.holdsComponent(direction)) {
                        sides.add(direction);
                    }
                }
                return new Placement(Model.WIRE, Direction.NORTH, null, 0F, false, false, 1, false,
                        sides);
            }
            case REPEATER -> {
                return diode(slot, Model.REPEATER, clampDelay(slot.delay), false);
            }
            case RETAINING_REPEATER -> {
                // Same four settings and the same look as a repeater; only the strength it hands on differs.
                return diode(slot, Model.RETAINING_REPEATER, clampDelay(slot.delay), false);
            }
            case DOUBLE_REPEATER -> {
                return diode(slot, Model.DOUBLE_REPEATER, clampDelay(slot.delay), false);
            }
            case DOUBLE_RETAINING_REPEATER -> {
                return diode(slot, Model.DOUBLE_RETAINING_REPEATER, clampDelay(slot.delay), false);
            }
            case COMPARATOR -> {
                boolean subtract =
                        slot.mode == com.jiangyuefengyu.redstonecircuit.data.ComparatorMode.SUBTRACT;
                return diode(slot, Model.COMPARATOR, 1, subtract);
            }
            case TORCH -> {
                return torch(slot);
            }
            case LEVER -> {
                return attached(Model.LEVER, slot);
            }
            case BUTTON -> {
                return attached(Model.BUTTON, slot);
            }
            case CONTROLLER -> {
                // Floor- or ceiling-mounted: a quarter turn about X as a diode is (#diode), so 正面 faces what it reads.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.CONTROLLER, Direction.SOUTH, null, tilt, slot.power > 0, false, 1,
                            false, Set.of());
                }
                return new Placement(Model.CONTROLLER, slot.facing, null, 0F, slot.power > 0, false, 1, false,
                        Set.of());
            }
            case TRANSFORMER -> {
                // The same plate and the same southern model: 变压器's 正面 is its input edge.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.TRANSFORMER, Direction.SOUTH, null, tilt, slot.power > 0, false, 1,
                            false, Set.of());
                }
                return new Placement(Model.TRANSFORMER, slot.facing, null, 0F, slot.power > 0, false, 1, false,
                        Set.of());
            }
            case CLOCK -> {
                // The same plate again; the lit flag carries the pulse - slot.power, a two-tick flash per period.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.CLOCK, Direction.SOUTH, null, tilt, slot.power > 0, false, 1,
                            false, Set.of());
                }
                return new Placement(Model.CLOCK, slot.facing, null, 0F, slot.power > 0, false, 1, false,
                        Set.of());
            }
            case EXTENDER -> {
                // The same plate once more: lit while it is answering, which is the display running.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.EXTENDER, Direction.SOUTH, null, tilt, slot.power > 0, false, 1,
                            false, Set.of());
                }
                return new Placement(Model.EXTENDER, slot.facing, null, 0F, slot.power > 0, false, 1, false,
                        Set.of());
            }
            default -> {
                // Pressure plates are not placeable yet, and no vanilla plate shape would be honest.
                return null;
            }
        }
    }

    /**
     * A diode: vanilla's {@code FACING} is the side it reads from, which is what {@link Slot#facing} means, so a
     * horizontal component maps straight onto it. A floor or ceiling mounting has no variant, so the southern
     * model is used with a quarter turn about X - south becomes down, or up.
     */
    private static Placement diode(Slot slot, Model model, int delay, boolean subtract) {
        boolean lit = slot.power > 0;
        // Vanilla's comparator has no locked property, so it never asks for that look.
        boolean locked = model != Model.COMPARATOR && slot.isRoutingLocked();
        if (slot.facing.getAxis() == Direction.Axis.Y) {
            float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
            return new Placement(model, Direction.SOUTH, null, tilt, lit, locked, delay, subtract,
                    Set.of());
        }
        return new Placement(model, slot.facing, null, 0F, lit, locked, delay, subtract, Set.of());
    }

    /**
     * A torch: lit by default, put out by whatever reaches the side it hangs on. Vanilla's torches power
     * everything except the side they hang on, so a wall torch's {@code facing} is flipped, and a ceiling
     * torch (which vanilla cannot place) is the floor torch turned upside down.
     */
    private static Placement torch(Slot slot) {
        boolean lit = slot.power > 0;
        if (slot.facing == Direction.DOWN) {
            return new Placement(Model.TORCH, Direction.NORTH, null, 0F, lit, false, 1, false, Set.of());
        }
        if (slot.facing == Direction.UP) {
            return new Placement(Model.TORCH, Direction.NORTH, null, 180F, lit, false, 1, false,
                    Set.of());
        }
        // The wall is behind the torch: vanilla's facing points away from the wall.
        return new Placement(Model.WALL_TORCH, slot.facing.getOpposite(), null, 0F, lit, false, 1, false,
                Set.of());
    }

    /**
     * A lever or a button: vanilla has floor, wall and ceiling variants of both, so all six mountings are
     * covered. A wall mounting is {@code FACE=WALL} with {@code FACING.getOpposite()}, exactly as the torch.
     */
    private static Placement attached(Model model, Slot slot) {
        boolean on = slot.powered;
        if (slot.facing == Direction.DOWN) {
            return new Placement(model, Direction.NORTH, AttachFace.FLOOR, 0F, on, false, 1, false,
                    Set.of());
        }
        if (slot.facing == Direction.UP) {
            return new Placement(model, Direction.NORTH, AttachFace.CEILING, 0F, on, false, 1, false,
                    Set.of());
        }
        return new Placement(model, slot.facing.getOpposite(), AttachFace.WALL, 0F, on, false, 1, false,
                Set.of());
    }

    // ------------------------------------------------------------- state assembly --

    /** The vanilla {@link BlockState} whose baked model is drawn; this half is the one that touches registries. */
    @Nullable
    private static BlockState stateOf(Placement placement, Slot slot) {
        switch (placement.model()) {
            case WIRE -> {
                BlockState state = Blocks.REDSTONE_WIRE.defaultBlockState()
                        .setValue(RedStoneWireBlock.POWER, PowerSolver.clamp(slot.power));
                for (Direction direction : Direction.Plane.HORIZONTAL) {
                    RedstoneSide side = placement.wireSides().contains(direction)
                            ? RedstoneSide.SIDE
                            : RedstoneSide.NONE;
                    state = state.setValue(RedStoneWireBlock.PROPERTY_BY_DIRECTION.get(direction), side);
                }
                return state;
            }
            case REPEATER -> {
                return repeaterState(Blocks.REPEATER, placement);
            }
            case RETAINING_REPEATER -> {
                return repeaterState(RCRegistry.RETAINING_REPEATER.get(), placement);
            }
            case DOUBLE_REPEATER -> {
                return repeaterState(RCRegistry.DOUBLE_REPEATER.get(), placement);
            }
            case DOUBLE_RETAINING_REPEATER -> {
                return repeaterState(RCRegistry.DOUBLE_RETAINING_REPEATER.get(), placement);
            }
            case COMPARATOR -> {
                return Blocks.COMPARATOR.defaultBlockState()
                        .setValue(DiodeBlock.FACING, placement.facing())
                        .setValue(DiodeBlock.POWERED, placement.lit())
                        .setValue(ComparatorBlock.MODE, placement.subtract()
                                ? net.minecraft.world.level.block.state.properties.ComparatorMode.SUBTRACT
                                : net.minecraft.world.level.block.state.properties.ComparatorMode.COMPARE);
            }
            case TORCH -> {
                return Blocks.REDSTONE_TORCH.defaultBlockState()
                        .setValue(RedstoneTorchBlock.LIT, placement.lit());
            }
            case WALL_TORCH -> {
                return Blocks.REDSTONE_WALL_TORCH.defaultBlockState()
                        .setValue(WallTorchBlock.FACING, placement.facing())
                        .setValue(RedstoneTorchBlock.LIT, placement.lit());
            }
            case LEVER -> {
                return Blocks.LEVER.defaultBlockState()
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, placement.face())
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, placement.facing())
                        .setValue(LeverBlock.POWERED, placement.lit());
            }
            case BUTTON -> {
                // Retrieving a button always hands back a stone one, so the stone model is honest; see HostRules#itemFor.
                return Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, placement.face())
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, placement.facing())
                        .setValue(ButtonBlock.POWERED, placement.lit());
            }
            case CONTROLLER -> {
                // Our own block's model: the plate is what the component inside a block really is.
                return RCRegistry.CONTROLLER.get().defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.FACING, placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.LIT, placement.lit());
            }
            case TRANSFORMER -> {
                // Only facing and LIT reach the model: the two lit models are what the answer selects.
                return RCRegistry.TRANSFORMER.get().defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.FACING, placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.LIT, placement.lit());
            }
            case CLOCK -> {
                // And the same for 时钟源, whose 上-亮 texture is what its model does with LIT.
                return RCRegistry.CLOCK.get().defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ClockBlock.FACING, placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ClockBlock.LIT, placement.lit());
            }
            case EXTENDER -> {
                // And for 信号延长器, whose lit model is the display with the marker running in it.
                return RCRegistry.EXTENDER.get().defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.FACING, placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.LIT, placement.lit());
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The state of a repeater-shaped block, vanilla's or one of ours: all four carry the same properties,
     * so one builder serves them and only the model that ends up being drawn differs.
     */
    private static BlockState repeaterState(Block block, Placement placement) {
        return block.defaultBlockState()
                .setValue(DiodeBlock.FACING, placement.facing())
                .setValue(DiodeBlock.POWERED, placement.lit())
                .setValue(RepeaterBlock.DELAY, placement.delay())
                .setValue(RepeaterBlock.LOCKED, placement.locked());
    }

    // ------------------------------------------------------------------- geometry --

    /**
     * A rotation about the block's centre: the models are authored with the block's corner at the origin,
     * and turning them about the corner would swing them out of the block.
     */
    private static Matrix4f tilt(float degrees) {
        if (degrees == 0F) {
            return NO_TILT;
        }
        return new Matrix4f()
                .translate(0.5F, 0.5F, 0.5F)
                .rotateX((float) Math.toRadians(degrees))
                .translate(-0.5F, -0.5F, -0.5F);
    }

    /**
     * The tint vanilla's own block colour provider would have applied: only dust (a greyscale texture) and
     * the superconductor are tinted, with vanilla's own ramp, so inner dust matches the dust outside the
     * block. Everything else draws at full brightness.
     */
    private static float[] tintOf(Slot slot) {
        if (slot.type != ComponentType.DUST && slot.type != ComponentType.SUPERCONDUCTOR) {
            return new float[] { 1F, 1F, 1F };
        }
        int argb = InnerComponentModel.wireColor(slot.type, slot.power);
        return new float[] {
                (argb >> 16 & 0xFF) / 255F,
                (argb >> 8 & 0xFF) / 255F,
                (argb & 0xFF) / 255F,
        };
    }

    private static int clampDelay(int delay) {
        return Math.max(1, Math.min(4, delay));
    }
}
