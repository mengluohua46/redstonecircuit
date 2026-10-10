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
 * <b>A comparator reads the block a machine DRAWS, not only the block that is really there.</b>
 *
 * <p>比较器的背面输入不是红石信号: 原版问它背后的方块要<em>模拟</em>输出（{@code BlockState#getAnalogOutputSignal}），
 * 而舵轮的转角只能从这条路径走出去。信号那一半早有查询时的回退（{@code PhysicsCompat#carriedSignalAt}），模拟那一半
 * 没有，于是被画在那里的物理化舵轮对所有比较器都不可见。守卫按读取经过的顺序排: 先问世界自己的答案且从不覆盖它，
 * 兼容层入口是后端类上的具名入口，遍历本身与信号那一半相同。
 */
class CarriedAnalogWiringTest {

    @Test
    @DisplayName("the world answers first, then the side, then the machine")
    void theWorldAnswersFirst() throws IOException {
        String body = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "InnerRedstoneNetwork.java"), "public static int readAnalogOutput(");

        int world = body.indexOf("state.getAnalogOutputSignal(level, pos)");
        int directional = body.indexOf("DirectionalAnalog.analogOutputFrom(state, level, pos, direction)");
        int here = body.indexOf("int here = Math.max(world, directional);");
        int guard = body.indexOf("int drawn = here > 0 || !(level instanceof ServerLevel server)");
        int carried = body.indexOf("PhysicsCompat.carriedAnalogAt(server, pos, direction)");
        assertTrue(world > 0 && directional > world && here > directional && guard > here && carried > guard,
                "three questions in the order a comparator asks them: what the world block answers, what it answers "
                        + "ABOUT THIS SIDE (a helm answers 0 to the plain question and only implements the directional "
                        + "one), and only then what a machine draws there - each step short-circuiting the one below it");
        assertTrue(body.contains("return Math.max(here, drawn);"),
                "and every half is combined the way the solver combines the signal halves: the larger of them");
        assertTrue(body.contains("@Nullable Direction direction"),
                "the direction is a parameter, not something this method guesses: vanilla's comparator passes its own "
                        + "facing, and a read that invents one would ask the helm about a side nobody is on");
        assertTrue(body.contains("DirectionalAnalog.answersDirectionally(")
                        && body.contains("describeNeighbour(state, direction)")
                        && body.contains("describeBlockEntity(level, pos)")
                        && body.contains("ANALOG_PROBE_TICKS"),
                "and a block that answers this question differently per side says all three answers out loud, once a "
                        + "second: the helm answers \"somebody is holding me\" from the side it faces and its steering "
                        + "angle from any other, so a read of 0 is two different facts, and what tells them apart is the "
                        + "block, the way it faces, WHETHER THAT IS THE SIDE ASKED ABOUT, every answer, and the block "
                        + "entity's own state at that moment (held? turned?)");
    }

    @Test
    @DisplayName("every caller of that read hands on the direction it was asked about")
    void theDirectionTravelsWithTheQuestion() throws IOException {
        String network = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "InnerRedstoneNetwork.java");
        String world = bodyOf(network, "public int analogOutput(Direction direction)");
        assertTrue(world.contains("readAnalogOutput(level, queryPos.relative(direction), direction)"),
                "the world host asks the neighbour and asks ABOUT THE SAME SIDE, which is exactly what vanilla's "
                        + "ComparatorBlock#getInputSignal does (its `direction` local is state.getValue(FACING) and the "
                        + "block it reads is pos.relative(direction)) - so an inner comparator reads a helm the way a "
                        + "comparator on the ground does, sign included");

        String io = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "carrier",
                "CarrierWorldIo.java");
        assertTrue(bodyOf(io, "public int analogOutputIntoOwnFrame(")
                        .contains("CarrierDirections.worldDirection(pose, localDirection)"),
                "a carried host's world half turns its local side into the world direction the drawn neighbour would "
                        + "be asked about");
        assertTrue(bodyOf(io, "public int analogOutputAtLocal(")
                        .contains("readAnalogOutput(level, localPos, localDirection)"),
                "while the structure's own half keeps the local direction: what it reads is a block of the structure's "
                        + "own space, and the pose has nothing to say about which of its faces is which");

        String driver = bodyOf(read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "SableProductDriver.java"), "static int analogAt(");
        int plain = driver.indexOf("state.getAnalogOutputSignal(level, local)");
        int turned = driver.indexOf("CarrierDirections.localDirection(space.pose(),");
        int directional = driver.indexOf(".analogOutputFrom(state, level, local, localDirection)");
        assertTrue(plain > 0 && turned > plain && directional > turned,
                "and the drawn block is asked both questions with the direction turned into that sub-level's frame - "
                        + "the same conversion the signal half does, because the machine may be rotated");
    }

    @Test
    @DisplayName("the borrowed API is found by name, resolved once, and never initialised")
    void theDirectionalApiIsBorrowedByName() throws IOException {
        String bridge = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "DirectionalAnalog.java");
        assertTrue(bridge.contains("\"dev.simulated_team.simulated.api.IDirectionalAnalogOutput\""),
                "the interface is named as text: it lives inside a mod this project does not compile against, so the "
                        + "reference cannot be a class literal");
        assertTrue(bridge.contains("Class.forName(INTERFACE, false,"),
                "and it is looked up WITHOUT initialising it, so a mod that ships the interface but has not started "
                        + "cannot be woken by a comparator query");
        assertTrue(bridge.contains("computeIfAbsent(block.getClass()")
                        && bridge.contains("ClassNotFoundException | LinkageError absent"),
                "one resolution per block class, and an absent mod is a normal cached answer rather than an error");
        assertTrue(!bridge.contains("import dev.simulated_team"),
                "no compile-time reference to any of it anywhere in this file");

        String compat = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "PhysicsCompat.java"), "public static int carriedAnalogAt(");
        assertTrue(compat.contains("ServerLevel.class, BlockPos.class, net.minecraft.core.Direction.class"),
                "and the gated entry point carries the direction through to the backend by name, with the same "
                        + "three-part signature the wiring test pins");
    }

    @Test
    @DisplayName("the fallback is a named backend entry point, gated like the signal one")
    void theBackendEntryPointIsNamed() throws IOException {
        String compat = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "PhysicsCompat.java"), "public static int carriedAnalogAt(");
        assertTrue(compat.contains("backendClass == null || !sableLoaded() || level == null || pos == null"),
                "the gate is the signal half's: no backend class, no Sable, or no position answers 0 without loading a "
                        + "single Sable type - a missing mod must never be able to throw NoClassDefFoundError");
        assertTrue(compat.contains("\"carriedAnalogAt\""),
                "and the class behind it is reached BY NAME with that name, which is why the backend has to declare it");

        String backend = bodyOf(read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "PhysicsCompatBackend.java"), "public static int carriedAnalogAt(");
        assertTrue(backend.contains("SableProductDriver.analogAt(level, worldPos, worldDirection)"),
                "the backend entry point delegates to the walk, and a failure of the bridge is a world that answers "
                        + "nothing extra rather than a broken query");
    }

    @Test
    @DisplayName("the walk is the signal half's, and it ends in the vanilla analogue question")
    void theWalkAsksTheAnalogueQuestion() throws IOException {
        String walk = bodyOf(read("src", "compat", "java", "com", "jiangyuefengyu", "redstonecircuit", "compat",
                "physics", "SableProductDriver.java"), "static int analogAt(");

        int live = walk.indexOf("container.getAllSubLevels()");
        int pose = walk.indexOf("space.containsWorld(worldPos)");
        int local = walk.indexOf("space.localFor(worldPos)");
        int ask = walk.indexOf("state.getAnalogOutputSignal(level, local)");
        assertTrue(live > 0 && pose > live && local > pose && ask > local,
                "live sub-levels, a pose that covers the position, Sable's own inverse for the position, and then the "
                        + "block that is really there - the same walk the signal half does, for the same reason");
        assertTrue(walk.contains("state.isAir()"),
                "an empty cell is skipped rather than asked: a plot coordinate that holds nothing is not a block "
                        + "answering 0, it is a position where this machine draws nothing");
        assertTrue(walk.contains("CarrierDirections.localDirection(space.pose(),"),
                "and the direction IS turned: the helm answers 0 to the plain analogue question and only implements the "
                        + "directional one, so this walk turns the asker's world direction into the sub-level's own - the "
                        + "same conversion the signal half does - and asks both questions, plain first");
    }

    @Test
    @DisplayName("a comparator's analogue input is POLLED, because some blocks move without telling anyone")
    void theAnalogueInputIsPolled() throws IOException {
        String poll = bodyOf(read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "InnerRedstoneNetwork.java"), "public static void pollAnalogInputs(");

        assertTrue(poll.contains("slot.type != ComponentType.COMPARATOR"),
                "only a comparator reads an analogue input (PowerSolver asks this question for that type and no other), "
                        + "so everything else is skipped rather than read: a world full of ordinary components pays one "
                        + "type comparison each");
        assertTrue(poll.contains("readAnalogOutput(level, pos.relative(slot.facing), slot.facing)"),
                "one read per comparator, at the neighbour its own input side points at and asked about that side - the "
                        + "identical question the solve asks, so the poll and the solve cannot disagree");
        assertTrue(poll.contains("if (before != null && before != now) {"),
                "and a re-solve only when the number MOVED: a comparator watching a still wheel must not keep the solver "
                        + "awake every tick, which is the cost this whole throttled design exists to avoid");
        assertTrue(poll.contains("ANALOG_POLL_TICKS"),
                "throttled to one redstone tick: the wheel changes its angle at kinetic speed, and two ticks is the "
                        + "same resolution the inner network gives every other driven component");
        assertTrue(poll.contains("store.slotAt(pos) == null"),
                "and the ledger of last readings drops positions whose component is gone, or a world that places and "
                        + "breaks comparators all day would grow it for ever");
        assertTrue(poll.contains("store.isEmpty()"),
                "an empty world pays one check and no map at all");

        String handler = read("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit", "logic",
                "InnerRedstoneTickHandler.java");
        assertTrue(bodyOf(handler, "public void onLevelTick(")
                        .contains("InnerRedstoneNetwork.pollAnalogInputs(level, InnerRedstoneStore.get(level))"),
                "and it is driven from the level tick, right after the queued updates are resolved - the same place the "
                        + "mod already batches everything that has to happen once per tick");
    }

    // ------------------------------------------------------------- helpers --

    private static String read(String... parts) throws IOException {
        // The compat source set is a second tree beside the main one, so both roots are tried the way the rest of
        // these guards do it - and a missing file is a failure rather than a skip.
        Path underNeoforge = Path.of("neoforge").resolve(Path.of("", parts));
        Path direct = Path.of("", parts);
        for (Path candidate : List.of(direct, underNeoforge)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + String.join("/", parts) + " from "
                + Path.of("").toAbsolutePath() + "; this guard reads the source on purpose");
    }

    /** The body of the first member whose declaration contains {@code signature}, by brace counting. */
    private static String bodyOf(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; the guard is stale");
        int open = source.indexOf('{', at);
        assertTrue(open > 0, "no body found for '" + signature + "'");
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(at, i);
                }
            }
        }
        throw new IllegalStateException("unbalanced braces after '" + signature + "'");
    }
}
