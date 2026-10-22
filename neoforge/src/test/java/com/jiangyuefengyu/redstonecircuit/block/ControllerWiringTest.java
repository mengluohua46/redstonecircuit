package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The things about 控制器 that only exist as wiring: which face answers, that the plate is one eighth of a
 * block thick, that the screen and the block share <b>one</b> copy of the rule, and that the resource
 * files a block needs are all there under the names they have to have.
 */
class ControllerWiringTest {

    @Test
    @DisplayName("正面读输入、背面回答：getSignal 只在背面给信号，输入读的就是 FACING 那一面")
    void theFrontIsTheInputAndTheBackAnswers() throws IOException {
        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "ControllerBlock.java");

        assertTrue(block.contains("if (side.getOpposite() != state.getValue(FACING).getOpposite()) {")
                        && block.contains("return answerAt(level, pos, state);"),
                "vanilla's side parameter names the way the query travelled, so the face that answers is"
                        + " side.getOpposite() - 背面 is FACING.getOpposite(), and 传输门 has the same shape."
                        + " The number comes from BlockAnswerCache instead of a state property");
        assertTrue(block.contains("return readingInto(level, pos, state.getValue(FACING))"),
                "and the input is read at 正面, in both frames (物理化之后按它看起来在哪) - through the helper every plate"
                        + " shares, so 红石变压器 cannot read its own front differently");
        String shared = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "RcPlateBlock.java");
        assertTrue(shared.contains("ProductFrame.carriedAt(serverLevel, pos).signalInto(serverLevel, neighbour, side)"),
                "which is this call, in RcPlateBlock, shared because a second device needs the same one");
        assertTrue(block.contains("wakeOutput(level, pos, state, input, power, \"controller\")"),
                "the wake names 背面 too, or the wire standing at the output would never hear the answer change");
        assertTrue(shared.contains("Direction output = outputFace(state);")
                        && shared.contains("BlockPos drawnSelf = frame.worldPosOf(pos);")
                        && shared.contains("BlockPos drawnTarget = frame.worldPosOf(target);"),
                "a carried device wakes the world at its own drawn position as well as at the drawn face it"
                        + " drives - the machine's own neighbours hear nothing at all otherwise");
        assertTrue(block.contains("public boolean shouldCheckWeakPower(BlockState state, SignalGetter level,"
                        + " BlockPos pos, Direction side) {"),
                "a device is not a block of stone - false here, or a wire at the front charges the plate and it"
                        + " re-emits that charge out of its back one step later");
    }

    @Test
    @DisplayName("薄板：1/8 格高、贴格子底部、水平朝向，模型就是一个 16×2×16 的元素")
    void thePlateIsOneEighthOfABlock() throws IOException {
        String plate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "RcPlateBlock.java");
        assertTrue(plate.contains("Block.box(0.0, 0.0, 0.0, 16.0, 2.0, 16.0)"),
                "薄板高 1/8 格 - two pixels of sixteen, and the shape and the collision are the same box");
        assertTrue(plate.contains("BlockStateProperties.HORIZONTAL_FACING")
                        && plate.contains("context.getHorizontalDirection().getOpposite()"),
                "the front is horizontal and points at whoever placed it - a repeater's own rule, because a plate never"
                        + " stands on a wall");

        String model = read("src", "main", "resources", "assets", "redstonecircuit", "models", "block",
                "controller.json");
        assertTrue(model.contains("\"to\": [16, 2, 16]") && model.contains("\"from\": [0, 0, 0]"),
                "and the model is that same box: the edges' 16x2 strips are sampled by vanilla's own auto-UV (a 2-tall"
                        + " element spans v 14…16)");
        String states = read("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "controller.json");
        assertTrue(states.contains("redstonecircuit:block/controller") && states.contains("controller_lit"),
                "lit=false/true pick the plain and the lit model - 控制器上面--亮 is the lit top face");
        for (String texture : List.of("controller_top", "controller_top_lit", "controller_bottom", "controller_side")) {
            assertTrue(Files.isRegularFile(path("src", "main", "resources", "assets", "redstonecircuit", "textures",
                            "block", texture + ".png")),
                    texture + ".png must be in the mod's own textures");
        }
    }

    @Test
    @DisplayName("规则只有一份：方块和界面都问 ControllerRule，谁也不抄第二遍")
    void theRuleHasOneCopy() throws IOException {
        String screen = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "ControllerScreen.java");
        assertTrue(screen.contains("import com.jiangyuefengyu.redstonecircuit.logic.ControllerRule;")
                        && screen.contains("ControllerRule.condition("),
                "the screen's own sentence is built from the same table the block runs, so the line the player"
                        + " reads cannot describe a condition the plate is not using");
        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "ControllerBlock.java");
        assertTrue(block.contains("ControllerRule.answer("),
                "and the block answers through it rather than through its own copy of the four sentences");
    }

    @Test
    @DisplayName("协议：两个包都注册了、版本号跟着走、解码器夹住了设备和每个数字")
    void theMessagesAreRegisteredAndBounded() throws IOException {
        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "RCNetwork.java");
        assertTrue(network.contains(".playToClient(DeviceViewPayload.TYPE, DeviceViewPayload.STREAM_CODEC,"
                        + " DeviceViewPayload::handle)")
                        && network.contains(".playToServer(DeviceEditPayload.TYPE, DeviceEditPayload.STREAM_CODEC,"
                        + " DeviceEditPayload::handle);"),
                "one view pair for every device, registered like every other message");
        assertTrue(network.contains("private static final String VERSION = \"15\";"),
                "and the version says the wire changed shape: the device screen, the selection's \"still a host\" "
                        + "flag, the structure and local block a picked block inside a machine is named by, a "
                        + "varint reading, because a clock's countdown did not fit in a byte, the blueprint "
                        + "selection a client announces before uploading one, and one panel entry replaced by "
                        + "index (REPLACE_MANUAL), because a rename changes the entry's identity");
        assertTrue(network.contains("DeviceKind.byOrdinal(kind) == null")
                        && network.contains("> DeviceKind.MAX_SETTING")
                        && network.contains("> DeviceKind.MAX_READING"),
                "a device this build does not have, a setting of 900, or a reading wider than any device reports, is a "
                        + "message no device of ours produced - checked at the decoder before any screen can draw it");
        assertTrue(network.contains("if (state.getBlock() instanceof RcPlateBlock plate) {"),
                "and a device edit is answered by the block that is really there, whatever the message called it");
    }

    @Test
    @DisplayName("四个资源文件 + 创造栏：方块名、掉落、配方、按键都在")
    void theResourcesAndTheCreativeTabAreWired() throws IOException {
        String registry = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCRegistry.java");
        assertTrue(registry.contains("BLOCKS.register(\"controller\", () -> new ControllerBlock(")
                        && registry.contains("output.accept(CONTROLLER_ITEM.get());"),
                "registered, and in the creative tab: our own things belong there");
        assertTrue(registry.contains("noOcclusion()"),
                "noOcclusion is the plate's own half, and it is also what keeps a plate from ever being a host: a"
                        + " component inside it would be a second owner of a cell the plate already answers from");

        String loot = read("src", "main", "resources", "data", "redstonecircuit", "loot_table", "blocks",
                "controller.json");
        assertTrue(loot.contains("\"name\": \"redstonecircuit:controller\""),
                "空手即可挖 needs a loot table as well as no tool requirement - without one the block drops nothing");
        String recipe = read("src", "main", "resources", "data", "redstonecircuit", "recipe", "controller.json");
        assertTrue(recipe.contains("[ \"   \", \" R \", \"GGG\" ]") && recipe.contains("\"minecraft:gold_ingot\"")
                        && recipe.contains("\"minecraft:redstone\""),
                "配方：最下方三个横着的金锭，中间一个红石粉");

        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("src", "main", "resources", "assets", "redstonecircuit", "lang", lang);
            for (String key : List.of("block.redstonecircuit.controller", "redstonecircuit.controller.title",
                    "redstonecircuit.controller.main.greater", "redstonecircuit.controller.sub.only_not_equal",
                    "redstonecircuit.controller.condition", "redstonecircuit.device.threshold")) {
                assertTrue(text.contains("\"" + key + "\""), lang + ": missing " + key);
            }
        }
    }

    @Test
    @DisplayName("物理化那一趟走查会问控制器（和整合器/传输门同一处）")
    void theCarriedWalkPollsTheController() throws IOException {
        String driver = read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableProductDriver.java");
        assertTrue(driver.contains("ControllerBlock.refreshCarried(level, cell, state);"),
                "a plate inside a structure is never told that the world changed next to where it is drawn, so the walk"
                        + " that already visits every signal source asks it again");
        assertTrue(driver.indexOf("TransmissionGateBlock.refreshCarried(level, cell, state);")
                        < driver.indexOf("ControllerBlock.refreshCarried(level, cell, state);"),
                "after the gate's, i.e. inside the pass that has already decided the block is a signal source");
    }

    @Test
    @DisplayName("八个朝向变体：模型跟着 facing 转（控制器放置下来只朝一个方向）")
    void theModelTurnsWithTheFacing() throws IOException {
        String states = read("src", "main", "resources", "assets", "redstonecircuit", "blockstates",
                "controller.json");
        for (String facing : List.of("north", "east", "south", "west")) {
            assertTrue(states.contains("\"facing=" + facing + ",lit=false\"")
                            && states.contains("\"facing=" + facing + ",lit=true\""),
                    "a plate whose art is the same on all four edges looks"
                            + " identical whichever way it points unless the model is turned, and each facing needs"
                            + " both its plain and its lit variant: facing=" + facing);
        }
        assertTrue(states.contains("\"y\": 180") && states.contains("\"y\": 270") && states.contains("\"y\": 90"),
                "with the four rotations (south is the unrotated one). The top face's display bar is drawn near the"
                        + " BOTTOM of the texture and vanilla's auto-UV puts a top face's bottom texture rows at its"
                        + " SOUTH part, so the model's own front is its south face and 正面 = north is a half turn"
                        + " (y = 180) - the bar then sits at the front, which is the 输入面");
    }

    @Test
    @DisplayName("关闭按键在两行状态下面")
    void theCloseButtonSitsBelowTheStatusLines() throws IOException {
        String screen = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "ControllerScreen.java");
        assertTrue(screen.contains("this.top + 168"),
                "the button sits on the panel's own bottom row");
        // The two offsets are compared as NUMBERS: where the two lines sit in the FILE says nothing about
        // the layout.
        Matcher close = Pattern.compile("WIDTH / 2 - 30, this\\.top \\+ (\\d+)").matcher(screen);
        Matcher live = Pattern.compile("live\\(\\), this\\.left \\+ 4, this\\.top \\+ (\\d+)").matcher(screen);
        assertTrue(close.find() && live.find(), "both offsets have to be readable out of the screen");
        assertTrue(Integer.parseInt(close.group(1)) > Integer.parseInt(live.group(1)),
                "the close button must sit BELOW the live line on the panel, not merely later in the file: button at"
                        + " +" + close.group(1) + ", live line at +" + live.group(1));
    }

    @Test
    @DisplayName("可放入方块内：塞得进、取得出、设置跟着元件走")
    void theControllerGoesInsideAHostBlock() throws IOException {
        String hosts = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "HostRules.java");
        assertTrue(hosts.contains("if (item == RCRegistry.CONTROLLER_ITEM.get()) {")
                        && hosts.contains("return ComponentType.CONTROLLER;"),
                "the handheld item has to map to the component, or the sneak click would just place the "
                        + "plate as a block");
        assertTrue(hosts.contains("case CONTROLLER -> new ItemStack(RCRegistry.CONTROLLER_ITEM.get());"),
                "and taking it back out has to hand the same item back, or the component would vanish into the block");

        String slot = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "data", "Slot.java");
        assertTrue(slot.contains("public com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting controller =")
                        && slot.contains("controller.save(controllerTag);")
                        && slot.contains("ControllerRule.Setting\n                .load(tag.getCompound(\"controller\"))"),
                "the four settings live on the slot and are saved with it, so a component inside a block keeps what the"
                        + " player set it to across a save");

        String node = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "SlotNode.java");
        assertTrue(node.contains("public ControllerRule.Setting controller() {")
                        && node.contains("return slot.controller;"),
                "and the solver reaches them through the node contract - SlotNode is the world's half");
        String carrier = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierSpace.java");
        assertTrue(carrier.contains("public com.jiangyuefengyu.redstonecircuit.logic.ControllerRule.Setting controller()"),
                "CarrierSpace's node is the other half: a controller riding a machine is read the same way");

        String solver = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "PowerSolver.java");
        assertTrue(solver.contains("return self.controller().answer(signal);"),
                "and the answer is the same table the plate runs, over the strength at the component's 正面");
        assertTrue(solver.contains("return type.hasInputSide() && direction == facing;"),
                "with the input side protected from a routing lock (ComponentType#hasInputSide)");
    }

    @Test
    @DisplayName("方块内的界面：空手右键宿主打开它，而且写入要广播出去")
    void theHostScreenIsWired() throws IOException {
        String interaction = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                "InnerRedstoneInteraction.java");
        assertTrue(interaction.contains("ControllerBlock.sendHostView(player, level, pos, true);"),
                "the bare-hand click on a host opens it");
        assertTrue(interaction.indexOf("refuseInnerAdjust(player, InnerSwitches.isHandSwitch(slot.type))")
                        < interaction.indexOf("sendHostView(player, level, pos, true)"),
                "and only after the goggles rule has allowed the adjustment (调方块内的元件要戴眼镜), or a"
                        + " player who cannot read the setting could change it");

        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "RCNetwork.java");
        assertTrue(network.contains("ControllerBlock.applyHostEdit(player, payload);"),
                "an edit arriving for a host block has to reach the component inside it, not only the plates");

        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "ControllerBlock.java");
        assertTrue(block.contains("public static void sendHostView(ServerPlayer player, ServerLevel level,"
                        + " BlockPos host, boolean open)"),
                "the screen for a stored component, addressed at the host's own coordinate");
        assertTrue(block.contains("announceHostSettings(level, host, slot)"),
                "the client draws a COPY of the store, so a change that is not broadcast is a change the player"
                        + " cannot see - the sequence is shared, in RcPlateBlock");
        String plate = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "RcPlateBlock.java");
        assertTrue(plate.contains("HostSync.broadcast(level, host, slot);"),
                "and that sequence is where the broadcast actually happens");
        assertTrue(block.contains("hostReading(level, host, slot)"),
                "and the live reading is the same one the solver takes, at the component's 正面");
        assertTrue(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", "RcPlateBlock.java")
                        .contains("InnerRedstoneNetwork.readSignal(level, neighbour, slot.facing)"),
                "which is that call, in the shared plate class, used by 红石变压器 too");
    }

    @Test
    @DisplayName("方块内看得见它：用自己那张薄板的模型，亮灭跟着输出走")
    void theStoredControllerIsDrawnAsItsOwnPlate() throws IOException {
        String visuals = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "InnerComponentVisuals.java");
        assertTrue(visuals.contains("CONTROLLER,"),
                "a model of its own - without one it would be invisible inside the block");
        assertTrue(visuals.contains("case CONTROLLER -> {")
                        && visuals.contains("RCRegistry.CONTROLLER.get().defaultBlockState()")
                        && visuals.contains("block.ControllerBlock.LIT, placement.lit()"),
                "drawn as the plate it is, with the same FACING and LIT the world plate carries - so the thing inside a"
                        + " block lights up exactly when the thing on the ground does");
        assertTrue(visuals.contains("if (slot.facing.getAxis() == Direction.Axis.Y) {")
                        && visuals.contains("float tilt = slot.facing == Direction.DOWN ? 90F : -90F;"),
                "a controller mounted on the floor or the ceiling is turned a quarter turn about X exactly as a"
                        + " diode is, so its 正面 points at the face it reads from instead of lying there unrotated");
    }

    @Test
    @DisplayName("物理化宿主改动的诊断：搬运者、结构内坐标、世界坐标、世界那格有没有被广播")
    void theCarriedChangeNamesItsOwnCoordinates() throws IOException {
        String carrier = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierInteraction.java");
        assertTrue(carrier.contains("carried host change: carrier={} local={} world={} worldEntry={}"),
                "物理化宿主上用扳手或转向杆之后的改动要能查下去：这一行同时给出搬运者、结构内坐标、世界坐标，"
                        + "以及世界那一格到底有没有被广播");
    }

    @Test
    @DisplayName("物理化宿主的界面：点击路开在 apply 之前，读写走 carrier 记录 + 世界那一格")
    void theCarriedHostScreenIsWired() throws IOException {
        String carrier = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierInteraction.java");
        assertTrue(carrier.contains("ControllerBlock.sendHostView(player, level, hit.localPos(), true);"),
                "the carried click opens it, at the structure's own coordinate - the same convention a"
                        + " physicalised plate's screen uses");
        assertTrue(carrier.indexOf("ControllerBlock.sendHostView(player, level, hit.localPos(), true);")
                        < carrier.indexOf("CarriedSwitch.Action action = CarriedSwitch.apply(index"),
                "and before apply is reached, so the controller is never handed to a switch that has no action for it");

        String switches = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "CarriedSwitch.java");
        assertTrue(switches.contains("|| type == ComponentType.CONTROLLER")
                        && switches.contains("|| type == ComponentType.TRANSFORMER")
                        && switches.contains("|| type == ComponentType.CLOCK"),
                "and it has to count as workable, or the carried click is refused before that branch can run -"
                        + " four devices now have a screen for their gesture");

        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "ControllerBlock.java");
        assertTrue(block.contains("hostSlot(level, host, ComponentType.CONTROLLER)"),
                "the read asks the shared three-store lookup, named with the one type this device is about");
        String shared = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "RcPlateBlock.java");
        assertTrue(shared.contains("slot = CarrierStore.slotAt(level, host);"),
                "one of whose three stores is the carrier's, or a physicalised host's settings would never be found");
        assertTrue(shared.contains("index.updateSlot(carrier, host, slot);")
                        && shared.contains("CarrierStore.broadcastCarrierNow(level, carrier);"),
                "and the write is the same three the other carried gestures make: the record, the carrier payload the"
                        + " client draws from, and the world entry at the position the machine is drawn at");
        // That third write is a CLEAR, not a broadcast: the position the machine was drawn at stops being the
        // machine's the moment it moves, and a world entry written there is a component drawn in mid-air.
        assertTrue(shared.contains("HostSync.publishWorldEntry(level, drawn)"),
                "搬运中宿主的写入：世界那一格只清不写（客户端靠上面那份 carrier 载荷画它）");
        assertFalse(shared.contains("HostSync.broadcast(level, drawn, slot);"),
                "那一格只清不写，这条广播不能再回来");
        String sync = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "network",
                "HostSync.java");
        assertTrue(sync.contains("public static void publishWorldEntry(ServerLevel level, BlockPos worldPos)")
                        && sync.contains("slotAt(worldPos) != null"),
                "这条规则只有一处（HostSync#publishWorldEntry），而且真有宿主站在那一格时让开 -"
                        + " 那一格的条目归世界自己的同步");
    }

    @Test
    @DisplayName("物理化宿主的界面开在眼镜判定之下（需要戴眼镜）；找不到元件与\"空气里的条目\"各留一行日志")
    void theCarriedScreenOpensWithoutGoggles() throws IOException {
        String carrier = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierInteraction.java");
        assertTrue(carrier.contains("before.type == ComponentType.CONTROLLER"),
                "the controller's screen has to be opened from the carried click");
        assertTrue(carrier.indexOf("refuseInnerAdjust(player,")
                        < carrier.indexOf("before.type == ComponentType.CONTROLLER"),
                "and <b>under</b> the goggles gate: the screen is one more adjustment of a component inside a"
                        + " block, which the goggle rule covers");

        String block = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "ControllerBlock.java");
        assertTrue(block.contains("controller screen at {}: world store={} carried index={}"),
                "and when the lookup finds nothing the server says so, naming both stores: \"点不开\" cannot be told apart"
                        + " from \"the click never arrived\" without it");

        String cache = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "client",
                "ClientHostCache.java");
        assertTrue(cache.contains("ghost probe: host entry for air at {}"),
                "an entry applied for a cell the world has"
                        + " no block in is the ghost's fingerprint, and this names it");
        assertTrue(cache.contains("private static void forgetLandedRemovals(List<HostEntry> entries)")
                        && cache.contains("if (entry.slot() == null) {")
                        && cache.contains("landed.forgetAt(entry.pos());"),
                "而 landed 表里也留着它的记录：清除消息必须连 landed 记录一起丢掉，"
                        + "否则\"世界那格还没有方块就自己画\"这条规则会让它在空气里永远挂着");
    }

    private static Path path(String... parts) {
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return direct;
    }

    private static String read(String... parts) throws IOException {
        for (Path candidate : List.of(path(parts), Path.of("neoforge").resolve(Path.of("", parts)))) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from " + Path.of("").toAbsolutePath());
    }
}
