package com.jiangyuefengyu.redstonecircuit.blueprint;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Where a client keeps the box it just exported, until the same file is uploaded to a server.
 *
 * <p>Exporting and uploading are two separate actions: the quill writes {@code schematics/<name>.nbt} locally, and the
 * schematic table uploads that file on a later click. The client cannot write the components itself (there is no store
 * on a client), so the only thing that can travel is the selection, and by then the box is no longer on screen - it is
 * remembered here, keyed by the file name the upload names.
 *
 * <p>A miss is ordinary and harmless: a blueprint saved in an earlier session, or copied in from elsewhere, has no
 * remembered box, and the upload then simply carries whatever the file already carries.
 */
public final class BlueprintLocalSaves {

    /** One exported file and the box it was taken from. */
    public record Save(String fileName, ResourceKey<Level> dimension, BlockPos first, BlockPos second) {
    }

    /** The last export of this client. Exporting again replaces it, so at most one is ever held. */
    @Nullable
    private static volatile Save last;

    private BlueprintLocalSaves() {
    }

    public static void remember(String fileName, ResourceKey<Level> dimension, BlockPos first, BlockPos second) {
        if (fileName == null || dimension == null || first == null || second == null) {
            return;
        }
        last = new Save(fileName, dimension, first, second);
    }

    /** The remembered box for that exact file, or {@code null} when this client never exported it. */
    @Nullable
    public static Save find(String fileName) {
        Save save = last;
        return save != null && save.fileName().equals(fileName) ? save : null;
    }
}
