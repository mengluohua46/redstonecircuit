package com.jiangyuefengyu.redstonecircuit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.jiangyuefengyu.redstonecircuit.structure.IoMode;
import com.jiangyuefengyu.redstonecircuit.structure.ReleaseJournal;
import com.jiangyuefengyu.redstonecircuit.structure.StructureCapture;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePayload;
import com.jiangyuefengyu.redstonecircuit.structure.StructureRelease;
import com.jiangyuefengyu.redstonecircuit.structure.StructureWorldStore;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * A release that a crash interrupted, and the repair.
 *
 * <p>A release writes its blocks over several ticks, so a server that stops in the middle of one leaves part of a
 * structure in the world with nothing anywhere saying so. {@code ReleaseJournal} writes down where a big release is
 * before its first block and the next server start finishes it; this drives the file half of that on a real server (the
 * file layer itself is unit-tested in {@code ReleaseJournalTest}, and the whole global recovery loop is exercised by
 * {@code /rc pocket journaltest} on a real server).
 *
 * <p>The repair's real trigger is "the in-memory jobs are gone, the files are not", but {@code forgetAll} cannot be called
 * here: a game test batch runs beside other batches and it drops <em>every</em> release on the server, including one
 * another test may be measuring. So the crash is simulated the way the recovery path does not care about - the
 * interrupted release is left where it is and a second release of the same payload is started, which is exactly what the
 * repair does: the payload decides the blocks, so writing them twice writes them once.
 */
public final class ReleaseRepairGameTest implements FabricGameTest {

    @GameTest(template = "redstonecircuit:empty")
    public void aReleaseInterruptedHalfWayIsWrittenAgainAndForgetsItsRecord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos source = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos target = helper.absolutePos(new BlockPos(1, 2, 6));
        for (int x = 0; x < 3; x++) {
            level.setBlock(source.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(source.offset(x, 0, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
            level.setBlock(target.offset(x, -1, 0), Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(target.offset(x, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        }

        StructureCapture.Result captured = StructureCapture.capture(level,
                new StructureCapture.Request(source, source.offset(2, 0, 0), IoMode.ACTIVATION_FACES,
                        "repair test", false, false, null),
                RCConfig.structureCaptureLimits());
        helper.assertTrue(captured.allowed(), "the test must be able to capture its own blocks: "
                + captured.refusal());
        StructurePayload payload = captured.captured().payload();
        int expected = payload.header().size().getX() * payload.header().size().getY()
                * payload.header().size().getZ();
        try {
            StructureWorldStore.of(level.getServer(), RCConfig.structurePayloadLimits()).add(payload);
        } catch (java.io.IOException failed) {
            helper.fail("the test could not write its structure to this save: " + failed);
        }
        Path directory = ReleaseJournal.directory(level.getServer());
        Path record = ReleaseJournal.fileOf(directory, payload.header().uuid());

        // One block a tick, so the first tick of it is a release that is genuinely unfinished.
        StructureRelease.Result started = StructureRelease.start(level, payload, target, true,
                new StructureRelease.Options(1, false));
        helper.assertTrue(started.started(), "the release must start: " + started.refusal());
        helper.assertTrue(Files.isRegularFile(record),
                "a multi-tick release must write down where it is before its first block");
        List<ReleaseJournal.Entry> entries = ReleaseJournal.readFrom(directory);
        helper.assertTrue(entries.stream().anyMatch(entry -> entry.structureId().equals(payload.header().uuid())
                        && entry.origin().equals(target) && entry.dimension().equals(level.dimension())),
                "and the record must name this release's save, where it is and which level: " + entries);

        StructureRelease.tick(level);
        helper.assertTrue(level.getBlockState(target).is(Blocks.GOLD_BLOCK),
                "one tick of a one-block-a-tick release writes one block");
        helper.assertTrue(level.getBlockState(target.offset(1, 0, 0)).isAir(),
                "and leaves the rest unwritten - which is the state a crash leaves behind");
        helper.assertTrue(Files.isRegularFile(record), "an unfinished release keeps its record");

        // The repair: the same payload and the same origin, which is what the recovery path does at server start.
        StructureRelease.Result resumed = StructureRelease.start(level, payload, target, true,
                RCConfig.structureReleaseOptions());
        helper.assertTrue(resumed.started(), "the repair must be able to start: " + resumed.refusal());
        StructureRelease.tick(level);
        for (int x = 0; x < expected; x++) {
            helper.assertTrue(level.getBlockState(target.offset(x, 0, 0)).is(Blocks.GOLD_BLOCK),
                    "the repaired release must write cell " + x + " of " + expected);
        }
        helper.assertTrue(!Files.exists(record),
                "and a finished release must remove its record, or the next start would write it all again");
        helper.succeed();
    }
}
