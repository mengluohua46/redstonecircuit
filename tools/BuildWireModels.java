import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Copies vanilla's redstone wire models, adding a render type to each.
 *
 * <h2>Why this is needed at all</h2>
 * Vanilla gives redstone wire the cutout layer through a hardcoded table keyed by the block instance
 * ({@code ItemBlockRenderTypes}), which a new block cannot join. Without a layer, the block is drawn
 * solid - and a solid pass ignores alpha, so every transparent texel in the dust art comes out black.
 * That is what made a placed superconducting wire look like a black smear.
 *
 * <p>NeoForge's documented answer is to say so in the model: {@code "render_type": "minecraft:cutout"}.
 * The models are copied from the client jar rather than hand-written, and the only edits are that field
 * for the ones that draw the wire's line, and the texture names, which point at the recoloured copies.
 *
 * <p>Run with: {@code java tools/BuildWireModels.java <minecraft-client.jar> <output-dir>}
 * A build-time tool, not part of the mod.
 */
public final class BuildWireModels {

    /** Vanilla model -> the output name, and the texture substitutions applied to it. */
    private static final String[][] MODELS = {
            { "redstone_dust_dot", "dust_dot" },
            { "redstone_dust_side", "dust_side" },
            { "redstone_dust_side_alt", "dust_side_alt" },
            { "redstone_dust_up", "dust_up" },
    };

    /** The four thin wrappers that pick one of the two line textures. */
    private static final String[][] SIDES = {
            { "redstone_dust_side0", "dust_side0", "line0" },
            { "redstone_dust_side1", "dust_side1", "line1" },
            { "redstone_dust_side_alt0", "dust_side_alt0", "line0" },
            { "redstone_dust_side_alt1", "dust_side_alt1", "line1" },
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: BuildWireModels <minecraft-client.jar> <output-dir>");
            System.exit(2);
        }
        File output = new File(args[1]);
        output.mkdirs();
        try (ZipFile zip = new ZipFile(args[0])) {
            for (String[] model : MODELS) {
                write(output, "superconducting_" + model[1] + ".json",
                        rewrite(read(zip, "assets/minecraft/models/block/" + model[0] + ".json")));
            }
            for (String[] side : SIDES) {
                String json = read(zip, "assets/minecraft/models/block/" + side[0] + ".json");
                // The wrapper only chooses a line texture and inherits the rest, so it is flattened here:
                // the parent is the model written just above, and the render type comes with it.
                json = json.replace("\"parent\": \"block/redstone_dust_side\"",
                                "\"parent\": \"redstonecircuit:block/superconducting_dust_side\"")
                        .replace("\"parent\": \"block/redstone_dust_side_alt\"",
                                "\"parent\": \"redstonecircuit:block/superconducting_dust_side_alt\"")
                        .replace("block/redstone_dust_line0",
                                "redstonecircuit:block/superconducting_dust_line0")
                        .replace("block/redstone_dust_line1",
                                "redstonecircuit:block/superconducting_dust_line1");
                // The render type is repeated here rather than left to the parent: whether a model
                // inherits it is a loader detail, and the cost of being wrong is the black wire again -
                // for the connecting lines this time, which are the parts a player looks at most.
                json = json.replaceFirst("\\{", "{\n    \"render_type\": \"minecraft:cutout\",");
                write(output, "superconducting_" + side[1] + ".json", json);
            }
        }
        System.out.println("wrote the wire models into " + output);
    }

    /** Swaps the wire's own art for the recoloured copies and names the render type. */
    private static String rewrite(String json) {
        return json.replace("\"ambientocclusion\"", "\"render_type\": \"minecraft:cutout\",\n    \"ambientocclusion\"")
                .replace("block/redstone_dust_dot", "redstonecircuit:block/superconducting_dust_dot")
                .replace("block/redstone_dust_line0", "redstonecircuit:block/superconducting_dust_line0")
                .replace("block/redstone_dust_line1", "redstonecircuit:block/superconducting_dust_line1");
        // The overlay is deliberately left as vanilla's: it is the pale shine drawn over the line, and it
        // carries no tint of its own.
    }

    private static String read(ZipFile zip, String entry) throws Exception {
        ZipEntry found = zip.getEntry(entry);
        if (found == null) {
            throw new IllegalStateException("no " + entry + " in the client jar");
        }
        try (java.io.InputStream in = zip.getInputStream(found)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void write(File dir, String name, String json) throws Exception {
        Files.writeString(new File(dir, name).toPath(), json, StandardCharsets.UTF_8);
    }
}
