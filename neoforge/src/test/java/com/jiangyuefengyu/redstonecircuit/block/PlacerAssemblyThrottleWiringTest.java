package com.jiangyuefengyu.redstonecircuit.block;

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
 * 方块放置器's structure assembly, as the three things that have to stay true about it.
 *
 * <p>A sub-level is given a physics body the <em>moment</em> Sable allocates it, before a single block has been
 * moved into its plot, and that body is weighed only later, from the plot's contents. A structure whose plot ends
 * up holding nothing therefore reaches the physics step with no mass and no centre of mass, while its plot box
 * still reads as the "no blocks here" sentinel - so the cost of assembling one such structure is not a wasted
 * block, it is the server. Three rules keep that from being reachable:
 *
 * <ul>
 *   <li>one structure per server tick, with the rest of the tick's placements waiting for a later tick instead of
 *       being assembled in the same pass;</li>
 *   <li>the source cell is read back <b>before</b> the assembly - the block Sable moves into the new plot is the
 *       block that is really standing in the world cell, so a cell that reads as air must not be assembled at
 *       all;</li>
 *   <li>a structure that was assembled is judged on the <b>next</b> carrier walk - not in the call that made it,
 *       where the plot's box has not been recomputed yet and a sound structure would read as empty - and a
 *       structure with no mass, no centre of mass, an empty plot or an unreadable pose switches assembly off for
 *       the rest of the run.</li>
 * </ul>
 *
 * <p>None of this changes what the machine places: a deferred placement still places its one block and spends its
 * one item, and a machine whose assembly is switched off still puts its block into the world.
 */
class PlacerAssemblyThrottleWiringTest {

    @Test
    @DisplayName("每个服务器 tick 至多组装一个结构，同一个 tick 的其余放置推迟到后续 tick")
    void oneAssemblyPerServerTickAndTheRestWait() throws IOException {
        String block = block();

        assertTrue(block.contains("if (slot >= 0 && RCConfig.placerCreatesStructures()")
                        && block.contains("&& !PlacerAssemblyGate.claim(level.getServer().getTickCount())) {"),
                "the carried half takes this tick's one assembly before it places anything, because the assembly "
                        + "happens inside the call that places");
        assertTrue(block.indexOf("RCConfig.placerCreatesStructures()") < block.indexOf("PlacerAssemblyGate.claim("),
                "and only when there is a structure to assemble at all: with the switch off this path is the one it "
                        + "always was, down to the ticks it schedules");
        assertTrue(block.contains("level.scheduleTick(pos, level.getBlockState(pos).getBlock(), PLACE_DELAY_TICKS);"),
                "and a placement that did not get it comes back on a later tick by rescheduling itself: it is "
                        + "deferred, never dropped");
        assertTrue(block.indexOf("PlacerAssemblyGate.claim(")
                        < block.indexOf("PhysicsCompat.placeSingleBlockStructure(level, drawn, out, stack)"),
                "the claim has to be taken before the physical half runs, or a tick would assemble twice");
        assertTrue(block.indexOf("PlacerAssemblyGate.claim(") < block.indexOf("placer.setItem(slot, stack);"),
                "and before the spent stack is written back, so a deferred placement spends nothing at all - the "
                        + "one item one activation consumes is still spent by the placement itself, once");
    }

    @Test
    @DisplayName("组装之前复读源格：读成空气就不组装（空 plot 的成因就在这一格）")
    void theSourceCellIsReadBackBeforeAssembling() throws IOException {
        String placement = placement();

        assertTrue(placement.contains("BlockState source = level.getBlockState(drawnTarget);"),
                "the cell Sable is about to move is read again here, through the same level lookup the world path "
                        + "uses: what Sable carries into the new plot is exactly what this read answers");
        assertTrue(placement.contains("if (source.isAir()) {"),
                "and an air cell is not assembled: an empty plot is a body with nothing to weigh");
        assertTrue(placement.indexOf("level.getBlockState(drawnTarget)") < placement.indexOf("assembleBlocks("),
                "the read has to come before the assembly, or it cannot stop it");
        assertTrue(placement.indexOf("PlacerAssemblyWatch.watch(") > placement.indexOf("assembleBlocks("),
                "only a structure that Sable really made is registered for the read-back");
        assertFalse(placement.contains("getMassTracker"),
                "and its body is deliberately not judged here: the plot's box is recomputed after the assembly "
                        + "returns, so judging in this call would report a sound structure as empty");
    }

    @Test
    @DisplayName("质量与质心在下一次 driver 走查里判，不满足就停用后续组装并留一条带证据的日志")
    void theBodyIsJudgedOnTheNextWalkAndSwitchesAssemblyOff() throws IOException {
        String placement = placement();
        assertTrue(placement.contains("SableAssemblyWatch.resolvePending(level);"),
                "the placement path judges an earlier structure first, so a structure with no body stops this one "
                        + "before it is made");

        String driver = driver();
        assertTrue(driver.contains("SableAssemblyWatch.resolvePending(container, level);"),
                "the carrier walk is the read point where the plot box and the mass tracker are trustworthy");
        assertTrue(driver.indexOf("SableAssemblyWatch.resolvePending(container, level)")
                        < driver.indexOf("holds no live sub-level"),
                "and it is asked before the gate that returns early on an empty container, or a structure that was "
                        + "already taken back out would never be judged");

        String watch = watch();
        assertTrue(watch.contains("getMassTracker()"),
                "the authoritative question is the body's own mass data: Sable weighs a structure from its plot, "
                        + "and a plot with nothing in it has no centre of mass to upload");
        assertTrue(watch.contains("getCenterOfMass()"),
                "so mass and centre of mass are read together, as one verdict");
        assertTrue(watch.contains("getBoundingBox()"),
                "with the plot box as the cheap early signal: the sentinel box is the same 'no blocks here' fact");
        assertTrue(watch.contains("SableCarrierClaim.poseOf("),
                "and the pose through the conversion the live carrier path already uses, so 'unreadable' means the "
                        + "same thing here as there");
        assertTrue(watch.contains("PlacerAssemblyGate.bodyProblem("),
                "one verdict, built in one place, so the log and the switch-off cannot disagree");
        assertTrue(watch.contains("if (PlacerAssemblyGate.switchOff("),
                "switching off is latched: the first structure that has no body is the one that says why, and only "
                        + "that one writes the line");
        assertTrue(watch.contains("entry.uuid()") && watch.contains("entry.worldTarget()"),
                "the line carries the sub-level and the world cell the machine was pointing at, or it cannot be "
                        + "matched against anything");

        String gate = gate();
        assertTrue(gate.contains("\"mass=\" + mass"), "the verdict names the mass it saw");
        assertTrue(gate.contains("\"centreOfMass=absent\""), "whether the centre of mass was there");
        assertTrue(gate.contains("\"plotBounds=empty\""), "whether the plot had any block at all");
        assertTrue(gate.contains("\"pose=unreadable\""), "and whether the pose could be read");
    }

    private static String block() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "block",
                "BlockPlacerBlock.java");
    }

    private static String placement() throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SablePlacerPlacement.java");
    }

    private static String driver() throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableProductDriver.java");
    }

    private static String watch() throws IOException {
        return read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "SableAssemblyWatch.java");
    }

    private static String gate() throws IOException {
        return read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat", "physics",
                "PlacerAssemblyGate.java");
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
                + Path.of("").toAbsolutePath() + "; this guard reads the file, so it must not be skipped");
    }
}
