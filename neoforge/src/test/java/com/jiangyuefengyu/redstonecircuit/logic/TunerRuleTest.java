package com.jiangyuefengyu.redstonecircuit.logic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 调频器's decision table, pinned without a running game.
 *
 * <p>这张表就是这个物品的全部语义, 而它有 32 种输入组合 (手里有没有频道 × 那个频道是不是别的维度 × 目标有没有频道 ×
 * 目标是不是就在同一个频道上 × 潜行没有)。所以这里不是挑几个代表, 而是把 32 行逐行写出来 - 判定的每一个分支都至少被
 * 一行钉住, 而任何一行的答案被改掉, 这里的某一句话就会失败。
 */
class TunerRuleTest {

    /** 一行: 五个事实 → 该做的事。写出来而不是算出来, 否则这个测试只会重复被测的那段代码。 */
    private static void expect(TunerRule.Action action, boolean handHasChannel, boolean handOtherDimension,
                               boolean targetHasChannel, boolean targetOnSameChannel, boolean sneaking) {
        assertEquals(action, TunerRule.decide(handHasChannel, handOtherDimension, targetHasChannel,
                        targetOnSameChannel, sneaking),
                "hand=" + handHasChannel + " foreign=" + handOtherDimension + " target=" + targetHasChannel
                        + " same=" + targetOnSameChannel + " sneaking=" + sneaking);
    }

