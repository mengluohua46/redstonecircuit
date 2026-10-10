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
 * 时钟源's wiring: the half of it a green unit suite cannot see, read out of the sources and the data files.
 *
 * <p>What is left after {@code ClockRuleTest} and {@code ClockGameTest} is the wiring that only shows up in
 * game, and this device has more of it than the other two, because it has to be <b>asked</b> rather than
 * told: the registration and the art, the three shapes something turns over (in the world, inside a host
 * block, and riding a machine), the settings that travel with a structure, and the screen at both addresses.
 */
class ClockWiringTest {

    @Test
    @DisplayName("随世界时钟：相位是 gameTime 的函数，方块自己不记任何计数")
    void thePhaseFollowsTheWorldClock() throws IOException {
        String rule = read("logic", "ClockRule.java");
        assertTrue(rule.contains("Math.floorMod(gameTime, period)"),
                "相位必须是世界时钟上的位置，不是每个方块自己的倒计时");
        assertTrue(rule.contains("public static int period(int seconds, int ticks)")
                        && rule.contains("clampSeconds(seconds) * TICKS_PER_SECOND + clampTicks(ticks)"),
                "周期是两个单位相加：秒 × 20 + tick");
        assertTrue(rule.contains("public static final int PULSE_TICKS = 2"),
                "脉宽是 2 tick：一个 tick 的脉冲会被结构那条路稀释掉");
        assertTrue(rule.contains("public static final int MAX_SECONDS = 3600")
                        && rule.contains("public static final int MAX_TICKS = 19"),
                "范围：秒 0-3600、tick 0-19（20 tick 就是一秒，所以 tick 差一档不重复拼法）");

        String block = read("block", "ClockBlock.java");
        assertTrue(block.contains("ClockRule.isOn(level.getGameTime(), setting.period())"),
                "方块自己也是按世界时钟算这一 tick 该不该输出");
        assertTrue(block.contains("ClockRule.ticksToEdge(level.getGameTime(), setting.period())"),
                "界面那一行「距下一次变化」用的是同一条纯函数（客户端自己也算得出来，所以它每帧都能动）");
    }

    @Test
    @DisplayName("设置不在方块状态里：只有 朝向 × 亮 = 8 个状态，三个数在方块旁边那张表里")
    void theSettingsLiveBesideTheBlock() throws IOException {
        String block = read("block", "ClockBlock.java");
        assertTrue(block.contains("builder.add(LIT)"), "状态里只加一个亮");
        assertFalse(block.contains("IntegerProperty"),
                "秒 0-3600 要是进了状态，单品就是 430 万个状态，所以它不能是方块状态属性");
        assertTrue(block.contains("store.clockAt(pos)") && block.contains("store.putClock(pos, wanted)"),
                "三个数读写的是 InnerRedstoneStore 那张按坐标的表");
        assertTrue(block.contains("private static void keepASetting") && block.contains("store.putClock(pos, ClockRule.Setting.DEFAULT)"),
                "放下就写一份默认设定：捕获读的就是这张表，没有条目就带不走");

        String store = read("..", "data", "InnerRedstoneStore.java");
        assertTrue(store.contains("clockAt(BlockPos pos)") && store.contains("putClock(BlockPos pos")
                        && store.contains("forgetClock(BlockPos pos)"),
                "表本身要有读、写、忘三件事（方块被拆掉时设定得跟着走）");
        assertTrue(store.contains("tag.put(\"clocks\"") && store.contains("tag.getList(\"clocks\""),
                "而且要进存档：重登世界不能把玩家设的周期忘掉（这跟 BlockAnswerCache 不一样，那个是推导出来的）");
        assertTrue(store.contains("nodes.isEmpty() && clocks.isEmpty()"),
                "一台只有时钟源的世界也不能被 isEmpty 那个快路径跳过，否则它一次都不会走");

        String settings = read("logic", "ClockRule.java");
        assertTrue(settings.contains("clock.save(clockTag)") || settings.contains("public void save(CompoundTag tag)"),
                "设定自己会存档（秒 / tick / 强度三个键）");
    }

