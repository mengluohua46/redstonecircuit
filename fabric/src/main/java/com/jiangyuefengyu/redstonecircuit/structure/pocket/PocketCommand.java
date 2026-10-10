package com.jiangyuefengyu.redstonecircuit.structure.pocket;

import java.util.Map;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.RCRegistry;
import com.jiangyuefengyu.redstonecircuit.block.RedstoneStructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.StructureBlockEntity;
import com.jiangyuefengyu.redstonecircuit.block.WeatherDetectorBlock;
import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneNode;
import com.jiangyuefengyu.redstonecircuit.data.InnerRedstoneStore;
import com.jiangyuefengyu.redstonecircuit.network.StructureManualTrigger;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;
import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.PlotAllocator;
import com.jiangyuefengyu.redstonecircuit.structure.PreviewRegion;
import com.jiangyuefengyu.redstonecircuit.structure.RegionEditor;
import com.jiangyuefengyu.redstonecircuit.structure.ReleaseJournal;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructureEdits;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureProductData;
import com.jiangyuefengyu.redstonecircuit.structure.StructureRelease;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;

/**
 * {@code /rc pocket} - diagnostic entry point for the pocket dimension; only a real server can show that its
 * redstone runs, so each check is one command and one verdict in the log.
 *
 * <p>Not a user interface: {@code list}, {@code clear} (release every plot), and one self test per feature -
 * {@code selftest}, {@code archtest}, {@code injecttest}, {@code manualtest}, {@code previewtest}.
 */
public final class PocketCommand {

    /** How long the self test waits for the repeater's tick before it calls it a failure. */
    private static final int SELF_TEST_TICKS = 60;

    public static void attach(LiteralArgumentBuilder<CommandSourceStack> root) {
        root.then(Commands.literal("pocket")
                .executes(PocketCommand::list)
                .then(Commands.literal("list").executes(PocketCommand::list))
                .then(Commands.literal("selftest").executes(PocketCommand::selfTest))
                .then(Commands.literal("archtest").executes(PocketCommand::archTest))
                .then(Commands.literal("injecttest").executes(PocketCommand::injectTest))
                .then(Commands.literal("manualtest").executes(PocketCommand::manualTest))
                .then(Commands.literal("previewtest").executes(PocketCommand::previewTest))
                .then(Commands.literal("journaltest").executes(PocketCommand::journalTest))
                .then(Commands.literal("weathertest").executes(PocketCommand::weatherTest))
                .then(Commands.literal("ownershiptest").executes(PocketCommand::ownershipTest))
                .then(Commands.literal("perftest")
                        .executes(ctx -> 0)
                        .then(Commands.argument("count", IntegerArgumentType.integer(0, 64))
                                .executes(PocketCommand::perfTest)))
                .then(Commands.literal("clear").executes(PocketCommand::clear)));
    }

    private static void say(CommandSourceStack source, String text, ChatFormatting colour) {
        source.sendSuccess(() -> Component.literal(text).withStyle(colour), false);
    }

    /** What a client would be able to draw for a structure description - the preview half of the verdict. */
    private static String describe(StructureProductData data) {
        return data.preview() == null
                ? "none (this description cannot draw itself - the missing-placeholder case)"
                : data.preview().describe();
    }

    /** A refusal goes to the caller and to the log: these checks usually start from a datapack function, and
     * vanilla suppresses a function source's output. */
    private static void refuse(CommandSourceStack source, String text) {
        say(source, text, ChatFormatting.RED);
        RCConfig.LOGGER.warn("[redstonecircuit] pocket: {}", text);
    }

    private static PocketSpace pocketOrReport(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerLevel pocket = PocketDimension.of(server);
        if (pocket == null) {
            refuse(source, "no level for " + PocketDimension.STRUCTURE_SPACE.location()
                    + " - data/redstonecircuit/dimension_type/structure_space.json and "
                    + "data/redstonecircuit/dimension/structure_space.json did not load");
            return null;
        }
        return PocketSpace.of(pocket);
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel pocket = space.level();
        ServerLevel overworld = source.getServer().overworld();

        say(source, "pocket dimension " + PocketDimension.STRUCTURE_SPACE.location()
                + " is present (" + pocket.dimensionType().minY() + ".."
                + (pocket.dimensionType().minY() + pocket.dimensionType().height()) + ")", ChatFormatting.AQUA);
        if (overworld != null) {
            say(source, "  time " + pocket.getDayTime() + " vs overworld " + overworld.getDayTime()
                    + ", raining " + pocket.isRaining() + " vs " + overworld.isRaining()
                    + " (sync " + RCConfig.structurePocketSyncEnvironment() + ")", ChatFormatting.GRAY);
        } else {
            say(source, "  time " + pocket.getDayTime(), ChatFormatting.GRAY);
        }
        say(source, "  budget: " + space.budget().usedPockets() + "/" + space.budget().maxPockets()
                + " pockets, " + space.budget().usedColumns() + "/" + space.budget().maxColumns()
                + " chunk columns", ChatFormatting.GRAY);

        if (space.plots().isEmpty()) {
            say(source, "  no plots", ChatFormatting.GRAY);
        }
        // The saved table says which plot belongs to which carrier; the grid above was rebuilt from it.
        Map<Integer, PocketPlots.Entry> claims = PocketPlots.get(pocket).entries();
        long now = pocket.getGameTime();
        space.plots().forEach((index, plot) -> say(source, "  plot " + index + " at "
                + plot.origin().toShortString() + " for a " + plot.size() + "³ structure, "
                + plot.columns() + " column(s), ticking="
                + pocket.shouldTickBlocksAt(net.minecraft.world.level.ChunkPos.asLong(
                        plot.origin().getX() >> 4, plot.origin().getZ() >> 4))
                + ", " + describeClaim(claims.get(index), now), ChatFormatting.GRAY));
        return 1;
    }

    /** One plot's owner record. The position shows where the owning block stands; a travelling stamp means the
     * claim is held until its lease runs out, whatever the budget is doing. */
    private static String describeClaim(@Nullable PocketPlots.Entry claim, long now) {
        if (claim == null) {
            return "owner unknown (the grid holds it and the saved table does not)";
        }
        if (claim.travellingSince() != 0) {
            return "travelling since " + claim.travellingSince() + " (" + (now - claim.travellingSince())
                    + " tick(s) ago, lease " + PocketSpace.TRAVEL_LEASE_TICKS + "), owner "
                    + claim.ownerDimension().location() + " @ " + claim.ownerPos().toShortString();
        }
        return "owner " + claim.ownerDimension().location() + " @ " + claim.ownerPos().toShortString();
    }

    private static int clear(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        // Giving a pocket back is the way out of a full budget; a block whose plot is returned keeps its structure file and gets a new plot on its own next tick (StructureBlockEntity#repairPlotIfDue).
        ServerLevel pocket = space.level();
        Map<Integer, PocketPlots.Entry> claims = PocketPlots.get(pocket).entries();
        long now = pocket.getGameTime();
        int released = 0;
        for (int index : space.plots().keySet()) {
            say(source, "  plot " + index + ": " + describeClaim(claims.get(index), now), ChatFormatting.GRAY);
            if (space.release(index)) {
                released++;
            }
        }
        say(source, "released " + released + " plot(s); budget now "
                + space.budget().usedPockets() + " pocket(s), " + space.budget().usedColumns()
                + " column(s). Every structure that still has a file is given a plot again on its own tick.",
                ChatFormatting.AQUA);
        return released;
    }

    /** A self test waiting for its answer. Driven by the level tick, not a server task queue: a queue drains
     * tasks added while draining, so a task that re-posts itself would run its whole count inside one tick. */
    private record Pending(CommandSourceStack source, BlockPos origin, BlockPos lampPos, BlockPos dustPos,
                           BlockPos repeaterPos, int plot, int columns, int deadline) {
    }

    private static final java.util.List<Pending> PENDING = new java.util.ArrayList<>();

    /** Called once per pocket level tick, from the mod's tick handler. */
    public static void tick(ServerLevel pocket) {
        tickSelfTests(pocket);
        tickArchTests(pocket);
        tickInjectTests(pocket);
        tickManualTests(pocket);
        tickPreviewTests(pocket);
        tickPerfTests(pocket);
        tickWeatherTests(pocket);
        tickOwnershipTests(pocket);
    }

