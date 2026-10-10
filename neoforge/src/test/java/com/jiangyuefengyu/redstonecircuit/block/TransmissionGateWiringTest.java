package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 传输门's wiring, in one place: the behaviour is a pure function ({@code TransmissionRule}, unit tested) and the block
 * around it is a handful of conventions the mod has already paid for - the front face is the one the machine speaks or
 * listens on, whose meaning differs from 逻辑整合器's by exactly one {@code getOpposite()}, which is why it is asserted
 * here rather than remembered; the answer is published on one face only; the block does not conduct power it did not
 * make; a state that has not moved writes nothing; and the two models carry a lit variant for the main input and the
 * main output and none for the side inputs.
 */
class TransmissionGateWiringTest {

    private static String block() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "TransmissionGateBlock.java");
    }

    @Test
    @DisplayName("正面是主输入、背面是主输出：一条 getOpposite() 之差，两个面都被钉住")
    void theFrontIsTheMainInputAndTheBackIsTheMainOutput() throws IOException {
        String block = block();

        assertTrue(block.contains("public static final DirectionProperty FACING = BlockStateProperties.FACING;"),
                "the front is a six-way facing, like 逻辑整合器's");
        assertTrue(block.contains("return defaultBlockState().setValue(FACING, context.getNearestLookingDirection().getOpposite());"),
                "and it points back at whoever placed it, so the main input faces the player and the output faces away");
        assertTrue(block.contains("return side.getOpposite() == state.getValue(FACING).getOpposite() ? state.getValue(POWER) : 0;"),
                "getSignal answers the BACK face only - vanilla's side parameter names the way the query travelled, so "
                        + "the face answering is side.getOpposite(), and comparing it against FACING (as 逻辑整合器 does, "
                        + "where the front IS the output) would make this gate answer into its own main input");
        assertTrue(block.contains("Direction output = state.getValue(FACING).getOpposite();"),
                "and the wake-up names the same face: the block behind the output, which is where a consumer stands");
        assertTrue(block.contains("level.updateNeighborsAtExceptFromFacing(target, state.getBlock(), output.getOpposite());"),
                "with vanilla's diode shape - the one face it drives is excluded from the neighbour sweep, exactly as a "
                        + "repeater does it");
    }

    @Test
    @DisplayName("五个输入面一次读完，输出面不读；只有'有信号'的面才计入副输入")
    void fiveFacesAreReadAndTheOutputFaceIsNot() throws IOException {
        String block = block();

        assertTrue(block.contains("Direction output = front.getOpposite();"), "the unread face is the main output");
        assertTrue(block.contains("if (side == output) {") && block.contains("continue;"),
                "so it is skipped in the pass over the six directions");
        assertTrue(block.contains("if (side == front) {") && block.contains("main = reading;"),
                "the front is the main input's strength - the value that gets through");
        assertTrue(block.contains("} else if (reading > 0) {") && block.contains("secondaries++;"),
                "and each of the four sides counts only while it carries a signal: '有信号', not 逻辑整合器's "
                        + "'attached' rule, which counts a face that is wired but silent");
        assertTrue(block.contains("return TransmissionRule.answer(sight.mainStrength(), sight.poweredSecondaries());"),
                "the answer itself is the pure rule, in logic/, unit tested without a game");
    }

    @Test
    @DisplayName("84.9 与'没变就不写'：不导电、不闪、只有答案动了才改状态并叫醒邻居")
    void itDoesNotConductAndOnlySpeaksWhenItsAnswerMoved() throws IOException {
        String block = block();

        assertTrue(block.contains("public boolean shouldCheckWeakPower(BlockState state, SignalGetter level, BlockPos pos, Direction side)"),
                "a gate is not a block of stone");
        assertTrue(block.contains("return false;"),
                "it answers with what it emits and nothing else - otherwise the strong power of the wire on its input "
                        + "face charges it, and it re-emits that charge on every face including its own input");

        assertTrue(block.contains("if (state.getValue(POWER) == wanted && state.getValue(LIT) == lit) {")
                        && block.contains("return;"),
                "and nothing is written while the answer stands still: a gate that notified six neighbours on every "
                        + "neighbour change would let two of them facing each other ring for ever");
        assertTrue(block.contains("level.setBlock(pos, announced, Block.UPDATE_CLIENTS);"),
                "when it does move, both POWER and LIT are published in one write (UPDATE_CLIENTS: the clients draw it, "
                        + "the server already knows)");
        assertTrue(block.contains("announce(serverLevel, pos);"),
                "from a neighbour change, from being placed, and from the carrier walk - the three places a machine's "
                        + "answer can go stale");
    }

    @Test
    @DisplayName("载体内那半：走查里和逻辑整合器并排再求解一次（世界在外面，绘图格里的人听不见）")
    void theCarrierWalkAsksItAgain() throws IOException {
        String block = block();
        assertTrue(block.contains("public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state) {")
                        && block.contains("if (!(state.getBlock() instanceof TransmissionGateBlock)) {"),
                "the poll is guarded by the block's own class, so a walk over mixed sources cannot call into it by mistake");

        String driver = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableProductDriver.java");
        assertTrue(driver.contains("TransmissionGateBlock.refreshCarried(level, cell, state);"),
                "and the driver calls it, in the one walk that already visits every signal source a structure draws");
        assertTrue(driver.indexOf("LogicIntegratorBlock.refreshCarried(level, cell, state);")
                        < driver.indexOf("TransmissionGateBlock.refreshCarried(level, cell, state);"),
                "right beside 逻辑整合器's, which goes stale for the same reason");
    }

    @Test
    @DisplayName("资源：6 朝向 × 亮/灭 = 12 个变体、两张模型（侧面不换贴图）、配方石英+四粉、木镐")
    void theResourcesMatchTheAuthorsFolder() throws IOException {
        String states = read("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "transmission_gate.json");
        for (String facing : List.of("north", "east", "south", "west", "up", "down")) {
            assertTrue(states.contains("\"facing=" + facing + ",lit=false\""), facing + " unlit variant");
            assertTrue(states.contains("\"facing=" + facing + ",lit=true\""), facing + " lit variant");
        }
        assertTrue(states.contains("\"model\": \"redstonecircuit:block/transmission_gate\""),
                "the unlit model");
        assertTrue(states.contains("\"model\": \"redstonecircuit:block/transmission_gate_lit\""),
                "and the lit one, so a pack can swap what 'lit' looks like");

        String off = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                "transmission_gate.json");
        String on = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                "transmission_gate_lit.json");
        assertTrue(off.contains("\"north\": \"redstonecircuit:block/transmission_gate_main_input\"")
                        && off.contains("\"south\": \"redstonecircuit:block/transmission_gate_main_output\""),
                "the model's faces: north is the main input (the front when facing=north) and south the main output");
        assertTrue(on.contains("transmission_gate_main_input_lit") && on.contains("transmission_gate_main_output_lit"),
                "the lit model lights the two faces that carry the signal");
        assertFalse(on.contains("transmission_gate_secondary_input_lit"),
                "and there is no lit side texture to use: the side input has only 传输门-副输入.png and no -亮 variant, "
                        + "so the four side faces read the same in both models");
        assertTrue(off.contains("transmission_gate_secondary_input") && on.contains("transmission_gate_secondary_input"),
                "which is what both models say");

        String item = read("src", "main", "resources", "assets", "redstonecircuit", "models", "item",
                "transmission_gate.json");
        assertTrue(item.contains("redstonecircuit:block/transmission_gate"), "the item looks like the block, unlit");

        String loot = read("src", "main", "resources", "data", "redstonecircuit", "loot_table", "blocks",
                "transmission_gate.json");
        assertTrue(loot.contains("\"name\": \"redstonecircuit:transmission_gate\""), "it drops itself");

        String recipe = read("src", "main", "resources", "data", "redstonecircuit", "recipe",
                "transmission_gate.json");
        assertTrue(recipe.contains("\"pattern\": [ \" R \", \"RQR\", \" R \" ]"),
                "the recipe is the middle quartz block with redstone on the four orthogonal cells and nothing in the corners");
        assertTrue(recipe.contains("\"Q\": { \"item\": \"minecraft:quartz_block\" }")
                        && recipe.contains("\"R\": { \"item\": \"minecraft:redstone\" }"),
                "quartz in the middle, redstone around it");

        String pickaxe = read("src", "main", "resources", "data", "minecraft", "tags", "block", "mineable",
                "pickaxe.json");
        assertTrue(pickaxe.contains("\"redstonecircuit:transmission_gate\""),
                "the pickaxe tag and no needs_*_tool tag, like the other machines");

        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src", "main", "resources", "assets", "redstonecircuit", "lang", lang);
            assertTrue(text.contains("\"block.redstonecircuit.transmission_gate\""), lang + ": the name");
            assertTrue(text.contains("\"block.redstonecircuit.transmission_gate.tip\""), lang + ": the tip");
        }
    }

    @Test
    @DisplayName("GameTest 必须真的被注册：类上要有 @GameTestHolder（少了它，测试一条都不会跑，而且报告里看不出来）")
    void theGameTestsAreActuallyRegistered() throws IOException {
        String gameTest = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                "TransmissionGateGameTest.java");

        assertTrue(gameTest.contains("@GameTestHolder(RedstoneCircuit.MODID)"),
                "少了类上的 @GameTestHolder，新测试一条都不会注册，而报告里仍然只显示 'All ... required tests passed'，"
                        + "看起来一切正常：这条断言就是为了让那种情况不可能再发生");
        assertTrue(gameTest.contains("@PrefixGameTestTemplate(false)"),
                "并且和同目录的其它 GameTest 类一样关掉模板前缀");
        assertTrue(gameTest.contains("@GameTest(template = \"empty\")"),
                "每条测试都用空模板，自己搭场景");
    }

    @Test
    @DisplayName("三个实机问题：打掉时的粒子、不能塞元件、物理化后的输出延迟（叫醒的是机器自己绘图位置）")
    void theThreeThingsTheAuthorFoundInGame() throws IOException {
        // 1) The break particle. Both models have to name a texture for it.
        for (String model : List.of("transmission_gate.json", "transmission_gate_lit.json")) {
            String text = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block", model);
            assertTrue(text.contains("\"particle\": \"redstonecircuit:block/transmission_gate_secondary_input\""),
                    model + ": the destroy particle reads this key, and a model that leaves it out shows a missing"
                            + " texture. The side face is the block's dominant look, and using the"
                            + " UNLIT one in both models keeps a broken block looking the same lit or not");
        }

        // 2) No component inside: the gate senses signals on its sides, so it is not a host.
        String hosts = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "HostRules.java");
        assertTrue(hosts.contains("if (state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.TransmissionGateBlock) {")
                        && hosts.contains("return false;"),
                "the gate is refused as a host by class, the way 逻辑整合器 and 红石结构方块 are: five of its six faces are"
                        + " inputs and the sixth answers, so a component inside it would be a second owner of the block -"
                        + " and the query it answers is asked before the block's own getSignal");

        // 3) The carried wake names the machine's own drawn position as well.
        String block = block();
        assertTrue(block.contains("BlockPos drawnSelf = frame.worldPosOf(pos);"),
                "the wake has to include where the MACHINE is drawn - \"the block at P changed\" notifies P's six"
                        + " neighbours, so announcing only the drawn neighbour tells the neighbour's neighbours and leaves"
                        + " the wire standing where the output points untouched");
        assertTrue(block.contains("level.updateNeighborsAt(drawnSelf, state.getBlock());")
                        && block.contains("InnerRedstoneNetwork.notifyOutputChanged(level, drawnSelf);"),
                "with both halves of the notification the mod uses everywhere else (vanilla's neighbours and the solver's"
                        + " own event)");
        assertTrue(block.contains("transmission gate: woke the world at {}"),
                "and a debugLog line naming both positions: a diagnostic that does not exist cannot answer a question");
    }

    private static String read(String... parts) throws IOException {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the file on purpose");
    }
}