    @Test
    @DisplayName("谁推进它：关卡那一趟每 tick 问一次（世界里 / 方块内 / 搬运中三种形态各有一条路）")
    void somethingTurnsItOverInBothForms() throws IOException {
        String block = read("block", "ClockBlock.java");
        assertTrue(block.contains("public static void tickStanding(ServerLevel level)")
                        && block.contains("store.clockKeys()"),
                "薄板由关卡那一趟每 tick 问一次，位置来自设定表本身（时钟的设定就是那张表，不用再维护第二个索引）");
        assertTrue(block.contains("level.isLoaded(pos) && level.getBlockState(pos).getBlock() instanceof ClockBlock"),
                "没加载的坐标不去问世界；加载回来的那一刻就会被问到（答案没变就什么都不写）");
        assertFalse(block.contains("level.scheduleTick("),
                "不用 Level#scheduleTick：方块在 GameTest 里不会自己 tick，"
                        + "而这一趟同时覆盖站着的、方块内的、搬运中的三种形态");
        assertTrue(read("logic", "InnerRedstoneTickHandler.java").contains("ClockBlock.tickStanding(level);"),
                "而那一趟确实挂在每个关卡 tick 上");
        assertTrue(block.contains("state.getValue(LIT) == on"),
                "答案变了没变拿 LIT 比：亮灭本身就是「上次说的答案」，所以这台设备不需要额外的缓存");

        String network = read("logic", "InnerRedstoneNetwork.java");
        String tick = bodyOf(network, "public static void tick(ServerLevel level)");
        assertTrue(tick.contains("tickTimedDevices(level, store);")
                        && tick.indexOf("tickTimedDevices(level, store);") < tick.indexOf("applyDueFlips(level, store);"),
                "方块内那一个由世界这一趟在 settle 之前写，边沿才落在同一 tick 上");
        String clocks = bodyOf(network,
                "public static void tickTimedDevices(ServerLevel level, InnerRedstoneStore store)");
        assertTrue(clocks.contains("slot.clock.answer(now)") && clocks.contains("slot.power = wanted")
                        && clocks.contains("notifyOutputChanged(level, pos)") && clocks.contains("HostSync.broadcast(level, pos, slot)"),
                "写答案的三步：改强度、叫醒世界、把客户端那份画出来的副本也更新（亮点就是靠它）");
        assertTrue(clocks.contains("positionKeys()"),
                "每 tick 都走的一趟不建 BlockPos 列表（跟比较器那条 poll 一样的顾虑）");
        assertTrue(read("logic", "InnerRedstoneTickHandler.java").contains("InnerRedstoneNetwork.tick(level);"),
                "而那一趟确实挂在每个关卡 tick 上");

        String carrier = read("..", "carrier", "CarrierNetworks.java");
        assertTrue(carrier.contains("if (slot.type.isTimeDriven())") && carrier.contains("slot.power = slot.clock.answer(tick)"),
                "搬运中的那一个由结构自己的那一趟写（和世界里那条镜像），否则机器一飞起来时钟就停了");
        String type = read("..", "data", "ComponentType.java");
        assertTrue(type.contains("public boolean isTimeDriven()") && type.contains("CLOCK;"),
                "「答案来自时间而不是来自输入」是这一件的新种类，两个 tick 路径都按它找元件");
        assertTrue(read("logic", "CarrierLightNetwork.java").contains("|| type.isTimeDriven()"),
                "它也必须是逻辑元件，否则它在结构里既不是种子也不会被算进去");

        // 搬运中的薄板：每 tick 那趟走查会问它，而且**不**在那里排任何计划 tick。
        String refresh = bodyOf(block, "public static void refreshCarried(ServerLevel level, BlockPos localPos, BlockState state)");
        assertTrue(refresh.contains("announce(level, localPos)"), "它只报答案");
        assertFalse(refresh.contains("scheduleTick("),
                "不排计划 tick：这一趟每 tick 都到，排了就是一 tick 一个约会，越堆越多");
        assertTrue(read("logic", "InnerRedstoneTickHandler.java")
                        .contains("PhysicsCompat.tickCarriedProducts(level, pocket)"),
                "而那一趟走查就是关卡 tick 对物理层的那一个调用，走的是带门禁的 compat 入口：没有后端时它不加载任何一个"
                        + "可选模组的类型。它按设备点名重算的那份名单写在门禁后面，所以这里钉住的是薄板自己那个入口");
    }

