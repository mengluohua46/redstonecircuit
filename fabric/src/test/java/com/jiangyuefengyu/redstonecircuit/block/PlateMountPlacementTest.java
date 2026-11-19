package com.jiangyuefengyu.redstonecircuit.block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a click on a face becomes a 贴面 and a 正面 - read from the source, because the rule lives in one place and the
 * registry classes it leans on cannot be initialised in a plain JUnit run.
 *
 * <p>The one case that must not change is the flat plate: 1.3.0's rule was 正面 back at whoever placed it, and a plate
 * on a floor or a ceiling still gets exactly that.
 */
class PlateMountPlacementTest {

    @Test
    @DisplayName("地板与吊顶：正面照旧回到放它的人（1.3.0 的规则一点没动）")
    void aFlatPlateStillPointsAtThePlayer() throws IOException {
        String plate = read("RcPlateBlock.java");
        assertTrue(plate.contains("if (mount.getAxis().isVertical()) {")
                        && plate.contains("return horizontalAway;"),
                "竖直贴面（地板/吊顶）走原来那一句: 正面 = context.getHorizontalDirection().getOpposite()");
        assertTrue(plate.contains("direction == state.getValue(MOUNT)"),
                "而且只有贴着的那一面变了才判生死 - 正面那面被拆不该带走板子");
    }

    @Test
    @DisplayName("墙上：交给贴面中继器那套三维取法，视线扎进墙里就落到墙内最近的方向")
    void aWallPlateReadsWhereThePlayerLooks() throws IOException {
        String plate = read("RcPlateBlock.java");
        assertTrue(plate.contains("SurfaceDiodeBlock.placementFacing(mount, lookAway, horizontalAway)"),
                "墙上直接复用贴面二极管那一个入口（含 snapFacing），两套取法不许各写一遍");
        assertTrue(plate.contains("Direction.getNearest(-look.x, -look.y, -look.z)"),
                "视线的反方向由玩家的 look 向量给出 - UseOnContext 没有「最近的方向」这一问");
    }

    @Test
    @DisplayName("放置：点哪面贴哪面，背后不是实心面就一个都不放")
    void aPlateIsPlacedAgainstTheFaceThatWasClicked() throws IOException {
        String plate = read("RcPlateBlock.java");
        assertTrue(plate.contains("Direction mount = context.getClickedFace().getOpposite();"),
                "贴面 = 被点的那一面的反面");
        assertTrue(plate.contains("canStay(state, context.getLevel(), context.getClickedPos()) ? state : null"),
                "挂不住的组合返回 null：物品不放出去，而不是放下去再当场掉");
    }

    private static String read(String name) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block", name);
        if (Files.isRegularFile(relative)) {
            return Files.readString(relative, StandardCharsets.UTF_8);
        }
        throw new IOException("could not find " + name + " (tried " + relative + ")");
    }
}
