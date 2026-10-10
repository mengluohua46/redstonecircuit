package com.jiangyuefengyu.redstonecircuit.client;

import java.util.HashSet;
import java.util.Set;

import com.jiangyuefengyu.redstonecircuit.RCConfig;
import com.jiangyuefengyu.redstonecircuit.structure.StructurePreview;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * One line per preview a client actually draws, and never more than one per preview per place it is drawn.
 *
 * <p>A renderer cannot be checked without a running game - no GameTest has a client and there is no headless way to
 * look at a frame - so what this pins is that the client found a preview, resolved it and drew cells from it, at the
 * debug level the test packages run with. Once per hash and site, not once per frame: the site is part of the key
 * because "in the hand" and "inside the placed block" are two different claims.
 */
@OnlyIn(Dist.CLIENT)
final class StructureRenderLog {

    /** Past this many distinct previews in one client session, the "already logged" set starts over. */
    private static final int SEEN_LIMIT = 1024;

    private static final Set<Long> SEEN = new HashSet<>();

    static void drew(StructurePreview preview, int cells, String where) {
        long key = ((long) preview.hash() << 8) | (where.hashCode() & 0xFFL);
        if (!RCConfig.debugLog() || !SEEN.add(key)) {
            return;
        }
        if (SEEN.size() > SEEN_LIMIT) {
            SEEN.clear();
        }
        RCConfig.LOGGER.info("[redstonecircuit] structure preview: drawing {} cell(s) of {}x{}x{} at scale {} "
                        + "{} (palette {}, {} byte(s) on the wire, {} preview cell(s))",
                cells, preview.size().getX(), preview.size().getY(), preview.size().getZ(), preview.scale(),
                where, preview.palette().size(), preview.wireBytes(), preview.cellCount());
    }

    private StructureRenderLog() {
    }
}
