package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * <b>机器上的两支同步火把: 调频器认得出它, 立着的那一支有人认领, 而这两种方块类型真的挂上了钩子.</b>
 *
 * <p>三块代码分开跑都编译得过: 手势规则、Create 那一侧的认领、以及注册入口。拆掉任何一块都不会让别的用例变红 - 只会让游戏里出现「调频器在机器上什么也不做」
 * 或者「那支落地之后一个人」。所以按源码位置钉, 与
 * {@code CarriedSyncTorchWiringTest} / {@code CarriedTunerWiringTest} 同一个写法。
 *
 * <p>为什么这里钉源码而不钉行为: 这三处一半在 {@code src/compat} (Create 是 compileOnly, 本机跑不到), 另一半是
 * {@code ItemStack}/注册表驱动的, 裸 JVM 造不出来。能钉的、也是唯一会悄悄坏掉的, 就是"这一句还在不在、在不在正确的一侧"。
 */
class CarriedInterfaceWiringTest {

    // ------------------------------------------------------------- 手势 --

    @Test
    @DisplayName("调频器是一个手势: 第五个事实在 Hold 上, 判定在 Kind.of 里, 而且排在其他工具的后面")
    void theTunerIsAGestureOfItsOwn() throws IOException {
        String gesture = main("carrier", "CarrierGesture.java");
        String kinds = bodyOf(gesture, "public enum Kind {");
        assertTrue(kinds.contains("TUNER"), "调频器没有自己的 kind, 于是它一路 NONE 到底");
        assertTrue(kinds.indexOf("TUNER") < kinds.indexOf("NONE"),
                "NONE 仍然是最后一个: 分派就是按这个顺序问的");

        String hold = bodyOf(gesture, "public record Hold(");
        assertTrue(hold.contains("boolean tuner"), "手里那一件是调频器这件事, 只能由 Hold 带着走");
        assertTrue(hold.contains("this(wrench, rod, component, empty, false)"),
                "四参数的构造器必须留着: 里里外外几十处 new Hold(...) 说的是同一件事 (手上没有调频器)");
        assertTrue(bodyOf(gesture, "public static Hold of(@Nullable ItemStack stack) {")
                        .contains("stack.getItem() instanceof FrequencyTunerItem"),
                "认的是那个物品类, 与 InnerRedstoneInteraction 问的同一句");

        String rule = bodyOf(gesture, "public static Kind of(Hold hand, Hold other, boolean sneaking) {");
        int tuner = rule.indexOf("return Kind.TUNER");
        assertTrue(tuner > 0, "规则本身要答得出这一个 kind");
        assertTrue(rule.indexOf("other.wrench() || other.rod()") < tuner,
                "另一只手上的扳手仍然独占这一整下: 调频器只对自己的那一手负责");
        assertTrue(tuner < rule.indexOf("if (hand.component())"),
                "调频器排在元件之前: 它决定的是「这一下对谁说」, 而不是「往哪儿放」");
        assertTrue(bodyOf(gesture, "public static String toolLabel(Kind kind) {")
                        .contains("case TUNER -> \"frequency tuner\""),
                "动作行上的 tool= 要说得出这一个手势");
    }

    // ------------------------------------------------------- Create 入口 --

