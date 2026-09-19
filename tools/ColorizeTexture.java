import java.awt.image.BufferedImage;
import java.io.File;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Colours a greyscale vanilla texture by multiplying it with a colour, keeping its alpha and shading.
 *
 * <p>Vanilla's redstone wire art is pure greyscale - the red comes entirely from a tint applied in code
 * - so a hue shift does nothing to it and a replacement colour would flatten the shading. Multiplying
 * keeps every highlight and shadow exactly where the artist put it, in a new hue.
 *
 * <p>The source is read straight out of the Minecraft client jar so the art cannot drift from the
 * version being built against.
 *
 * <p>Run with: {@code java tools/ColorizeTexture.java <minecraft-client.jar> <entry.png> <output.png> <RRGGBB>}
 * A build-time tool, not part of the mod.
 */
public final class ColorizeTexture {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: ColorizeTexture <minecraft-client.jar> <entry.png> <output.png> <RRGGBB>");
            System.exit(2);
        }
        int rgb = Integer.parseInt(args[3], 16);
        float red = ((rgb >> 16) & 0xFF) / 255.0F;
        float green = ((rgb >> 8) & 0xFF) / 255.0F;
        float blue = (rgb & 0xFF) / 255.0F;

        BufferedImage source;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry entry = zip.getEntry(args[1]);
            if (entry == null) {
                throw new IllegalStateException("no " + args[1] + " in " + args[0]);
            }
            try (java.io.InputStream in = zip.getInputStream(entry)) {
                source = ImageIO.read(in);
            }
        }

        BufferedImage out = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                int r = channel(((argb >> 16) & 0xFF) * red);
                int g = channel(((argb >> 8) & 0xFF) * green);
                int b = channel((argb & 0xFF) * blue);
                out.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }

        File target = new File(args[2]);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(out, "png", target);
        System.out.println("wrote " + target + " (" + out.getWidth() + "x" + out.getHeight()
                + ", colour #" + args[3] + ")");
    }

    private static int channel(float value) {
        return (int) Math.max(0.0F, Math.min(255.0F, value));
    }
}
