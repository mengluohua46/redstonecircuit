package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 信号延长器's wiring: the half a green unit suite cannot see, read out of the sources and the data files.
 *
 * <p>{@code ExtenderRuleTest} pins the rule as a function of time; {@code ExtenderGameTest} pins it in a level. What is
 * left is the plumbing a copy gets wrong: the two places a hold is advanced, the pass-through the solver has to do, the
 * tables every new device must be named in, and the art the author drew - seven frames of one marker.
 */
class ExtenderWiringTest {

    @Test
    @DisplayName("保持是一个时刻，不是一个倒计时：截止时刻 = 输入那一刻 + 设定时长，错过的 tick 不会把延长拖长")
    void theHoldIsAMomentNotACountdown() throws IOException {
        String rule = read("logic", "ExtenderRule.java");
        assertTrue(rule.contains("return hold > 0 ? now + hold : NO_DEADLINE;"),
                "有输入就把截止时刻推到这一刻之后；设定 0 tick 的那一档不留保持, 写下的就是「没有保持」");
        assertTrue(rule.contains("now <= deadline"),
                "而且是一个时刻：到点就是到点，不数 tick，所以错过的 tick 不会把延长拖长");
        assertTrue(rule.contains("ClockRule.period(seconds, ticks)") && rule.contains("ClockRule.isSeconds(value)")
                        && rule.contains("ClockRule.isTicks(value)"),
                "秒与刻就是时钟源的那一对单位：换算和范围都问它，不写第二遍");
        assertTrue(rule.contains("public static final long NO_DEADLINE = Long.MIN_VALUE"),
                "「没有保持这回事」不能用 0：世界时钟从 0 开始，0 是一个真的时刻");

        String block = read("block", "ExtenderBlock.java");
        assertTrue(block.contains("neighborChanged") && block.contains("announce(serverLevel, pos);"),
                "输入一动（邻居变了）就在同一 tick 重新算答案");
        assertTrue(block.contains("public static void tickStanding(ServerLevel level)")
                        && block.contains("store.extenderKeys()"),
                "薄板由关卡那一趟每 tick 问一次，位置来自设定表本身");
        assertTrue(block.contains("level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ExtenderBlock"),
                "没加载的坐标不去问世界");
        assertTrue(block.contains("public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state)"),
                "物理化之后薄板在 plot 里，Sable 那条走查是唯一每 tick 都到它那儿的人");
        assertTrue(block.contains("ExtenderDeadlines.put(level, pos, deadline)"),
                "截止时刻住在方块旁边（和答案一样是「此刻的事」），不进方块状态也不进存档");
        assertTrue(block.contains("BlockAnswerCache.forget(serverLevel, pos)"),
                "方块没了, 它的答案也跟着走");

        String handler = read("logic", "InnerRedstoneTickHandler.java");
        assertTrue(handler.contains("ExtenderBlock.tickStanding(level);")
                        && handler.indexOf("ExtenderBlock.tickStanding(level);") < handler.indexOf("InnerRedstoneNetwork.tick(level);"),
                "而它挂在每个关卡 tick 上, 且在求解那一趟之前, 边沿才落在同一 tick");
        assertTrue(handler.contains("ExtenderDeadlines.clear(level.dimension())"),
                "维度卸载时那条时刻要跟着走, 否则下一个世界会读到上一个世界的");

        String network = read("logic", "InnerRedstoneNetwork.java");
        String timed = bodyOf(network,
                "public static void tickTimedDevices(ServerLevel level, InnerRedstoneStore store)");
        assertTrue(timed.contains("slot.type.isTimeDriven()") && timed.contains("ComponentType.EXTENDER"),
                "方块内那一个由世界这一趟推进（时钟源那种「时间驱动」和延长器那种「保持」都在这里）");
        assertTrue(network.contains("slot.extender.holdTicks()") && network.contains("ExtenderRule.deadline(")
                        && network.contains("ExtenderRule.answer("),
                "推进用的就是规则本身那两个函数");
        assertTrue(network.contains("slot.lastInput >= 0") && network.contains("readSignal(level, neighbour, slot.facing)"),
                "输入取求解器读到的那个数（机器内部的坐标不是这个世界的），没解过才回落到世界一次读");
        assertTrue(timed.contains("slot.power = wanted") && timed.contains("notifyOutputChanged(level, pos)")
                        && timed.contains("HostSync.broadcast(level, pos, slot)"),
                "写答案的三步：改强度、叫醒世界、把客户端那份画出来的副本也更新");

        String carrier = read("..", "carrier", "CarrierNetworks.java");
        assertTrue(carrier.contains("slot.type == ComponentType.EXTENDER") && carrier.contains("slot.lastInput"),
                "搬运中的那一个由结构自己那一趟推进（和世界里那条镜像），否则机器一飞起来保持就不会结束");
        assertTrue(read("compat", "physics", "SableProductDriver.java")
                        .contains("ExtenderBlock.refreshCarried(level, cell, state);"),
                "物理化的那一个也要被那条走查每 tick 问一次");

        String solver = read("logic", "PowerSolver.java");
        assertTrue(solver.contains("case EXTENDER:"), "求解器要认这个类型");
        String desired = bodyOf(solver, "public static int desiredOutput");
        assertTrue(desired.contains("return signal > 0 ? clamp(signal) : clamp(self.power());"),
                "跟随是组合的：输入在就原样出去（一 tick 都不等）；没有输入时保持上一次的答案"
                        + " —— 这一步不能把答案算成 0，否则保持会被求解器自己打断");
        String emits = bodyOf(solver, "public static boolean emitsToward(ComponentType type, Direction facing,"
                + " Direction direction,");
        assertTrue(emits.contains("case EXTENDER:") && emits.contains("return direction == facing.getOpposite();"),
                "只从背面出去：薄板和方块内那一个是一件设备");
        String reads = bodyOf(solver, "public static boolean readsToward(ComponentType type, Direction facing,"
                + " Direction direction,");
        assertTrue(reads.contains("case EXTENDER:") && reads.contains("return direction == facing;"),
                "只读正面：侧面的东西不是它的输入");
        assertTrue(read("..", "data", "ComponentType.java").contains("|| this == EXTENDER;"),
                "而「有输入面」也是它：求解器才会重新推导它、路由锁才拿不走它的输入");
    }