    @Test
    @DisplayName("contraption 入口先分派调频器, 再走「不是火把就交回 Create」那道闸")
    void theTunerBranchComesBeforeTheNotATorchGate() throws IOException {
        String interaction = main("carrier", "CarrierInteraction.java");
        String entry = bodyOf(interaction, "public static boolean handleContraptionClick(ServerPlayer player, "
                + "UUID entityUuid,");
        int branch = entry.indexOf("if (gesture == CarrierGesture.Kind.TUNER) {");
        int gate = entry.indexOf("if (gesture == CarrierGesture.Kind.NONE) {");
        assertTrue(branch > 0, "contraption 入口必须有调频器分支, 否则那一下被当成 NONE 扔掉");
        assertTrue(branch < gate, "而且排在 NONE 那道闸之前: 闸之后 return false 的就是调频器");
        assertTrue(entry.contains("return handleTunerClick(level, player, hand, entityUuid, localPos);"),
                "分派给调频器自己的那一段");

        String tuner = bodyOf(interaction, "private static boolean handleTunerClick(ServerLevel level, "
                + "ServerPlayer player,");
        int notATorch = tuner.indexOf("if (target == null) {");
        int refusal = tuner.indexOf("return false;");
        int writes = tuner.indexOf("SyncGroups.joinCarried(");
        assertTrue(notATorch > 0 && refusal > notATorch,
                "目标不是同步火把就返回 false: Create 拿回这一下, 而「什么都没写」是这一条的答案");
        assertTrue(writes > refusal, "而且那道闸在一次写入之前: 不是火把时一个号码都不许动");
        assertTrue(tuner.contains("SyncGroups.leaveCarried("), "解绑走同样的搬运位入口");
        assertTrue(tuner.contains("SyncGroups.newChannel(") && tuner.contains("FrequencyTunerItem.setChannel("),
                "新号码由世界那一层发, 写进物品的也是物品自己的那一份");
        assertTrue(tuner.contains("SyncGroups.tick(level)"),
                "改完这一 tick 的答案要跟上: 组 pass 是幂等的");
    }

    @Test
    @DisplayName("判定表只有一份: 六动作由 TunerRule.decide 决定, 这里只把答案写进那一格")
    void theSixActionTableIsNotReimplemented() throws IOException {
        String tuner = bodyOf(main("carrier", "CarrierInteraction.java"),
                "private static boolean handleTunerClick(ServerLevel level, ServerPlayer player,");
        assertTrue(tuner.contains("TunerRule.decide(handHasChannel, foreign, target.hasChannel(),"),
                "四件事实问那张表, 与 FrequencyTunerItem#use 问的是同一张");
        assertFalse(tuner.contains("player.isSecondaryUseActive() ?"),
                "潜行那一半是表的一半, 不许在调用方再判一次");
        assertTrue(tuner.contains("FrequencyTunerItem.channelOf(stack)"),
                "手里的号码读的是物品自己那一份, 不是第二份");
        assertTrue(tuner.contains("CarrierStore.isActive()"),
                "没有物理后端时这一步一个字节都不动");
        assertTrue(tuner.contains("SyncGroups.carriedTarget(level, carrier, localPos)"),
                "目标按搬运位问: 立着的那一支没有槽位, 走宿主解析会答「这里什么都没有」");
        assertEquals(2, count(main("carrier", "CarrierInteraction.java"), "handleTunerClick"),
                "只有 contraption 入口分派它 (定义 + 一处调用): Sable 那一侧走的是世界路径, 两边不能同时动手");
    }

    @Test
    @DisplayName("Sable 入口对调频器让路: 那边方块就是 level 的方块, 世界路径已经把它办了")
    void theSubLevelEntryLeavesTheTunerAlone() throws IOException {
        String block = bodyOf(main("carrier", "CarrierInteraction.java"),
                "public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {");
        int tuner = block.indexOf("if (gesture == CarrierGesture.Kind.TUNER) {");
        int resolves = block.indexOf("BlockPos pos = event.getPos();");
        assertTrue(tuner > 0 && tuner < resolves,
                "在解析宿主之前就交回: 两边都动手会把同一支绑两次");
    }

    // ------------------------------------------------------- 立式认领 --

