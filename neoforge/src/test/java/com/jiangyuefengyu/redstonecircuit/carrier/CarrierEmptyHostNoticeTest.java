package com.jiangyuefengyu.redstonecircuit.carrier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.jiangyuefengyu.redstonecircuit.data.ComponentType;
import com.jiangyuefengyu.redstonecircuit.data.Slot;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * <b>Taking a component out of a carried block: the message, and the ghost it used to leave behind
 * (P1.43).</b>
 *
 * <h2>The author's report</h2>
 * <em>"Taking the redstone component out of a physicalised block with a bare hand: the frame and the
 * component inside it are still displayed, breaking the block drops nothing, and they are still
 * displayed. The data is right - the component really is gone."</em> Two different things are in that
 * sentence and only one of them is a defect:
 *
 * <ul>
 *   <li><b>breaking an emptied block drops nothing</b> - correct, and it must stay that way: there is
 *       nothing inside to drop. What was <em>not</em> correct is that the removal was never announced,
 *       which is why the same break left the ghost untouched;</li>
 *   <li><b>the frame and the component are still drawn</b> - the defect, and it is not a renderer bug.
 *       The server's record was riding and empty from the first tick (that is what
 *       {@code CarriedHostIndex#removeSlot} writes), the client's copy of it was never told, and the
 *       client is not allowed to guess: it was told nothing, and it had just been told the same shape
 *       means "put down".</li>
 * </ul>
 *
 * <h2>Why the two cases look identical on the wire, and why that is not fixable in the client</h2>
 * A carrier message replaces one structure's host list wholesale, and it lists the hosts that are riding
 * <b>with their components</b> - an empty host is left out of it on purpose
 * ({@code HostSync#carrierPayload}; {@code CarrierEmptyHostTest} pins that decision). So a host that
 * leaves the list has left for one of two reasons:
 *
 * <table>
 *   <caption>Absence means two different things</caption>
 *   <tr><th>why it left</th><th>where the component is</th><th>what the client must do</th></tr>
 *   <tr><td><b>put down</b> (a release)</td><td>back in the world, or on the ground where it landed</td>
 *       <td>keep the record as a <b>landed</b> one and draw it - even over air. This is what makes a
 *       component put down over nothing visible, and dropping it would bring that defect back</td></tr>
 *   <tr><td><b>emptied</b> (this report)</td><td>in the player's inventory</td>
 *       <td>draw it <b>nowhere</b>, and do not keep it under any other identity</td></tr>
 * </table>
 *
 * <p>The client cannot tell them apart from absence alone, so the server has to say it - and it already
 * had the field to say it with: P1.21's {@code removed} list, the one a break fills. A retrieval now
 * fills it too (see {@code CarriedInsert#removalNotice}), so nothing new goes on the wire and the client
 * needs no new branch.
 *
 * <h2>What is pinned here</h2>
 * <ol>
 *   <li><b>The reproduction, in the client's own terms:</b> the message shape a retrieval used to send
 *       (host absent from the list, removal list empty) turns the host the client is holding into a
 *       landed record that still carries the component - the frame and the component are drawn from that
 *       one record, which is why the author saw both. This test passes on the code before the fix and
 *       after it, on purpose: it is the defect, stated where it can be read.</li>
 *   <li><b>The contract:</b> the same message with the host named as removed leaves nothing to draw - not
 *       in the carried index, not as a landed record, and not on the fallback list.</li>
 *   <li><b>The wiring:</b> the two production lines that make those two facts true are asserted against
 *       their own source (the idiom this repository already uses for a decision that cannot be reached
 *       without a running server - see {@code HostBufferTallyTest}). These two fail on the code before
 *       the fix.</li>
 * </ol>
 *
 * <p>Pure where it can be: {@link CarrierIndex} and {@link LandedHosts} name no level, no packet and no
 * renderer, so facts 1 and 2 are decided without a game. Fact 3 reads the source, which is a weaker form
 * of evidence and is labelled as such - what it proves is that the notice is plugged in, not that it
 * arrives.
 */
class CarrierEmptyHostNoticeTest {

    private static final ResourceKey<Level> OVERWORLD =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));

    private static final CarrierId CARRIER = CarrierId.contraption(
            UUID.fromString("00000000-0000-0000-0000-0000000000e1"));

    /** Far from the origin, so "it is drawn at the origin" can never be an accident of the numbers. */
    private static final BlockPos ORIGIN = new BlockPos(2400, 70, -1300);

    /** The host's coordinate inside the structure; the Sable-shaped magnitude a click actually carries. */
    private static final BlockPos LOCAL = new BlockPos(20481032, 127, 20524040);

    /** What the client was holding before the retrieval: the host <b>with</b> its component. */
    private static CarrierIndex.CarriedView holdingTheComponent(BlockPos local) {
        Slot slot = new Slot(ComponentType.DUST);
        slot.power = 15;
        return new CarrierIndex.CarriedView(CARRIER, local, ORIGIN, ORIGIN, slot);
    }

    private static CarrierIndex clientHolding(CarrierIndex.CarriedView held) {
        CarrierIndex carriers = new CarrierIndex();
        carriers.applySnapshot(OVERWORLD, List.of(held),
                Map.of(CARRIER, CarrierPose.of(new Vector3d(100.0, 64.0, 100.0), new Quaterniond())));
        return carriers;
    }

    // ------------------------------------------------------- the defect itself --

    /**
     * <b>The ghost, reproduced.</b>
     *
     * <p>This is the message a retrieval <em>used</em> to send: the carrier payload names the hosts that
     * are riding with their components, so the host the player just emptied is simply not in it, and the
     * removal list is empty because only a break filled it. Everything below is the unchanged client
     * reading that message - which is the point: the client did nothing wrong.
     */
    @Test
    @DisplayName("the message a retrieval used to send draws the component the player had just taken out")
    void theOldMessageShapeDrawsWhatWasJustTakenOut() {
        CarrierIndex.CarriedView held = holdingTheComponent(LOCAL);
        CarrierIndex carriers = clientHolding(held);

        // The message: the emptied host is absent from the host list, and nothing is named as removed.
        List<CarrierIndex.CarriedView> hostsTheCarrierStillReports = List.of();
        LandedHosts.Forget forget =
                LandedHosts.forget(List.of(held), List.of(), CARRIER);
        List<CarrierIndex.CarriedView> stowed =
                LandedHosts.departed(forget.kept(), hostsTheCarrierStillReports, false);

        // The client reads "absent" as "put down": it keeps the record it holds, and that record is the
        // one it was drawing the component from.
        assertEquals(1, stowed.size(), "the client keeps a put-down record of the host it was holding");
        assertEquals(held, stowed.get(0));
        assertNotNull(stowed.get(0).slot(),
                "and the record still holds the component - that is the ghost: the frame and the "
                        + "component are both emitted from this one view, so both stay on screen");

        LandedHosts landed = new LandedHosts();
        assertEquals(1, landed.stow(stowed));

        // And it is drawn every frame, because the world path cannot draw over that position: the claim
        // moved the component out of the world store, so there is no world host there at all.
        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(LOCAL)),
                "the carrier payload does drop it from the riding index, which is the half that worked");
        assertEquals(0, carriers.size());
        List<CarrierIndex.Drawable> drawn =
                carriers.drawablesNear(OVERWORLD, ORIGIN, 8, landed, List.of());
        assertEquals(1, drawn.size(), "so the landed record is what is left drawing there");
        assertTrue(drawn.get(0).landed(), "and it draws itself at the position the block came from");
        assertNotNull(drawn.get(0).view().slot(),
                "with the component inside it: this is exactly the author's 'the frame and the component "
                        + "are still displayed'");
    }

    /**
     * <b>The contract the fix has to satisfy:</b> the same client, told the same thing a break tells it,
     * keeps nothing - in any of the three places a component can be drawn from.
     *
     * <p>Those three are the whole of the "it is still on screen" surface: the carried index (still
     * riding), the landed records (put down and drawn over air), and the fallback list (the carrier's
     * pose went stale, so it is drawn at the block it came from - which is the position the player is
     * standing at, the shape the author described).
     */
    @Test
    @DisplayName("with the removal notice the same message leaves nothing to draw, in any of the three shapes")
    void theNoticeLeavesNothingToDraw() {
        CarrierIndex.CarriedView held = holdingTheComponent(LOCAL);
        CarrierIndex carriers = clientHolding(held);
        List<BlockPos> notice = List.of(LOCAL);

        // `ClientHostCache#applyCarrier`, in its own order: the notice is applied to what the client was
        // holding, the stow is filtered by it, and the wholesale replacement follows.
        LandedHosts.Forget forget = LandedHosts.forget(List.of(held), notice, CARRIER);
        assertEquals(List.of(held), forget.forgotten(), "the emptied host is named, so it is dropped");
        assertTrue(forget.kept().isEmpty(), "and it is not one of the hosts that may be stowed");
        assertEquals(0, LandedHosts.departed(forget.kept(), List.of(), false).size(),
                "so nothing becomes a landed record - which is the whole difference from a put-down");

        assertEquals(1, carriers.applyRemovals(CARRIER, notice));
        assertEquals(0, carriers.size(), "gone from the riding index too");

        // The landed half: nothing is stowed for it now, and a landed record of the same host left by an
        // earlier message is cleared as well - that is what stops a host already kept as "put down" from
        // surviving the notice that says it is not.
        LandedHosts landed = new LandedHosts();
        assertEquals(0, landed.stow(forget.kept()), "and there is nothing to land");
        LandedHosts keptFromBefore = new LandedHosts();
        assertEquals(1, keptFromBefore.stow(List.of(held)),
                "the setup really does hold a landed record of this host");
        assertEquals(1, keptFromBefore.forgetHosts(CARRIER, notice));
        assertEquals(0, keptFromBefore.size());

        assertTrue(carriers.drawablesNear(OVERWORLD, ORIGIN, 8, landed, List.of()).isEmpty(),
                "nothing at all is drawn at that coordinate: not the frame and not the component");
        assertTrue(keptFromBefore.all().isEmpty(),
                "and the landed record that existed is gone too, so neither path can draw it");
        // The third shape - the fallback list - is the caller's own, and the production path drops this
        // carrier's named coordinates from it in the same call (ClientHostCache#applyForget, which is the
        // one place all three deletions are made). An empty list here is therefore the honest state, not
        // a gap in the test.
    }

    // ----------------------------------------------------------- the wiring --

    /**
     * <b>The production line that fills the notice for a retrieval.</b>
     *
     * <p>Read from the source because the decision is taken inside a click handler that needs a running
     * server to reach, and this repository's answer to that is a wiring guard rather than a claim (see
     * {@code HostBufferTallyTest}, {@code CarrierClickRecordFilingTest}). What it proves is narrow and
     * worth stating: the notice is computed from the action and the host's <b>local</b> coordinate, and
     * it is passed to the carrier broadcast - not to the world sync, which is keyed by the projected
     * world position and can therefore never identify a carried host.
     *
     * <p><b>This is one of the two guards that fail before the fix.</b>
     */
    @Test
    @DisplayName("the retrieval is wired to the same host-level removal notice a break sends")
    void theRetrievalIsWiredToTheRemovalNotice() throws IOException {
        String body = methodBody(flat(readMain("carrier", "CarrierInteraction.java")),
                "private static void broadcastPlacementChange");

        assertTrue(body.contains("CarriedInsert.removalNotice(action, hit.localPos())"),
                "the notice must be computed by the placement's own rule, from the action the write "
                        + "returned and the host's local coordinate - a retrieval leaves the host "
                        + "riding and empty, which the carrier payload's host list cannot say");
        assertTrue(body.contains("broadcastCarrierNow(level, hit.carrier(), removed)"),
                "and it must travel on the carrier message, beside the host list: that is the one "
                        + "message the client reads a removal from");
        assertTrue(body.contains("HostSync.broadcast(level, hit.worldPos(), null)"),
                "the world-side write stays: the projected position holds nothing now, and a block "
                        + "standing there has to stop drawing a component it no longer has");
    }

    /**
     * <b>The production line that makes an empty record's removal announced.</b>
     *
     * <p>The break path's notice used to sit behind an early return on a null slot, so the one removal
     * that could be left unannounced was exactly the one a retrieval had just created. The record is
     * discarded either way; only the item is conditional.
     *
     * <p><b>This is the second guard that fails before the fix.</b>
     */
    @Test
    @DisplayName("a discarded record is announced even when it held nothing to drop")
    void anEmptyRecordsRemovalIsAnnounced() throws IOException {
        String body = methodBody(flat(readMain("carrier", "CarrierStore.java")),
                "private static Slot dropCandidate");

        assertTrue(body.contains("List.of(destroyed.localPos())"),
                "the removal notice must name the discarded local coordinate");
        assertTrue(body.contains("if (slot != null)"),
                "the item drop must be the conditional half: nothing is dropped from an empty host");
        assertFalse(body.contains("removed.slot() == null"),
                "and the null slot must not be an early return: that is the line that left the host "
                        + "the client still held unannounced, and it is why breaking an emptied block "
                        + "left the ghost on screen");

        int dropped = body.indexOf("Block.popResource");
        int announced = body.indexOf("List.of(destroyed.localPos())");
        assertTrue(dropped >= 0, "the item drop must still be there for a host that holds something");
        assertTrue(announced > dropped, "the notice is written after the drop, as it always was");
        assertFalse(body.substring(dropped, announced).contains("return null;"),
                "and nothing may return between them: the notice is owed by every discarded record, "
                        + "not only by the ones that had an item in them");
    }

    /**
     * <b>The one broadcast entry that can put a retrieval's notice on the wire.</b>
     *
     * <p>The two facts the client's whole behaviour turns on are "which carrier" and "which local
     * coordinates", and both have to reach {@code HostSync} through the carrier payload - the same
     * message the put-down and the break already use. This asserts the third argument is carried through
     * rather than dropped on the way, which is the failure mode a signature-only guard would miss.
     */
    @Test
    @DisplayName("the carrier broadcast carries the removal list through to the wire")
    void theCarrierBroadcastCarriesTheNotice() throws IOException {
        String body = methodBody(flat(readMain("carrier", "CarrierStore.java")),
                "public static void broadcastCarrierNow(ServerLevel level, CarrierId carrier, "
                        + "List<BlockPos> removed)");

        assertTrue(body.contains("broadcastCarrier(level, carrier, indexOf(level), poseOf(level, "
                        + "carrier), removed"),
                "the notice must be handed to the one carrier broadcast exit, not dropped: an overload "
                        + "that ignored its argument would compile, pass every other test here, and "
                        + "leave the author's ghost exactly where it was");
    }

    // ------------------------------------------------------------------ helpers --

    /** The body of one method, located by brace counting so a guard cannot silently drift. */
    private static String methodBody(String source, String signature) {
        int at = source.indexOf(signature);
        assertTrue(at >= 0, "the source no longer contains '" + signature + "'; this guard is stale");
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
        throw new AssertionError("unbalanced braces after '" + signature + "'");
    }

    /** One main source with its whitespace flattened, so substring guards do not depend on layout. */
    private static String flat(String source) {
        return source.replace("\r\n", "\n").replaceAll("\\s+", " ");
    }

    /** Finds one of the mod's main sources, failing loudly rather than silently skipping the guard. */
    private static String readMain(String packagePath, String fileName) throws IOException {
        Path relative = Path.of("src", "main", "java", "com", "jiangyuefengyu", "redstonecircuit",
                packagePath, fileName);
        Path absolute = Path.of("neoforge").resolve(relative);
        for (Path candidate : List.of(relative, absolute)) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("could not find " + fileName + " from " + Path.of("").toAbsolutePath()
                + " (tried " + relative + " and " + absolute + "); this guard reads the source on "
                + "purpose, so it must not be skipped silently");
    }
}