    @Test
    @DisplayName("随结构走：载荷里多一张表，捕获/释放/材料化三处都读写它")
    void theSettingsTravelWithAStructure() throws IOException {
        String payload = read("..", "structure", "StructurePayload.java");
        assertTrue(payload.contains("List<SettingEntry> settingData"),
                "载荷里要有它自己的那张表，和 slotData 并列");
        assertTrue(payload.contains("SettingEntry.CODEC.listOf().optionalFieldOf(\"setting_data\", List.of())"),
                "老文件没有这个键就是空表，不需要动格式版本号");
        assertTrue(payload.contains("public record SettingEntry(BlockPos localPos, CompoundTag setting)"),
                "一格一条：坐标 + 三个数的 tag");
        assertTrue(payload.contains("sortedByPosition(this.settingData, StructurePayload.SettingEntry::localPos)"),
                "渲染缓存键也要把它算进去（位置顺序无所谓，所以先排序）");
        assertTrue(payload.contains("for (SettingEntry entry : this.settingData)"),
                "越界坐标的校验也不能漏");

        assertTrue(read("..", "structure", "StructureCapture.java").contains("store.clockAt(pos)")
                        && read("..", "structure", "StructureCapture.java").contains("new StructurePayload.SettingEntry("),
                "捕获时按同一个 migrateSlots 开关把设定抄进载荷");
        assertTrue(read("..", "structure", "StructureRelease.java").contains("payload.settingData()")
                        && read("..", "structure", "StructureRelease.java").contains("putClock(pos,"),
                "释放到世界里时写回方块旁边");
        assertTrue(read("..", "structure", "pocket", "PocketMaterializer.java").contains("payload.settingData()")
                        && read("..", "structure", "pocket", "PocketMaterializer.java").contains("putClock(pos,"),
                "放进结构空间（plot）时也一样，否则机器一搬运就回到 1 秒");
    }

    @Test
    @DisplayName("界面：同一个协议认这一件，两个地址都能开，写入要广播")
    void theScreenWorksAtBothAddresses() throws IOException {
        String kind = read("logic", "DeviceKind.java");
        assertTrue(kind.contains("CLOCK(\"clock\")"), "那一对通用包靠这个枚举认设备");
        assertTrue(kind.contains("ClockRule.MAX_SECONDS") && kind.contains("ClockRule.MAX_PERIOD"),
                "而解码端的上界必须跟得上最宽的那个设定（秒 3600）和最宽的那个读数（时钟源的一整个周期 72019）");

        String block = read("block", "ClockBlock.java");
        assertTrue(block.contains("DeviceKind.CLOCK.ordinal(), readout.setting().seconds(), readout.setting().ticks(),")
                        && block.contains("readout.setting().strength(), 0, readout.power(), readout.ticksToEdge()));"),
                "世界里的薄板：三个数用前三位，第四个留着；后面那两个是「现在输出了多少」和「距下一次变化还有几 tick」，"
                        + "由服务器算好一起发过来");
        assertTrue(block.contains("public static void sendHostView") && block.contains("hostSlot(level, host, ComponentType.CLOCK)"),
                "方块内那一个：同一张屏幕，按点击到达的坐标找元件");
        assertTrue(block.contains("announceHostSettings(level, host, slot)"),
                "写入要广播：客户端画的是元件的副本，不广播就看不见");

        String router = read("client", "DeviceScreens.java");
        assertTrue(router.contains("instanceof ClockScreen showing") && router.contains("new ClockScreen(message)"),
                "客户端按 kind 开屏幕，并且认得出「这就是已经开着的那一张」");
        String screen = read("client", "ClockScreen.java");
        assertTrue(screen.contains("RCNetwork.DeviceEditPayload.settings(this.pos, DeviceKind.CLOCK,")
                        && screen.contains("RCNetwork.DeviceEditPayload.probe(this.pos, DeviceKind.CLOCK)"),
                "屏幕只管把玩家填的数字发回去、并按节奏问一次；所有数值都是服务器给的");
        assertTrue(screen.contains("ClockRule.isStrength(value)") && screen.contains("ClockRule.isTicks(value)"),
                "填错标红、不发 - 和另外两件同一条规矩");
        assertTrue(screen.contains("ClockRule.answer(now, this.seconds, this.ticks, this.strength)"),
                "而「现在输出了没有」它自己就能算：相位是世界时钟的纯函数，两边算出来的是同一个答案");

        String network = read("..", "network", "RCNetwork.java");
        assertTrue(network.contains("case CLOCK -> ClockBlock.applyHostEdit(player, payload);"),
                "服务端按 kind 把方块内的写入交给它自己的处理器");
        assertTrue(read("..", "InnerRedstoneInteraction.java").contains("ClockBlock.sendHostView"),
                "世界里的宿主：空手右键开它的界面");
        assertTrue(read("..", "carrier", "CarrierInteraction.java").contains("ClockBlock.sendHostView"),
                "物理化宿主那边也一样");
    }