    @Test
    @DisplayName("startMoving 先问「这一格是不是同步火把」, 而且排在「里面没有元件」那句早退之前")
    void theStandingTorchIsClaimedBeforeTheNoComponentReturn() throws IOException {
        String hook = compat("MovementHostHook.java");
        String start = bodyOf(hook, "private static void onStartMoving(MovementContext context) {");
        int torch = start.indexOf("if (isSyncTorch(context)) {");
        int empty = start.indexOf("if (!hasComponent) {");
        assertTrue(torch > 0, "没有这一条, 立着的那一支根本没有认领者");
        assertTrue(torch < empty, "必须在那句早退之前: 同步火把没有元件, 那一句正是把它挡在门外的那一句");
        assertTrue(start.contains("claimSyncTorch(level, context, entity, worldPos);"), "认领交给它自己的那一段");

        String claim = bodyOf(hook, "private static void claimSyncTorch(@Nullable ServerLevel level, "
                + "MovementContext context,");
        int defer = claim.indexOf("if (defersToSable(\"startMoving\", context, entity)) {");
        int capture = claim.indexOf("CarriedSyncTorches.capture(");
        assertTrue(defer > 0 && defer < capture,
                "Sable 子层级的交叉守卫在认领之前: 一个物理装配体既是子层级又是 contraption, 认领两次就是老毛病");
        assertTrue(claim.indexOf("CarriedSyncTorches.take(level, worldPos)") < capture,
                "先说方块钩子寄存的那一份 (它离开世界时交出来的号码与掩码)");
        assertTrue(claim.contains("store.hasSyncTorch(worldPos)"), "再去问世界 store 的那一行");
        assertTrue(claim.indexOf("store.removeSyncTorch(worldPos)") > capture,
                "号码交出去之后, 世界那一行才收: 反了号码就没了");
        assertFalse(claim.contains("CarrierStore.startMoving"),
                "同步火把不是宿主: 它没有槽位, 进搬运索引就不是它了");
        assertFalse(claim.contains("putSyncTorchInputs(local"),
                "contraption 的局部坐标是相对锚点的, 在世界里不是一格: 往那儿写就是往别人家写");
    }

    @Test
    @DisplayName("落地: 那一行写回位姿算出的那一格, 而不是留给一张没人收的表")
    void thePutDownHandsTheChannelBack() throws IOException {
        String hook = compat("MovementHostHook.java");
        String stop = bodyOf(hook, "private static void onStopMoving(MovementContext context) {");
        int torch = stop.indexOf("if (isSyncTorch(context)) {");
        assertTrue(torch > 0 && torch < stop.indexOf("CarrierStore.stopMovingOrDefer("),
                "调频器那一格没有宿主可释放: 在这里交回, 而且要赶在普通的逐宿主释放之前");
        String handBack = bodyOf(hook, "private static void handBackSyncTorch(ServerLevel level, "
                + "MovementContext context,");
        assertTrue(handBack.contains("CarriedSyncTorches.handBack("),
                "写回只有一个实现, 就是搬运层落地那一句 (Sable 那边也是它)");
        assertTrue(handBack.contains("CarrierStore.landingFor(pose)"),
                "落点由位姿算: 被搬了两格的机器不该把火把送回它被拆下来的地方");

        String table = main("carrier", "CarriedSyncTorches.java");
        assertTrue(table.contains("public static int rekey("),
                "provisional → 实体 UUID 的改名也要落到这张表上, 否则落地时按实体名一条都找不到");
        String tick = bodyOf(hook, "private static void onTick(MovementContext context) {");
        assertTrue(tick.indexOf("CarriedSyncTorches.rekey(") > tick.indexOf("CarrierStore.reconcile("),
                "tick 那条改名之后紧跟同一句改名");
        assertTrue(stop.indexOf("CarriedSyncTorches.rekey(") > stop.indexOf("CarrierStore.reconcile("),
                "落地那条也是");

        assertTrue(compat("SableCarrierClaim.java").contains("CarriedSyncTorches.capture(space.carrier()"),
                "Sable 那一侧的这一句不许被动过: 它是这套算术的参照");
        assertTrue(compat("SableProductDriver.java").contains("SyncTorchBlock.refreshCarried(level, cell, state)"),
                "搬运中的亮灭由驱动那一趟写进机器里那一格, 而那一趟只有 Sable 有 - 见报告里「只能上机验」的那一段");
    }

    // --------------------------------------------------------- 类型注册 --

