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

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

/**
 * The <b>vanilla</b> model that stands in for a component stored inside a host block.
 *
 * <h2>Why vanilla models rather than our own shapes</h2>
 * The first version of the mod drew every component as a handful of coloured boxes
 * ({@link InnerComponentModel}). It was exact and it worked, but it looked like what it was - flat
 * coloured plastic next to pixel art - and players said so.
 *
 * <p>Every component we support happens to be a vanilla block as well, so the honest answer is to draw
 * vanilla's own model: the repeater's slab and torches, the comparator's side pips, the torch's stick,
 * the lever's handle, dust's dot-and-lines - textures, geometry and all. Nothing is copied: a model is
 * only ever <em>named</em> ({@code minecraft:block/repeater_1tick}), and the game loads it out of its
 * own jar.
 *
 * <p>The three repeaters added in 1.1.0 are the exception, and the reason this class now names a couple
 * of our own blocks as well: a retaining repeater that looked exactly like a vanilla one inside a host
 * block would be a lie, so those draw their own models - the player's own art - in the world and inside
 * a host block alike. The controller, the transformer, the clock source and the signal extender are the
 * same case: each is a device whose plate is its own model, so the component inside a block is drawn from
 * the very block the player would have placed.
 *
 * <h2>How a state is obtained without placing a block</h2>
 * A component is mapped onto a real vanilla {@link BlockState}, and
 * {@code BlockModelShaper#getBlockModel} hands back the baked model for it. That includes the
 * variant's rotation, because a variant's {@code ModelState} is applied when the model is baked - so
 * a repeater facing any horizontal direction needs no matrix from us at all. Rendering is then just
 * "translate to the host block, hand the model to {@code ModelBlockRenderer#renderModel}", with the
 * tint that vanilla's own block colour provider would have supplied.
 *
 * <h2>The two cases vanilla cannot express</h2>
 * A host block can hold a component on its floor, its ceiling or a wall, and our diodes and torches
 * accept all six. Vanilla has no standing repeater (its {@code FACING} is horizontal only) and no
 * ceiling torch, so those two cases reuse the flat-authored model with a quarter turn of our own -
 * see {@link #tilt}. Buttons and levers do have ceiling variants, so they need no such help.
 *
 * <h2>What is deliberately still ours</h2>
 * Vanilla has no notion of a connection being pinned or cut, so the wrench's green "locked" and dark
 * "cut" markers stay custom - see {@link ConnectionBars}. The repeater's own vanilla "locked" look
 * ({@code repeater_*_locked}) is switched on whenever the wrench has locked one of its sides, so the
 * state reads in vanilla's own visual language as well.
 */