    @Test
    @DisplayName("设置住在方块旁边那张表里，保持的时刻住在更外面：两者都不进方块状态")
    void theSettingsAreBesideTheBlock() throws IOException {
        String block = read("block", "ExtenderBlock.java");
        assertTrue(block.contains("builder.add(LIT)"), "状态里只加一个亮");
        assertFalse(block.contains("IntegerProperty"),
                "秒 0-3600 要是进了状态，单品就是 57 万个状态，所以它不能是方块状态属性");
        assertTrue(block.contains("store.extenderAt(pos)") && block.contains("store.putExtender(pos, wanted)"),
                "两个数读写的是 InnerRedstoneStore 那张按坐标的表");
        assertTrue(block.contains("private static void keepASetting")
                        && block.contains("store.putExtender(pos, ExtenderRule.Setting.DEFAULT)"),
                "放下就写一份默认设定：捕获读的就是这张表，没有条目就带不走");

        String store = read("..", "data", "InnerRedstoneStore.java");
        assertTrue(store.contains("extenderAt(BlockPos pos)") && store.contains("putExtender(BlockPos pos")
                        && store.contains("forgetExtender(BlockPos pos)"),
                "表本身要有读、写、忘三件事（方块被拆掉时设定得跟着走）");
        assertTrue(store.contains("tag.put(\"extenders\"") && store.contains("tag.getList(\"extenders\""),
                "而且要进存档：重登世界不能把玩家设的时长忘掉");

        String slot = read("..", "data", "Slot.java");
        assertTrue(slot.contains("ExtenderRule.Setting extender =") && slot.contains("copy.extender = this.extender;")
                        && slot.contains("tag.put(\"extender\", extenderTag)"),
                "槽位字段、复制、存档三处都要有，否则塞进方块再取出来设定就丢了");
        assertTrue(slot.contains(".load(tag.getCompound(\"extender\"))"),
                "读档走 Setting#load（0 秒 0 刻 是合法设定，所以判据是有没有键）");
        assertTrue(slot.contains("public long extenderDeadline") && !slot.contains("putLong(\"extenderDeadline\""),
                "而保持的时刻不进存档：换一个存档就没有「刚才那个输入是什么时候走的」了");
        assertTrue(slot.contains("copy.extenderDeadline = this.extenderDeadline;"),
                "但要跟着广播的那份副本走：屏幕要显示还剩多少");

        String timers = read("logic", "ExtenderDeadlines.java");
        assertTrue(timers.contains("public static long get(ServerLevel level, BlockPos pos)")
                        && timers.contains("ExtenderRule.NO_DEADLINE"),
                "这张表就是这一件事：一个位置一个时刻");
        assertFalse(timers.contains("extends SavedData"), "它不是存档数据，只是一个世界此刻的事实");
    }

