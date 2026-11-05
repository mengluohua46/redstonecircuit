package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.RCConfig;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 方块放置器's wiring: the lines no test above can see, plus the files a dedicated server never loads.
 *
 * <p>After {@code PlacerRuleTest} and {@code BlockPlacerGameTest}, what is left is the shape of the machine,
 * and each item is a line that can be moved or dropped while both of those stay green: which vanilla answer
 * each question goes to (the placement is vanilla's own {@code BlockItem#place}, the two orientation
 * questions answered from the output face), the rising edge, the physical half staying in front of the
 * placement, the nine-slot {@code DispenserMenu}, and the resources.
 */
class BlockPlacerWiringTest {

    private static final String BLOCK = "BlockPlacerBlock.java";
    private static final String ENTITY = "BlockPlacerBlockEntity.java";

    @Test
    @DisplayName("正面是输出面，而且只有上升沿动作（含一处不抄 vanilla 的条款）")
    void theFrontFaceIsTheOutputAndOnlyARisingEdgeFires() throws IOException {
        String block = block();

        assertTrue(block.contains("public static final DirectionProperty FACING = BlockStateProperties.FACING;"),
                "the front can point any of the six ways, like an observer's and 逻辑整合器's");
        assertTrue(block.contains("return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());"),
                "and it points back at whoever placed the machine (the piston's own rule)");
        assertTrue(block.contains("BlockPos front = pos.relative(out);"),
                "the receiving cell is the one step in front of that face");
        assertTrue(block.contains("if (powered == state.getValue(TRIGGERED)) {\n            return false;\n        }"),
                "a rising edge arms, a falling edge releases, and an unchanged "
                        + "signal is not an event at all: this one line is what keeps a lever that is left on from "
                        + "placing a block per tick");
        assertTrue(block.contains("level.scheduleTick(pos, state.getBlock(), PLACE_DELAY_TICKS);"),
                "the world is written on the tick after the edge, not from inside the neighbour update");
        assertTrue(block.contains("level.setBlock(pos, state.setValue(TRIGGERED, powered), Block.UPDATE_CLIENTS);"),
                "and the latch is kept in the block state, where it survives a save without a block entity being awake");
        String latch = block.substring(block.indexOf("private static boolean latch(ServerLevel"),
                block.indexOf("protected void tick("));
        assertFalse(latch.contains("pos.above()"),
                "vanilla's dispenser also fires when the block ABOVE it is powered; that clause is not "
                        + "copied here - this block's own six faces trigger it");
    }

    @Test
    @DisplayName("放置走 vanilla 自己的 BlockItem#place，朝向由输出面回答")
    void thePlacementIsVanillasOwnWithTheOutputFaceAnswering() throws IOException {
        String block = block();

        assertTrue(block.contains("InteractionResult placed = place(context, stack);")
                        && block.contains("return blockItem.place(context);"),
                "the block goes down through vanilla's own placement, so waterlogging, block-entity data carried by "
                        + "the item, the place sound and the special cases are vanilla's rather than a copy of them - "
                        + "and the context built for the verdict is the very one used to place");
        assertTrue(block.contains("private static BlockHitResult hitAt(BlockPos pos, Direction face) {"),
                "with the synthetic click built in one place, because both paths need one and they must be the same "
                        + "kind of click (the world path clicks the machine, the plot path clicks the cell)");
        assertTrue(block.contains("return new BlockHitResult(faceCentre(pos, face), face, pos, false);"),
                "and the synthetic click is on the clicked block's own face, so the cell that receives the block is the "
                        + "one vanilla already puts an ordinary placement into");
        assertTrue(block.contains("return new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.5,"),
                "with vanilla's own hit vector (the middle of that face, BlockPlaceContext#at), which is what a slab "
                        + "reads to decide top or bottom");
        assertTrue(block.contains("public Direction getHorizontalDirection() {\n            return this.out;\n        }"),
                "the direction a player would be looking is the output face, which is what stairs, "
                        + "logs and everything else that takes FACING straight from it reads");
        assertTrue(block.contains("public Direction getNearestLookingDirection() {\n            return this.out.getOpposite();\n        }"),
                "and the opposite is answered for the blocks that flip this question (pistons, observers), so that "
                        + "their front ends up pointing along the output as well: one meaning of \"朝向\", two vanilla "
                        + "conventions");
        assertTrue(block.contains("if (placed.consumesAction()) {")
                        && block.contains("placer.setItem(slot, stack);")
                        && !block.contains(".shrink("),
                "vanilla's place already consumes the item (ItemStack#consume) from the stack it was handed - the "
                        + "container's own stack - so the machine only writes it back. An extra shrink here would take "
                        + "two blocks per activation and be invisible until someone counted");
    }

