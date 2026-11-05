package com.jiangyuefengyu.redstonecircuit.block;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜂蜜活塞's wiring: what a game test cannot state about the code itself.
 *
 * <p>Three conventions are asserted here because nothing in the world can show them being broken without looking like a
 * piston bug: the drag decision lives in the <b>block state</b> (the two retractions are indistinguishable in the world,
 * so a decision read from the world would silently never fire), the block is built <b>non-sticky</b> (a sticky one drags
 * on every retraction and there is no per-retraction choice left), and each of the four injections is aimed at one named
 * instruction - the two {@code Blocks.PISTON_HEAD} reads of {@code moveBlocks} that a head passes through, the second
 * {@code isSticky} read of {@code triggerEvent}, and the head of {@code isPushable}.
 */
class HoneyPistonWiringTest {

    private static String source(String... parts) throws IOException {
        return Files.readString(Path.of("src", parts), StandardCharsets.UTF_8);
    }

    private static String block() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", "HoneyPistonBlock.java");
    }

    private static String head() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "HoneyPistonHeadBlock.java");
    }

    private static String mixin() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin", "HoneyPistonMixin.java");
    }

    private static String registry() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "RCRegistry.java");
    }

    @Test
    @DisplayName("拉不拉记在方块状态里，而且只在伸出那一刻记：断电时世界已经一模一样")
    void theDragIsRecordedInTheBlockStateWhenThePistonExtends() throws IOException {
        String block = block();

        assertTrue(block.contains("public static final BooleanProperty GRABBING = BooleanProperty.create(\"grabbing\");"),
                "一个方块状态属性, 不是世界里的某一格");
        assertTrue(block.contains("builder.add(GRABBING);"),
                "和 FACING/EXTENDED 一起注册, 否则它不会出现在状态里, 也就写不上世界");
        assertTrue(block.contains("if (id == TRIGGER_EXTEND) {"), "只在伸出那一刻算");
        assertTrue(block.contains("state.setValue(GRABBING, level.getBlockState(pos.relative(state.getValue(FACING))).isAir())"),
                "而算的依据是正前方那一格 - 断电时它已经被杆头或方块占住了");
        assertTrue(block.contains("return super.triggerEvent(deciding, level, pos, id, param);"),
                "并且把带标记的状态交给原版: 原版正是用它来 setValue(EXTENDED) 和写世界的");
    }

    @Test
    @DisplayName("杆头认蜂蜜底座：三个父类方法各自重写，取回来的物品是蜂蜜活塞")
    void theHeadAnswersForAHoneyBase() throws IOException {
        String head = head();

        assertTrue(head.contains("base.is(RCRegistry.HONEY_PISTON.get())"),
                "父类那个私有 isFittingBase 只认 Blocks.PISTON/STICKY_PISTON, 蜂蜜底座必须在别处被认出来");
        assertTrue(head.contains("protected boolean canSurvive("),
                "不然杆头贴上去的第一次邻居更新就被判为孤块");
        assertTrue(head.contains("protected void onRemove("), "底座没了杆头要跟着没");
        assertTrue(head.contains("public BlockState playerWillDestroy("), "创造模式敲掉杆头要连底座一起");
        assertTrue(head.contains("return new ItemStack(RCRegistry.HONEY_PISTON_ITEM.get());"),
                "取方块取回的是蜂蜜活塞本身");
        assertFalse(head.contains("BooleanProperty"), "不新增属性: 父类 getShape 只读 FACING 和 SHORT");
    }

    @Test
    @DisplayName("四处注入各自瞄着一条具名指令：0/1 两次读 PISTON_HEAD、第二次读 isSticky、isPushable 的 HEAD")
    void everyInjectionIsAimedAtOneNamedInstruction() throws IOException {
        String mixin = mixin();

        assertTrue(mixin.contains("target = \"Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:Lnet/minecraft/world/level/block/Block;\",")
                        && mixin.contains("ordinal = 0") && mixin.contains("ordinal = 1"),
                "moveBlocks 里 PISTON_HEAD 被读三次: 第一次是收杆前认杆头, 第二次是造杆头, 各取一次");
        assertTrue(mixin.contains("target = \"Lnet/minecraft/world/level/block/piston/PistonBaseBlock;isSticky:Z\","),
                "isSticky 是 private final, 只有这次读决定收杆拉不拉");
        assertTrue(mixin.contains("method = \"isPushable\", at = @At(\"HEAD\"), cancellable = true"),
                "伸出/收起的可推性与原版活塞一致");
        assertTrue(mixin.contains("HoneyPistonBlock.drag(piston)"),
                "而'拉不拉'的答案从方块状态来, 不是从这个私有字段来");
        assertTrue(source("main", "resources", "redstonecircuit.mixins.json").contains("\"HoneyPistonMixin\""),
                "required=true 的配置里没有它, 四处注入就一行也不会跑");
    }

    @Test
    @DisplayName("底座按非粘性活塞构造，杆头没有物品形态")
    void theBaseIsBuiltAsANonStickyPiston() throws IOException {
        assertTrue(block().contains("super(false, properties);"),
                "粘性的那个值会让原版每一次收杆都走'拉'的那一支, 而这里要的是每次断电各自决定");
        assertFalse(registry().contains("ITEMS.register(\"honey_piston_head\""),
                "杆头是活塞自己的手臂, 原版杆头也不是物品");
        assertTrue(registry().contains("BLOCKS.register(\"honey_piston\"") &&
                        registry().contains("BLOCKS.register(\"honey_piston_head\""),
                "两个方块都注册");
        assertTrue(registry().contains("pushReaction(PushReaction.BLOCK)"),
                "推不动: 原版活塞和杆头的值, 逐项对齐");
    }
}