    @Test
    @DisplayName("方块类型自己就是器件: 注册在 install 一侧, 不在 hostBlockStored 那一侧")
    void theTypeIsRegisteredAsItsOwnDevice() throws IOException {
        String backend = compat("PhysicsCompatBackend.java");
        String install = bodyOf(backend, "public static void installProductCarrier() {");
        assertTrue(install.contains("if (PhysicsCompat.createLoaded()) {"), "没有 Create 时整段不成立");
        assertTrue(install.contains("installOwnDeviceTypes();"), "而它在 Create 在场时被叫到");
        String devices = bodyOf(backend, "private static void installOwnDeviceTypes() {");
        assertTrue(devices.contains("RCRegistry.SYNC_TORCH.get()"), "注册的就是同步火把这一个类型");
        assertTrue(devices.contains("MovementHostHook.register(torch)"),
                "MovementBehaviour: 搬运时认领得起来");
        assertTrue(devices.contains("CarrierToolHook.register(torch)"),
                "MovingInteractionBehaviour: 点击才走得到搬运层 (调频器那一手全靠它)");

        String stored = bodyOf(backend, "public static void hostBlockStored(ServerLevel level, BlockPos pos, "
                + "Block block) {");
        assertFalse(stored.contains("SYNC_TORCH") || stored.contains("installOwnDeviceTypes"),
                "不改 hostBlockStored: 它的语义是「一个宿主类型第一次装进元件」, 而同步火把永远是元件、不是宿主, "
                        + "那个回调对它一次都不会发生");
    }

    @Test
    @DisplayName("三处新路径在没有 Create / 没有 Sable 的世界里一步都走不到")
    void nothingIsReachedWithoutTheOptionalMods() throws IOException {
        // 1. The registration is behind the mod-list gate, and the gate itself is on this side of the source
        //    sets: PhysicsCompat (main) names the backend only as a string, so no Create type is ever resolved
        //    by a jar that does not have Create.
        String compatGate = main("compat", "physics/PhysicsCompat.java");
        assertTrue(bodyOf(compatGate, "public static void installProductCarrier() {").contains("!createLoaded()"),
                "注册入口在 Create 缺席时直接返回");
        assertFalse(compatGate.contains("import com.simibubi"),
                "main 源集里一个 Create 类型都不许出现: 它们全在 src/compat, 那半个源集在没有嵌套 jar 时根本不编译");
        assertTrue(compatGate.contains("\"com.jiangyuefengyu.redstonecircuit.compat.physics."
                        + "PhysicsCompatBackend\""),
                "那半个源集是按名字被摸到的: 没有 Create 时它连加载都不会发生");

        // 2. Both hooks live in src/compat (that is what compat(...) reads), and the movement hook asks
        //    CarrierStore.isActive() before it reads anything, so with no backend the callbacks return at the
        //    first static boolean.
        String hook = compat("MovementHostHook.java");
        assertTrue(bodyOf(hook, "private static void onStartMoving(MovementContext context) {")
                        .contains("if (!CarrierStore.isActive()) {"),
                "没有物理后端时认领那一段在第一个静态布尔处就结束");
        assertTrue(bodyOf(hook, "private static void onStopMoving(MovementContext context) {")
                        .contains("if (!CarrierStore.isActive()) {"),
                "落地那一段同样");

        // 3. The tuner's contraption path is reached from the packet only, and that packet is Create's.
        String interaction = main("carrier", "CarrierInteraction.java");
        assertEquals(2, count(interaction, "handleTunerClick"),
                "只有 contraption 入口叫得到它; 而 contraption 入口的调用方 (CarrierToolHook) 在 src/compat");
        assertTrue(bodyOf(interaction, "private static boolean handleTunerClick(ServerLevel level, "
                + "ServerPlayer player,").contains("CarrierStore.isActive()"),
                "而且它自己也在 isActive() 之后");
    }

    // ------------------------------------------------------------- helpers --

    private static int count(String source, String needle) {
        int total = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            total++;
        }
        return total;
    }

    private static String main(String area, String name) throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", area, name);
    }

    private static String compat(String name) throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                name);
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
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
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
}
