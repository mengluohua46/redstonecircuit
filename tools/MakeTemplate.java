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
 * <p>Layout: a 3x3x3 box with a stone floor, air interior on the middle layer, and a barrier shell
 * on the top layer plus the four sides of the middle layer. Tests overwrite whatever they need.
 *
 * <p>Run: java MakeTemplate.java &lt;output.nbt&gt;
 */
public final class MakeTemplate {

    private static final String[] PALETTE = {
            "minecraft:air",
            "minecraft:stone",
            "minecraft:barrier"
    };

    public static void main(String[] args) throws Exception {
        Path out = Path.of(args[0]);

        // Collect the non-air blocks: stone floor, barrier shell around the middle layer.
        List<int[]> blocks = new ArrayList<>(); // x, y, z, state
        for (int x = 0; x < 3; x++) {
            for (int y = 0; y < 3; y++) {
                for (int z = 0; z < 3; z++) {
                    int state;
                    if (y == 0) {
                        state = 1; // stone floor
                    } else if (y == 2 || x == 0 || x == 2 || z == 0 || z == 2) {
                        state = 2; // barrier shell
                    } else {
                        state = 0; // air interior
                    }
                    if (state != 0) {
                        blocks.add(new int[] { x, y, z, state });
                    }
                }
            }
        }

        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out2 = new DataOutputStream(raw)) {
            out2.writeByte(0x0A);          // TAG_Compound
            writeName(out2, "");           // root name

            // TAG_List("size") of TAG_Int, length 3
            out2.writeByte(0x09);
            writeName(out2, "size");
            out2.writeByte(0x03);
            out2.writeInt(3);
            out2.writeInt(3);
            out2.writeInt(3);
            out2.writeInt(3);

            // TAG_List("entities") of TAG_Compound, empty
            out2.writeByte(0x09);
            writeName(out2, "entities");
            out2.writeByte(0x0A);
            out2.writeInt(0);

            // TAG_List("blocks") of TAG_Compound
            out2.writeByte(0x09);
            writeName(out2, "blocks");
            out2.writeByte(0x0A);
            out2.writeInt(blocks.size());
            for (int[] b : blocks) {
                // TAG_List("pos") of TAG_Int, length 3
                out2.writeByte(0x09);
                writeName(out2, "pos");
                out2.writeByte(0x03);
                out2.writeInt(3);
                out2.writeInt(b[0]);
                out2.writeInt(b[1]);
                out2.writeInt(b[2]);

                // TAG_Int("state")
                out2.writeByte(0x03);
                writeName(out2, "state");
                out2.writeInt(b[3]);

                out2.writeByte(0x00); // end of this block compound
            }

            // TAG_List("palette") of TAG_Compound
            out2.writeByte(0x09);
            writeName(out2, "palette");
            out2.writeByte(0x0A);
            out2.writeInt(PALETTE.length);
            for (String name : PALETTE) {
                out2.writeByte(0x08); // TAG_String
                writeName(out2, "Name");
                writeName(out2, name);
                out2.writeByte(0x00);
            }

            // TAG_Int("DataVersion") for 1.21.1
            out2.writeByte(0x03);
            writeName(out2, "DataVersion");
            out2.writeInt(3953);

            out2.writeByte(0x00); // end of root compound
        }

        byte[] bytes = raw.toByteArray();
        Files.createDirectories(out.getParent());
        try (var fileOut = new FileOutputStream(out.toFile());
             var gz = new GZIPOutputStream(fileOut)) {
            gz.write(bytes);
        }

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
