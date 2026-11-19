package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>压力板（贴面） riding a machine is re-asked by the same walk that re-asks every other device, and the entry it is
 * asked through really recomputes.</b>
 *
 * <p>A plate's answer is not a function of its neighbours: it counts the entities standing in its cell. A carried plate
 * keeps its state at a coordinate of the carrier's own storage while the feet are on the block the machine <em>draws</em>,
 * so {@code entityInside} never fires and no neighbour change ever arrives - the per-tick walk over a physicalised
 * structure's own signal sources is the only caller that can turn the plate over (作者 2026-11-16：物理方块上的贴面压力板
 * 无法被激活).
 *
 * <p>Two facts are checked, because each of them alone leaves the defect in place: the entry exists with that
 * signature, and the count reads the frame the plate is drawn in.
 */
class CarriedPressurePlateWiringTest {

    @Test
    @DisplayName("物理化之后压力板也被那趟走查问到，而且那个入口真的会两帧数人")
    void thePlateIsPolledWhenItIsCarried() throws IOException {
        // The walk that calls this entry lives in the compat source set (Sable), which this loader does not build; see 移植日志 §八.

        String plate = source(List.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block"),
                "SurfacePressurePlateBlock.java");
        assertTrue(plate.contains(
                "public static void refreshCarried(ServerLevel level, BlockPos cell, BlockState state)"),
                "走查会调一个不存在的方法：入口签名变了就要连这里一起改");
        assertTrue(plate.contains("ProductFrame.carriedAt(server, pos)"),
                "数实体要两帧都数：物理化之后脚踩的是画出来的那一格，plot 里那一格永远没有人");
    }

    /** One source file, found from either layout the harness may run in, failing loudly rather than skipping. */
    private static String source(List<String> from, String name) throws IOException {
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
