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
 * Taking a component out of a carried block: the removal has to be announced, or the client keeps drawing
 * the component it no longer has.
 *
 * <p>The carrier payload lists the hosts riding <b>with their components</b>, so an emptied host simply
 * leaves the list - and absence already means "put down", which the client must keep drawing. The client
 * cannot tell the two apart from absence alone, so the server names the removal in the existing
 * {@code removed} list, the one a break fills ({@code CarriedInsert#removalNotice}).
 *
 * <p>A client told the removal drops the host from all three draw paths - the riding index, the landed
 * records and the fallback list. The wiring guards assert that the production lines fill and carry the
 * notice at all.
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
     * The message shape a retrieval sends without a notice: the emptied host is absent from the host list
     * and nothing is named as removed. The client reads that as "put down" and keeps drawing it.
     */
    @Test
    @DisplayName("the message a retrieval sends without a notice draws the component the player had just taken out")
    void theOldMessageShapeDrawsWhatWasJustTakenOut() {
        CarrierIndex.CarriedView held = holdingTheComponent(LOCAL);
        CarrierIndex carriers = clientHolding(held);

        // The message: the emptied host is absent from the host list, and nothing is named as removed.
        List<CarrierIndex.CarriedView> hostsTheCarrierStillReports = List.of();
        LandedHosts.Forget forget =
                LandedHosts.forget(List.of(held), List.of(), CARRIER);
        List<CarrierIndex.CarriedView> stowed =
                LandedHosts.departed(forget.kept(), hostsTheCarrierStillReports, false);

        // The client reads "absent" as "put down" and keeps the record it was drawing the component from.
        assertEquals(1, stowed.size(), "the client keeps a put-down record of the host it was holding");
        assertEquals(held, stowed.get(0));
        assertNotNull(stowed.get(0).slot(),
                "and the record still holds the component: the frame and the "
                        + "component are both emitted from this one view, so both stay on screen");

        LandedHosts landed = new LandedHosts();
        assertEquals(1, landed.stow(stowed));

        // Drawn every frame: the claim moved the component out of the world store, so no world host is there.
        assertEquals(1, carriers.applyRemovals(CARRIER, List.of(LOCAL)),
                "the carrier payload does drop it from the riding index, which is the half that worked");
        assertEquals(0, carriers.size());
        List<CarrierIndex.Drawable> drawn =
                carriers.drawablesNear(OVERWORLD, ORIGIN, 8, landed, List.of());
        assertEquals(1, drawn.size(), "so the landed record is what is left drawing there");
        assertTrue(drawn.get(0).landed(), "and it draws itself at the position the block came from");
        assertNotNull(drawn.get(0).view().slot(),
                "with the component inside it: the frame and the component are both still displayed");
    }

    /**
     * The same client, told what a break tells it, keeps nothing - in any of the three places a component
     * can be drawn from: the carried index, the landed records, and the fallback list.
     */
    @Test
    @DisplayName("with the removal notice the same message leaves nothing to draw, in any of the three shapes")
    void theNoticeLeavesNothingToDraw() {
        CarrierIndex.CarriedView held = holdingTheComponent(LOCAL);
        CarrierIndex carriers = clientHolding(held);
        List<BlockPos> notice = List.of(LOCAL);

        // ClientHostCache#applyCarrier's own order: the notice, then the stow, then the replacement.
        LandedHosts.Forget forget = LandedHosts.forget(List.of(held), notice, CARRIER);
        assertEquals(List.of(held), forget.forgotten(), "the emptied host is named, so it is dropped");
        assertTrue(forget.kept().isEmpty(), "and it is not one of the hosts that may be stowed");
        assertEquals(0, LandedHosts.departed(forget.kept(), List.of(), false).size(),
                "so nothing becomes a landed record - which is the whole difference from a put-down");

        assertEquals(1, carriers.applyRemovals(CARRIER, notice));
        assertEquals(0, carriers.size(), "gone from the riding index too");

        // A landed record of the same host left by an earlier message is cleared too, so "put down" cannot
        // outlive the notice that says otherwise.
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
        // The fallback list is the caller's own: ClientHostCache#applyForget drops this carrier's local
        // coordinates from it in the same call, so an empty list here is the honest state.
    }

    // ----------------------------------------------------------- the wiring --

    /**
     * Read from the source: the notice is computed from the action and the host's <b>local</b> coordinate
     * and travels on the carrier message, not on the world sync, which is keyed by world position.
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
     * The record is discarded either way; only the item drop is conditional on a non-null slot, so an
     * empty host's removal is announced like any other.
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
                "and the null slot must not be an early return: that is the line that left a removal "
                        + "unannounced");

        int dropped = body.indexOf("Block.popResource");
        int announced = body.indexOf("List.of(destroyed.localPos())");
        assertTrue(dropped >= 0, "the item drop must still be there for a host that holds something");
        assertTrue(announced > dropped, "the notice is written after the drop, as it always was");
        assertFalse(body.substring(dropped, announced).contains("return null;"),
                "and nothing may return between them: the notice is owed by every discarded record, "
                        + "not only by the ones that had an item in them");
    }

    /**
     * The carrier payload is the one message the client reads a removal from, so the third argument must
     * be carried through rather than dropped on the way - which a signature-only guard would miss.
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
                        + "announce no removal at all");
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
                + " (tried " + relative + " and " + absolute + "); this guard reads the source, so it "
                + "must not be skipped silently");
    }
}
