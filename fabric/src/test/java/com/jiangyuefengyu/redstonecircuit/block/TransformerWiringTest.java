package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.logic.DeviceKind;

/**
 * 红石变压器的接线: the parts a green unit suite cannot see, read out of the sources and the data files.
 *
 * <p>What is left after the rule and the in-level behaviour tests is the half that shows up only in game: whether the
 * block is registered with a model, a loot table, a recipe and both languages; whether the front/back art lands on the
 * right faces; whether the screen exists for both addresses (the plate in the world and the component inside a host);
 * and whether the shared pieces of plate machinery are really shared rather than copied.
 */
class TransformerWiringTest {

    @Test
    @DisplayName("面：正面输入、背面输出，且只有背面回答（和控制器同一行、差一个 getOpposite）")
    void theBackFaceAnswersAndNothingElseDoes() throws IOException {
        String block = read("block", "TransformerBlock.java");
        assertTrue(block.contains("side.getOpposite() != outputFace(state)"),
                "背面才回答：side 是原版那个反向参数（查询走来的方向），所以回答的那一面是 side.getOpposite()");
        assertTrue(block.contains("public class TransformerBlock extends RcPlateBlock implements NoWeakPowerConduction {"),
                "而薄板不是导体：要拿掉的是原版自己加的那条弱信号项，它是 SignalGetter#getSignal 里加的，而那是接口上的"
                        + "默认方法，方块没有方法可以覆写它——所以用这个标记接口，由 mixin/SignalGetterMixin 认出来、把那一项"
                        + "去掉。少了它，正面的线会给它充电，它就把这份电荷从六个面重新发出去");
        assertTrue(block.contains("return readingInto(level, pos, state.getValue(FACING))"),
                "正面 is the input face, read in both frames through the shared helper");

        String base = read("block", "RcPlateBlock.java");
        assertTrue(base.contains("protected static int readingInto(")
                        && base.contains("protected static void wakeOutput(")
                        && base.contains("public static Slot hostSlot(")
                        && base.contains("public static int hostReading("),
                "四个设备共用的那套（两个坐标系读数、唤醒背面、方块内三处查找）住在 RcPlateBlock 里");

        // The click's own resolver names the record (CarrierStore#hitAt scans in-structure coordinates), while a record's
        // world key is where it answered when it was CAPTURED - so a lookup through the position the block is DRAWN at
        // misses as soon as the machine has flown. hostSlot asks the same resolver the click does.
        String hostSlot = bodyOf(base, "public static Slot hostSlot");
        assertTrue(hostSlot.contains("CarrierStore.hitAt(level, host)")
                        && hostSlot.contains("index.at(hit.carrier(), hit.localPos())"),
                "方块内那一个要靠点击自己用的那个解析器来认（CarrierStore#hitAt 扫的是结构内坐标），"
                        + "而不是靠「它被捕获时回答的位置」");
        assertFalse(hostSlot.contains("carrierHolding("),
                "carrierHolding 是按 CarriedHost#at 建的表，机器一飞走就查不到");
        String controller = read("block", "ControllerBlock.java");
        assertTrue(controller.contains("wakeOutput(level, pos, state, input, power, \"controller\")"),
                "控制器也走同一份，所以两边不会各修一半");
        assertFalse(controller.contains("private static void notifyOutput") && controller.contains("frame.carriedAt(level, pos);"),
                "控制器里不再留一份自己的实现");
    }

    @Test
    @DisplayName("状态里是设定值和亮灯，不是答案：启动状态数与控制器同一量级")
    void theStateCarriesTheSettingAndTheLight() throws IOException {
        String block = read("block", "TransformerBlock.java");
        assertTrue(block.contains("IntegerProperty.create(\"setting\", TransformerRule.MIN, TransformerRule.MAX)"),
                "设定值留在方块状态里（活塞、结构捕获都要带着它走）");
        assertTrue(block.contains("BlockStateProperties.LIT"),
                "亮灯是状态（贴图靠它选）");
        assertFalse(block.contains("IntegerProperty.create(\"power\"") || block.contains("builder.add(SETTING, LIT, POWER)"),
                "答案不在状态里：它是 BlockAnswerCache 里的一条（代码里只有注释会提到那个被删掉的属性，所以这里按「属性创建」"
                        + "和「注册进状态」两处查）");
        assertTrue(block.contains("BlockAnswerCache.get(serverLevel, pos)")
                        && block.contains("BlockAnswerCache.forget(serverLevel, pos)"),
                "因此答案要缓存、方块没了要忘掉");
    }