    @Test
    @DisplayName("元件表：物品↔类型、扳手手势、搬运手势、名字、Jade 一处不缺")
    void everyTableThatNamesComponentsHasIt() throws IOException {
        String rules = read("..", "HostRules.java");
        assertTrue(rules.contains("RCRegistry.CLOCK_ITEM") && rules.contains("case CLOCK -> new ItemStack(RCRegistry.CLOCK_ITEM)"),
                "方块物品 → 元件类型、元件类型 → 物品，两个方向都要有");
        String switches = read("logic", "InnerSwitches.java");
        assertTrue(switches.contains("TRANSFORMER, CLOCK, EXTENDER -> Gesture.SETTING")
                        && switches.contains("case CLOCK -> Result.NONE;"),
                "扳手手势是 SETTING（要眼镜），但没有可步进的东西：界面才是它的手势");
        assertTrue(read("logic", "CarriedSwitch.java").contains("|| type == ComponentType.CLOCK"),
                "搬运那一侧的可操作性名单也要加上它");
        assertTrue(read("..", "carrier", "CarrierMessages.java").contains("case CLOCK -> \"block.redstonecircuit.clock\";"),
                "消息里要能说出它的名字");
        String jade = read("..", "compat", "jade", "RedstoneCircuitJadePlugin.java");
        assertTrue(jade.contains("K_CLOCK") && jade.contains("case CLOCK ->"),
                "Jade 那一行：它输出的是玩家设的强度，不能用「有输出（15）」糊过去");
        String line = read("client", "InnerComponentLine.java");
        assertTrue(line.contains("case CLOCK -> StateKind.CLOCK;") && line.contains("case CLOCK -> type +"),
                "方块内那行字也要认它");
        String visuals = read("client", "InnerComponentVisuals.java");
        assertTrue(visuals.contains("CLOCK,") && visuals.contains("case CLOCK -> {")
                        && visuals.contains("RCRegistry.CLOCK.defaultBlockState()"),
                "方块内看得见它：用自己那张薄板的模型，亮灭跟着输出走");
    }