@Environment(EnvType.CLIENT)
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
        /** 控制器: our own device plate, standing in for itself inside the host block. */
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
     *
     * @param model      which vanilla block to borrow
     * @param facing     the {@code facing} property, already resolved for this model
     * @param face       the {@code face} property (levers and buttons); {@code null} elsewhere
     * @param tilt       degrees of rotation about the block's X axis for the cases vanilla cannot
     *                   express: 0, ±90 (a diode lying on its back) or 180 (a torch hanging)
     * @param lit        torch: is it lit; diode: is it powered; switch: is it thrown
     * @param locked     repeater: has the wrench locked one of its sides (vanilla's "locked" look)
     * @param delay      repeater delay, 1-4
     * @param subtract   comparator: subtract mode rather than compare
     * @param wireSides  dust: the horizontal sides a line is drawn towards
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
     * What to draw for {@code slot}, or {@code null} when no vanilla model fits and the caller should
     * fall back to the box shapes.
     *
     * @param neighbours which neighbouring blocks hold a component, which is what decides where a wire
     *                   draws its lines
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

    /**
     * The vanilla vocabulary for one component.
     *
     * <p>Pure: no registries, no client state, no world. {@code Direction}, {@link AttachFace} and
     * {@link RedstoneSide} are the only vanilla types involved, and all three are plain enums.
     */
    @Nullable
    static Placement placementFor(Slot slot, InnerComponentModel.Neighbours neighbours) {
        switch (slot.type) {
            case DUST, SUPERCONDUCTOR -> {
                // Vanilla draws a wire out of one flat "dot" quad plus one line quad per side it
                // connects to, so all we have to decide is which sides those are. A side counts when
                // the signal leaves that way and something is actually there to receive it - the same
                // rule the connection bars use, so the drawing and the solver cannot disagree.
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
                // Same four settings as a repeater, and the same look: what it does differently is the
                // strength it hands on, which no block model can show.
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
                // Floor- or ceiling-mounted: the same quarter turn about X as a diode, so the plate's
                // front face is turned towards whatever it reads.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.CONTROLLER, Direction.SOUTH, null, tilt, slot.power > 0,
                            false, 1, false, Set.of());
                }
                return new Placement(Model.CONTROLLER, slot.facing, null, 0F, slot.power > 0, false, 1,
                        false, Set.of());
            }
            case TRANSFORMER -> {
                // The same plate and the same southern model: the transformer's front face is its input
                // edge, so it is the face a diode's FACING names.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.TRANSFORMER, Direction.SOUTH, null, tilt, slot.power > 0,
                            false, 1, false, Set.of());
                }
                return new Placement(Model.TRANSFORMER, slot.facing, null, 0F, slot.power > 0, false, 1,
                        false, Set.of());
            }
            case CLOCK -> {
                // The same plate again; the lit flag carries the pulse - a two-tick flash per period.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.CLOCK, Direction.SOUTH, null, tilt, slot.power > 0, false,
                            1, false, Set.of());
                }
                return new Placement(Model.CLOCK, slot.facing, null, 0F, slot.power > 0, false, 1, false,
                        Set.of());
            }
            case EXTENDER -> {
                // The same plate once more: lit while it is answering, which is the display running.
                if (slot.facing.getAxis() == Direction.Axis.Y) {
                    float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
                    return new Placement(Model.EXTENDER, Direction.SOUTH, null, tilt, slot.power > 0,
                            false, 1, false, Set.of());
                }
                return new Placement(Model.EXTENDER, slot.facing, null, 0F, slot.power > 0, false, 1,
                        false, Set.of());
            }
            default -> {
                // Pressure plates are not placeable yet, and no vanilla plate shape would be honest.
                return null;
            }
        }
    }

    /**
     * A diode.
     *
     * <p>Vanilla's {@code FACING} is the side a diode <em>reads</em> from: {@code getInputSignal} asks
     * the neighbour in that direction, and {@code updateNeighborsInFront} notifies the block on the
     * opposite one. That is the same thing our {@link Slot#facing} means, so a horizontal component
     * maps straight onto it and vanilla's own variant table does the rotating.
     *
     * <p>For a component on the floor or the ceiling there is no such variant: vanilla's diode
     * {@code FACING} is {@code HORIZONTAL_FACING}, and its blockstate has only the four quarter turns.
     * So the unrotated model (authored with {@code facing=south}, i.e. reading from the south) is used
     * instead, turned a quarter turn about X: south becomes down for a component reading from below,
     * and up for one reading from above.
     */
    private static Placement diode(Slot slot, Model model, int delay, boolean subtract) {
        boolean lit = slot.power > 0;
        // Vanilla's comparator has no locked property at all - only the repeaters do - so a comparator
        // never asks for that look even when the wrench has pinned one of its sides.
        boolean locked = model != Model.COMPARATOR && slot.isRoutingLocked();
        if (slot.facing.getAxis() == Direction.Axis.Y) {
            float tilt = slot.facing == Direction.DOWN ? 90F : -90F;
            return new Placement(model, Direction.SOUTH, null, tilt, lit, locked, delay, subtract,
                    Set.of());
        }
        return new Placement(model, slot.facing, null, 0F, lit, locked, delay, subtract, Set.of());
    }

    /**
     * A torch: lit by default, put out by whatever reaches the side it hangs on.
     *
     * <p>Vanilla's floor torch is attached below and powers everything except below; its wall torch is
     * attached at {@code facing.getOpposite()} and powers everything except that side. Both are exactly
     * "does not power the side it hangs on", so a torch on a wall maps onto the wall torch with
     * {@code facing} flipped, and a torch on the floor maps onto the floor torch untouched.
     *
     * <p>A torch hanging from a ceiling has no vanilla counterpart at all - vanilla cannot place one -
     * so the floor torch is turned upside down.
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
        // The wall is the side the torch hangs on, so the wall is behind it: vanilla's facing points
        // away from the wall.
        return new Placement(Model.WALL_TORCH, slot.facing.getOpposite(), null, 0F, lit, false, 1, false,
                Set.of());
    }

    /**
     * A lever or a button: vanilla has floor, wall and ceiling variants of both, so all six of our
     * mounting directions are covered without a matrix.
     *
     * <p>The attached block is {@code FACE} for the floor and the ceiling, and {@code FACE=WALL} with
     * {@code FACING.getOpposite()} for a wall - which is why a horizontal component flips its facing
     * here, exactly as the torch does.
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

    /**
     * Turns a {@link Placement} into the vanilla {@link BlockState} whose baked model is drawn.
     *
     * <p>This half touches the registries, which is why it is kept apart from {@link #placementFor}.
     */
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
                return repeaterState(RCRegistry.RETAINING_REPEATER, placement);
            }
            case DOUBLE_REPEATER -> {
                return repeaterState(RCRegistry.DOUBLE_REPEATER, placement);
            }
            case DOUBLE_RETAINING_REPEATER -> {
                return repeaterState(RCRegistry.DOUBLE_RETAINING_REPEATER, placement);
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
                // Retrieving a button always hands back a stone one, so the stone model is the honest
                // one to draw; see HostRules#itemFor.
                return Blocks.STONE_BUTTON.defaultBlockState()
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, placement.face())
                        .setValue(FaceAttachedHorizontalDirectionalBlock.FACING, placement.facing())
                        .setValue(ButtonBlock.POWERED, placement.lit());
            }
            case CONTROLLER -> {
                // Our own block's model: the plate is what the component inside a block really is.
                return RCRegistry.CONTROLLER.defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.FACING,
                                placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ControllerBlock.LIT,
                                placement.lit());
            }
            case TRANSFORMER -> {
                // Only facing and LIT reach the model: the two lit models are what the answer selects.
                return RCRegistry.TRANSFORMER.defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.FACING,
                                placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.TransformerBlock.LIT,
                                placement.lit());
            }
            case CLOCK -> {
                // And the same for the clock source, whose lit texture is what its model does with LIT.
                return RCRegistry.CLOCK.defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ClockBlock.FACING,
                                placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ClockBlock.LIT,
                                placement.lit());
            }
            case EXTENDER -> {
                // And for the signal extender, whose lit model is the display with the marker running.
                return RCRegistry.EXTENDER.defaultBlockState()
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.FACING,
                                placement.facing())
                        .setValue(com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock.LIT,
                                placement.lit());
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * The state of a repeater-shaped block, vanilla's or one of ours.
     *
     * <p>All four carry the same properties, so one builder serves them: the model that ends up being
     * drawn is the only thing that differs, which is what makes our own repeaters look like themselves
     * inside a host block as well as in the world.
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
     * A rotation about the block's centre, because the models are authored with the block's corner at
     * the origin and turning them about the corner would swing them out of the block.
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
     * The tint vanilla's own block colour provider would have applied.
     *
     * <p>Only dust is tinted - its texture is greyscale, which is what makes a wire readable at a
     * glance - and we reproduce vanilla's ramp exactly, so inner dust looks like the dust outside the
     * block. The superconductor is the same ramp in orange. Everything else draws at full brightness.
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