    @Test
    @DisplayName("元件表：物品↔类型、扳手手势、搬运手势、名字、Jade、方块内那行字、画法，一处不缺")
    void everyTableThatNamesComponentsHasIt() throws IOException {
        String rules = read("..", "HostRules.java");
        assertTrue(rules.contains("RCRegistry.EXTENDER_ITEM.get()")
                        && rules.contains("case EXTENDER -> new ItemStack(RCRegistry.EXTENDER_ITEM.get())"),
                "方块物品 → 元件类型、元件类型 → 物品，两个方向都要有");
        String switches = read("logic", "InnerSwitches.java");
        assertTrue(switches.contains("CLOCK, EXTENDER -> Gesture.SETTING")
                        && switches.contains("case EXTENDER -> Result.NONE;"),
                "扳手手势是 SETTING（要眼镜），但没有可步进的东西：界面才是它的手势");
        assertTrue(read("logic", "CarriedSwitch.java").contains("|| type == ComponentType.EXTENDER;"),
                "搬运那一侧的可操作性名单也要加上它");
        assertTrue(read("..", "carrier", "CarrierMessages.java")
                        .contains("case EXTENDER -> \"block.redstonecircuit.extender\";"),
                "消息里要能说出它的名字");
        String jade = read("..", "compat", "jade", "RedstoneCircuitJadePlugin.java");
        assertTrue(jade.contains("K_EXTENDER") && jade.contains("case EXTENDER ->"),
                "Jade 那一行：它回答的是输入/保持的强度，不能用「有输出（15）」糊过去");
        String line = read("client", "InnerComponentLine.java");
        assertTrue(line.contains("case EXTENDER -> StateKind.EXTENDER;") && line.contains("case EXTENDER -> type +"),
                "方块内那行字也要认它");
        String visuals = read("client", "InnerComponentVisuals.java");
        assertTrue(visuals.contains("EXTENDER,") && visuals.contains("case EXTENDER -> {")
                        && visuals.contains("RCRegistry.EXTENDER.get().defaultBlockState()"),
                "方块内看得见它：用自己那张薄板的模型，亮灭跟着输出走");

        assertTrue(read("logic", "DeviceKind.java").contains("EXTENDER(\"extender\")"),
                "那一对通用包靠这个枚举认设备");
        assertTrue(read("..", "network", "RCNetwork.java")
                        .contains("case EXTENDER -> ExtenderBlock.applyHostEdit(player, payload);"),
                "方块内那一个的写入要走自己的处理器");
        assertTrue(read("InnerRedstoneInteraction.java")
                        .contains("ExtenderBlock.sendHostView(player, level, pos, true);"),
                "空手右键宿主：开界面");
        assertTrue(read("..", "carrier", "CarrierInteraction.java")
                        .contains("ExtenderBlock.sendHostView(player, level, hit.localPos(), true);"),
                "物理化宿主上的那一次点击：同一个界面，按结构自己的坐标寻址");
        String screen = read("client", "ExtenderScreen.java");
        assertTrue(screen.contains("RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.EXTENDER,")
                        && screen.contains("RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.EXTENDER)"),
                "屏幕的写入与刷新都点着这一件设备");
    }

    @Test
    @DisplayName("搬运：设置跟着结构走，而保持的时刻不跟（那是此刻的事）")
    void theSettingsTravelWithACapturedStructure() throws IOException {
        assertTrue(read("..", "structure", "StructureCapture.java")
                        .contains("ExtenderRule.Setting extender = store.extenderAt(pos)"),
                "捕获时要把方块旁那张表读出来，否则释放回来的是默认时长");
        assertTrue(read("..", "structure", "pocket", "PocketMaterializer.java")
                        .contains("instanceof com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock"),
                "放进口袋时按「站在这一格的是哪件设备」把条目写回去");
        assertTrue(read("..", "structure", "StructureRelease.java")
                        .contains("instanceof com.jiangyuefengyu.redstonecircuit.block.ExtenderBlock"),
                "释放时同样按方块认设备");
        assertTrue(read("..", "structure", "StructurePayload.java").contains("SettingEntry"),
                "两种「方块旁边的数据」共用一条 SettingEntry：位置 + 一个 compound，谁的不重要");
    }