    @Test
    @DisplayName("四个资源文件 + 创造栏 + 双语言：方块名、掉落、配方、界面文案都在")
    void theResourcesAndTheCreativeTabAreWired() throws IOException {
        String registry = read("..", "RCRegistry.java");
        assertTrue(registry.contains("public static final TransformerBlock TRANSFORMER = Registry.register(")
                        && registry.contains("RedstoneCircuit.id(\"transformer\")")
                        && registry.contains("new TransformerBlock(")
                        && registry.contains("output.accept(TRANSFORMER_ITEM);"),
                "注册了，也在创造栏里 - 我们自己的东西要能拿到。字段本身就是那个方块：这个加载器的 Registry.register 直接把"
                        + "对象递回来，没有一层 holder");
        assertTrue(registry.contains("noOcclusion()"),
                "noOcclusion 既是薄板该有的，也保证它永远不能当宿主");

        String loot = read("resources", "data", "redstonecircuit", "loot_table", "blocks", "transformer.json");
        assertTrue(loot.contains("\"name\": \"redstonecircuit:transformer\""),
                "空手即可挖要有掉落表，否则挖了什么都不掉");

        String recipe = read("resources", "data", "redstonecircuit", "recipe", "transformer.json");
        assertTrue(recipe.contains("[ \"QCQ\", \"RRR\", \"QCQ\" ]") && recipe.contains("\"minecraft:quartz\"")
                        && recipe.contains("\"minecraft:copper_ingot\"") && recipe.contains("\"minecraft:redstone\""),
                "中间横着三个红石，四个角下界石英，正上下铜锭。下界石英的物品 id 是 minecraft:quartz（不是 nether_quartz）——"
                        + "写错了这个只读文本的断言照样通过，能抓住的是 RCRecipeGameTest 里真的把它合出来那一条");

        for (String lang : List.of("zh_cn.json", "en_us.json")) {
            String text = read("resources", "assets", "redstonecircuit", "lang", lang);
            for (String key : List.of("block.redstonecircuit.transformer", "redstonecircuit.transformer.title",
                    "redstonecircuit.transformer.rule", "redstonecircuit.transformer.live",
                    "jade.redstonecircuit.inner.transformer")) {
                assertTrue(text.contains("\"" + key + "\""), lang + ": missing " + key);
            }
        }

        String blockstate = read("resources", "assets", "redstonecircuit", "blockstates", "transformer.json");
        assertTrue(blockstate.contains("\"facing=south,lit=false\": { \"model\": \"redstonecircuit:block/transformer\" }"),
                "未旋转的模型朝向正面 = 南（和控制器的约定一致，方块内画法用的是同一个基准）");
        assertTrue(blockstate.contains("transformer_lit"),
                "亮的那一套用另一个模型文件，四个面的亮贴图都在里面");

        String model = read("resources", "assets", "redstonecircuit", "models", "block", "transformer.json");
        assertTrue(model.contains("\"south\": { \"texture\": \"#front\" }") && model.contains("\"north\": { \"texture\": \"#back\" }"),
                "正面贴图贴在南面、背面贴在北面 - 这两面各自是 16×2 的边条，也是这块装置唯一有方向感的地方");
        assertTrue(model.contains("[16, 2, 16]"),
                "元素就是一块 16×2×16 的薄板：贴图边条画在最下两行，正好是原版 auto-UV 给 2 像素高元素采样的范围");

        for (String texture : List.of("transformer_top.png", "transformer_top_lit.png", "transformer_side.png",
                "transformer_side_lit.png", "transformer_front.png", "transformer_front_lit.png",
                "transformer_back.png", "transformer_back_lit.png")) {
            assertTrue(Files.isRegularFile(path("resources", "assets", "redstonecircuit", "textures", "block",
                    texture)), "贴图 " + texture + " 必须在包里，否则模型是紫黑格");
        }
    }

