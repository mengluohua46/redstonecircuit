package com.jiangyuefengyu.redstonecircuit.block;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 转向杆 against the four 薄板: the rod walks the ring inside the plate's own plane and leaves 贴面 alone.
 *
 * <p>Read from the source for the same reason {@link PlateMountPlacementTest} is: the classes it leans on cannot be
 * initialised without a bootstrapped registry. What the turn actually does to the answer is a GameTest.
 */
class PlateTurnRodWiringTest {

    @Test
    @DisplayName("转向杆认得这四件：交给 RcPlateBlock 那一处，不自己改状态")
    void theRodKnowsThePlates() throws IOException {
        String rod = read("logic", "RotationRod.java");
        assertTrue(rod.contains("state.getBlock() instanceof com.jiangyuefengyu.redstonecircuit.block.RcPlateBlock")
                        && rod.contains("RcPlateBlock.turnClockwise(level, pos, state)"),
                "turnWorldDiode 里要有薄板这一支，而且真正动手的是薄板自己那一处");
        assertTrue(rod.indexOf("RcPlateBlock.turnClockwise") < rod.indexOf("instanceof ComparatorBlock"),
                "放在原版中继器/比较器那两支之前：那四件是我们自己的方块，不该落到通用分支");
    }

    @Test
    @DisplayName("转一格 = 板面内下一个方向：贴面不参与，转完自己再算一遍答案")
    void theStepStaysInsideThePlate() throws IOException {
        String plate = read("block", "RcPlateBlock.java");
        assertTrue(plate.contains("public static Direction turnClockwise(ServerLevel level, BlockPos pos, BlockState state)"),
                "和贴面中继器/贴面触发器同名同签名，转向杆才有一处统一的调用面");
        assertTrue(plate.contains("SurfaceDiodeBlock.nextFacing(state.getValue(MOUNT), state.getValue(FACING))"),
                "下一个正面走贴面二极管那条环：墙上才有上/下，地板与吊顶是水平四向");
        assertTrue(plate.contains("BlockState turned = state.setValue(FACING, next);"),
                "只改正面 - 贴面（挂在哪一面）不归转向杆管");
        assertTrue(plate.contains("level.neighborChanged(pos, turned.getBlock(), pos);"),
                "转完照邻居变化那样通知自己一次，四件各自的 announce 才会重算答案");
    }

    private static String read(String... parts) throws IOException {
        List<Path> roots = List.of(
                Path.of("neoforge", "src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit"),
                Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit"));
        for (Path root : roots) {
            Path candidate = root.resolve(Path.of("", parts));
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from " + Path.of("").toAbsolutePath());
    }
}
