package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.nbt.CompoundTag;

/**
 * 红石触发器's rules: the three sentences, the edge memory they need, and the wiring that only shows up in game.
 *
 * <p>三句话就是全部语义: 一条上升沿切换一次; 保持通电期间不再切换; 断电那一下不切换 (之后再来一条上升沿才切换).
 * {@link TriggerRule} 是它们的纯函数形式, 世界形态与内嵌形态调的都是它 - so what is proved here holds for both forms at
 * once, and {@code TriggerGameTest} only has to prove that each form actually calls in.
 *
 * <p>接线那一半 ({@link Wiring}) 读源码: 32 个方块状态的口径、{@code Slot#triggerInput} 的存档、求解器的接入点、以及
 * "物品两种手势"那两张表. It was going to be its own file ({@code TriggerWiringTest}); it lives here because this task's
 * budget is a dozen files and the two halves assert the same device.
 */
class TriggerRuleTest {

    /** 走一步并回报"新的开关", 好让一串读数写成一行. */
    private static boolean step(Judge judge, boolean input) {
        TriggerRule.Step next = TriggerRule.step(judge.lastInput, input, judge.powered);
        judge.lastInput = next.input();
        judge.powered = next.powered();
        return judge.powered;
    }

    /** 两个形态各自持有的那两个值: 上一次读到的输入, 和开关. */
    private static final class Judge {
        boolean lastInput;
        boolean powered;
    }

    @Test
    @DisplayName("一条上升沿切换一次：没电 → 有电 的那一次翻，答案同时是 15")
    void aRisingEdgeFlipsTheSwitch() {
        TriggerRule.Step on = TriggerRule.step(false, true, false);
        assertTrue(on.powered(), "上升沿把开关翻到开");
        assertTrue(on.input(), "而这一次的读数要记下来 - 下一次判上升沿全靠它");
        assertEquals(TriggerRule.ON, TriggerRule.output(on.powered()), "开着就是 15");
        assertEquals(15, TriggerRule.ON, "满强度，与红石火把、拉杆同一个量级");

        // And the same edge the other way: an open trigger is closed by the next rising edge.
        TriggerRule.Step off = TriggerRule.step(false, true, true);
        assertFalse(off.powered(), "第二条上升沿把它关掉");
        assertEquals(TriggerRule.OFF, TriggerRule.output(off.powered()), "关着就是 0");
        assertEquals(0, TriggerRule.OFF, "不是别的小数字");
    }

    @Test
    @DisplayName("保持通电不切换；断电那一下也不切换（但读数要跟着变）")
    void aHoldKeepsItAndAFallDoesNotFlip() {
        TriggerRule.Step held = TriggerRule.step(true, true, true);
        assertTrue(held.powered(), "一直通着电：开关一次都不该动 - 这一条就是边沿记忆存在的理由");
        assertTrue(held.input(), "读数还是 1，所以下一次比较仍然是「保持」");

        TriggerRule.Step heldOff = TriggerRule.step(true, true, false);
        assertFalse(heldOff.powered(), "关着而输入一直通着，也不会被翻成开");

        TriggerRule.Step fell = TriggerRule.step(true, false, true);
        assertTrue(fell.powered(), "断电那一下不切换：开着的还是开着");
        assertFalse(fell.input(), "但读数必须记成 0，否则下一条上升沿判不出来");

        TriggerRule.Step rose = TriggerRule.step(false, false, false);
        assertFalse(rose.powered(), "没电的两趟之间什么都不发生");
        assertFalse(rose.input(), "读数还是 0");
    }