    @Test
    @DisplayName("界面：同一个协议认这个设备，两个地址（薄板 / 方块内）都能开")
    void theScreenWorksAtBothAddresses() throws IOException {
        String kind = read("logic", "DeviceKind.java");
        assertTrue(kind.contains("TRANSFORMER(\"transformer\")"),
                "那一对通用包靠这个枚举认设备，所以一件设备加一个值、不动协议");

        String block = read("block", "TransformerBlock.java");
        assertTrue(block.contains("DeviceKind.TRANSFORMER.ordinal(), readout.setting(), 0, 0, 0"),
                "世界里的薄板：一个数字框，四个设置位只用第一个");
        assertTrue(block.contains("public static void sendHostView") && block.contains("hostSlot(level, host, ComponentType.TRANSFORMER)"),
                "方块内那一个：同一张屏幕，按点击到达的坐标找元件");
        assertTrue(block.contains("applyHostSettings") && block.contains("announceHostSettings(level, host, slot)"),
                "写入要广播：客户端画的是元件的副本，不广播就看不见。"
                        + "五步现在是 RcPlateBlock#announceHostSettings 一处");

        String router = read("client", "DeviceScreens.java");
        assertTrue(router.contains("instanceof TransformerScreen showing") && router.contains("new TransformerScreen(message)"),
                "客户端按 kind 开屏幕，并且认得出「这就是已经开着的那一张」");

        String screen = read("client", "TransformerScreen.java");
        assertTrue(screen.contains("RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.TRANSFORMER,")
                        && screen.contains("RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.TRANSFORMER)"),
                "屏幕只管把玩家填的数字发回去、并按节奏问一次；所有数值都是服务器给的");
        assertTrue(screen.contains("TransformerRule.isOutput(value)"),
                "填错标红、不发 - 和控制器同一条规矩");

        String world = read("..", "InnerRedstoneInteraction.java");
        assertTrue(world.contains("ComponentType.TRANSFORMER") && world.contains("TransformerBlock.sendHostView"),
                "空手右键宿主 → 变压器的界面（和控制器并列的那一条分支）");
        String carried = read("..", "carrier", "CarrierInteraction.java");
        assertTrue(carried.contains("before.type == ComponentType.TRANSFORMER")
                        && carried.contains("TransformerBlock.sendHostView"),
                "物理化宿主那边也一样");
    }

    /**
     * <b>A device stored inside a block must reach its own handler.</b> The screen's edits and its refresh probes both
     * arrive as {@code DeviceEditPayload}, so a handler that sent every position that is not a plate to the controller
     * would post a transformer's writes to a controller that is not there. The kind is what the message carries for
     * exactly this, so this walks {@link DeviceKind} and demands a branch for each one.
     */
    @Test
    @DisplayName("每一个 DeviceKind 在服务端那个处理器里都有一条分支")
    void everyDeviceKindIsRoutedOnTheServer() throws IOException {
        String handler = handlerBody(read("network", "RCNetwork.java"));
        java.util.Map<String, String> armsByKind = java.util.Map.of(
                "CONTROLLER", "ControllerBlock.applyHostEdit(player, payload);",
                "TRANSFORMER", "TransformerBlock.applyHostEdit(player, payload);",
                "CLOCK", "ClockBlock.applyHostEdit(player, payload);",
                "EXTENDER", "ExtenderBlock.applyHostEdit(player, payload);");
        assertEquals(DeviceKind.values().length, armsByKind.size(),
                "这个断言表必须和 DeviceKind 一样长：新增一件设备就要在这里补一行，否则它会在游戏里静默失灵");
        for (DeviceKind kind : DeviceKind.values()) {
            String arm = armsByKind.get(kind.name());
            assertTrue(arm != null && handler.contains(arm),
                    "服务端必须按 kind 把方块内的写入交给 " + kind + " 自己的处理器，实际这条分支不在（" + arm + "）");
            assertTrue(handler.contains("case " + kind.name() + " ->"),
                    "而且是这一件设备自己的 case，不是「其它都当控制器处理」");
        }
        assertFalse(handler.contains("case null, default -> ControllerBlock"),
                "默认分支必须什么都不做：一件这里没有的设备不该被另一件设备处理");
    }