    private static void tickSelfTests(ServerLevel pocket) {
        if (PENDING.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<Pending> waiting = PENDING.iterator();
        while (waiting.hasNext()) {
            Pending test = waiting.next();
            boolean lit = lampLit(pocket, test.lampPos());
            if (!lit && server.getTickCount() < test.deadline()) {
                continue;
            }
            waiting.remove();
            long chunk = net.minecraft.world.level.ChunkPos.asLong(test.origin().getX() >> 4,
                    test.origin().getZ() >> 4);
            String verdict = lit
                    ? "SELF TEST PASSED: the lamp is lit, so vanilla redstone ran inside the pocket"
                    : "SELF TEST FAILED: the lamp is still dark after " + SELF_TEST_TICKS
                            + " tick(s) - ticking=" + pocket.shouldTickBlocksAt(chunk)
                            + " pendingRepeaterTick=" + pocket.getBlockTicks()
                                    .hasScheduledTick(test.repeaterPos(), Blocks.REPEATER)
                            + " wirePower=" + pocket.getBlockState(test.dustPos())
                                    .getValue(RedStoneWireBlock.POWER);
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source(), verdict, lit ? ChatFormatting.GREEN : ChatFormatting.RED);
            say(test.source(), "  plot " + test.plot() + " at " + test.origin().toShortString() + ", "
                    + test.columns() + " column(s), ticking=" + pocket.shouldTickBlocksAt(chunk)
                    + "; the plot is released again", ChatFormatting.GRAY);
            // Built for a check, not left behind: the plot goes away once the answer is known.
            PocketSpace.of(pocket).release(test.plot());
        }
    }

    /** Forgets every waiting self test; called when the server stops. */
    public static void clear() {
        PENDING.clear();
        ARCH.clear();
        INJECT.clear();
        MANUAL.clear();
        PREVIEW.clear();
        PERF.clear();
        WEATHER.clear();
        OWNERSHIP.clear();
        StructureManualTrigger.forgetAll();
        StructurePreviews.forgetAll();
    }

    /** An architecture test in flight: a circuit built, captured, put into a pocket plot, its lever thrown
     * <b>inside the pocket</b> and its lamp awaited - on a real server, which the GameTest harness cannot be. */
    private static final class ArchPending {

        private final CommandSourceStack source;
        private final ServerLevel pocket;
        private final int plot;
        private final net.minecraft.core.Vec3i size;
        private final boolean shell;
        private final BlockPos leverPos;
        private final BlockPos lampPos;
        /** The input side of the host block that carries one of our own components, and the lamp it drives. */
        private final BlockPos innerInputPos;
        private final BlockPos innerLampPos;
        private final int deadline;
        private boolean flipped;