    @Test
    @DisplayName("关 → 开 → 关 → 开：一串读数走完，答案只在上升沿处变")
    void theOffOnOffOnSequence() {
        Judge judge = new Judge();
        // 0 没电, 1 有电 - 一段真实的波形: 静默、上升、保持、下降、静默、上升、保持、下降、静默、上升.
        boolean[] readings = { false, true, true, false, false, true, true, false, false, true };
        int[] expected = { TriggerRule.OFF, TriggerRule.ON, TriggerRule.ON, TriggerRule.ON, TriggerRule.ON,
                TriggerRule.OFF, TriggerRule.OFF, TriggerRule.OFF, TriggerRule.OFF, TriggerRule.ON };
        List<Integer> answers = new ArrayList<>();
        for (boolean reading : readings) {
            boolean powered = step(judge, reading);
            answers.add(TriggerRule.output(powered));
        }
        assertEquals(List.of(expected[0], expected[1], expected[2], expected[3], expected[4], expected[5],
                        expected[6], expected[7], expected[8], expected[9]), answers,
                "三条上升沿 = 三次切换（关→开→关→开），四次下降沿一次都不算");
        assertTrue(judge.powered, "最后停在开");
        assertTrue(judge.lastInput, "而读数停在 1：下一趟保持通电不会翻它");
    }

    @Test
    @DisplayName("答案只有两个数：开 15、关 0，没有强度可调")
    void theAnswerIsFifteenOrZero() {
        assertEquals(15, TriggerRule.output(true), "开着 15");
        assertEquals(0, TriggerRule.output(false), "关着 0");
        assertTrue(TriggerRule.live(1) && TriggerRule.live(15), "任何非零读数都算有电 - 这一件不认强度");
        assertFalse(TriggerRule.live(0), "0 就是没电");
        assertTrue(TriggerRule.ON == PowerSolver.MAX_POWER,
                "常量与求解器满强度是同一个数：世界形态与内嵌形态答的是同一句话");
    }

    @Test
    @DisplayName("同一个读数求解两次不会有第二个边沿：这就是同一 tick 里被求解多次也不会连翻两下的全部原因")
    void theSameReadingSolvedTwiceChangesNothing() {
        TriggerRule.Step first = TriggerRule.step(false, true, false);
        TriggerRule.Step second = TriggerRule.step(first.input(), true, first.powered());
        assertEquals(first, second, "第二趟拿第一趟记下的读数当「上次」，于是它看到的不是上升沿");
        assertEquals(TriggerRule.ON, TriggerRule.output(second.powered()), "开关还是一次");

        // The same in the other direction, and for a hold that never ended.
        TriggerRule.Step third = TriggerRule.step(second.input(), true, second.powered());
        assertEquals(second, third, "第三趟也一样：规则本身幂等，不需要「这一 tick 已经翻过」那种额外的状态");
    }

    @Test
    @DisplayName("Slot#triggerInput 真的进存档，也真的从存档里回来")
    void theEdgeMemorySurvivesSaveAndLoad() {
        Slot slot = new Slot(ComponentType.TRIGGER);
        slot.facing = net.minecraft.core.Direction.UP;
        slot.powered = true;
        slot.power = TriggerRule.ON;
        slot.triggerInput = true;

        CompoundTag tag = new CompoundTag();
        slot.save(tag);
        assertTrue(tag.getBoolean("triggerInput"), "存档里要有一个自己的键，不能借 powered");

        Slot loaded = Slot.load(tag);
        assertEquals(ComponentType.TRIGGER, loaded.type, "类型跟着走");
        assertEquals(net.minecraft.core.Direction.UP, loaded.facing, "连输入面是「上」也要跟着走");
        assertTrue(loaded.powered, "开关跟着走");
        assertTrue(loaded.triggerInput, "边沿记忆跟着走 - 少了它，重进世界会把一次保持当成一次上升沿");
        assertEquals(TriggerRule.ON, loaded.power, "输出强度跟着走");

        // A save written before this device existed has no such key at all, and reads back as "never seen it powered".
        CompoundTag old = new CompoundTag();
        old.putString("type", ComponentType.TRIGGER.name());
        old.putBoolean("powered", true);
        assertFalse(Slot.load(old).triggerInput, "老存档没有这个键 = 上一次读到没电，不需要数据版本");
    }