    /**
     * <b>The screen shows the number the solve read.</b> A level query at the neighbour of the coordinate the screen is
     * addressed at is the wrong frame for a component inside a machine: the solve reads a carried component's input
     * through the structure's own view ({@code CarrierSpace#structureSignal}). The number is therefore recorded where
     * the solver reads it, and the level query is only the fallback for a component not solved yet.
     */
    @Test
    @DisplayName("界面上的「输入」是求解器读到的那个数，不是另外再问一次世界")
    void theScreenShowsTheNumberTheSolveRead() throws IOException {
        String solver = read("logic", "PowerSolver.java");
        String desired = bodyOf(solver, "public static int desiredOutput");
        assertTrue(desired.indexOf("self.noteInput(signal);") < desired.indexOf("switch (self.type())"),
                "记录必须发生在读完之后、按类型给答案之前：那个 signal 就是这块元件读到的输入");

        assertTrue(read("logic", "PowerEnvironment.java").contains("default void noteInput(int input)"),
                "节点接口给了一个默认空实现：导线没有单一的输入面，测试里的假节点也没有槽位可写");
        assertTrue(read("logic", "SlotNode.java").contains("slot.lastInput = input;")
                        && read("..", "carrier", "CarrierSpace.java").contains("slot.lastInput = input;"),
                "世界里的求解和搬运中的求解都要写进去，否则机器里那一个还是老样子");

        String slot = read("data", "Slot.java");
        assertTrue(slot.contains("public int lastInput = -1;"),
                "槽位上那个数就是界面要显示的东西，-1 表示「还没解过」（刚放下、或刚读档）");
        assertFalse(slot.contains("tag.putInt(\"lastInput\""),
                "而且它不进存档：它是「上一次求解」的事实，不是元件的事实");

        String plate = read("block", "RcPlateBlock.java");
        String reading = bodyOf(plate, "public static int hostReading");
        assertTrue(reading.contains("if (slot.lastInput >= 0)") && reading.contains("return slot.lastInput;"),
                "读数优先用求解器记下的那一个");
        assertTrue(reading.contains("InnerRedstoneNetwork.readSignal(level, neighbour, slot.facing)"),
                "没解过时才退回问世界那一格 - 世界里的宿主两条路本来就一致");
    }

