import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/**
 * Generates the GameTest structure template used by InnerRedstoneGameTest.
 *
 * <p>Pure JDK on purpose: the file must be byte-exact NBT, and pulling Minecraft's own NBT writer
 * in would drag the whole game classpath along.
 *
 * <p>Layout: a completely empty 5x3x5 box of air. Tests place every block they need themselves.
 *
 * <p>The template is deliberately empty rather than pre-filled with floor or walls. Two earlier
 * versions were not, and both produced false failures:
 * <ul>
 *   <li>a barrier shell around the test area made vanilla pistons refuse to extend, because
 *       {@code PistonStructureResolver} rejects a move that would shove the piston itself;</li>
 *   <li>a stone floor did the same, because the stone a piston wanted to push had the floor behind
 *       it and the push chain could not resolve.</li>
 * </ul>
 * Both looked exactly like "inner redstone does not power pistons". A control test using a vanilla
 * redstone block is included in the suite to catch this class of mistake in future.
 *
 * <p>Run: java MakeTemplate.java &lt;output.nbt&gt;
 */
public final class MakeTemplate {

    private static final int WIDTH = 5;
    private static final int HEIGHT = 3;

    private static final String[] PALETTE = {
            "minecraft:air"
    };

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);

        // No blocks at all: a pure air volume.
        List<int[]> blocks = new ArrayList<>();

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream data = new DataOutputStream(raw)) {
            data.writeByte(0x0A);          // TAG_Compound
            writeName(data, "");           // root name

            // TAG_List("size") of TAG_Int, length 3
            data.writeByte(0x09);
            writeName(data, "size");
            data.writeByte(0x03);
            data.writeInt(3);
            data.writeInt(WIDTH);
            data.writeInt(HEIGHT);
            data.writeInt(WIDTH);

            // TAG_List("entities") of TAG_Compound, empty
            data.writeByte(0x09);
            writeName(data, "entities");
            data.writeByte(0x0A);
            data.writeInt(0);

            // TAG_List("blocks") of TAG_Compound
            data.writeByte(0x09);
            writeName(data, "blocks");
            data.writeByte(0x0A);
            data.writeInt(blocks.size());

            // TAG_List("palette") of TAG_Compound: a structure with no blocks still needs the air
            // entry to be considered valid.
            data.writeByte(0x09);
            writeName(data, "palette");
            data.writeByte(0x0A);
            data.writeInt(PALETTE.length);
            for (String name : PALETTE) {
                data.writeByte(0x08); // TAG_String
                writeName(data, "Name");
                writeName(data, name);
                data.writeByte(0x00);
            }

            // TAG_Int("DataVersion") for 1.21.1
            data.writeByte(0x03);
            writeName(data, "DataVersion");
            data.writeInt(3953);

            data.writeByte(0x00); // end of root compound
        }

        byte[] bytes = raw.toByteArray();
        Files.createDirectories(out.getParent());
        try (var fileOut = new FileOutputStream(out.toFile());
             var gz = new GZIPOutputStream(fileOut)) {
            gz.write(bytes);
        }

        System.out.println("size    = " + WIDTH + "x" + HEIGHT + "x" + WIDTH);
        System.out.println("blocks  = " + blocks.size());
        System.out.println("raw     = " + bytes.length + " bytes");
        System.out.println("gzipped = " + Files.size(out) + " bytes");
        System.out.println("written = " + out.toAbsolutePath());
    }

    private static void writeName(DataOutputStream out, String name) throws IOException {
        byte[] utf8 = name.getBytes(StandardCharsets.UTF_8);
        out.writeShort(utf8.length);
        out.write(utf8);
    }
}