    @Test
    @DisplayName("读档出来的元件再求解一次：输入仍是 0 时开关一动不动")
    void aLoadedTriggerDoesNotFlipOnItsFirstSolve() {
        Slot slot = new Slot(ComponentType.TRIGGER);
        slot.facing = net.minecraft.core.Direction.NORTH;
        slot.powered = true;
        slot.power = TriggerRule.ON;
        slot.triggerInput = true;
        CompoundTag tag = new CompoundTag();
        slot.save(tag);
        Slot loaded = Slot.load(tag);

        // The first solve after the load: the face is not powered any more (a wire that was removed while the world was
        // unloaded), so the reading fell - and a fall never flips.
        TriggerRule.Step first = TriggerRule.step(loaded.triggerInput, false, loaded.powered);
        assertTrue(first.powered(), "读档后的第一趟不能翻转开关：输入掉了只是掉了，不是上升沿");
        assertFalse(first.input(), "读数要落到 0");
        loaded.powered = first.powered();
        loaded.triggerInput = first.input();

        TriggerRule.Step again = TriggerRule.step(loaded.triggerInput, false, loaded.powered);
        assertTrue(again.powered(), "而下一趟同样不会翻：0 一直是 0");
        assertEquals(first, again, "两趟结果一模一样");

        // Only a real rising edge closes it, which is the point of keeping the memory across a load.
        TriggerRule.Step risen = TriggerRule.step(loaded.triggerInput, true, loaded.powered);
        assertFalse(risen.powered(), "真的来了一条上升沿，它才关");
        assertEquals(TriggerRule.OFF, TriggerRule.output(risen.powered()), "关掉就是 0");
    }

    // --------------------------------------------------------------- 接线 --

    /**
     * The half a green unit suite cannot see: what the sources say about the two forms, read straight out of them.
     */
    @Nested
    class Wiring {

        @Test
        @DisplayName("方块状态：face × facing × powered × input = 32，属性名就是这四个")
        void theBlockStateIsThirtyTwoStates() throws IOException {
            String block = read("block", "TriggerBlock.java");
            assertTrue(block.contains("public static final EnumProperty<AttachFace> FACE =")
                            && block.contains("EnumProperty.create(\"face\", AttachFace.class, AttachFace.FLOOR, AttachFace.CEILING)"),
                    "贴法用的是本件自己的两值属性：键名仍是 face（与拉杆同名，资源那边按熟悉的名字写），取值恰好是 floor 与 ceiling");
            assertTrue(!codeOf(block).contains("ATTACH_FACE"),
                    "所以代码行里不能出现原版那个三值属性（floor / wall / ceiling）：直接用会给状态空间多出 16 个到不了、"
                            + "也没有模型的 wall 状态，32 会变成 48 —— 注释行先被剔掉了，因为这段 javadoc 正是为了说明"
                            + "「为什么不用它」才写着它");
            assertTrue(block.contains("public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;"),
                    "朝向 4 个水平方向：正面 = 读取面");
            assertTrue(block.contains("public static final BooleanProperty POWERED = BlockStateProperties.POWERED;"),
                    "开关就是答案");
            assertTrue(block.contains("public static final BooleanProperty INPUT = BooleanProperty.create(\"input\");"),
                    "而 input 是边沿记忆 - 它不在原版属性表里，所以是这一件自己的一个键");
            assertTrue(block.contains("builder.add(FACE, FACING, POWERED, INPUT);"),
                    "四个属性一起进状态定义：2 × 4 × 2 × 2 = 32 个状态，资源那边就按这个写");

            String faceFor = bodyOf(block, "public static AttachFace faceFor(Direction clickedFace)");
            assertTrue(faceFor.contains("if (clickedFace.getAxis().isHorizontal()) {") && faceFor.contains("return null;"),
                    "四个水平面 = 贴墙 → 那一条分支直接不给出状态；而真正那条放置路径由 GameTest aWallClickPlacesNothing"
                            + " 用一个真 BlockPlaceContext 验（源码断言只钉分支存在且返回 null，不为了过测试去改产品代码的形状）");
            assertTrue(block.contains(".setValue(FACING, context.getHorizontalDirection().getOpposite())"),
                    "朝向按「正面朝向放下它的玩家」那一条");
            assertTrue(!block.contains("enum Shape") && !block.contains("BlockEntity"),
                    "边沿记忆活在方块状态里，不另开 BlockEntity，也不另建一张世界表");
        }

