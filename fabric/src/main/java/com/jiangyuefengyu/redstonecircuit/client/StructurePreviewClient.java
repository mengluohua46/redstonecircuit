package com.jiangyuefengyu.redstonecircuit.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.network.StructurePreviews;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

/**
 * The client's copy of the borders: one list per dimension, replaced whole by every snapshot.
 *
 * <p>The server sends the dimension's whole list ({@code PreviewSnapshotPayload}), so there is no bookkeeping to get
 * wrong - no "add one", no "remove one" and no ordering to depend on. A message that is lost, duplicated or overtaken
 * leaves exactly the same picture: the last list that arrived. Nothing else is held, because a preview is position and
 * size and no structure data ever crosses the wire, which is what lets a client show a border for a controller whose
 * chunk it has never loaded.
 *
 * <p>One log line per <b>change</b>, never per frame - the same rule {@link StructureRenderLog} exists for. It is the
 * only way to tell "the server stopped sending borders" from "the renderer stopped drawing them" without a debugger.
 *
 * <p>The snapshot itself is handed in from the receiver the client entrypoint registers for it, next to the other
 * client-bound ones; {@link #register()} carries the one hook this class owns, because leaving a world has to empty
 * these maps whichever way the client got there.
 */
@Environment(EnvType.CLIENT)
public final class StructurePreviewClient {

    /** The borders of each dimension, as the last snapshot said. */
    private static final Map<ResourceKey<Level>, List<StructurePreviews.Entry>> BORDERS = new HashMap<>();

    /** What was last written about each dimension, so a static screen writes nothing. */
    private static final Map<ResourceKey<Level>, String> LAST_LOGGED = new HashMap<>();

    /** A snapshot arrived: this dimension's borders are now exactly these. */
    public static void accept(ResourceKey<Level> dimension, List<StructurePreviews.Entry> entries) {
        if (entries.isEmpty()) {
            BORDERS.remove(dimension);
        } else {
            BORDERS.put(dimension, entries);
        }
        String line = describe(entries);
        String previous = LAST_LOGGED.put(dimension, line);
        if (!line.equals(previous) && RCConfig.debugLog()) {
            RCConfig.LOGGER.info("[redstonecircuit] structure border: {} border(s) in {}: {}", entries.size(),
                    dimension.location(), line);
        }
    }

    /** The borders to draw in a dimension. Empty when the player is nowhere, or there are none. */
    public static List<StructurePreviews.Entry> borders(@Nullable ResourceKey<Level> dimension) {
        return dimension == null ? List.of() : BORDERS.getOrDefault(dimension, List.of());
    }

    /** The whole state, as one line: what the log prints, and what the renderer draws. */
    static String describe(List<StructurePreviews.Entry> entries) {
        if (entries.isEmpty()) {
            return "none";
        }
        StringBuilder line = new StringBuilder();
        for (StructurePreviews.Entry entry : entries) {
            if (line.length() > 0) {
                line.append("; ");
            }
            line.append("controller ").append(entry.controller().toShortString()).append(' ')
                    .append(entry.release() ? "release" : "capture").append(' ')
                    .append(entry.region().describe())
                    .append(entry.armed()
                            ? " CONFIRMED - the next operation does it"
                            : " previewing - nothing has happened yet");
            if (entry.release() && entry.overwrite() > 0) {
                line.append(" (").append(entry.overwrite()).append(" block(s) in the way)");
            }
        }
        return line.toString();
    }

    /**
     * A client that has left the server is showing no borders.
     *
     * <p>The next server sends its own list, so this is not strictly needed - but a stale border drawn on the
     * title screen, for a world that is not loaded, is exactly the kind of "is this a bug?" a player would be
     * right to report.
     */
    public static void onLoggingOut() {
        BORDERS.clear();
        LAST_LOGGED.clear();
    }

    /**
     * Binds this class's one callback; called once, from the client entrypoint.
     *
     * <p>Fabric's disconnect callback carries no event object - the connection it describes is already gone - so the
     * body is {@link #onLoggingOut()} with no arguments and this registration is the whole of it. Nothing else here
     * needs a hook: a snapshot is a packet, and packets are routed by the receiver the entrypoint registers.
     */
    public static void register() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLoggingOut());
    }

    private StructurePreviewClient() {
    }
}
