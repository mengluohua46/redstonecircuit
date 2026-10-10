package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.logic.LogicGate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 逻辑整合器's wiring: the parts no unit test can run and no game test would notice.
 *
 * <p>Which face answers, where an input is read, who is told when the answer moves, and what the screen may
 * decide - each is a line that can be moved or dropped without the gate's arithmetic or its behaviour in a
 * level going red. The carried half needs a sub-level a game test does not have, and the resources half is
 * invisible to a dedicated server: a facing the blockstate misses has no model, and a missing language key
 * shows a raw key on the screen.
 */
class LogicIntegratorWiringTest {

    @Test
    @DisplayName("only the front face answers, and it answers from the cache beside the block")
    void theFrontFaceIsTheOnlyOneThatAnswers() throws IOException {
        String block = block();

        assertTrue(block.contains("if (side.getOpposite() != state.getValue(FACING)) {")
                        && block.contains("return answerAt(level, pos, state);"),
                "getSignal answers on the front face and nowhere else, and it "
                        + "converts vanilla's backwards direction first: side names the way the query travelled, from "
                        + "the asker towards this block, so the answering face is side.getOpposite() - the same "
                        + "conversion StructureBlock#getSignal makes, and the one thing a test that asks the block "
                        + "about its own facing would never notice going wrong. The strength it answers with "
                        + "comes from BlockAnswerCache, not from a property: the property alone was 16x the state space");
        assertTrue(block.contains("public class LogicIntegratorBlock extends Block implements NoWeakPowerConduction {"),
                "and the block refuses to be a conductor - the NoWeakPowerConduction marker is how this build says it, "
                        + "and it has to be a marker because the term being refused is added by SignalGetter#getSignal: "
                        + "a default method on an interface, which no block can override. Without it that method hands "
                        + "every neighbour max(this block's answer, the charge it carries), and since a dust answers the "
                        + "strong question with its own signal on every side, the gate would re-emit the dust in front of "
                        + "it out of all six faces: with X == Y both are activated at once, and the gate flashes");
        assertTrue(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin",
                        "SignalGetterMixin.java").contains("if (state.getBlock() instanceof NoWeakPowerConduction) {"),
                "which is the question the patch asks at the return of that method: a block carrying the marker answers "
                        + "with its own value, every other block keeps the one vanilla computed");
        assertTrue(block.contains("return true;") && block.contains("protected boolean isSignalSource(BlockState state) {"),
                "and the block declares itself a source, or no consumer would ever ask it");
        assertTrue(block.contains("public static final DirectionProperty FACING = BlockStateProperties.FACING;"),
                "the front can point any of the six ways, like an observer's - and it is read from one property");
    }

    @Test
    @DisplayName("every input is read through the carrier frame, so a physicalised gate sees both frames")
    void everyInputReadGoesThroughTheCarrierFrame() throws IOException {
        String block = block();

        assertTrue(block.contains("ProductFrame frame = frameOf(level, pos);"),
                "the frame is resolved once per read, not once per face");
        assertTrue(block.contains("ProductFrame.carriedAt(serverLevel, pos)"),
                "and a carried block's frame comes from the carrier layer's own lookup (plotOwnerAt), which is "
                        + "the same question every other carried read in this mod asks");
        assertTrue(block.contains("return PowerSolver.clamp(frame.signalInto(serverLevel, neighbour, side));"),
                "one face is read through ProductFrame#signalInto - the world position the face is drawn at and the "
                        + "carrier's own storage, larger wins (a physicalised block is read where it is drawn)");
        assertTrue(block.contains("if (side == front) {"),
                "and the front face is not read at all: it is the output face, and a signal arriving on it is not an "
                        + "input (the count rule counts the five input faces)");
        assertTrue(block.contains("if (reading > 0 || attached(level, pos, side, frame)) {"),
                "a face COUNTS as an input when it carries a signal or has something attached to it, even when "
                        + "the signal it carries is 0");
        assertTrue(block.contains("state.isSignalSource() || CAN_EMIT.contains(block)")
                        && block.contains("|| block instanceof DaylightDetectorBlock"),
                "\"attached\" asks vanilla's own question: wires (including ours), levers, buttons, pressure plates "
                        + "and the rest all answer isSignalSource while they are OFF - then CAN_EMIT for the emitters "
                        + "vanilla does not call sources, then the daylight detector, whose inverted setting is a "
                        + "block of its own");
        assertTrue(block.contains("Blocks.TARGET") && block.contains("Blocks.CALIBRATED_SCULK_SENSOR")
                        && block.contains("Blocks.LIGHTNING_ROD"),
                "and that list is spelled out rather than left to isSignalSource, or a target between two shots "
                        + "would read as nothing attached");
        assertTrue(block.contains("Slot slot = InnerRedstoneStore.get(serverLevel).slotAt(at);")
                        && block.contains("CarrierStore.slotAt(serverLevel, at)"),
                "one of our own components inside the neighbour counts too, from both stores: a component writes no block "
                        + "state, so neither vanilla's question nor the list can see it (the pair "
                        + "InnerRedstoneTickHandler#holdsComponent asks)");
        assertTrue(block.contains("return slot != null && PowerSolver.emitsToward(slot, towardsUs);"),
                "and such a component is wired only where its own rule lets the signal out, wrench overrides "
                        + "included - the side the wrench cut is not an input at all");
        assertTrue(block.contains("BlockPos drawn = frame.worldPosOf(neighbour);"),
                "the attachment is asked in both frames, exactly as the reading is - the coordinate in front of the face "
                        + "and the world position that face is drawn at");
        assertTrue(block.contains("if (state.isAir()) {"),
                "while air is never attached: a gate with nothing around it is 不工作, not five inputs reading zero");
        assertTrue(block.contains("Math.max(level.getSignal(neighbour, side), level.getDirectSignal(neighbour, side))"),
                "the in-place fallback (a client level) keeps asking both of vanilla's questions, exactly as "
                        + "ProductFrame#ask does, so the two frames cannot disagree about weak and strong power");
    }

    @Test
    @DisplayName("the answer is cached only when it moved, the front is then told by hand, and the state is left alone")
    void theAnswerIsOnlyWrittenWhenItMoved() throws IOException {
        String block = block();

        assertTrue(block.contains("int wanted = answerOf(state, sight(level, pos, state.getValue(FACING))).power();")
                        && block.contains("if (BlockAnswerCache.get(level, pos) == wanted) {")
                        && block.contains("BlockAnswerCache.put(level, pos, wanted);"),
                "the answer is derived from the five faces and compared with what the block already announces: "
                        + "two gates facing each other must not ring for "
                        + "ever, and an answer that moves costs a map write instead of a block update");
        assertTrue(block.contains("notifyInFront(level, pos, state);"),
                "and the notification is what the answer moving means: the block in front is woken by hand, and no block "
                        + "state is written, because nothing about the block changed - only what it is answering");
        assertTrue(!block.contains("setValue(POWER"),
                "所以 POWER 不进方块状态：进了状态空间会大一个数量级，"
                        + "而档位等设置仍然留在状态里（活塞要推着走）");
        assertTrue(block.contains("level.neighborChanged(target, state.getBlock(), pos);")
                        && block.contains("level.updateNeighborsAtExceptFromFacing(target, state.getBlock(), front.getOpposite());"),
                "vanilla's diode shape instead - the block in front is woken, and its other neighbours are told, which "
                        + "is also the event the mod's own solver listens to (NeighborNotifyEvent)");
        assertTrue(block.contains("protected void neighborChanged(") && block.contains("protected void onPlace("),
                "and the two moments the answer can appear from nothing: a neighbour moved, or the block arrived");
    }

    @Test
    @DisplayName("a carried gate pokes the world where it is drawn, and the plot walk asks it again every pass")
    void aCarriedGatePokesWhereItIsDrawn() throws IOException {
        String block = block();

        assertTrue(block.contains("BlockPos drawn = frame.worldPosOf(target);"),
                "the position in front of the output face is projected by the carrier's own pose");
        assertTrue(block.contains("level.updateNeighborsAt(drawn, state.getBlock());")
                        && block.contains("InnerRedstoneNetwork.notifyOutputChanged(level, drawn);")
                        && block.contains("InnerRedstoneNetwork.notifyOutputChangedNow(level, drawn);"),
                "and the drawn position is woken with vanilla's notification AND the mod's, because the two halves "
                        + "listen to different ones (the pair pokeWorld sends)");
        assertTrue(block.contains("public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state)")
                        && block.contains("announce(level, localPos);"),
                "and the carried poll is derive-again-and-publish: no ledger, no timer, and a write only when the "
                        + "answer moved");
        assertTrue(block.contains("player.getEyePosition().distanceToSqr(Vec3.atCenterOf(drawn)) > RANGE * RANGE"),
                "the distance to an edit is measured where the block is DRAWN, or a screen for a "
                        + "physicalised block could never be used - its plot coordinate is most of a world away");

        assertTrue(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                        "InnerRedstoneTickHandler.java").contains("PhysicsCompat.tickCarriedProducts(level, pocket)"),
                "and that walk is the one call the level tick makes into the physics layer - nothing in the world "
                        + "notifies a plot cell when a lever outside changes, so the visit IS the notification. It goes "
                        + "through the gated compat entry point, and the list of devices it re-derives is written behind "
                        + "that gate rather than in this jar, so what is pinned here is the gate and this gate's own "
                        + "refreshCarried above");
    }

    @Test
    @DisplayName("the screen decides nothing: it sends what was typed and draws what came back")
    void theScreenDecidesNothing() throws IOException {
        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "RCNetwork.java");
        String screen = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "LogicIntegratorScreen.java");
        String router = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "LogicIntegratorScreens.java");

        assertTrue(network.contains("PayloadTypeRegistry.playS2C().register(LogicViewPayload.TYPE,")
                        && network.contains("PayloadTypeRegistry.playC2S().register(LogicEditPayload.TYPE,")
                        && network.contains("ServerPlayNetworking.registerGlobalReceiver(LogicEditPayload.TYPE,")
                        && router.contains("ClientPlayNetworking.registerGlobalReceiver(RCNetwork.LogicViewPayload.TYPE,"),
                "both messages are registered, or the screen would open on nobody's screen and answer nobody: the type "
                        + "and its codec go into the registry on both sides, and the handling is installed on the side "
                        + "that receives - the server for the edit, the client's own router for the view");
        assertTrue(network.contains("throw new DecoderException(\"there is no logic integrator reading \"")
                        && network.contains("throw new DecoderException(\"a logic integrator cannot be set to \""),
                "and both decoders bound what they read: a gate this build does not have, a strength of 90 or a sixth "
                        + "face reading is a message no block of ours produced");
        assertTrue(screen.contains("ClientPlayNetworking.send(RCNetwork.LogicEditPayload.settings(this.pos,")
                        && screen.contains("ClientPlayNetworking.send(RCNetwork.LogicEditPayload.probe(this.pos));"),
                "the screen's two outgoing messages: what the player set, and the refresh that keeps the readings and "
                        + "the verdict honest while a lever is thrown with the screen open");
        assertTrue(network.contains("int inputs = buffer.readByte();")
                        && network.contains("|| inputs < 0 || inputs >= 1 << FACES"),
                "the view also carries WHICH faces count, and the decoder bounds that mask like everything else: the "
                        + "screen must not re-derive \"an input\" from the strengths, because a wired face can "
                        + "read 0");
        assertTrue(screen.contains("Integer.bitCount(this.inputs)")
                        && screen.contains("if ((this.inputs & (1 << side.ordinal())) != 0) {"),
                "and the screen counts and lists exactly the mask it was given - the same number the verdict came from");
        assertTrue(screen.contains("return LogicGate.UNSET;") && screen.contains("LogicGate.isStrength(value)"),
                "an empty field travels as UNSET and a typed one must be a strength: \"X = 0\" and \"X is blank\" are "
                        + "two different settings all the way from the text box to the block state");
        assertTrue(screen.contains("private static final int REFRESH_TICKS = 10;")
                        && screen.contains("private static final int SEND_DELAY_TICKS = 4;"),
                "and neither direction is chatty: a refresh twice a second, and one edit after the player stops typing");
        assertTrue(router.contains("minecraft.setScreen(new LogicIntegratorScreen(message));")
                        && router.contains("showing.accept(message);"),
                "the client-only router opens the screen for the block the message names and feeds every later answer "
                        + "to the screen already showing it - a class of its own, so a dedicated server never loads "
                        + "Screen (which is why StructureScreens exists)");
    }

    @Test
    @DisplayName("the integrator is not a host: a component inside it would be a second owner of its six faces")
    void theIntegratorCannotHoldAComponent() throws IOException {
        String rules = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "HostRules.java");
        assertTrue(rules.contains("instanceof com.jiangyuefengyu.redstonecircuit.block.LogicIntegratorBlock"),
                "HostRules refuses it by class, like the structure product: the query a component "
                        + "inside it would answer is asked BEFORE the block's own getSignal, so the gate is the half "
                        + "that would go quiet");
        assertTrue(rules.contains("WORKSTATION_DENY.contains(state.getBlock())"),
                "and the deny list it is refused alongside is still there - this is an addition, not a replacement");
    }

    @Test
    @DisplayName("the blockstate models every facing, and the front carries the output texture")
    void theResourcesNameTheRightFaces() throws IOException {
        String blockstate = read("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "logic_integrator.json");
        for (String facing : List.of("north", "east", "south", "west", "up", "down")) {
            assertTrue(blockstate.contains("\"facing=" + facing + "\""),
                    "the blockstate has no variant for facing=" + facing + ", so a block placed that way has no model "
                            + "at all - and x/y/mode/power are not in the variant keys, because none of "
                            + "them changes the look");
        }
        assertTrue(blockstate.contains("\"facing=up\": { \"model\": \"redstonecircuit:block/logic_integrator\", \"x\": 270 }")
                        && blockstate.contains("\"facing=down\": { \"model\": \"redstonecircuit:block/logic_integrator\", \"x\": 90 }"),
                "up and down use the observer's own rotations for a north-facing model (x: 270 / x: 90), which is the "
                        + "one pair that is easy to get backwards and impossible to notice in a test that only counts "
                        + "variants");

        String model = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                "logic_integrator.json");
        assertTrue(model.contains("\"north\": \"redstonecircuit:block/logic_integrator_output\""),
                "the model's north face is the front, and the front carries the output texture");
        assertEquals(1, count(model, "logic_integrator_output"),
                "exactly one face may carry the output texture: the other five are input faces");
        assertEquals(6, count(model, "logic_integrator_input"),
                "the five input faces carry the input texture, and so does the particle - the only other texture a full "
                        + "cube's model refers to");

        String item = read("src", "main", "resources", "assets", "redstonecircuit", "models", "item",
                "logic_integrator.json");
        assertTrue(item.contains("\"parent\": \"redstonecircuit:block/logic_integrator\""),
                "the item is the block, seen from outside");
    }

    @Test
    @DisplayName("the mod's creative tab shows 红石结构方块")
    void theCreativeTabShowsTheStructureBlock() throws IOException {
        String registry = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCRegistry.java");
        assertTrue(registry.contains(".icon(() -> new ItemStack(REDSTONE_STRUCTURE_BLOCK_ITEM))"),
                "the tab's icon is the structure block - and the supplier is lazy, so it runs when the tab is drawn "
                        + "rather than while the mod is being constructed, and the field it names IS the item this "
                        + "loader's Registry.register handed back");
    }

    @Test
    @DisplayName("the drop, the pickaxe tag and both languages are all in place")
    void theDropAndTheWordsAreTheAuthors() throws IOException {
        // The recipe itself is not checked here: RCRecipeGameTest runs it through the recipe manager, which is the only
        // way to know the grid the player will really use.
        assertTrue(read("src", "main", "resources", "data", "redstonecircuit", "loot_table", "blocks",
                        "logic_integrator.json").contains("\"name\": \"redstonecircuit:logic_integrator\""),
                "a block with no loot table is a block that is simply gone when it is mined");
        assertTrue(read("src", "main", "resources", "data", "minecraft", "tags", "block", "mineable",
                        "pickaxe.json").contains("\"redstonecircuit:logic_integrator\""),
                "the pickaxe tag and no needs_*_tool tag is what makes it mineable with a wooden pickaxe");

        for (String language : List.of("zh_cn.json", "en_us.json")) {
            String lang = read("src", "main", "resources", "assets", "redstonecircuit", "lang", language);
            assertTrue(lang.contains("\"block.redstonecircuit.logic_integrator\""),
                    language + " is missing the block's name");
            assertTrue(lang.contains("\"block.redstonecircuit.logic_integrator.tip\""),
                    language + " is missing the tooltip that says the screen is a right-click away");
            for (LogicGate.Mode mode : LogicGate.Mode.values()) {
                assertTrue(lang.contains("\"redstonecircuit.logic.mode." + mode.key() + "\""),
                        language + " is missing the screen's label for " + mode.key());
                assertTrue(lang.contains("\"redstonecircuit.logic.rule." + mode.key() + "\""),
                        language + " is missing the sentence the screen shows for " + mode.key()
                                + " - the one place a player can read what X and Y mean for the gate they picked");
            }
            for (String key : List.of("title", "x", "y", "blank", "close", "inputs", "inputs.none", "facing",
                    "output", "notworking.count", "notworking.x", "notworking.y", "mode.chosen")) {
                assertTrue(lang.contains("\"redstonecircuit.logic." + key + "\""),
                        language + " is missing redstonecircuit.logic." + key + ", which the screen draws");
            }
        }
    }

    @Test
    @DisplayName("only a neighbour that can really drive that face is wired to it")
    void onlyAFaceThatCanBeDrivenCountsAsWired() throws IOException {
        String block = block();

        assertTrue(block.contains("if (block instanceof LogicIntegratorBlock) {")
                        && block.contains("return state.getValue(FACING) == towardsUs;"),
                "an input face facing an input face would otherwise count as a redstone connection, but an input "
                        + "face cannot emit, so only an output face facing us counts - isSignalSource answers about "
                        + "the BLOCK and a gate has to say yes to it, so the neighbour is "
                        + "asked about its FACE instead, the same one line getSignal answers with");
        assertTrue(block.contains("if (block instanceof RcPlateBlock) {")
                        && block.contains("return state.getValue(RcPlateBlock.FACING).getOpposite() == towardsUs;")
                        && block.contains("if (block instanceof TransmissionGateBlock) {")
                        && block.contains("return state.getValue(TransmissionGateBlock.FACING).getOpposite() == towardsUs;"),
                "the plates (控制器 / 变压器 / 时钟源) and 传输门 read 正面 and answer on 背面, so only the back of "
                        + "one of those is a wire");
        assertTrue(block.contains("if (block instanceof DiodeBlock) {")
                        && block.contains("return state.getValue(DiodeBlock.FACING) == towardsUs.getOpposite();")
                        && block.contains("if (block instanceof ObserverBlock) {")
                        && block.contains("return state.getValue(ObserverBlock.FACING) == towardsUs.getOpposite();"),
                "a repeater, a comparator and an observer answer on the one face they point at - vanilla's own "
                        + "getSignal rule, in the same backwards direction convention (a repeater that does not point"
                        + " this way does not count)");
        assertTrue(block.contains("if (block instanceof RedstoneWallTorchBlock) {")
                        && block.contains("return state.getValue(RedstoneWallTorchBlock.FACING).getOpposite() != towardsUs;")
                        && block.contains("if (block instanceof RedstoneTorchBlock) {")
                        && block.contains("return towardsUs != Direction.DOWN;"),
                "a wall torch never drives the block it hangs on and a standing torch never drives the block under "
                        + "it: those two faces are the blocks they are attached to");
        assertTrue(block.contains("if (block instanceof RedStoneWireBlock) {")
                        && block.contains("return towardsUs != Direction.UP;"),
                "and dust - ours included - never drives the block above it, exactly as vanilla's getSignal says");
        assertTrue(block.contains("if (emitterAt(level, neighbour, side.getOpposite())) {"),
                "the neighbour's face that points back at us is side.getOpposite() - vanilla's direction parameter read "
                        + "the way getSignal reads it, so the face rule and the answer cannot disagree");
        assertTrue(block.contains("emitterAt(level, drawn, frame.worldFace(side).getOpposite())"),
                "and where the block is drawn it is the world face, through the very mapping the reading uses "
                        + "(ProductFrame#signalInto's worldFace): a turned carrier must not have one half of the question "
                        + "standing on a different face than the other");
        assertTrue(block.contains("emitterAt(level, neighbour, side.getOpposite())")
                        && !block.contains("emitterAt(level, neighbour)"),
                "and no call site still asks without the face, which is the bug in one character");
        assertTrue(block.contains("private static boolean emitterAt(Level level, BlockPos at, Direction towardsUs)"),
                "the two arguments are always in one frame: the neighbour's coordinate and the direction from it "
                        + "towards this gate, as ProductFrame#signalInto asks its own pair");
    }

    private static int count(String text, String needle) {
        int found = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            found++;
            at = text.indexOf(needle, at + needle.length());
        }
        return found;
    }

    private static String block() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "LogicIntegratorBlock.java");
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