        @Test
        @DisplayName("世界形态：邻居通知 → 同一个规则 → 只写动过的那一半；答案只在背面，右键什么都不做")
        void theBlockStepsTheRuleAndAnswersOnTheBackOnly() throws IOException {
            String block = read("block", "TriggerBlock.java");
            assertTrue(block.contains("protected void neighborChanged(") && block.contains("evaluate(serverLevel, pos);"),
                    "输入只可能因为邻居而变，所以一条邻居通知就是一次边沿的来源");
            assertTrue(block.contains("protected void onPlace(") && block.indexOf("evaluate(serverLevel, pos);")
                            < block.indexOf("public static void evaluate("),
                    "放下那一刻也走一步：放在一条已经通着电的线旁边会立刻打开，和内嵌形态第一趟完全一样");
            String evaluate = bodyOf(block, "public static void evaluate(ServerLevel level, BlockPos pos)");
            assertTrue(evaluate.contains("TriggerRule.step(state.getValue(INPUT), input, powered)"),
                    "世界形态调的是那个纯函数，不是自己一套");
            assertTrue(evaluate.contains("setValue(POWERED, step.powered()).setValue(INPUT, step.input())"),
                    "两格一起写回：只写开关的话下一趟还会把同一次通电当成上升沿");
            assertTrue(evaluate.contains("if (step.powered() != powered)") && evaluate.contains("level.updateNeighborsAt(pos, state.getBlock());"),
                    "只有答案动了才叫醒邻居；读数自己变了没有谁要听");

            assertTrue(block.contains("if (side.getOpposite() != outputFace(state)) {")
                            && block.contains("return state.getValue(POWERED) ? TriggerRule.ON : TriggerRule.OFF;"),
                    "输出只在背面：与控制器/变压器/时钟源/延长器逐字相同的那一个判断");
            assertTrue(block.contains("readingAt(BlockGetter level, BlockState state, BlockPos pos)")
                            && block.contains("InnerRedstoneNetwork.readSignal(level, pos.relative(face), face)"),
                    "输入只读正面那一面，而且用的是求解器自己那个读取实现（两个形态读同一个数）");
            assertTrue(!block.contains("InteractionResult") && !block.contains("useWithoutItem"),
                    "它没有右键动作：不继承 LeverBlock#use，开关只听红石的，右键留给放置和宿主方块那套手势");
            assertTrue(!block.contains("extends LeverBlock") && !block.contains("extends FaceAttachedHorizontalDirectionalBlock"),
                    "也不继承拉杆：那会白白继承一个可以徒手拨的开关");
        }

