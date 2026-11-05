package com.jiangyuefengyu.redstonecircuit.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 蜂蜜活塞's client hook, asserted as text because nothing here can run it: the game test server is a dedicated server,
 * so {@code PistonHeadRenderer} and its mixin are never even loaded in the tests that do run. What those tests therefore
 * cannot catch is exactly what could reach a player's machine and break the client, so it is pinned here:
 *
 * <ul>
 *   <li>the hook lives in its own config, optional and with {@code defaultRequire 0} - an injection that misses must
 *       cost vanilla's own texture and a log line, never a client that will not start;
 *   <li>it is declared to FML, or nothing loads it at all;
 *   <li>both substitutions are gated on the frame's own moving state, and both fall back to {@code Blocks.PISTON_HEAD},
 *       so 活塞 and 粘性活塞 are drawn exactly as before.
 * </ul>
 */
class PistonHeadRenderWiringTest {

    private static String source(String... parts) throws IOException {
        return Files.readString(Path.of("src", parts), StandardCharsets.UTF_8);
    }

    private static String mixin() throws IOException {
        return source("main", "java", "com", "jiangyuefengyu", "redstonecircuit", "mixin", "client",
                "PistonHeadRendererMixin.java");
    }

    private static String config() throws IOException {
        return source("main", "resources", "redstonecircuit.client.mixins.json");
    }

    @Test
    @DisplayName("钩子放在独立配置里：required=false + defaultRequire 0，且不在那份 required 配置里")
    void theClientHookCannotBrickTheClient() throws IOException {
        String config = config();

        assertTrue(config.contains("\"required\": false"), "配置本身不是必需的: 载不到也只是退回原版贴图");
        assertTrue(config.contains("\"defaultRequire\": 0"), "注入点没匹配上也只是打一行日志, 不是加载失败");
        assertTrue(config.contains("\"package\": \"com.jiangyuefengyu.redstonecircuit.mixin.client\""),
                "客户端那一半单独一个包");
        assertTrue(config.contains("\"client\": ["), "只挂客户端");
        assertTrue(config.contains("\"PistonHeadRendererMixin\""), "而挂上去的就是这一个");

        String required = source("main", "resources", "redstonecircuit.mixins.json");
        assertFalse(required.contains("PistonHeadRendererMixin"),
                "绝不能塞进那份 required: true 的配置 - GameTest 里根本不会加载它, 出错只会在玩家开机时炸");
        assertFalse(required.contains("mixin.client"), "同一件事, 从另一边钉住");
    }

    @Test
    @DisplayName("这份配置真的交给了 FML：toml 的 [[mixins]] 里有一行")
    void theConfigIsDeclaredToFml() throws IOException {
        String toml = source("main", "templates", "META-INF", "neoforge.mods.toml");

        assertTrue(toml.contains("config=\"${mod_id}.client.mixins.json\""),
                "和另外三份配置一个写法; 少了这一行, 上面那份 json 就是死文件");
        assertTrue(toml.contains("config=\"${mod_id}.mixins.json\""), "原来那几行一个字都没动");
    }

    @Test
    @DisplayName("两处替代都先认这一帧在画什么，并且都退回原版杆头")
    void bothSubstitutionsAskTheMovingStateFirst() throws IOException {
        String mixin = mixin();

        assertTrue(mixin.contains("moving.getMovedState()"),
                "帧开头记住的就是这块: 渲染器每一帧都从 carried 的方块状态重画");
        assertTrue(mixin.contains("private static final String VANILLA_HEAD =") && mixin.contains(
                "Lnet/minecraft/world/level/block/Blocks;PISTON_HEAD:Lnet/minecraft/world/level/block/Block;"),
                "两处要换的都是同一个字段读");
        assertTrue(mixin.contains("ordinal = 0") && mixin.contains("ordinal = 1"),
                "第 0 次是'这块是不是杆头'那个比较, 第 1 次是底座格子里造出来的那根杆");
        assertTrue(mixin.contains(": Blocks.PISTON_HEAD;"), "原版那条分支的返回值原样保留");
        assertTrue(mixin.contains("method = RENDER"), "用整条描述符点名, 免得撞上接口桥接方法那份 render");
    }

    @Test
    @DisplayName("原版活塞一行行为都不变：两处替换各自只认蜂蜜活塞/蜂蜜杆头")
    void vanillaPistonsAreDrawnExactlyAsBefore() throws IOException {
        String mixin = mixin();

        assertTrue(mixin.contains("moved.getBlock() instanceof HoneyPistonHeadBlock"),
                "杆头滑出那一段: 只有当 carried 的就是我们的杆头时才走变短动画");
        assertTrue(mixin.contains("moved.getBlock() instanceof HoneyPistonBlock"),
                "收杆时底座那一格: 只有底座是蜂蜜活塞时才换成我们的杆头");
        assertTrue(mixin.contains("BlockState moved = DRAWING.get();"), "两处都从这一帧记下的状态出发");
        assertTrue(mixin.contains("DRAWING.set(ours ? moved : null);"),
                "而不是无条件替换: 别的方块这一帧记的是 null");
    }
}
