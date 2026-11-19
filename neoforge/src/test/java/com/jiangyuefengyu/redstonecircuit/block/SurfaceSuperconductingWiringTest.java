package com.jiangyuefengyu.redstonecircuit.block;

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

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 超导红石粉（贴面）: its art exists for every face, and it takes its strength from the network instead of working
 * one out.
 *
 * <p>The second half is the one that matters. This sheet is the same geometry as 红石粉（贴面） but not the same
 * physics: an ordinary sheet decides its own strength and the superconductor's is decided <b>once for the whole run</b>,
 * floor dust and sheets together. A sheet that computed its own would hold the value it read from its neighbour and
 * never come down - the pair at gain exactly one this project has paid for twice already.
 */
class SurfaceSuperconductingWiringTest {

    private static final List<String> FACES = List.of("down", "up", "north", "south", "east", "west");

    private static Path resource(String... parts) {
        Path file = Path.of("src", "main", "resources", "assets", "redstonecircuit");
        for (String part : parts) {
            file = file.resolve(part);
        }
        return file;
    }

    private static String source(String... parts) throws IOException {
        Path file = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit");
        for (String part : parts) {
            file = file.resolve(part);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private static JsonObject json(Path path) throws IOException {
        return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    @Test
    @DisplayName("the blockstate names one dot model per face, all of them the superconducting art")
    void theBlockstateCoversAllSixFaces() throws IOException {
        JsonObject blockstate = json(resource("blockstates", "surface_superconducting_redstone.json"));
        String text = blockstate.toString();
        for (String face : FACES) {
            assertTrue(text.contains("redstonecircuit:block/superconducting_dust_" + face + "_dot"),
                    face + " 的圆点模型没被引用：" + text);
        }
        assertFalse(text.contains("surface_dust_"), "贴面红石粉的模型不该混进来：" + text);
    }

    @Test
    @DisplayName("all 24 strip models exist and are the superconducting line on vanilla's overlay")
    void everyStripModelExists() throws IOException {
        for (String face : FACES) {
            assertTrue(Files.exists(resource("models", "block", "superconducting_dust_" + face + "_dot.json")),
                    face + " 的圆点模型文件不存在");
            for (int slot = 0; slot < 4; slot++) {
                JsonObject model = json(resource("models", "block",
                        "superconducting_dust_" + face + "_flat_" + slot + ".json"));
                assertEquals("redstonecircuit:block/superconducting_dust_line0",
                        model.getAsJsonObject("textures").get("line").getAsString(),
                        face + " 第 " + slot + " 条条带用的不是超导贴图");
                // 白银那一层是原版的高光，两种粉共用；换掉它就是把几何之外的东西也改了。
                assertEquals("minecraft:block/redstone_dust_overlay",
                        model.getAsJsonObject("textures").get("overlay").getAsString(),
                        face + " 第 " + slot + " 条条带的高光层被改了");
            }
        }
    }

    @Test
    @DisplayName("the sheet hands its strength back to the network instead of computing one")
    void theSheetDoesNotWorkOutItsOwnStrength() throws IOException {
        String sheet = source("block", "SurfaceSuperconductingWireBlock.java");
        assertTrue(sheet.contains("SuperconductingWireBlock.updateComponent(level, pos)"),
                "贴面超导粉必须把重算交给整条 run：" + sheet);
        assertFalse(sheet.contains("updatePowerStrength"),
                "贴面超导粉不能自己算强度（那会和 run 打架）：" + sheet);
        assertTrue(sheet.contains("isSuppressed()"),
                "求解期间它必须像地板粉一样沉默，否则会把存着的强度读回给自己");
    }

    @Test
    @DisplayName("the run spans the floor dust and its sheets, in collecting and in writing")
    void theRunSpansBothKinds() throws IOException {
        String body = source("block", "SuperconductingWireBlock.java");
        assertTrue(body.contains("public static boolean isSuperconducting(BlockState state)"),
                "网络成员这一条判据必须存在：" + body);
        assertTrue(body.contains("if (!isSuperconducting(level.getBlockState(origin)))"),
                "从一片贴面出发也得能收集到整条 run：" + body);
        assertTrue(body.contains("if (!isSuperconducting(state) || state.getValue(POWER) == strength)"),
                "写强度时必须把贴面一起写：" + body);
        assertTrue(body.contains("private static List<BlockPos> joinedSheets("),
                "贴面要有自己的连接规则（同一平面 + 地板那条粉）：" + body);
        assertTrue(body.contains("Direction.Plane.HORIZONTAL"),
                "地板粉只能从水平相邻的贴面接上（竖直方向贴面挂不住）：" + body);
    }
}