    @Test
    @DisplayName("资源：方块状态、两张模型（顶面动画 + 亮点）、物品、掉落、合成、六张文件、两种语言")
    void theResources() throws IOException {
        String blockstate = read("resources", "assets", "redstonecircuit", "blockstates", "extender.json");
        assertTrue(blockstate.contains("\"facing=south,lit=false\": { \"model\": \"redstonecircuit:block/extender\" }"),
                "模型自己朝南那一个不加 y（正面 = 南），和其它三件同一套约定");
        assertTrue(blockstate.contains("extender_lit"), "亮的那一档要有自己的模型");

        String model = read("resources", "assets", "redstonecircuit", "models", "block", "extender.json");
        assertTrue(model.contains("\"from\": [0, 0, 0]") && model.contains("\"to\": [16, 2, 16]"),
                "薄板：高 1/8 格，贴在格子底部");
        assertTrue(model.contains("\"up\": { \"texture\": \"#top\" }")
                        && model.contains("\"down\": { \"texture\": \"#bottom\" }"),
                "上下面是两张不同的贴图（上面是那条显示条，下面是素面）");
        assertTrue(model.contains("\"south\": { \"texture\": \"#side\" }")
                        && model.contains("\"north\": { \"texture\": \"#side\" }")
                        && model.contains("\"east\": { \"texture\": \"#side\" }")
                        && model.contains("\"west\": { \"texture\": \"#side\" }"),
                "四个侧面同一张贴图（那条 2 像素高的横条）");
        String lit = read("resources", "assets", "redstonecircuit", "models", "block", "extender_lit.json");
        assertTrue(lit.contains("extender_top_run"),
                "亮的时候顶面是那张 7 帧动画：编号 1-7 就是同一个面上那个滑标在跑");
        assertTrue(lit.contains("\"dot\": \"redstonecircuit:block/extender_dot\"")
                        && lit.contains("\"from\": [1, 2.01, 10]") && lit.contains("\"to\": [3, 2.01, 12]"),
                "而「亮点」是一个 2×2 像素的贴片，正好压在显示条那个红点的位置（暗红点是灭，亮红点是亮）");
        assertTrue(read("resources", "assets", "redstonecircuit", "models", "item", "extender.json")
                        .contains("redstonecircuit:block/extender"), "物品用方块模型");

        assertTrue(read("resources", "data", "redstonecircuit", "loot_table", "blocks", "extender.json")
                        .contains("\"name\": \"redstonecircuit:extender\""), "掉落表要掉它自己（空手可挖）");
        String recipe = read("resources", "data", "redstonecircuit", "recipe", "extender.json");
        assertTrue(recipe.contains("\"pattern\": [ \"IGI\", \"LRL\", \"IGI\" ]"),
                "比例：中间红石，两侧青金石，上下金锭，四角铁锭");
        assertTrue(recipe.contains("\"item\": \"minecraft:redstone\"") && recipe.contains("\"item\": \"minecraft:lapis_lazuli\"")
                        && recipe.contains("\"item\": \"minecraft:gold_ingot\"")
                        && recipe.contains("\"item\": \"minecraft:iron_ingot\""),
                "四个材料都要是真实存在的物品");

        for (String texture : List.of("extender_top.png", "extender_top_run.png", "extender_top_run.png.mcmeta",
                "extender_bottom.png", "extender_side.png", "extender_dot.png")) {
            assertTrue(Files.isRegularFile(path("resources", "assets", "redstonecircuit", "textures", "block", texture)),
                    texture + " 必须在（模型引用它）");
        }
        Path strip = path("resources", "assets", "redstonecircuit", "textures", "block", "extender_top_run.png");
        var image = ImageIO.read(strip.toFile());
        assertEquals(112, image.getHeight(), "七帧竖着叠成一张 16×112 的动画条");
        assertEquals(16, image.getWidth(), "每一帧就是那张 16×16");
        assertTrue(Files.readString(path("resources", "assets", "redstonecircuit", "textures", "block",
                        "extender_top_run.png.mcmeta")).contains("\"frametime\""), "而且要是动画");

        for (String lang : List.of("zh_cn", "en_us")) {
            String file = read("resources", "assets", "redstonecircuit", "lang", lang + ".json");
            for (String key : List.of("block.redstonecircuit.extender", "block.redstonecircuit.extender.tip",
                    "block.redstonecircuit.extender.tip.detail", "redstonecircuit.extender.title",
                    "redstonecircuit.extender.rule", "redstonecircuit.extender.hold",
                    "redstonecircuit.extender.off", "redstonecircuit.extender.holding",
                    "redstonecircuit.extender.passing", "redstonecircuit.extender.silent",
                    "jade.redstonecircuit.inner.extender")) {
                assertTrue(file.contains("\"" + key + "\""), lang + " 少了 " + key);
            }
        }

        String registry = read("RCRegistry.java");
        assertTrue(registry.contains("BLOCKS.register(\"extender\", () -> new ExtenderBlock(")
                        && registry.contains("output.accept(EXTENDER_ITEM.get());"),
                "方块、物品、创造模式物品栏三处都要注册");
    }

    // ------------------------------------------------------------- helpers --

    /** The body of the first member whose declaration starts with {@code signature}, scoped by brace counting. */
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
        Path relative;
        if (area.equals("resources")) {
            relative = Path.of("src", "main").resolve(Path.of("", tail.toArray(String[]::new)));
        } else if (area.equals("..")) {
            relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit")
                    .resolve(Path.of("", tail.subList(1, tail.size()).toArray(String[]::new)));
        } else if (area.equals("compat")) {
            relative = Path.of("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat")
                    .resolve(Path.of("", tail.subList(1, tail.size()).toArray(String[]::new)));
        } else {
            relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit")
                    .resolve(Path.of("", tail.toArray(String[]::new)));
        }
        Path fromParent = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, fromParent)) {
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return relative;
    }
}
