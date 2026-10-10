package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    @DisplayName("物理那半：交给物理后端新建一个只含这一格的结构，而且必须在世界放置之前分岔")
    void aCarriedMachinePlacesAStandaloneStructure() throws IOException {
        String block = block();

        assertTrue(block.contains("ProductFrame frame = ProductFrame.carriedAt(level, pos);")
                        && block.contains("if (frame.carried()) {"),
                "the machine asks this mod's own carrier "
                        + "layer where it is, the same question the rest of the carried code asks");
        assertTrue(block.contains("PhysicsCompat.placeSingleBlockStructure(level, drawn, out, stack)"),
                "and the physical half is one call into the compat gate, because this source set may not name a type of "
                        + "the optional physics mod at all");
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
    @DisplayName("物理那半（门面）：没有后端的时候答案必须是 false，而不是去加载一个后端类型")
    void theFacadeAnswersFalseWithoutABackend() throws IOException {
        String gate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "PhysicsCompat.java");

        assertTrue(gate.contains("if (backendClass == null || !sableLoaded() || level == null || drawnTarget == null || out == null"),
                "the gate answers false - \"nothing was placed\", so nothing is spent - without touching a backend type");
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

        assertTrue(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "BlockPlacerGameTest.java")
                        .contains("BlockPlacerBlock.refreshCarried(level, machine, machine(helper))"),
                "and the entry point is really driven: the game test calls it directly, because in a game the caller is "
                        + "the level tick's one call into the physics layer - and the machine is not a signal source, so "
                        + "a filter meant for sources is exactly what would skip the block that needs asking");
    }

    @Test
    @DisplayName("子维度创建默认关闭（读这个开关、并且必须判在分配之前的那一处不在这个 jar 里）")
    void creatingAStructureIsOffByDefault() {
        assertFalse(RCConfig.placerCreatesStructures(),
                "making a structure and immediately taking it back is the only thing this mod does that touches the "
                        + "optional mod's saved sub-level bookkeeping, and a damaged saved sub-level is what a failed "
                        + "load reports. So the feature ships switched off, and the default is the half of that fact "
                        + "this build can state: the refusal that reads the switch, and the requirement that it comes "
                        + "before anything is allocated, live in the compat half, which is not part of this jar");
    }

    private static String block() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", BLOCK);
    }

    private static String entity() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", ENTITY);
    }

    /**
     * Reads one of the repository's own files, failing loudly rather than quietly skipping the check. The path is
     * resolved against this project's own directory, so the guard can only ever read the sources it is about.
     */
    private static String read(String... parts) throws IOException {
        Path direct = Path.of("", parts);
        if (Files.isRegularFile(direct)) {
            return Files.readString(direct, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file, so it must not be skipped");
    }
}