    @Test
    @DisplayName("物理那半：交给 Sable 新建一个只含这一格的结构，而且必须在世界放置之前分岔")
    void aCarriedMachinePlacesAStandaloneStructure() throws IOException {
        String block = block();

        assertTrue(block.contains("ProductFrame frame = ProductFrame.carriedAt(level, pos);")
                        && block.contains("if (frame.carried()) {"),
                "the machine asks this mod's own carrier "
                        + "layer where it is, the same question the rest of the carried code asks");
        assertTrue(block.contains("PhysicsCompat.placeSingleBlockStructure(level, drawn, out, stack)"),
                "and the physical half is one call into the compat gate, because this source set may not name a Sable "
                        + "type at all");
        assertTrue(block.indexOf("if (frame.carried()) {") < block.indexOf("place(context, stack)"),
                "the branch has to come FIRST: a carried machine that went on to place a world block would put it at a "
                        + "plot coordinate - in the wrong place, and left behind the moment the structure moved");
        assertTrue(block.contains("level.getBlockState(front).canBeReplaced(probe)")
                        && block.contains("level.getBlockState(drawn).canBeReplaced(probe)"),
                "the cell in front is asked once per coordinate frame - the cell inside the machine's OWN structure and "
                        + "the world cell the new structure would be drawn at (a carried block lives in "
                        + "two frames, and either one being taken means nothing happens)");
        assertTrue(block.contains("public static InteractionResult placeInto(Level level, BlockPos cell, Direction out, ItemStack stack) {"),
                "and the block goes into the new structure through the same public helper the world path's own game "
                        + "test drives, not through a second implementation of \"placing a block\"");
        assertTrue(block.contains("level.getBlockState(cell).canBeReplaced(context) ? place(context, stack) : InteractionResult.FAIL"),
                "which promises \"into THIS cell or not at all\": vanilla's rule for an unreplaceable cell is to place "
                        + "beside it, and for a structure that was just made that could only ever be a bug");
    }

    @Test
    @DisplayName("方块先正常进世界，再由 Sable 自己的组装接口变成独立结构；本模组不碰它那本账")
    void theSableHalfGoesThroughSablesOwnAssembly() throws IOException {
        String placement = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "SablePlacerPlacement.java");

        assertTrue(placement.contains("SubLevelAssemblyHelper.assembleBlocks(level, drawnTarget, List.of(drawnTarget), box)"),
                "the assembly is Sable's own call - the very one its physics "
                        + "assembler makes: the plot, the chunk, its contents, the storage "
                        + "record and the pose are all Sable's business, done in the order Sable expects");
        assertTrue(placement.contains("BlockPlacerBlock.placeInto(level, drawnTarget, out, stack)"),
                "and the block itself goes down through the world path's own placement first, so the two paths cannot "
                        + "disagree about orientation, waterlogging or what counts as a cell that can take a block");
        assertTrue(placement.indexOf("placeInto(level, drawnTarget") < placement.indexOf("assembleBlocks("),
                "in that order: there has to be a block in the world before there is anything to assemble");