    /**
     * <b>Every plate has to leave the physicalised walk a way in, or it goes stale only while it is riding a
     * machine.</b>
     *
     * <p>A plate inside a structure has input faces outside it that can never notify a plot cell, and its answer is
     * cached beside the block, so the per-tick walk over a sub-level's own cells is what keeps it fresh while it
     * flies. That walk is one gated call into the compat half ({@code PhysicsCompat#tickCarriedProducts}), and the
     * list of devices it re-derives is written behind that gate rather than in this jar - so what this guard can
     * demand of every plate is the entry point the list calls: it walks the plates that exist and requires a
     * {@code refreshCarried} of each.
     */
    @Test
    @DisplayName("每一块薄板都给那趟走查留了重算入口（漏一个，它就只在机器上失灵）")
    void everyPlateIsPolledWhenItIsCarried() throws IOException {
        List<String> plates = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(path("block"), "*.java")) {
            for (Path file : files) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                if (!text.contains("extends RcPlateBlock")) {
                    continue;
                }
                String plate = file.getFileName().toString().replace(".java", "");
                plates.add(plate);
                assertTrue(text.contains("public static void refreshCarried(ServerLevel level, BlockPos localPos,"
                                + " BlockState state) {"),
                        plate + " 没给那趟走查留重算入口：它物理化之后答案就再也不重算了");
            }
        }
        assertTrue(plates.contains("ControllerBlock") && plates.contains("TransformerBlock"),
                "扫描本身要能看见这两块薄板，否则上面的断言就是空转；实际找到 " + plates);
        assertTrue(read("logic", "InnerRedstoneTickHandler.java")
                        .contains("PhysicsCompat.tickCarriedProducts(level, pocket)"),
                "而那一趟走查就是关卡 tick 对物理层的那一个调用，走的是带门禁的 compat 入口：没有后端时它不加载任何"
                        + "一个可选模组的类型，直接什么都不做");
    }

    /**
     * The body of the {@code DeviceEditPayload} handler - the switch that routes a screen's write. The search starts
     * at the registration rather than at a method declaration, because the handler IS the receiver the server installs
     * for that payload: {@code ServerPlayNetworking.registerGlobalReceiver} takes the body as a lambda, and the type it
     * is registered for is the same one the decoder above bounds.
     */
    private static String handlerBody(String network) {
        int at = network.indexOf("ServerPlayNetworking.registerGlobalReceiver(DeviceEditPayload.TYPE");
        assertTrue(at >= 0, "RCNetwork 里找不到 DeviceEditPayload 的处理器，断言过期了");
        int open = network.indexOf('{', at);
        int depth = 0;
        for (int i = open; i < network.length(); i++) {
            char c = network.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return network.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after the device edit handler");
    }

    @Test
    @DisplayName("方块内形态：求解器、槽位字段、物品映射、扳手手势、Jade 一处不缺")
    void theComponentFormIsWiredEndToEnd() throws IOException {
        String solver = read("logic", "PowerSolver.java");
        assertTrue(solver.contains("case TRANSFORMER:") && solver.contains("TransformerRule.answer(signal, self.transformer())"),
                "求解器里那一条就是这条规则本身，不是副本");

        String environment = read("logic", "PowerEnvironment.java");
        assertTrue(environment.contains("default int transformer()"),
                "节点接口给了一个默认值，这样别的节点（测试用的假节点）不必知道这个装置");
        assertTrue(read("logic", "SlotNode.java").contains("return slot.transformer;")
                        && read("..", "carrier", "CarrierSpace.java").contains("return slot.transformer;"),
                "世界里的槽位和搬运中的槽位都把它接出来");

        String slot = read("data", "Slot.java");
        assertTrue(slot.contains("public int transformer = ") && slot.contains("copy.transformer = this.transformer;")
                        && slot.contains("tag.putInt(\"transformer\", transformer)"),
                "槽位字段、复制、存档三处都要有，否则塞进方块再取出来设定就丢了");
        assertTrue(slot.contains("TransformerRule.isOutput(transformer)"),
                "读档时 0（没有这个键）按默认值读，不按上下界夹 - 那会把「默认 15」读成 1");

        String rules = read("..", "HostRules.java");
        assertTrue(rules.contains("RCRegistry.TRANSFORMER_ITEM") && rules.contains("case TRANSFORMER ->"),
                "方块物品 → 元件类型、元件类型 → 物品，两个方向都要有，否则塞进去 / 取出来就断了");

        String switches = read("logic", "InnerSwitches.java");
        assertTrue(switches.contains("COMPARATOR, CONTROLLER, TRANSFORMER, CLOCK, EXTENDER -> Gesture.SETTING")
                        && switches.contains("case TRANSFORMER -> Result.NONE;"),
                "扳手/空手的手势归类：需要眼镜的 SETTING，但本身没有可「步进」的东西（界面才是它的手势）");
        assertTrue(read("logic", "CarriedSwitch.java").contains("|| type == ComponentType.TRANSFORMER"),
                "搬运那一侧的可操作性名单也要加上它");
        assertTrue(read("..", "carrier", "CarrierMessages.java").contains("case TRANSFORMER -> \"block.redstonecircuit.transformer\";"),
                "消息里要能说出它的名字");
        assertTrue(read("..", "compat", "jade", "RedstoneCircuitJadePlugin.java").contains("case TRANSFORMER ->"),
                "Jade 那一行也是：它输出的是玩家设的强度，不能用「有输出（15）」糊过去");
    }

    // ------------------------------------------------------------- helpers --

    /**
     * The body of the first member whose declaration starts with {@code signature}, scoped by brace counting rather than by
     * a line window: a window still passes once the method grows.
     */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }

    /** Finds a source or a data file, failing loudly rather than silently skipping the guard. */
    private static String read(String... parts) throws IOException {
        Path file = path(parts);
        if (!Files.isRegularFile(file)) {
            throw new IOException("could not find " + file + " from " + Path.of("").toAbsolutePath());
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static Path path(String... parts) {
        List<String> tail = List.of(parts);
        String area = tail.get(0);
        if (area.equals("resources")) {
            return Path.of("src", "main").resolve(Path.of("", tail.toArray(String[]::new)));
        }
        if (area.equals("..")) {
            return Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit")
                    .resolve(Path.of("", tail.subList(1, tail.size()).toArray(String[]::new)));
        }
        return Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit")
                .resolve(Path.of("", tail.toArray(String[]::new)));
    }
}