        @Test
        @DisplayName("求解器与 tick：一个面读、对面写；每 tick 走一趟，位置来自 store；物品两种手势两张表都在")
        void theSolverRoutesItAndTheLevelStepsItOncePerTick() throws IOException {
            String solver = read("logic", "PowerSolver.java");
            String emits = bodyOf(solver,
                    "public static boolean emitsToward(ComponentType type, Direction facing, Direction direction,");
            assertTrue(emits.contains("case TRIGGER:") && emits.contains("return direction == facing.getOpposite();"),
                    "只往背面输出，与控制器/变压器/时钟源/延长器同一档");
            String reads = bodyOf(solver,
                    "public static boolean readsToward(ComponentType type, Direction facing, Direction direction,");
            assertTrue(reads.contains("case TRIGGER:") && reads.contains("return direction == facing;"),
                    "只读正面，于是扳手锁一条线也拆不掉它的输入");
            String desired = bodyOf(solver, "public static int desiredOutput(PowerEnvironment env, BlockPos pos, PowerEnvironment.Node self)");
            assertTrue(desired.contains("case TRIGGER:") && desired.contains("return clamp(self.power());"),
                    "求解器推不出它：它答的是边沿而不是此刻的输入，重新推一次就等于每来一条通知翻一下");

            String type = read("data", "ComponentType.java");
            assertTrue(type.contains("TRIGGER") && type.contains("|| this == TRIGGER"),
                    "hasInputSide 要认它（输入面是它自己的一面），而 isDriven 不认（没有延迟、不跟随输入）");
            assertFalse(ComponentType.TRIGGER.isDriven(), "运行时也确认一遍：它不是被驱动的那一类");
            assertTrue(ComponentType.TRIGGER.hasInputSide(), "但它有一个属于自己的输入面");

            String network = read("logic", "InnerRedstoneNetwork.java");
            String tick = bodyOf(network, "public static void tick(ServerLevel level)");
            assertTrue(tick.contains("tickTriggers(level, store);")
                            && tick.indexOf("tickTriggers(level, store);") < tick.indexOf("applyDueFlips(level, store);"),
                    "方块内那一个由世界这一趟在 settle 之前走：边沿才落在同一 tick 的传播里");
            String pass = bodyOf(network, "private static void tickTriggers(ServerLevel level, InnerRedstoneStore store)");
            assertTrue(pass.contains("slot.type != ComponentType.TRIGGER") && pass.contains("store.positionKeys()"),
                    "每 tick 走的就是元件表本身，不建第二个索引");
            assertTrue(pass.contains("TriggerRule.step(slot.triggerInput, input, slot.powered)"),
                    "内嵌形态调的也是那个纯函数 - 两条路不可能不一致");
            assertTrue(pass.contains("slot.triggerInput = step.input();") && pass.contains("store.markDirty();"),
                    "读数也要落盘：它是存档字段，丢了重进世界就会翻转");
            assertTrue(pass.contains("notifyOutputChanged(level, BlockPos.of(key));")
                            && pass.contains("HostSync.broadcast(level, BlockPos.of(key), slot);"),
                    "开关动了三步：叫醒世界、让传播跟上、把客户端那份副本也更新");

            String slot = read("data", "Slot.java");
            assertTrue(slot.contains("tag.putBoolean(\"triggerInput\", triggerInput);")
                            && slot.contains("slot.triggerInput = tag.getBoolean(\"triggerInput\");")
                            && slot.contains("copy.triggerInput = this.triggerInput;"),
                    "Slot#triggerInput 三处都要有：存档、读档、复制（广播与捕获都走 copy）");

            String rules = read("HostRules.java");
            assertTrue(rules.contains("if (item == RCRegistry.TRIGGER_ITEM)")
                            && rules.contains("case TRIGGER -> new ItemStack(RCRegistry.TRIGGER_ITEM);"),
                    "物品 → 元件、元件 → 物品，两个方向都要有 - 这就是「右键地面放方块、潜行右键塞进方块」那套手势");
            String registry = read("RCRegistry.java");
            assertTrue(registry.contains("BuiltInRegistries.BLOCK, RedstoneCircuit.id(\"trigger\"),")
                            && registry.contains("new BlockItem(TRIGGER, new Item.Properties())")
                            && registry.contains("output.accept(TRIGGER_ITEM);"),
                    "方块、物品、创造模式物品栏三处都要注册，id 就是 redstonecircuit:trigger");
            assertTrue(read("carrier", "CarrierMessages.java").contains("case TRIGGER -> \"block.redstonecircuit.trigger\";"),
                    "消息里要能说出它的名字（枚举多了常量，那两个 switch 表达式必须补齐才能编译）");
            assertTrue(read("client", "InnerComponentLine.java").contains("case TRIGGER -> StateKind.TORCH;"),
                    "方块内那行字也认它：亮/灭两态就是它全部的可见状态");
        }

        // ------------------------------------------------------------ helpers --

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

        /**
         * The source with its <b>comment lines</b> taken out, so an assertion about code can be neither satisfied nor
         * defeated by a sentence in the javadoc. {@code TriggerBlock} names {@code ATTACH_FACE} in its own comment to
         * say why it is not used, and a plain {@code contains} reads that sentence as the very thing it warns against.
         */
        private static String codeOf(String source) {
            StringBuilder code = new StringBuilder();
            for (String line : source.split("\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("//")) {
                    continue;
                }
                code.append(line).append('\n');
            }
            return code.toString();
        }

        /** Finds a source file under {@code src/main/java/.../redstonecircuit}, failing loudly rather than skipping. */
        private static String read(String... parts) throws IOException {
            Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit")
                    .resolve(Path.of("", parts));
            Path fromParent = Path.of("neoforge").resolve(relative);
            for (Path candidate : List.of(relative, fromParent)) {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate, StandardCharsets.UTF_8);
                }
            }
            throw new IOException("could not find " + relative + " from " + Path.of("").toAbsolutePath());
        }
    }
}