        private ArchPending(CommandSourceStack source, ServerLevel pocket, int plot,
                            net.minecraft.core.Vec3i size, boolean shell, BlockPos leverPos,
                            BlockPos lampPos, BlockPos innerInputPos, BlockPos innerLampPos, int deadline) {
            this.source = source;
            this.pocket = pocket;
            this.plot = plot;
            this.size = size;
            this.shell = shell;
            this.leverPos = leverPos;
            this.lampPos = lampPos;
            this.innerInputPos = innerInputPos;
            this.innerLampPos = innerLampPos;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<ArchPending> ARCH = new java.util.ArrayList<>();

    /** Builds the acceptance circuit, captures it into a pocket plot, and checks that its own redstone runs -
     * the lamp lights with no player nearby only if the plot's ticket makes that region tick. */
    private static int archTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel world = source.getLevel();
        ServerLevel pocket = space.level();

        // x0..x6 holds two things: the design's own lever -> dust -> repeater -> lamp, and one of our components inside a host block.
        BlockPos base = new BlockPos(8, 100, 8);
        for (int x = 0; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                world.setBlock(base.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
                world.setBlock(base.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 3);
                world.setBlock(base.offset(x, 2, z), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
        world.setBlock(base.offset(0, 1, 0), lever, 3);
        world.setBlock(base.offset(1, 1, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(2, 1, 0), Blocks.REPEATER.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 3);
        world.setBlock(base.offset(3, 1, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // A component of ours lives in the world's store keyed by position, not in the host block, so a capture that moved only block states would leave an empty shell and the lamp at x6 would never light.
        world.setBlock(base.offset(5, 1, 0), Blocks.STONE.defaultBlockState(), 3);
        InnerRedstoneNode inner = InnerRedstoneNode.of(ComponentType.RETAINING_REPEATER);
        inner.slot().facing = Direction.WEST;
        InnerRedstoneStore.get(world).getOrCreate(base.offset(5, 1, 0)).setSlot(inner.slot());
        world.setBlock(base.offset(6, 1, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(base, base.offset(6, 1, 0), IoMode.ACTIVATION_FACES, "arch test",
                        false, true, null),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "arch test could not capture its own circuit: " + captured.refusal());
            return 0;
        }
        StructurePayload payload = captured.captured().payload();
        try {
            StructureWorldStore.of(source.getServer(), RCConfig.structurePayloadLimits()).add(payload);
        } catch (java.io.IOException failed) {
            refuse(source, "arch test could not store the structure: " + failed);
            return 0;
        }

        PocketMaterializer.Result materialized = PocketMaterializer.materialize(world, payload, true,
                world.dimension(), base);
        if (!materialized.materialized()) {
            refuse(source, "arch test could not get a pocket: " + materialized.refusal());
            return 0;
        }
        BlockPos origin = PocketSpace.originOf(materialized.plot());
        say(source, "arch test: captured the circuit, pocket plot " + materialized.plot() + " at "
                + origin.toShortString() + " holds " + materialized.blocks() + " block(s), "
                + materialized.plot() + "->" + space.plots().get(materialized.plot()).columns()
                + " column(s); throwing its lever in the pocket", ChatFormatting.AQUA);
        RCConfig.LOGGER.info("[redstonecircuit] pocket: arch test preview: {}",
                describe(StructureProductData.of(payload, RCConfig.structurePreviewBytes())));
        ARCH.add(new ArchPending(source, pocket, materialized.plot(), payload.header().size(), true,
                origin.offset(0, 1, 0), origin.offset(3, 1, 0), origin.offset(4, 1, 0),
                origin.offset(6, 1, 0), source.getServer().getTickCount() + SELF_TEST_TICKS));
        return 1;
    }

    private static void tickArchTests(ServerLevel pocket) {
        if (ARCH.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<ArchPending> waiting = ARCH.iterator();
        while (waiting.hasNext()) {
            ArchPending test = waiting.next();
            if (!test.flipped) {
                // Thrown in the pocket by nobody: not a player input, but the structure's own blocks being live where the plot's ticket made tick.
                test.flipped = true;
                pocket.setBlock(test.leverPos,
                        pocket.getBlockState(test.leverPos).cycle(LeverBlock.POWERED), 3);
                pocket.updateNeighborsAt(test.leverPos, Blocks.LEVER);
                // The host block's input side is powered too, so what must answer is the component that travelled there in the payload's slot data.
                pocket.setBlock(test.innerInputPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                pocket.updateNeighborsAt(test.innerInputPos, Blocks.REDSTONE_BLOCK);
                continue;
            }
            boolean lit = lampLit(pocket, test.lampPos);
            boolean innerLit = lampLit(pocket, test.innerLampPos);
            if ((!lit || !innerLit) && server.getTickCount() < test.deadline) {
                continue;
            }
            waiting.remove();
            long chunk = net.minecraft.world.level.ChunkPos.asLong(test.lampPos.getX() >> 4,
                    test.lampPos.getZ() >> 4);
            String verdict = lit && innerLit
                    ? "ARCH TEST PASSED: a captured circuit is running in a pocket plot, and so is one of "
                            + "our own components inside a host block - both lamps lit with no player "
                            + "anywhere near them, only because the plot is ticked"
                    : "ARCH TEST FAILED: lamp=" + lit + " innerComponentLamp=" + innerLit
                            + " (ticking=" + pocket.shouldTickBlocksAt(chunk) + ", lever="
                            + pocket.getBlockState(test.leverPos) + ", hostInput="
                            + pocket.getBlockState(test.innerInputPos) + ", innerLamp="
                            + pocket.getBlockState(test.innerLampPos) + ")";
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source, verdict, lit && innerLit ? ChatFormatting.GREEN : ChatFormatting.RED);
            PocketMaterializer.release(pocket, test.plot);
        }
    }

    /** The activation-face mode end to end: a face of the placed product block is energized <b>in the world</b>,
     * and the cells the structure's table names inside the pocket are pushed with the strength the table says. */
    private static final class InjectPending {

        private final CommandSourceStack source;
        private final ServerLevel world;
        private final ServerLevel pocket;
        private final int plot;
        private final BlockPos productPos;
        private final BlockPos energizerPos;
        private final BlockPos injectedPos;
        private final BlockPos besidePos;
        private final BlockPos lampPos;
        private final int strength;
        private final int deadline;
        /** Whether the pocket was dark while nothing drove the face - the half that has to be true first. */
        private boolean seenDark;
        private boolean energized;

        private InjectPending(CommandSourceStack source, ServerLevel world, ServerLevel pocket, int plot,
                              BlockPos productPos, BlockPos energizerPos, BlockPos injectedPos,
                              BlockPos besidePos, BlockPos lampPos, int strength, int deadline) {
            this.source = source;
            this.world = world;
            this.pocket = pocket;
            this.plot = plot;
            this.productPos = productPos;
            this.energizerPos = energizerPos;
            this.injectedPos = injectedPos;
            this.besidePos = besidePos;
            this.lampPos = lampPos;
            this.strength = strength;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<InjectPending> INJECT = new java.util.ArrayList<>();

    /** Builds the activation-face case and lets it run: a controller with a filled table, a capture that writes
     * it into the payload, a product block, a pocket plot. Only the energizing block is placed by hand. */
    private static int injectTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel world = source.getLevel();
        ServerLevel pocket = space.level();

        // Dust, dust, lamp on a stone floor; floor and air travel as the capture's context shell, which keeps the dust usable in the plot.
        BlockPos base = new BlockPos(8, 100, 20);
        for (int x = 0; x <= 3; x++) {
            world.setBlock(base.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(base.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        world.setBlock(base, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(1, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(2, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // The table a player would have filled in: the west face pushes 9 into the first cell of the selection.
        BlockPos controllerPos = base.offset(0, 2, 3);
        world.setBlock(controllerPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        if (!(world.getBlockEntity(controllerPos) instanceof RedstoneStructureBlockEntity controller)) {
            refuse(source, "inject test could not place its controller");
            return 0;
        }
        controller.setCorner(false, base);
        controller.setCorner(true, base.offset(2, 0, 0));
        controller.setFaceEntry(Direction.WEST, BlockPos.ZERO, 9);

        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(base, base.offset(2, 0, 0), IoMode.ACTIVATION_FACES,
                        "inject test", false, true, controllerPos, controller.faces()),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "inject test could not capture its own circuit: " + captured.refusal());
            return 0;
        }
        StructurePayload payload = captured.captured().payload();
        if (payload.faces().isEmpty()) {
            refuse(source, "inject test: the controller's table did not reach the payload, so there is "
                    + "nothing to test");
            return 0;
        }

        PocketMaterializer.Result materialized = PocketMaterializer.materialize(world, payload, true,
                world.dimension(), base);
        if (!materialized.materialized()) {
            refuse(source, "inject test could not get a pocket: " + materialized.refusal());
            return 0;
        }
        BlockPos origin = PocketSpace.originOf(materialized.plot());

        // A real placed block whose entity reads its own six faces every tick, placed where the face table aims at its west side.
        BlockPos productPos = base.offset(6, 1, 0);
        world.setBlock(productPos, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (!(world.getBlockEntity(productPos) instanceof StructureBlockEntity product)) {
            refuse(source, "inject test could not place its product block");
            return 0;
        }
        product.setStructure(StructureProductData.of(payload, RCConfig.structurePreviewBytes()));
        product.setPlotIndex(materialized.plot());
        RCConfig.LOGGER.info("[redstonecircuit] pocket: inject test preview: {}",
                describe(product.asProduct()));

        say(source, "inject test: plot " + materialized.plot() + " at " + origin.toShortString()
                + " holds " + materialized.blocks() + " block(s); the west face of the product at "
                + productPos.toShortString() + " pushes " + 9 + " into its first cell", ChatFormatting.AQUA);
        INJECT.add(new InjectPending(source, world, pocket, materialized.plot(), productPos,
                productPos.west(), origin, origin.offset(1, 0, 0), origin.offset(2, 0, 0), 9,
                source.getServer().getTickCount() + SELF_TEST_TICKS));
        return 1;
    }

    /** The power a redstone wire at this position carries, or -1 when there is no wire there. A {@code getValue}
     * on a block that is not what the check expects throws out of the level tick and takes the server down. */
    private static int wirePower(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_WIRE) ? state.getValue(RedStoneWireBlock.POWER) : -1;
    }

    /** Whether a redstone lamp is lit, and false when the block is not a lamp at all. */
    private static boolean lampLit(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.REDSTONE_LAMP) && state.getValue(BlockStateProperties.LIT);
    }

    private static void tickInjectTests(ServerLevel pocket) {
        if (INJECT.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<InjectPending> waiting = INJECT.iterator();
        while (waiting.hasNext()) {
            InjectPending test = waiting.next();
            if (!test.energized) {
                // Negative control, read while nothing drives the face: without it the test could not tell "the injection works" from "the capture left the lamp lit".
                test.seenDark = pocket.getSignal(test.injectedPos, Direction.EAST) == 0
                        && wirePower(pocket, test.injectedPos) == 0
                        && !lampLit(pocket, test.lampPos);
                RCConfig.LOGGER.info("[redstonecircuit] pocket: inject test setup: plot {} at {}, injected "
                                + "cell {} holds {} (loaded={}), below it {}, beside it {}, the lamp {}",
                        test.plot, PocketSpace.originOf(test.plot).toShortString(),
                        test.injectedPos.toShortString(), pocket.getBlockState(test.injectedPos),
                        pocket.isLoaded(test.injectedPos), pocket.getBlockState(test.injectedPos.below()),
                        pocket.getBlockState(test.besidePos), pocket.getBlockState(test.lampPos));
                test.energized = true;
                // And now the world energizes one face of the product block - the design's whole input.
                test.world.setBlock(test.energizerPos, Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
                test.world.updateNeighborsAt(test.energizerPos, Blocks.REDSTONE_BLOCK);
                continue;
            }
            int signal = pocket.getSignal(test.injectedPos, Direction.EAST);
            int beside = wirePower(pocket, test.besidePos);
            boolean lit = lampLit(pocket, test.lampPos);
            if ((signal != test.strength || !lit) && server.getTickCount() < test.deadline) {
                continue;
            }
            waiting.remove();
            int own = wirePower(pocket, test.injectedPos);
            // 这一格的粉停在填写的强度上，紧挨着的粉比它低一档。
            boolean pass = test.seenDark && signal == test.strength && own == test.strength
                    && beside == test.strength - 1 && lit;
            String verdict = pass
                    ? "INJECT TEST PASSED: dark while nothing drove the face, and then a redstone block "
                            + "against the product's west face pushed " + test.strength + " into the plot - "
                            + "the injected cell's own dust reads " + own + " (the filled strength, pinned by "
                            + "the wire mixin because a wire decides its own strength from its neighbours and "
                            + "never asks itself), the dust beside it reads " + beside + " (one hop down), a "
                            + "cell asked from a side that is not a wire answers " + signal + ", and the lamp "
                            + "two cells along is lit"
                    : "INJECT TEST FAILED: signal=" + signal + " (wanted " + test.strength + "), own=" + own
                            + " (wanted " + test.strength + "), beside=" + beside + " (wanted "
                            + (test.strength - 1) + "), lamp=" + lit + ", wasDark=" + test.seenDark;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source, verdict, pass ? ChatFormatting.GREEN : ChatFormatting.RED);
            say(test.source, "  plot " + test.plot + ", product at " + test.productPos.toShortString()
                    + ", energizer at " + test.energizerPos.toShortString() + "; the plot is released again",
                    ChatFormatting.GRAY);
            PocketMaterializer.release(pocket, test.plot);
        }
    }

    /** A mode B test in flight: the manual counterpart of {@code injecttest}. The entry is pressed through the
     * same function the packet handler calls, with a player id of its own; client key polling stays unchecked. */
    private static final class ManualPending {

        private final CommandSourceStack source;
        private final ServerLevel world;
        private final ServerLevel pocket;
        private final int plot;
        private final BlockPos productPos;
        private final BlockPos injectedPos;
        private final BlockPos lampPos;
        private final int strength;
        private final int deadline;
        private long pressedAt;
        private boolean sawDark;
        private boolean sawPush;
        private boolean pressed;
        private String refusal = "";

        private ManualPending(CommandSourceStack source, ServerLevel world, ServerLevel pocket, int plot,
                              BlockPos productPos, BlockPos injectedPos, BlockPos lampPos, int strength,
                              int deadline) {
            this.source = source;
            this.world = world;
            this.pocket = pocket;
            this.plot = plot;
            this.productPos = productPos;
            this.injectedPos = injectedPos;
            this.lampPos = lampPos;
            this.strength = strength;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<ManualPending> MANUAL = new java.util.ArrayList<>();

    /** Builds a mode B structure, captures it into a pocket plot and presses its one key. Same layout as the
     * injection test, so the verdict reads the same way - except the pulse then ends by itself. */
    private static int manualTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel world = source.getLevel();
        ServerLevel pocket = space.level();

        BlockPos base = new BlockPos(8, 100, 40);
        for (int x = 0; x <= 3; x++) {
            world.setBlock(base.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(base.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        world.setBlock(base, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(1, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(2, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // The controller, in manual mode, with the one key the test presses.
        BlockPos controllerPos = base.offset(0, 2, 3);
        world.setBlock(controllerPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        if (!(world.getBlockEntity(controllerPos) instanceof RedstoneStructureBlockEntity controller)) {
            refuse(source, "manual test could not place its controller");
            return 0;
        }
        controller.setCorner(false, base);
        controller.setCorner(true, base.offset(2, 0, 0));
        controller.setIoMode(IoMode.MANUAL);
        controller.setManualEntry("key.keyboard.g", BlockPos.ZERO, 9, "manual test");

        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(base, base.offset(2, 0, 0), IoMode.MANUAL, "manual test",
                        false, true, controllerPos, controller.faces(), controller.manual()),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "manual test could not capture its own circuit: " + captured.refusal());
            return 0;
        }
        StructurePayload payload = captured.captured().payload();
        if (payload.manual().isEmpty()) {
            refuse(source, "manual test: the controller's panel did not reach the payload, so there is nothing "
                    + "to press");
            return 0;
        }

        PocketMaterializer.Result materialized = PocketMaterializer.materialize(world, payload, true,
                world.dimension(), base);
        if (!materialized.materialized()) {
            refuse(source, "manual test could not get a pocket: " + materialized.refusal());
            return 0;
        }

        BlockPos productPos = base.offset(6, 1, 0);
        world.setBlock(productPos, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (!(world.getBlockEntity(productPos) instanceof StructureBlockEntity product)) {
            refuse(source, "manual test could not place its product block");
            return 0;
        }
        product.setStructure(StructureProductData.of(payload, RCConfig.structurePreviewBytes()));
        product.setPlotIndex(materialized.plot());
        RCConfig.LOGGER.info("[redstonecircuit] pocket: manual test preview: {}",
                describe(product.asProduct()));

        BlockPos origin = PocketSpace.originOf(materialized.plot());
        say(source, "manual test: plot " + materialized.plot() + " at " + origin.toShortString()
                + " holds " + materialized.blocks() + " block(s); pressing entry 0 of the panel at "
                + productPos.toShortString() + " (io mode " + product.ioMode() + ")", ChatFormatting.AQUA);
        MANUAL.add(new ManualPending(source, world, pocket, materialized.plot(), productPos, origin,
                origin.offset(2, 0, 0), 9, source.getServer().getTickCount() + SELF_TEST_TICKS));
        return 1;
    }

    private static void tickManualTests(ServerLevel pocket) {
        if (MANUAL.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        // A player id of its own, so this test's cooldown is nobody else's.
        java.util.UUID player = java.util.UUID.nameUUIDFromBytes("redstonecircuit:manualtest".getBytes());
        java.util.Iterator<ManualPending> waiting = MANUAL.iterator();
        while (waiting.hasNext()) {
            ManualPending test = waiting.next();
            if (!test.pressed) {
                // The negative control first: nothing is pressed yet, so nothing is pushed.
                test.sawDark = pocket.getSignal(test.injectedPos, Direction.EAST) == 0
                        && !lampLit(pocket, test.lampPos);
                test.pressed = true;
                test.pressedAt = server.getTickCount();
                StructureManualTrigger.Result result = StructureManualTrigger.press(test.world, player,
                        Vec3.atCenterOf(test.productPos), test.productPos, 0);
                test.refusal = result.accepted() ? "" : result.refusal();
                continue;
            }
            int signal = pocket.getSignal(test.injectedPos, Direction.EAST);
            boolean lit = lampLit(pocket, test.lampPos);
            if (signal == test.strength && lit) {
                test.sawPush = true;
            }
            // The pulse lasts structureManualPulseTicks and the lamp then takes vanilla's four ticks to go out, so the answer is "cell no longer pushed and lamp dark", waited for rather than counted.
            boolean ended = signal == 0 && !lit;
            if (!ended && server.getTickCount() < test.deadline) {
                continue;
            }
            waiting.remove();
            boolean pass = test.sawDark && test.refusal.isEmpty() && test.sawPush && ended;
            String verdict = pass
                    ? "MANUAL TEST PASSED: the panel's key pushed " + test.strength + " into the plot through "
                            + "the server's own press path (the entry's cell answered " + test.strength
                            + " and the lamp beside it lit), and a press is a pulse - it ended by itself "
                            + "with no release packet to lose"
                    : "MANUAL TEST FAILED: sawDark=" + test.sawDark + " sawPush=" + test.sawPush
                            + " refusal=" + test.refusal + " signal=" + signal + " (wanted "
                            + test.strength + " while pressed, 0 after) lamp=" + lit;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source, verdict, pass ? ChatFormatting.GREEN : ChatFormatting.RED);
            PocketMaterializer.release(pocket, test.plot);
        }
    }

    /** One entry of a level's border list, or null - what a client would be drawing for a controller. */
    @Nullable
    private static StructurePreviews.Entry borderAt(ServerLevel level, BlockPos controller) {
        for (StructurePreviews.Entry entry : StructurePreviews.snapshot(level)) {
            if (entry.controller().equals(controller)) {
                return entry;
            }
        }
        return null;
    }

    /** The two-stage operation end to end, almost all of it synchronous: a preview publishes a border without
     * touching the world, the second operation applies it, and every press goes through {@code StructurePreviews.press}. */
    private static int previewTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        java.util.List<String> failed = new java.util.ArrayList<>();

        BlockPos base = new BlockPos(8, 100, 60);
        for (int x = 0; x <= 3; x++) {
            world.setBlock(base.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(base.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        world.setBlock(base, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(1, 0, 0), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        world.setBlock(base.offset(2, 0, 0), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        BlockPos controllerPos = base.offset(0, 2, 4);
        world.setBlock(controllerPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        if (!(world.getBlockEntity(controllerPos) instanceof RedstoneStructureBlockEntity controller)) {
            refuse(source, "preview test could not place its controller");
            return 0;
        }
        controller.setMode(RedstoneStructureBlockEntity.Mode.CAPTURE);
        controller.setTier(RedstoneStructureBlockEntity.Tier.CUT);
        // The first operation, as an edit: "第一次操作 = 预览态" - a region is up and nothing is possible yet, however the player chose it.
        controller.setPreview(PreviewRegion.of(base, base.offset(2, 0, 0)));
        if (!controller.isPreviewing() || controller.isPreviewArmed()) {
            failed.add("showing a region left the controller in the wrong state");
        }
        StructurePreviews.Entry shown = borderAt(world, controllerPos);
        if (shown == null) {
            failed.add("showing a region published no border for a client to draw");
        } else if (shown.armed() || !shown.region().equals(controller.preview())) {
            failed.add("the published border is not the region being previewed, or is already confirmed");
        }
        if (!isDust(world, base) || !isDust(world, base.offset(1, 0, 0)) || !isLamp(world, base.offset(2, 0, 0))) {
            failed.add("showing a region changed the world");
        }

        // The next operation confirms it, and still changes nothing.
        StructurePreviews.Result second = StructurePreviews.press(world, null, controllerPos);
        if (second.step() != StructurePreviews.Step.ARMED) {
            failed.add("the confirming operation reported " + second.step() + " (" + second.message() + ")");
        }
        if (!isDust(world, base)) {
            failed.add("confirming the region changed the world");
        }
        StructurePreviews.Entry armed = borderAt(world, controllerPos);
        if (armed == null || !armed.armed()) {
            failed.add("the confirmed border is not marked as confirmed");
        }

        // An edit starts the two operations over. It goes through StructureEdits, the same functions the screen's buttons reach.
        if (!StructureEdits.resizePreview(world, controllerPos, new Vec3i(2, 1, 1)).ok()) {
            failed.add("the edit path refused to resize the region");
        }
        StructurePreviews.Entry edited = borderAt(world, controllerPos);
        if (edited == null || edited.armed()) {
            failed.add("editing the region did not put the confirmation back");
        }
        if (!isDust(world, base)) {
            failed.add("editing the region changed the world");
        }

        // A region that can never be taken is refused while it is only being previewed.
        if (!StructureEdits.setRegion(world, controllerPos,
                RegionEditor.absolute(new Vec3i(base.getX(), base.getY(), base.getZ()),
                        new Vec3i(49, 1, 1))).ok()) {
            failed.add("the edit path refused to set a region at all");
        }
        StructurePreviews.Result refused = StructurePreviews.press(world, null, controllerPos);
        if (refused.ok() || refused.step() != StructurePreviews.Step.REFUSED) {
            failed.add("a 49-block region was not refused at the preview stage: " + refused.message());
        }

        // The confirmed operation does it: two cells, so the lamp outside the region has to survive.
        if (!StructureEdits.selectCorners(world, controllerPos, base, base.offset(1, 0, 0)).ok()) {
            failed.add("the edit path refused to select the two cells");
        }
        StructurePreviews.Result again = StructurePreviews.press(world, null, controllerPos);
        StructurePreviews.Result taken = StructurePreviews.press(world, null, controllerPos);
        if (again.step() != StructurePreviews.Step.ARMED) {
            failed.add("the region's confirmation reported " + again.step());
        }
        if (taken.step() != StructurePreviews.Step.APPLIED) {
            failed.add("the operation reported " + taken.step() + " (" + taken.message() + ")");
        }
        if (!world.getBlockState(base).isAir() || !world.getBlockState(base.offset(1, 0, 0)).isAir()) {
            failed.add("a cut left the region behind");
        }
        if (!isLamp(world, base.offset(2, 0, 0))) {
            failed.add("the cut took a block outside the region");
        }
        StructureProductData product = controller.pending();
        if (product == null) {
            failed.add("the operation left nothing in the controller's slot");
        } else if (product.size().getX() != 2) {
            failed.add("the slot holds a " + product.size().getX() + "-wide structure, wanted 2");
        }
        if (controller.isPreviewing() || borderAt(world, controllerPos) != null) {
            failed.add("a successful operation did not end the preview");
        }

        // Destroying the controller ends a preview.
        BlockPos brokenPos = controllerPos.offset(3, 0, 0);
        world.setBlock(brokenPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        StructurePreviews.press(world, null, brokenPos);
        world.destroyBlock(brokenPos, false);
        if (borderAt(world, brokenPos) != null) {
            failed.add("the border outlived the controller being broken");
        }

        // Switching the mode ends a preview.
        BlockPos switchedPos = controllerPos.offset(6, 0, 0);
        world.setBlock(switchedPos, RCRegistry.REDSTONE_STRUCTURE_BLOCK.defaultBlockState(), 3);
        if (world.getBlockEntity(switchedPos) instanceof RedstoneStructureBlockEntity switched) {
            // The world outlives one run, so say which mode it starts in: "switch to the mode it is already in" is a no-op (RedstoneStructureBlockEntity#setMode).
            switched.setMode(RedstoneStructureBlockEntity.Mode.CAPTURE);
            StructurePreviews.press(world, null, switchedPos);
            switched.setMode(RedstoneStructureBlockEntity.Mode.RELEASE);
            if (switched.isPreviewing() || borderAt(world, switchedPos) != null) {
                failed.add("switching the mode did not end the preview");
            }
        } else {
            failed.add("the mode-switch check could not place its controller");
        }

        String verdict = failed.isEmpty()
                ? "PREVIEW TEST PASSED (capture): the first operation showed 3x1x1 and touched nothing, the "
                        + "second confirmed it and still touched nothing, an edit put the confirmation back, a "
                        + "49-block region was refused while it was only being previewed, and the confirmed "
                        + "operation cut exactly its 2x1x1 cells, kept the lamp outside them, filled the slot "
                        + "and took the border down; breaking the controller and switching the mode each ended "
                        + "a preview too, and every edit the screen's buttons send (a region relative to the "
                        + "controller, a face entry, a panel entry, a rename, a mode switch) reached the "
                        + "controller through the same functions"
                : "PREVIEW TEST FAILED (capture): " + String.join("; ", failed);
        RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
        say(source, verdict, failed.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED);

        // The release half: it needs ticks, because a release writes its budget per level tick.
        if (product != null) {
            BlockPos target = new BlockPos(8, 100, 70);
            for (int x = 0; x <= 3; x++) {
                world.setBlock(target.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
                world.setBlock(target.offset(x, 0, 0), Blocks.STONE.defaultBlockState(), 3);
            }
            controller.setMode(RedstoneStructureBlockEntity.Mode.RELEASE);
            controller.setPending(product);
            int volume = product.size().getX() * product.size().getY() * product.size().getZ();

            // The first operation with no region: a release starts from the size of what would be written.
            StructurePreviews.Result releaseShown = StructurePreviews.press(world, null, controllerPos);
            if (releaseShown.step() != StructurePreviews.Step.SHOWN) {
                failed.add("the release's first operation reported " + releaseShown.step());
            }
            if (!controller.isPreviewing() || !controller.preview().size().equals(product.size())) {
                failed.add("a release's starting border is not the size of what would be written");
            }

            // Then the target is edited - "释放：以控制器为基准微调偏移", through the edit path a screen uses.
            if (!StructureEdits.target(world, controllerPos, target).ok()) {
                failed.add("the edit path refused to place the release");
            }
            StructurePreviews.Result placed = StructurePreviews.press(world, null, controllerPos);
            StructurePreviews.Entry releaseBorder = borderAt(world, controllerPos);
            if (placed.step() != StructurePreviews.Step.ARMED || releaseBorder == null) {
                failed.add("the placed release preview did not confirm a border");
            } else {
                if (!releaseBorder.release()) {
                    failed.add("the release's border is not marked as a release");
                }
                if (releaseBorder.overwrite() != volume) {
                    failed.add("the release preview reported " + releaseBorder.overwrite()
                            + " block(s) in the way, wanted " + volume);
                }
            }
            if (!world.getBlockState(target).is(Blocks.STONE)) {
                failed.add("confirming a release wrote into the target already");
            }
            StructurePreviews.Result releaseDone = StructurePreviews.press(world, null, controllerPos);
            if (releaseDone.step() != StructurePreviews.Step.APPLIED) {
                failed.add("the release reported " + releaseDone.step() + " (" + releaseDone.message() + ")");
            }
            if (controller.pending() != null) {
                failed.add("the release did not consume the slot");
            }
            if (controller.isPreviewing() || borderAt(world, controllerPos) != null) {
                failed.add("the release did not end the preview");
            }
            PREVIEW.add(new PreviewPending(source, world, target, product.size(), volume,
                    source.getServer().getTickCount() + SELF_TEST_TICKS));
        }

        // The editor's own edits, the same functions every screen button reaches; a player types these into text boxes.
        BlockPos relativeCell = controllerPos.offset(1, 0, 0);
        if (!StructureEdits.setRegion(world, controllerPos, RegionEditor.relative(new Vec3i(1, 0, 0),
                new Vec3i(2, 1, 1))).ok()) {
            failed.add("the editor could not set a region relative to the controller");
        } else if (!controller.preview().min().equals(relativeCell)) {
            failed.add("a relative region did not resolve against the controller: got "
                    + controller.preview().describe() + ", wanted " + relativeCell.toShortString());
        }
        if (!StructureEdits.setFaceEntry(world, controllerPos, Direction.EAST, relativeCell, 9).ok()) {
            failed.add("the editor could not fill a face table");
        }
        // Emptied first, so the panel does not accumulate an entry per run: the world outlives a single run.
        StructureEdits.clearManual(world, controllerPos);
        if (!StructureEdits.setManualEntry(world, controllerPos, "key.keyboard.g", relativeCell, 7,
                "preview test").ok()) {
            failed.add("the editor could not fill the panel");
        }
        if (!StructureEdits.setName(world, controllerPos, "the editor's name").ok()
                || !controller.structureName().equals("the editor's name")) {
            failed.add("the editor could not rename the structure: got " + controller.structureName());
        }
        if (!StructureEdits.setMode(world, controllerPos, false).ok()
                || controller.mode() != RedstoneStructureBlockEntity.Mode.CAPTURE) {
            failed.add("the editor could not switch the mode back");
        }
        if (controller.faces().isEmpty() || controller.manual().isEmpty()) {
            failed.add("the editor's table edits did not reach the controller");
        }
        return failed.isEmpty() ? 1 : 0;
    }

    /** A release in flight: what should be where once its job has finished. */
    private static final class PreviewPending {

        private final CommandSourceStack source;
        private final ServerLevel world;
        private final BlockPos target;
        private final Vec3i size;
        private final int volume;
        private final int deadline;

        private PreviewPending(CommandSourceStack source, ServerLevel world, BlockPos target, Vec3i size,
                               int volume, int deadline) {
            this.source = source;
            this.world = world;
            this.target = target;
            this.size = size;
            this.volume = volume;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<PreviewPending> PREVIEW = new java.util.ArrayList<>();

    /** Waits for the release half of the preview test: the job writes over the next tick(s), so its blocks are
     * checked once it reports done rather than counted down. */
    private static void tickPreviewTests(ServerLevel pocket) {
        if (PREVIEW.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<PreviewPending> waiting = PREVIEW.iterator();
        while (waiting.hasNext()) {
            PreviewPending test = waiting.next();
            int written = 0;
            for (int x = 0; x < test.size.getX(); x++) {
                for (int y = 0; y < test.size.getY(); y++) {
                    for (int z = 0; z < test.size.getZ(); z++) {
                        if (test.world.getBlockState(test.target.offset(x, y, z)).is(Blocks.REDSTONE_WIRE)) {
                            written++;
                        }
                    }
                }
            }
            boolean done = StructureRelease.jobs().isEmpty();
            if (written != test.volume && server.getTickCount() < test.deadline) {
                continue;
            }
            waiting.remove();
            boolean pass = written == test.volume && done;
            String verdict = pass
                    ? "PREVIEW TEST PASSED (release): a release preview reported " + test.volume
                            + " block(s) in the way without writing anything, and the confirmed operation "
                            + "then wrote exactly " + test.volume + " block(s) into "
                            + test.target.toShortString() + " and consumed the slot"
                    : "PREVIEW TEST FAILED (release): wrote " + written + " of " + test.volume
                            + " block(s) into " + test.target.toShortString() + ", jobsDone=" + done;
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source, verdict, pass ? ChatFormatting.GREEN : ChatFormatting.RED);
        }
    }

    /** <b>性能测量: "给出 N 个实例的性能数字"</b>: the server's own last-100-ticks ring with nothing of ours in
     * it, then the same ring with {@code count} running circuits, read ten ticks after the build so the build tick is out of it. */
    private static int perfTest(CommandContext<CommandSourceStack> context)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        int count = IntegerArgumentType.getInteger(context, "count");
        PERF.clear();
        // The baseline is taken after the world settles: the ticks right after a server start are world generation, and a baseline taken during them is not a baseline.
        PERF.add(new PerfPending(source, space, count, source.getLevel().dimension(),
                source.getServer().getTickCount() + PERF_SETTLE_TICKS + PERF_WINDOW_TICKS));
        say(source, "perftest: letting the world settle for " + PERF_SETTLE_TICKS + " tick(s), then measuring an "
                + "idle window of " + PERF_WINDOW_TICKS + " tick(s), then " + count + " structure(s)", ChatFormatting.AQUA);
        return count;
    }

    /** How long each of the two windows is: the server's own ring holds a hundred ticks, so that is the window. */
    private static final int PERF_WINDOW_TICKS = 100;

    /** Ticks to let the world settle before the idle window, so that world generation is not counted as idle cost. */
    private static final int PERF_SETTLE_TICKS = 300;

    /** Extra ticks after the build before the loaded window is read, so the build tick leaves the ring. */
    private static final int PERF_AFTER_BUILD_TICKS = 10;

    /** A tick-time sample: what the server says about itself over one window. */
    private record Window(double averageMs, double worstMs, int samples) {

        static Window of(MinecraftServer server) {
            long[] times = server.getTickTimesNanos();
            long total = 0;
            long worst = 0;
            int samples = 0;
            for (long nanos : times) {
                if (nanos <= 0) {
                    continue;
                }
                total += nanos;
                worst = Math.max(worst, nanos);
                samples++;
            }
            return new Window(samples == 0 ? 0.0 : (total / (double) samples) / 1_000_000.0,
                    worst / 1_000_000.0, samples);
        }
    }

    /** One measurement in flight: the idle window, or the loaded one. */
    private static final class PerfPending {

        private final CommandSourceStack source;
        private final PocketSpace space;
        private final int count;
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> ownerDimension;
        private Window idle;
        private java.util.List<Integer> claimed = java.util.List.of();
        private int pockets;
        private int columns;
        private int maxColumns;
        private int deadline;

        private PerfPending(CommandSourceStack source, PocketSpace space, int count,
                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> ownerDimension,
                            int deadline) {
            this.source = source;
            this.space = space;
            this.count = count;
            this.ownerDimension = ownerDimension;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<PerfPending> PERF = new java.util.ArrayList<>();

    /** Walks each measurement through its two windows. Driven from the pocket level tick like the other self
     * tests; it times nothing itself, it only reports the server's own numbers. */
    private static void tickPerfTests(ServerLevel pocket) {
        if (PERF.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<PerfPending> waiting = PERF.iterator();
        while (waiting.hasNext()) {
            PerfPending test = waiting.next();
            if (server.getTickCount() < test.deadline) {
                continue;
            }
            if (test.idle == null) {
                // Window one is done: this is what the server costs with nothing of ours in it.
                test.idle = Window.of(server);
                buildMeasuredStructures(test);
                test.deadline = server.getTickCount() + PERF_AFTER_BUILD_TICKS + PERF_WINDOW_TICKS;
                continue;
            }
            waiting.remove();
            Window loaded = Window.of(server);
            double delta = loaded.averageMs() - test.idle.averageMs();
            String perStructure = test.claimed.isEmpty() ? "n/a"
                    : String.format(java.util.Locale.ROOT, "%+.3f", delta / test.claimed.size());
            String verdict = "PERF TEST (" + test.claimed.size() + " structure(s)): idle "
                    + String.format(java.util.Locale.ROOT, "%.2f", test.idle.averageMs()) + " ms average / "
                    + String.format(java.util.Locale.ROOT, "%.2f", test.idle.worstMs()) + " ms worst, loaded "
                    + String.format(java.util.Locale.ROOT, "%.2f", loaded.averageMs()) + " ms average / "
                    + String.format(java.util.Locale.ROOT, "%.2f", loaded.worstMs()) + " ms worst over "
                    + loaded.samples() + " tick(s) each; delta "
                    + String.format(java.util.Locale.ROOT, "%+.2f", delta) + " ms average ("
                    + perStructure + " ms per structure); pocket budget " + test.pockets + " pocket(s), "
                    + test.columns + "/" + test.maxColumns + " chunk column(s)";
            RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
            say(test.source, verdict, ChatFormatting.GREEN);
            // Built for a measurement, not to be kept: the plots go back, so the next measurement starts from the same world.
            for (int index : test.claimed) {
                test.space.release(index);
            }
        }
    }

    /** Claims the plots and builds one running circuit in each, for the loaded window to measure. */
    private static void buildMeasuredStructures(PerfPending test) {
        ServerLevel pocket = test.space.level();
        java.util.List<Integer> claimed = new java.util.ArrayList<>();
        BlockPos owner = new BlockPos(2000, 100, 2000);
        for (int i = 0; i < test.count; i++) {
            PocketSpace.Plotted plotted = test.space.claim(4, false, test.ownerDimension, owner);
            if (!plotted.allowed()) {
                refuse(test.source, "the budget refused structure " + (i + 1) + " of " + test.count + ": "
                        + plotted.refusal());
                break;
            }
            claimed.add(plotted.plot().index());
            buildRunningCircuit(pocket, plotted.plot().index());
        }
        test.claimed = claimed;
        test.pockets = test.space.budget().usedPockets();
        test.columns = test.space.budget().usedColumns();
        test.maxColumns = test.space.budget().maxColumns();
        say(test.source, "perftest: " + claimed.size() + " structure(s) built and running", ChatFormatting.GRAY);
    }

    /** The self test's circuit, running: lever → dust → repeater → lamp, with the lever thrown. */
    private static void buildRunningCircuit(ServerLevel pocket, int plotIndex) {
        BlockPos base = PocketSpace.originOf(plotIndex);
        BlockPos leverPos = base.above();
        for (int dx = 0; dx <= 3; dx++) {
            pocket.setBlock(base.offset(dx, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        pocket.setBlock(leverPos, Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH), 3);
        pocket.setBlock(leverPos.east(), Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        pocket.setBlock(leverPos.east(2), Blocks.REPEATER.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 3);
        pocket.setBlock(leverPos.east(3), Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        // Thrown after everything is in place: this neighbour update schedules the repeater's tick, so the measured window contains a scheduled tick.
        pocket.setBlock(leverPos, pocket.getBlockState(leverPos).cycle(LeverBlock.POWERED), 3);
        pocket.updateNeighborsAt(leverPos, Blocks.LEVER);
    }

    /** A release a crash interrupted, and the repair: {@link ReleaseJournal} records where a multi-tick release is
     * before its first block and the next server start finishes it; the test drops the in-memory job - all a restart
     * loses - and calls the server's own {@code ReleaseJournal.recover(server)}. */
    private static int journalTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        java.util.List<String> failed = new java.util.ArrayList<>();

        // Three stone blocks captured out of the world, so the target can be checked cell by cell.
        BlockPos origin = new BlockPos(8, 100, 90);
        BlockPos target = new BlockPos(8, 100, 96);
        for (int x = 0; x <= 3; x++) {
            world.setBlock(origin.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(origin.offset(x, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            world.setBlock(target.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            world.setBlock(target.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        }
        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(origin, origin.offset(2, 0, 0), IoMode.ACTIVATION_FACES,
                        "journal test", false, false, null),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "journal test could not capture its own blocks: " + captured.refusal());
            return 0;
        }
        StructurePayload payload = captured.captured().payload();
        try {
            StructureWorldStore.of(source.getServer(), RCConfig.structurePayloadLimits()).add(payload);
        } catch (java.io.IOException failedToStore) {
            refuse(source, "journal test could not write its structure: " + failedToStore);
            return 0;
        }
        java.util.UUID id = payload.header().uuid();
        java.nio.file.Path journals = ReleaseJournal.directory(source.getServer());

        // One block a tick, so the first tick of it is a release that is genuinely unfinished.
        StructureRelease.Result started = StructureRelease.start(world, payload, target, true,
                new StructureRelease.Options(1, false));
        if (!started.started()) {
            refuse(source, "journal test could not start its release: " + started.refusal());
            return 0;
        }
        if (journalFor(journals, id) == null) {
            failed.add("a multi-tick release must write down where it is before its first block");
        }
        StructureRelease.tick(world);
        int written = 0;
        int expected = payload.header().size().getX() * payload.header().size().getY()
                * payload.header().size().getZ();
        for (int x = 0; x < payload.header().size().getX(); x++) {
            if (world.getBlockState(target.offset(x, 0, 0)).is(Blocks.GOLD_BLOCK)) {
                written++;
            }
        }
        if (written != 1) {
            failed.add("one block of a one-block-a-tick release should be written, got " + written);
        }
        if (journalFor(journals, id) == null) {
            failed.add("the record must still be there while the release is unfinished");
        }

        // The crash: the jobs in memory are gone (which is all a restart loses), the journal is not.
        StructureRelease.forgetAll();
        if (journalFor(journals, id) == null) {
            failed.add("a release that was interrupted must leave its record behind");
        }
        int resumed = ReleaseJournal.recover(source.getServer());
        if (resumed < 1) {
            failed.add("the repair did not find the interrupted release");
        }
        StructureRelease.tick(world);
        int complete = 0;
        for (int x = 0; x < payload.header().size().getX(); x++) {
            if (world.getBlockState(target.offset(x, 0, 0)).is(Blocks.GOLD_BLOCK)) {
                complete++;
            }
        }
        if (complete != expected) {
            failed.add("the repaired release wrote " + complete + " of " + expected + " block(s)");
        }
        if (journalFor(journals, id) != null) {
            failed.add("the record must be gone once the release has finished");
        }

        String verdict = failed.isEmpty()
                ? "JOURNAL TEST PASSED: a release that one tick of was written, with its record on disk, was left "
                        + "to a crash (the in-memory job dropped); the repair resumed it and wrote all " + expected
                        + " block(s), and the record was removed when it finished"
                : "JOURNAL TEST FAILED: " + String.join("; ", failed);
        RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
        say(source, verdict, failed.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED);
        return failed.isEmpty() ? 1 : 0;
    }

    /** This release's journal file, or null - never "some file is there", which a parallel test could satisfy. */
    @Nullable
    private static java.nio.file.Path journalFor(java.nio.file.Path directory, java.util.UUID id) {
        java.nio.file.Path file = ReleaseJournal.fileOf(directory, id);
        return java.nio.file.Files.isRegularFile(file) ? file : null;
    }

    /** <b>天气探测器在口袋里照常工作.</b> The detector polls {@code level.isRaining()}/{@code isThundering()} once a
     * second, and the pocket's environment is synced from the overworld ({@code structurePocketSyncEnvironment}). */
    private static int weatherTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel world = source.getLevel();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel pocket = space.level();

        // A detector on a solid block, set to watch 雨天: the plate is a *filter* - you tell it which sky to watch, and it answers while that is the sky.
        BlockPos base = new BlockPos(8, 100, 110);
        world.setBlock(base.below(), Blocks.STONE.defaultBlockState(), 3);
        world.setBlock(base, RCRegistry.WEATHER_DETECTOR.defaultBlockState()
                .setValue(WeatherDetectorBlock.WEATHER, WeatherDetectorBlock.Mode.RAIN), 3);
        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(base, base, IoMode.ACTIVATION_FACES, "weather test", false, false,
                        null),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "weather test could not capture its own detector: " + captured.refusal());
            return 0;
        }
        PocketMaterializer.Result materialized = PocketMaterializer.materialize(world,
                captured.captured().payload(), true, world.dimension(), base);
        if (!materialized.materialized()) {
            refuse(source, "weather test could not get a pocket: " + materialized.refusal());
            return 0;
        }
        BlockPos origin = PocketSpace.originOf(materialized.plot());
        // A lamp beside the plate: the detector answers 15 while it is active.
        BlockPos lamp = origin.offset(1, 0, 0);
        pocket.setBlock(lamp, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);

        // The negative control starts from a known sky: whatever the server's weather was, make it clear first.
        world.setWeatherParameters(6000, 0, false, false);
        WEATHER.add(new WeatherPending(source, world, pocket, materialized.plot(), origin, lamp,
                source.getServer().getTickCount() + SELF_TEST_TICKS));
        say(source, "weather test: plot " + materialized.plot() + " at " + origin.toShortString()
                + " holds a weather detector, lamp at " + lamp.toShortString()
                + "; clear, then rain, then thunder", ChatFormatting.AQUA);
        return 1;
    }

    /** One weather test in flight, and which sky it is waiting for. */
    private static final class WeatherPending {

        private final CommandSourceStack source;
        private final ServerLevel world;
        private final ServerLevel pocket;
        private final int plot;
        private final BlockPos detector;
        private final BlockPos lamp;
        private int phase;
        private int deadline;
        private boolean sawDarkUnderRainSetting;
        private boolean sawLitWhileRaining;
        private boolean sawDarkUnderClearSetting;

        private WeatherPending(CommandSourceStack source, ServerLevel world, ServerLevel pocket, int plot,
                               BlockPos detector, BlockPos lamp, int deadline) {
            this.source = source;
            this.world = world;
            this.pocket = pocket;
            this.plot = plot;
            this.detector = detector;
            this.lamp = lamp;
            this.deadline = deadline;
        }
    }

    private static final java.util.List<WeatherPending> WEATHER = new java.util.ArrayList<>();

    /** Walks two skies against two settings, in both directions. Each phase waits for the detector's own answer: the
     * plate polls once a second and the pocket copies the weather once per pocket tick, so reading the lamp in the same tick measures nothing. */
    private static void tickWeatherTests(ServerLevel pocket) {
        if (WEATHER.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        java.util.Iterator<WeatherPending> waiting = WEATHER.iterator();
        while (waiting.hasNext()) {
            WeatherPending test = waiting.next();
            boolean lit = lampLit(test.pocket, test.lamp);
            boolean active = detectorActive(test.pocket, test.detector);
            boolean arrived = switch (test.phase) {
                // Watching 雨天, clear sky.
                case 0 -> !active && !lit;
                // Watching 雨天, and now it rains.
                case 1 -> active && lit;
                // Watching 晴天 while it still rains: the same sky, the other setting, and it must be dark.
                case 2 -> !active && !lit;
                // Watching 晴天, and the sky clears.
                default -> active && lit;
            };
            if (!arrived && server.getTickCount() < test.deadline) {
                continue;
            }
            switch (test.phase) {
                case 0 -> {
                    test.sawDarkUnderRainSetting = arrived;
                    test.world.setWeatherParameters(0, 6000, true, false);
                }
                case 1 -> {
                    test.sawLitWhileRaining = arrived;
                    // The other half: switch the plate to watch 晴天 while it still rains.
                    test.pocket.setBlock(test.detector, test.pocket.getBlockState(test.detector)
                            .setValue(WeatherDetectorBlock.WEATHER, WeatherDetectorBlock.Mode.CLEAR), 3);
                }
                case 2 -> {
                    test.sawDarkUnderClearSetting = arrived;
                    test.world.setWeatherParameters(6000, 0, false, false);
                }
                default -> {
                    waiting.remove();
                    boolean pass = test.sawDarkUnderRainSetting && test.sawLitWhileRaining
                            && test.sawDarkUnderClearSetting && arrived;
                    String verdict = pass
                            ? "WEATHER TEST PASSED: a weather detector captured into a pocket plot followed the "
                                    + "overworld's own sky in both directions - watching 雨天 it was dark under a clear "
                                    + "sky and lit while it rained, and watching 晴天 it was dark while it was still "
                                    + "raining and lit once the sky cleared - so the pocket's environment really is the "
                                    + "overworld's"
                            : "WEATHER TEST FAILED: darkUnderRainSetting=" + test.sawDarkUnderRainSetting
                                    + " litWhileRaining=" + test.sawLitWhileRaining + " darkUnderClearSetting="
                                    + test.sawDarkUnderClearSetting + " at phase " + test.phase + " active=" + active
                                    + " lamp=" + lit + " setting=" + detectorSetting(test.pocket, test.detector)
                                    + " pocketRaining=" + test.pocket.isRaining() + " pocketThundering="
                                    + test.pocket.isThundering() + " overworldRaining=" + test.world.isRaining()
                                    + " overworldThundering=" + test.world.isThundering();
                    RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
                    say(test.source, verdict, pass ? ChatFormatting.GREEN : ChatFormatting.RED);
                    PocketMaterializer.release(test.pocket, test.plot);
                    continue;
                }
            }
            test.phase++;
            test.deadline = server.getTickCount() + SELF_TEST_TICKS;
        }
    }

    /** The detector's own answer: true while the sky is the one it was set to watch. */
    private static boolean detectorActive(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(RCRegistry.WEATHER_DETECTOR) && state.getValue(WeatherDetectorBlock.ACTIVE);
    }

    /** Which sky the plate was told to watch, or null when the block there is not one of ours. */
    @Nullable
    private static WeatherDetectorBlock.Mode detectorSetting(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(RCRegistry.WEATHER_DETECTOR) ? state.getValue(WeatherDetectorBlock.WEATHER) : null;
    }

    /** <b>一个被搬走的产物方块，图纸不会被回收.</b> A plot is stamped as travelling when its block is picked up, and
     * the sweep - which hands back every plot whose owner is gone - must leave it and its blocks alone; the block's own
     * tick ({@code StructureBlockEntity.serverTick}) then re-owns the plot where it lands and clears the stamp. */
    private static int ownershipTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        // Queued behind the other self tests: it calls the real sweep, which is global, and their plots are fixtures with no product block behind them.
        OWNERSHIP.addLast(source);
        say(source, "ownership test: queued, it runs about " + (OWNERSHIP_DELAY_TICKS / 20)
                + "s behind the other self tests (it calls the real sweep)", ChatFormatting.AQUA);
        return 1;
    }

    /** Commands waiting to run the ownership test, in order. */
    private static final java.util.ArrayDeque<CommandSourceStack> OWNERSHIP = new java.util.ArrayDeque<>();

    /** How long the ownership test waits for the other self tests. */
    private static final int OWNERSHIP_DELAY_TICKS = 800;

    private static void tickOwnershipTests(ServerLevel pocket) {
        if (OWNERSHIP.isEmpty()) {
            return;
        }
        MinecraftServer server = pocket.getServer();
        if (server.getTickCount() % OWNERSHIP_DELAY_TICKS != 0) {
            return;
        }
        CommandSourceStack source = OWNERSHIP.pollFirst();
        if (source != null) {
            runOwnershipTest(source);
        }
    }

    private static void runOwnershipTest(CommandSourceStack source) {
        ServerLevel world = source.getLevel();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return;
        }
        ServerLevel pocket = space.level();
        java.util.List<String> failed = new java.util.ArrayList<>();

        // A structure with a plot of its own, captured and materialised in the world.
        BlockPos home = new BlockPos(8, 100, 130);
        world.setBlock(home.below(), Blocks.STONE.defaultBlockState(), 3);
        world.setBlock(home, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        StructureCapture.Result captured = StructureCapture.capture(world,
                new StructureCapture.Request(home, home, IoMode.MANUAL, "ownership test", false, false, null),
                RCConfig.structureCaptureLimits());
        if (!captured.allowed()) {
            refuse(source, "ownership test could not capture: " + captured.refusal());
            return;
        }
        PocketMaterializer.Result materialized = PocketMaterializer.materialize(world,
                captured.captured().payload(), true, world.dimension(), home);
        if (!materialized.materialized()) {
            refuse(source, "ownership test could not get a pocket: " + materialized.refusal());
            return;
        }
        int plot = materialized.plot();
        java.util.UUID structureId = captured.captured().payload().header().uuid();
        // A plot must belong to a block that is really there: the materialiser writes only the *pocket* copy, so the product block in the world is the caller's business.
        world.setBlock(home.below(), Blocks.STONE.defaultBlockState(), 3);
        world.setBlock(home, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (world.getBlockEntity(home) instanceof StructureBlockEntity product) {
            product.setStructure(structureId, "ownership test", new Vec3i(1, 1, 1), IoMode.MANUAL, 0);
            product.setPlotIndex(plot);
        } else {
            failed.add("the ownership test could not place the product block that owns the plot");
        }
        PocketPlots.Entry before = PocketPlots.get(pocket).entry(plot);
        if (before == null || !before.ownerPos().equals(home)) {
            failed.add("the plot's record must start where the block was placed, was " + before);
        }
        BlockState insideBefore = pocket.getBlockState(PocketSpace.originOf(plot));

        // The pickup: the plot is stamped, and the block leaves the world.
        long now = world.getGameTime();
        if (!PocketSpace.of(pocket).noteTravelling(world.dimension(), home, now)) {
            failed.add("a plot whose block is picked up must be stamped as travelling");
        }
        PocketPlots.Entry travelling = PocketPlots.get(pocket).entry(plot);
        if (travelling == null || travelling.travellingSince() != now) {
            failed.add("and the stamp must be the moment it left, was " + travelling);
        }
        world.setBlock(home, Blocks.AIR.defaultBlockState(), 3);
        if (PocketPlots.get(pocket).entry(plot) == null) {
            failed.add("the record must survive the block being taken out of the world");
        }
        java.util.List<Integer> swept = PocketSpace.of(pocket).sweep();
        if (swept.contains(plot)) {
            failed.add("THE SWEEP RECLAIMED A PLOT THAT IS TRAVELLING - this is the data loss this test exists for");
        }
        if (PocketPlots.get(pocket).entry(plot) == null) {
            failed.add("and the plot must still be recorded");
        }
        if (!pocket.getBlockState(PocketSpace.originOf(plot)).equals(insideBefore)) {
            failed.add("and its blocks must still be in the pocket, was " + insideBefore + " now "
                    + pocket.getBlockState(PocketSpace.originOf(plot)));
        }

        // The landing, somewhere else entirely, with the same block ticked for real.
        BlockPos landed = new BlockPos(12, 100, 134);
        world.setBlock(landed.below(), Blocks.STONE.defaultBlockState(), 3);
        world.setBlock(landed, RCRegistry.STRUCTURE.defaultBlockState(), 3);
        if (world.getBlockEntity(landed) instanceof StructureBlockEntity product) {
            product.setStructure(structureId, "ownership test", new Vec3i(1, 1, 1), IoMode.MANUAL, 0);
            product.setPlotIndex(plot);
            // The block's own tick is what tells the table where it is.
            StructureBlockEntity.serverTick(world, landed, world.getBlockState(landed), product);

            PocketPlots.Entry after = PocketPlots.get(pocket).entry(plot);
            if (after == null || !after.ownerPos().equals(landed)
                    || !after.ownerDimension().equals(world.dimension())) {
                failed.add("the record must follow the block to where it landed, was " + after);
            } else if (after.travellingSince() != 0) {
                failed.add("and the travelling stamp must be cleared by the landing");
            }
            if (!pocket.getBlockState(PocketSpace.originOf(plot)).equals(insideBefore)) {
                failed.add("and the plot's blocks must be exactly as they were");
            }
            if (PocketSpace.of(pocket).sweep().contains(plot)) {
                failed.add("a plot whose owner is standing right there must never be swept");
            }
            // A second block claiming the same plot must not steal it: the record stays where it is.
            BlockPos thief = new BlockPos(16, 100, 138);
            world.setBlock(thief, RCRegistry.STRUCTURE.defaultBlockState(), 3);
            if (world.getBlockEntity(thief) instanceof StructureBlockEntity copy) {
                copy.setStructure(java.util.UUID.randomUUID(), "a copy", new Vec3i(1, 1, 1), IoMode.MANUAL, 0);
                copy.setPlotIndex(plot);
                StructureBlockEntity.serverTick(world, thief, world.getBlockState(thief), copy);
            }
            PocketPlots.Entry still = PocketPlots.get(pocket).entry(plot);
            if (still == null || !still.ownerPos().equals(landed)) {
                failed.add("a second block holding the same plot index must not take the plot over, was " + still);
            }
            world.setBlock(thief, Blocks.AIR.defaultBlockState(), 3);
            world.destroyBlock(landed, false);
        } else {
            failed.add("the ownership test could not place the landed block");
        }
        PocketMaterializer.release(pocket, plot);
        world.setBlock(home.below(), Blocks.AIR.defaultBlockState(), 3);

        String verdict = failed.isEmpty()
                ? "OWNERSHIP TEST PASSED: a plot survived its block being taken out of the world by a moving "
                        + "structure - it was stamped as travelling, the sweep left it and its blocks alone, the block's "
                        + "own tick re-owned the plot where it landed (stamp cleared), a second block holding the same "
                        + "plot index did not take it over, and the blocks inside were untouched throughout"
                : "OWNERSHIP TEST FAILED: " + String.join("; ", failed);
        RCConfig.LOGGER.info("[redstonecircuit] pocket: {}", verdict);
        say(source, verdict, failed.isEmpty() ? ChatFormatting.GREEN : ChatFormatting.RED);
    }

    private static boolean isDust(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.REDSTONE_WIRE);
    }

    private static boolean isLamp(ServerLevel level, BlockPos pos) {
        return level.getBlockState(pos).is(Blocks.REDSTONE_LAMP);
    }

    private static int selfTest(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        PocketSpace space = pocketOrReport(source);
        if (space == null) {
            return 0;
        }
        ServerLevel pocket = space.level();

        // The owner is recorded for the repair sweep's sake; this test releases its plot at the end anyway.
        PocketSpace.Plotted claimed = space.claim(4, false, source.getLevel().dimension(),
                BlockPos.containing(source.getPosition()));
        if (!claimed.allowed()) {
            refuse(source, "the budget refused a 4³ self test: " + claimed.refusal());
            return 0;
        }
        BlockPos base = claimed.plot().origin();
        say(source, "plot " + claimed.plot().index() + " at " + base.toShortString()
                + ", building lever -> dust -> repeater -> lamp", ChatFormatting.AQUA);

        BlockPos leverPos = base.above();
        BlockPos dustPos = leverPos.east();
        BlockPos repeaterPos = leverPos.east(2);
        BlockPos lampPos = leverPos.east(3);

        for (int dx = 0; dx <= 3; dx++) {
            pocket.setBlock(base.offset(dx, 0, 0), Blocks.STONE.defaultBlockState(), 3);
        }
        BlockState lever = Blocks.LEVER.defaultBlockState()
                .setValue(FaceAttachedHorizontalDirectionalBlock.FACE, AttachFace.FLOOR)
                .setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH);
        pocket.setBlock(leverPos, lever, 3);
        pocket.setBlock(dustPos, Blocks.REDSTONE_WIRE.defaultBlockState(), 3);
        pocket.setBlock(repeaterPos, Blocks.REPEATER.defaultBlockState()
                .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST), 3);
        pocket.setBlock(lampPos, Blocks.REDSTONE_LAMP.defaultBlockState(), 3);
        // Thrown after everything is in place: this neighbour update schedules the repeater's tick, which the plot's ticket has to be able to run.
        pocket.setBlock(leverPos, pocket.getBlockState(leverPos).cycle(LeverBlock.POWERED), 3);
        pocket.updateNeighborsAt(leverPos, Blocks.LEVER);

        PENDING.add(new Pending(source, base, lampPos, dustPos, repeaterPos, claimed.plot().index(),
                claimed.plot().columns(), source.getServer().getTickCount() + SELF_TEST_TICKS));
        say(source, "checking in up to " + SELF_TEST_TICKS + " tick(s)...", ChatFormatting.GRAY);
        return 1;
    }

    private PocketCommand() {
    }
}