    @Test
    @DisplayName("潜行半边: 目标有频道就清掉目标的, 目标没有就什么都不做; 手里的一律不动")
    void theSneakingHalfOnlyTouchesTheTarget() {
        // 手里没有频道。
        expect(TunerRule.Action.NONE, false, false, false, false, true);
        expect(TunerRule.Action.NONE, false, false, false, true, true);
        expect(TunerRule.Action.UNBIND, false, false, true, false, true);
        expect(TunerRule.Action.UNBIND, false, false, true, true, true);
        // 手里有频道, 同维度: 针对的仍然是"那一支火把", 手里那个不动。
        expect(TunerRule.Action.NONE, true, false, false, false, true);
        expect(TunerRule.Action.NONE, true, false, false, true, true);
        expect(TunerRule.Action.UNBIND, true, false, true, false, true);
        expect(TunerRule.Action.UNBIND, true, false, true, true, true);
        // 手里那个频道属于别的维度 - 这一条压过一切。
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, false, false, true);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, false, true, true);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, true, false, true);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, true, true, true);
        // "手里没有频道" 时维度那个旗标没有意义。
        expect(TunerRule.Action.NONE, false, true, false, false, true);
        expect(TunerRule.Action.NONE, false, true, false, true, true);
        expect(TunerRule.Action.UNBIND, false, true, true, false, true);
        expect(TunerRule.Action.UNBIND, false, true, true, true, true);
    }

    @Test
    @DisplayName("不潜行半边: 手里没频道而目标有, 就选中目标那一个; 手里有别的频道才是搬动; 目标没有则绑定")
    void thePlainClickAdoptsBindsOrMoves() {
        // 手里没有频道: 目标有频道 = 选中它那一个(COPY), 目标没有 = 新开一个并绑定(BIND)。
        expect(TunerRule.Action.BIND, false, false, false, false, false);
        expect(TunerRule.Action.BIND, false, false, false, true, false);
        expect(TunerRule.Action.COPY, false, false, true, false, false);
        expect(TunerRule.Action.COPY, false, false, true, true, false);
        expect(TunerRule.Action.BIND, false, true, false, false, false);
        expect(TunerRule.Action.BIND, false, true, false, true, false);
        expect(TunerRule.Action.COPY, false, true, true, false, false);
        expect(TunerRule.Action.COPY, false, true, true, true, false);
        // 手里有频道, 同维度: 目标在别的频道上是搬动, 没有或是同一个则是绑定。
        expect(TunerRule.Action.BIND, true, false, false, false, false);
        expect(TunerRule.Action.BIND, true, false, false, true, false);
        expect(TunerRule.Action.MOVE, true, false, true, false, false);
        expect(TunerRule.Action.BIND, true, false, true, true, false);
        // 别的维度: 那一条压过一切。
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, false, false, false);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, false, true, false);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, true, false, false);
        expect(TunerRule.Action.OTHER_DIMENSION, true, true, true, true, false);
    }

    @Test
    @DisplayName("表是全的: 32 种组合每一种都有答案, 而且每个动作都只从它该出现的地方出现")
    void theTableIsTotalAndEveryActionKeepsItsGuard() {
        Set<TunerRule.Action> seen = new HashSet<>();
        for (boolean hand : new boolean[] {false, true}) {
            for (boolean foreign : new boolean[] {false, true}) {
                for (boolean target : new boolean[] {false, true}) {
                    for (boolean same : new boolean[] {false, true}) {
                        for (boolean sneaking : new boolean[] {false, true}) {
                            TunerRule.Action action = TunerRule.decide(hand, foreign, target, same, sneaking);
                            assertNotNull(action, "每一种组合都必须有一个答案");
                            seen.add(action);

                            if (action == TunerRule.Action.OTHER_DIMENSION) {
                                assertTrue(hand && foreign,
                                        "OTHER_DIMENSION 只在手里那个频道确实属于别的维度时出现");
                            }
                            if (action == TunerRule.Action.NONE) {
                                assertTrue(sneaking && !target,
                                        "NONE 只在 Shift 半边、且目标本来就没有频道时出现");
                            }
                            if (action == TunerRule.Action.UNBIND) {
                                assertTrue(sneaking && target, "UNBIND 只在 Shift 半边、且目标真有频道时");
                            }
                            if (action == TunerRule.Action.COPY) {
                                assertTrue(!sneaking && !hand && target,
                                        "选中目标那一个只在普通手势、手里没有频道、而目标有频道时");
                            }
                            if (action == TunerRule.Action.MOVE) {
                                assertTrue(!sneaking && hand && target && !same,
                                        "MOVE 只在非潜行、手里有频道、目标确实在别的频道上时出现");
                            }
                            if (action == TunerRule.Action.BIND) {
                                assertTrue(!sneaking && !(hand && foreign), "BIND 是普通手势, 且不会在维度不合时出现");
                            }
                        }
                    }
                }
            }
        }
        assertEquals(Set.of(TunerRule.Action.values()), seen, "32 种组合应该把六个动作全都走到");
    }

    @Test
    @DisplayName("每个动作都说一句话, 六句话各不相同, 而且各自要的参数是明确的")
    void everyActionNamesItsOwnSentence() {
        Set<String> keys = new HashSet<>();
        for (TunerRule.Action action : TunerRule.Action.values()) {
            TunerRule.Message message = action.message();
            if (action == TunerRule.Action.NONE) {
                assertNull(message, "NONE 什么也没做, 于是没什么可说的");
                continue;
            }
            assertNotNull(message, action + " 必须有一句反馈");
            assertTrue(keys.add(message.key()), message.key() + " 被两个动作共用, 于是玩家读到的话会说谎");
            assertTrue(message.key().startsWith("message.redstonecircuit.tuner."),
                    message.key() + " 应该落在 tuner 那一段键里");
        }
        assertEquals(TunerRule.Action.values().length - 1, keys.size(), "除了 NONE, 每个动作各说各的");
        // 只有"加入/搬动/取到"三句报一个号码; 清掉目标的频道与维度不合两句不带参数。
        assertTrue(TunerRule.Action.BIND.message().namesChannel());
        assertTrue(TunerRule.Action.MOVE.message().namesChannel());
        assertTrue(TunerRule.Action.COPY.message().namesChannel());
        assertFalse(TunerRule.Action.UNBIND.message().namesChannel());
        assertFalse(TunerRule.Action.OTHER_DIMENSION.message().namesChannel());
        assertNotEquals(TunerRule.Action.BIND.message(), TunerRule.Action.MOVE.message(),
                "绑定与搬动是两件事, 玩家要能分辨");
        assertNotEquals(TunerRule.Action.UNBIND.message(), TunerRule.Message.CLEARED,
                "清掉火把的频道与清空手里的频道是两件事: 有一支火把真的变了, 而另一种只改了手里");
    }

    @Test
    @DisplayName("一个频道是号码加维度: 号码不是频道号的都不算, 维度比较是字面比较")
    void aChannelIsANumberPlusADimension() {
        assertTrue(new TunerRule.Channel(3, "minecraft:overworld").isBound());
        assertFalse(new TunerRule.Channel(SyncTorchRule.UNBOUND, "minecraft:overworld").isBound());
        assertFalse(new TunerRule.Channel(0, "minecraft:overworld").isBound(),
                "0 与 UNBOUND 都不是频道号, 与 SyncTorchRule#isChannel 同一个答案");

        TunerRule.Channel overworld = new TunerRule.Channel(3, "minecraft:overworld");
        assertTrue(overworld.inDimension("minecraft:overworld"));
        assertFalse(overworld.inDimension("minecraft:the_nether"),
                "同一个号码在另一个维度里是另一个组, 这正是这里要挡住的那件事");
        assertEquals(3, overworld.number());
        assertEquals("minecraft:overworld", overworld.dimension());
    }

    @Test
    @DisplayName("六句反馈与两个 tooltip 键在中英两份语言文件里都存在, 而且两份的键集合一致")
    void everyKeyIsTranslatedInBothLanguages() throws IOException {
        JsonObject zh = lang("zh_cn.json");
        JsonObject en = lang("en_us.json");
        assertEquals(zh.keySet(), en.keySet(), "两份语言文件的键集合必须一致, 否则总有一种语言会露出原始键");

        for (TunerRule.Message message : TunerRule.Message.values()) {
            assertTrue(zh.has(message.key()), "zh_cn.json 缺 " + message.key());
            assertTrue(en.has(message.key()), "en_us.json 缺 " + message.key());
        }
        for (String key : List.of("item.redstonecircuit.frequency_tuner.tip",
                "item.redstonecircuit.frequency_tuner.tip.detail",
                "item.redstonecircuit.frequency_tuner.channel",
                "item.redstonecircuit.frequency_tuner.channel.none")) {
            assertTrue(zh.has(key), "zh_cn.json 缺 " + key);
            assertTrue(en.has(key), "en_us.json 缺 " + key);
        }
    }

    private static JsonObject lang(String file) throws IOException {
        Path underNeoforge = Path.of("neoforge", "src", "main", "resources", "assets", "redstonecircuit",
                "lang").resolve(file);
        Path direct = Path.of("src", "main", "resources", "assets", "redstonecircuit", "lang").resolve(file);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                JsonElement parsed = JsonParser.parseString(Files.readString(candidate, StandardCharsets.UTF_8));
                assertTrue(parsed.isJsonObject(), candidate + " 应该是一个 JSON 对象");
                return parsed.getAsJsonObject();
            }
        }
        throw new IOException("找不到 " + file + " (从 " + Path.of("").toAbsolutePath() + " 找起)");
    }
}