        // The safety property, asserted on the IMPORTS rather than on the prose around them: the file must not
        // be able to name the allocating, plot-creating, pose-writing or removing types at all.
        assertFalse(placement.contains("import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;")
                        || placement.contains("import dev.ryanhcode.sable.sublevel.plot.LevelPlot;")
                        || placement.contains("import dev.ryanhcode.sable.sublevel.SubLevel;")
                        || placement.contains("import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;"),
                "so this mod touches NONE of Sable's structure bookkeeping itself: no allocating plots, no "
                        + "creating chunks by hand, no writing poses and - above all - no REMOVING anything");

        assertTrue(placement.contains("return true;"),
                "while a refusal to assemble is not a failure the player pays for: the block is already placed, so it "
                        + "simply stays an ordinary block and the item stays spent");
    }

    @Test
    @DisplayName("世界拒绝之后才回退到支撑结构的 plot，坐标只从 localBlockOf 来，探察方向有界")
    void aRefusedWorldPlacementIsOfferedToTheSupportStructuresPlot() throws IOException {
        String placement = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "SablePlacerPlacement.java");

        assertTrue(placement.contains("if (!placed.consumesAction()) {"),
                "the fallback is offered to the world placement's own answer: consumesAction() is the one thing "
                        + "vanilla says for \"the block went down\", so a placement that succeeded never reaches it");
        assertTrue(offsetOf(placement, "if (!placed.consumesAction())")
                        < offsetOf(placement, "return placeIntoSupportStructure(level, drawnTarget, out, stack);"),
                "and the call sits INSIDE that refusal, so the world path's behaviour - assembling one structure, or "
                        + "leaving an ordinary block behind - is untouched for every placement that does go down");
        assertEquals(2, placement.split("placeIntoSupportStructure\\(", -1).length - 1,
                "with exactly one call and one declaration, so no other path can write into a structure's plot behind "
                        + "the world placement's back");

        assertTrue(placement.contains("BlockPos supportCell = CarrierProjection.localBlockOf(pose, probeCell);"),
                "the probe cell is converted back by this mod's one world-cell-to-structure-cell answer, so no second "
                        + "rounding is written here");
        assertTrue(placement.contains("if (supportCell == null || level.getBlockState(supportCell).isAir()) {"),
                "and the probe is believed only when the structure's OWN storage holds a block there: carrierAt "
                        + "answers from a world bounding box, and a turned structure's box overhangs the blocks it "
                        + "holds, so without this proof a probe writes into a structure that is not actually there - "
                        + "into whatever plot coordinate the inverse happens to land on");
        assertTrue(offsetOf(placement, "level.getBlockState(supportCell)")
                        < offsetOf(placement, "BlockPlacerBlock.placeInto(level, plotCell"),
                "asked before anything is written into a plot, or it cannot stop that write");
        assertTrue(placement.contains("BlockPos plotCell = CarrierProjection.localBlockOf(pose, drawnTarget);"),
                "while the cell that receives the block is the TARGET cell, not the probe's: the probe cell's own "
                        + "coordinate would write the block into the floor it stands on");
        assertFalse(placement.contains("transformPositionInverse(")
                        || placement.contains("blockCentre(")
                        || placement.contains("Math.floor("),
                "and no second rounding is written here: a pose transforms block corners, so a hand-rolled floor of "
                        + "the inverted point picks the cell beside the one the player acted on as soon as the "
                        + "structure is turned");
        assertTrue(placement.contains("BlockPos probeCell = drawnTarget.relative(probe);")
                        && placement.contains("source.carrierAt(level, probeCell)"),
                "the probe cell is named once and asked of the carrier layer, which is the only place that knows what "
                        + "a Sable sub-level's world bounds are - and it is that same cell the structure's plot then has "
                        + "to prove it really holds");

        assertTrue(placement.contains("Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, "
                        + "Direction.WEST, Direction.UP,"),
                "the probe order is a literal: down first (that is the cell a sapling, a seed or a torch asks "
                        + "vanilla about), then the four sides, then up - and it must be the same order on every "
                        + "activation, or the same machine would keep choosing a different structure");
        assertTrue(placement.contains("for (Direction probe : SUPPORT_PROBES) {"),
                "walked once, so one activation may look at six cells and write at most one plot");
        assertFalse(placement.contains("Direction.values()"),
                "and never over every direction: an unbounded probe would write into whichever structure happened to "
                        + "be listed first rather than into the one holding the support");

        String loop = bodyOf(placement, "for (Direction probe : SUPPORT_PROBES) {");
        assertFalse(loop.contains("return false;"),
                "and a direction that is not the answer must not end the search: the six probes ARE the search, so a "
                        + "false inside the loop would abandon the cell that really does hold the support");
        assertTrue(loop.contains("continue;"),
                "each rejected direction skips to the next one instead, and the only false is the summary after all "
                        + "six were tried");
        assertTrue(loop.contains("return true;"),
                "with the one return that spends the item being a placement that actually went down");
    }

    @Test
    @DisplayName("物理那半（门面）：没有 Sable 的时候答案必须是 false，而不是去加载一个 Sable 类型")
    void theFacadeAnswersFalseWithoutSable() throws IOException {
        String gate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "PhysicsCompat.java");

        assertTrue(gate.contains("if (backendClass == null || !sableLoaded() || level == null || drawnTarget == null || out == null"),
                "the gate answers false - \"nothing was placed\", so nothing is spent - without touching a Sable type");
        assertTrue(gate.contains("invoke(backendClass, \"placeSingleBlockStructure\","),
                "and reaches the compat half by name, which is the one rule this mod's optional-dependency layer is "
                        + "built on: main never links against compat");
        assertTrue(gate.contains("return answer instanceof Boolean placed && placed;"),
                "with the answer read as a boolean, so a missing backend (null) is false rather than an exception");
    }

    @Test
    @DisplayName("界面就是发射器的那一个：九格 + DispenserMenu + 只收方块")
    void theWindowIsVanillasDispenser() throws IOException {
        String block = block();
        String entity = entity();

        assertTrue(entity.contains("public class BlockPlacerBlockEntity extends RandomizableContainerBlockEntity {"),
                "vanilla's own container base class is what brings saving, the still-valid check, "
                        + "hopper insertion and the comparator's fill reading - not a look-alike");
        assertTrue(entity.contains("public static final int CONTAINER_SIZE = 9;"), "nine slots, like the dispenser's");
        assertTrue(entity.contains("return new DispenserMenu(containerId, playerInventory, this);"),
                "and the menu IS vanilla's DispenserMenu - registered under MenuType.GENERIC_3x3 and drawn by "
                        + "DispenserScreen - so this mod has no screen, no texture and no menu registration of its own "
                        + "for it, and cannot drift from the dispenser's window");
        assertTrue(entity.contains("return !stack.isEmpty() && stack.getItem() instanceof BlockItem;"),
                "the slot, the hopper and the fast-shift all ask this one question, so an item that "
                        + "cannot be placed never gets in and never looks broken");
        assertTrue(entity.contains("return PlacerRule.firstFilled(filled);"),
                "and the slot rule is the tested one rather than a loop written here");
        assertTrue(block.contains("player.openMenu(placer);"),
                "the machine opens that window on the server, which is the side that owns the slots");
        assertTrue(block.contains("public void playerDestroy(Level level, Player player, BlockPos pos, BlockState state,")
                        && block.contains("Containers.dropContents(serverLevel, pos, placer);"),
                "the drop hangs on the PLAYER's half of a removal, because a carrier "
                        + "takes a block out of the world by removing it: the dispenser's own "
                        + "dropContentsOnDestroy would spill the box on the ground while the very same slots travelled "
                        + "into the plot");
        assertFalse(block.contains("protected void onRemove("),
                "so there is no onRemove override at all: that is the path a contraption or a physics assembler takes, "
                        + "and it must not touch the box. Vanilla's own line cannot be argued with either - it spills for "
                        + "a piston move as happily as for an explosion, because all it asks is whether the block changed");
        assertTrue(block.contains("return AbstractContainerMenu.getRedstoneSignalFromBlockEntity(level.getBlockEntity(pos));"),
                "and a comparator reads the box, as it does a dispenser's");
    }

    @Test
    @DisplayName("资源：六个朝向、贴图与底面、掉落表、木镐标签、配方、两份语言")
    void theResourcesAreTheAuthors() throws IOException {
        String blockstate = read("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "block_placer.json");
        for (String facing : List.of("facing=north", "facing=east", "facing=south", "facing=west", "facing=up",
                "facing=down")) {
            assertTrue(blockstate.contains("\"" + facing + "\""),
                    "a blockstate that misses " + facing + " leaves that facing with no model at all - the machine "
                            + "would be invisible from one side");
        }
        assertTrue(blockstate.contains("\"x\": 270") && blockstate.contains("\"x\": 90"),
                "up and down are the same model turned on its side, like the observer's");

        String model = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                "block_placer.json");
        assertTrue(model.contains("\"north\": \"redstonecircuit:block/block_placer_output\""),
                "the 输出 texture is the front face");
        assertTrue(model.contains("\"down\": \"minecraft:block/furnace_top\""),
                "vanilla's dispenser bottom is that same furnace_top, and taking it "
                        + "from vanilla rather than copying the PNG keeps it consistent with the dispenser next to it");
        assertTrue(model.contains("redstonecircuit:block/block_placer_side"),
                "and the sides are the 侧面 texture");

        assertTrue(read("src", "main", "resources", "data", "redstonecircuit", "loot_table", "blocks",
                        "block_placer.json").contains("\"name\": \"redstonecircuit:block_placer\""),
                "a block with no loot table is a block that is simply gone when it is mined");
        assertTrue(read("src", "main", "resources", "data", "minecraft", "tags", "block", "mineable",
                        "pickaxe.json").contains("\"redstonecircuit:block_placer\""),
                "the pickaxe tag and no needs_*_tool tag is what makes it mineable with a wooden pickaxe");

        String recipe = read("src", "main", "resources", "data", "redstonecircuit", "recipe", "block_placer.json");
        assertTrue(recipe.contains("\"CPC\"") && recipe.contains("\"PIP\""),
                "配方：中间一个铁锭，正上下左右为任意木板，四个角为圆石");
        assertTrue(recipe.contains("\"tag\": \"minecraft:planks\""),
                "and \"任意木板\" is the tag, not one wood type - the recipe test crafts it with two different ones");

        for (String language : List.of("zh_cn.json", "en_us.json")) {
            String lang = read("src", "main", "resources", "assets", "redstonecircuit", "lang", language);
            assertTrue(lang.contains("\"block.redstonecircuit.block_placer\""),
                    language + " is missing the block's name");
            assertTrue(lang.contains("\"container.redstonecircuit.block_placer\""),
                    language + " is missing the window's title - without it the dispenser's window shows a raw "
                            + "translation key where the machine's name should be");
            assertTrue(lang.contains("\"block.redstonecircuit.block_placer.tip\""),
                    language + " is missing the tooltip that says the front is the output face and only blocks go in");
        }
    }

    @Test
    @DisplayName("红石口径：两个坐标系都问，而且载体那条每 tick 的走查必须问它（它不是信号源）")
    void theSignalIsAskedInBothFramesAndByTheCarrierWalk() throws IOException {
        String block = block();

        assertTrue(block.contains("private static boolean powered(ServerLevel level, BlockPos pos, BlockState state) {")
                        && block.contains("BlockPos drawn = frame.worldPosOf(pos);")
                        && block.contains("level.hasNeighborSignal(drawn)"),
                "a plot cell's neighbours are the plot's, so a signal the world carries beside where the machine is "
                        + "DRAWN never arrives. The question is asked at the cell and at the drawn block");
        assertTrue(block.contains("level.hasNeighborSignal(pos)"),
                "the cell's own six sides first, which is what an ordinary machine on the ground answers with");
        assertTrue(block.contains("public static void refreshCarried(ServerLevel level, BlockPos pos, BlockState state) {")
                        && block.contains("latch(serverLevel, pos, state);"),
                "and the carrier's walk gets the same rising edge the neighbour update gets - one latch, two askers");
        assertTrue(block.contains("level.scheduleTick(pos, state.getBlock(), PLACE_DELAY_TICKS);"),
                "with the placement still deferred to the next tick, whichever of the two asked");

        String driver = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableProductDriver.java");
        assertTrue(driver.contains("instanceof com.jiangyuefengyu.redstonecircuit.block.BlockPlacerBlock"),
                "the walk has to look for the machine by class: it is not a signal source, so the filter that is there "
                        + "for sources would skip exactly the block that needs asking");
        assertTrue(driver.indexOf("BlockPlacerBlock.refreshCarried(level, cell, state)") < driver.indexOf("if (!state.isSignalSource())"),
                "and the visit must come BEFORE that filter - that order is the whole bug this closes");
    }

    @Test
    @DisplayName("子维度创建默认开启，而且开关必须在分配之前判")
    void creatingAStructureIsOnByDefault() throws IOException {
        assertTrue(RCConfig.placerCreatesStructures(),
                "the machine assembles a structure for what it places unless the player turns that off; the switch is "
                        + "what keeps a world with a damaged saved sub-level from being written to at all");

        String placement = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SablePlacerPlacement.java");
        assertTrue(placement.contains("if (!RCConfig.placerCreatesStructures()) {"),
                "and the placement refuses before anything else");
        assertTrue(placement.indexOf("if (!RCConfig.placerCreatesStructures()) {")
                        < placement.indexOf("BlockPlacerBlock.placeInto(level, drawnTarget"),
                "before the block is placed and before anything is assembled: with the switch off a world is not written "
                        + "to at all");
    }

    private static String block() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", BLOCK);
    }

    private static String entity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", ENTITY);
    }

    /**
     * The offset of {@code anchor} in {@code source}, failing the guard when the source no longer holds it.
     *
     * <p>Every offset in these guards is used in a comparison, and an {@code indexOf} that answers {@code -1} makes
     * such a comparison pass for free - so a misspelt anchor would not fail, it would quietly stop guarding. Every
     * anchor is resolved here, and nowhere else.
     */
    private static int offsetOf(String source, String anchor) {
        int offset = source.indexOf(anchor);
        assertTrue(offset >= 0, "the guard's anchor \"" + anchor + "\" is not in the source any more, so the "
                + "comparison built on it would pass for free instead of failing");
        return offset;
    }

    /**
     * The body of the block that opens with {@code opening}, its braces matched and its string literals skipped.
     *
     * <p>Some of these guards are about what a region does NOT contain - "no early return inside this loop" - and
     * that cannot be asked with {@code indexOf}. Braces inside a log format string are why the literals are skipped.
     */
    private static String bodyOf(String text, String opening) {
        int start = text.indexOf(opening);
        assertTrue(start >= 0, "the guard could not find " + opening + ", so it must fail rather than pass silently");
        int depth = 0;
        boolean quoted = false;
        for (int i = start; i < text.length(); i++) {
            char current = text.charAt(i);
            if (quoted) {
                quoted = current != '"';
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return text.substring(start, i);
            }
        }
        fail("the block opening with " + opening + " is never closed in this file");
        return "";
    }

    /**
     * Reads one of the repository's own files, failing loudly rather than quietly skipping the check. Tries
     * both layouts the harness may run from: the project directory and the repository root.
     */
    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file, so it must not be skipped");
    }
}
