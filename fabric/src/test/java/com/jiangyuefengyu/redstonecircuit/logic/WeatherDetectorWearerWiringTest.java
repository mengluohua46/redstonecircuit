package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>头盔槽靠轮询认，而轮询是白跑的 —— 两条一起才成立。</b>
 *
 * <p>这个加载器没有"装备变化"事件（fabric-api 0.102.0 的 {@code fabric-entity-events-v1} 只有
 * {@code AfterDeath / AllowDamage / AllowDeath / MobConversion}），所以"头上有没有那件东西"只能靠在每个玩家身上定期
 * 重算一次来发现。要让这个"晚"小到看不见，就只能把间隔收到 2 tick；而收小间隔的前提是重算本身<b>幂等</b> —— 否则
 * 每个玩家每两 tick 都会被重设一次属性（值一样，但会重算属性、连带血量与同步），省下的手感被凭空多出来的开销抵掉。
 *
 * <p>四条断言是同一件事的四面：间隔、幂等、以及两个触发点的接线。少任何一条，这个设计都不成立。
 */
class WeatherDetectorWearerWiringTest {

    @Test
    @DisplayName("换装靠 2 tick 的轮询认，而那次轮询在状态没变时一个字节都不动")
    void theHeadSlotIsFoundByACheapPoll() throws IOException {
        String wearer = source("WeatherDetectorWearer.java");

        assertTrue(wearer.contains("public static final int POLL = 2;"),
                "轮询间隔不是 2：没有换装事件时，头盔槽就靠这一趟认，间隔一放大，换装手感立刻退回半秒");
        assertTrue(wearer.contains("if (instance.hasModifier(id) == wanted) {"),
                "重算不再幂等：每个玩家每 2 tick 都会被重设一次属性，这和刚把间隔收小是互相打脸的两件事");
        assertTrue(wearer.indexOf("if (instance.hasModifier(id) == wanted) {")
                        < wearer.indexOf("instance.addOrUpdateTransientModifier("),
                "幂等判断被放到了写属性的后面：那样它一次都拦不住");
        assertTrue(wearer.contains("ServerTickEvents.END_WORLD_TICK.register"),
                "世界上没有挂：没有它，天气与头盔槽都永远不会被发现");
        assertTrue(wearer.contains("ServerLivingEntityEvents.ALLOW_DAMAGE.register"),
                "落雷那一条触发没了：雷暴里打中谁就落雷不再成立");
    }

    /** One source file, found from either layout the harness may run in, failing loudly rather than skipping. */
    private static String source(String name) throws IOException {
        List<String> from = List.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic");
        Path relative = Path.of("", from.toArray(String[]::new)).resolve(name);
        Path atRepoRoot = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, atRepoRoot)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + name + " from " + Path.of("").toAbsolutePath());
    }
}