    @Test
    @DisplayName("资源：方块状态、两张模型、物品、掉落、合成、四张贴图、两种语言")
    void theResources() throws IOException {
        String blockstate = read("resources", "assets", "redstonecircuit", "blockstates", "clock.json");
        assertTrue(blockstate.contains("\"facing=south,lit=false\": { \"model\": \"redstonecircuit:block/clock\" }"),
                "模型自己朝南那一个不加 y（正面 = 南），和另外两件同一套约定");
        assertTrue(blockstate.contains("clock_lit"), "亮的那一档要有自己的模型");

        String model = read("resources", "assets", "redstonecircuit", "models", "block", "clock.json");
        assertTrue(model.contains("\"from\": [0, 0, 0]") && model.contains("\"to\": [16, 2, 16]"),
                "薄板：高 1/8 格，贴在格子底部");
        assertTrue(model.contains("\"up\": { \"texture\": \"#top\" }")
                        && model.contains("\"down\": { \"texture\": \"#bottom\" }"),
                "上下面是两张不同的贴图（上 / 下）");
        assertTrue(model.contains("\"south\": { \"texture\": \"#side\" }")
                        && model.contains("\"north\": { \"texture\": \"#side\" }")
                        && model.contains("\"east\": { \"texture\": \"#side\" }")
                        && model.contains("\"west\": { \"texture\": \"#side\" }"),
                "四个侧面同一张贴图 —— 所以世界里看不出正/背面，只有上表面会亮");
        assertTrue(read("resources", "assets", "redstonecircuit", "models", "block", "clock_lit.json")
                        .contains("clock_top_lit"), "亮的是上表面（上表面亮 = 正在输出）");
        assertTrue(read("resources", "assets", "redstonecircuit", "models", "item", "clock.json")
                        .contains("redstonecircuit:block/clock"), "物品用方块模型");

        assertTrue(read("resources", "data", "redstonecircuit", "loot_table", "blocks", "clock.json")
                        .contains("\"name\": \"redstonecircuit:clock\""), "掉落表要掉它自己（空手可挖）");
        String recipe = read("resources", "data", "redstonecircuit", "recipe", "clock.json");
        assertTrue(recipe.contains("\"pattern\": [ \" Q \", \"GCG\", \" Q \" ]"),
                "比例：中间一个钟，左右金锭，上下下界石英");
        assertTrue(recipe.contains("\"item\": \"minecraft:clock\"") && recipe.contains("\"item\": \"minecraft:gold_ingot\"")
                        && recipe.contains("\"item\": \"minecraft:quartz\""),
                "三个材料都要是真实存在的物品（净下界石英的 id 是 minecraft:quartz）");

        for (String texture : List.of("clock_top.png", "clock_top_lit.png", "clock_bottom.png", "clock_side.png")) {
            assertTrue(Files.isRegularFile(path("resources", "assets", "redstonecircuit", "textures", "block", texture)),
                    texture + " 必须在（模型引用它）");
        }

        for (String lang : List.of("zh_cn", "en_us")) {
            String file = read("resources", "assets", "redstonecircuit", "lang", lang + ".json");
            for (String key : List.of("block.redstonecircuit.clock", "block.redstonecircuit.clock.tip",
                    "block.redstonecircuit.clock.tip.detail", "redstonecircuit.clock.title",
                    "redstonecircuit.clock.rule", "redstonecircuit.clock.period", "redstonecircuit.clock.emitting",
                    "redstonecircuit.clock.silent", "redstonecircuit.clock.next", "redstonecircuit.device.seconds",
                    "redstonecircuit.device.ticks", "jade.redstonecircuit.inner.clock")) {
                assertTrue(file.contains("\"" + key + "\""), lang + " 少了 " + key);
            }
        }

        String registry = read("RCRegistry.java");
        assertTrue(registry.contains("public static final ClockBlock CLOCK = Registry.register(")
                        && registry.contains("RedstoneCircuit.id(\"clock\")")
                        && registry.contains("new ClockBlock(")
                        && registry.contains("output.accept(CLOCK_ITEM);"),
                "方块、物品、创造模式物品栏三处都要注册。字段本身就是那个方块：这个加载器的 Registry.register 直接把对象"
                        + "递回来，没有一层 holder");
    }

    @Test
    @DisplayName("界面写入不写「机器画在哪一格」的世界条目：那一条是清掉，不是写上去")
    void theScreenWriteLeavesNoGhost() throws IOException {
        String plate = read("block", "RcPlateBlock.java");
        assertTrue(plate.contains("HostSync.publishWorldEntry(level, drawn)"),
                "搬运中的宿主：客户端靠上面那份 carrier 载荷画它，世界那一格只该被清掉");
        assertFalse(plate.contains("HostSync.broadcast(level, drawn, slot);"),
                "那一行会在机器画的那一格写下元件，机器走了以后永远没人再改它");
        String sync = read("..", "network", "HostSync.java");
        assertTrue(sync.contains("public static void publishWorldEntry(ServerLevel level, BlockPos worldPos)")
                        && sync.contains("slotAt(worldPos) != null"),
                "而这条规则只有一处（HostSync#publishWorldEntry），并且真有宿主站在那一格时让开");
        assertTrue(read("..", "carrier", "CarrierInteraction.java")
                        .contains("HostSync.publishWorldEntry(level, hit.worldPos())"),
                "扳手 / 旋转棒那几条路用的是同一个方法");
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
