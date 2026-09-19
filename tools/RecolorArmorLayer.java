import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Builds the goggles' head-layer texture out of vanilla's leather helmet.
 *
 * <p>An {@code ArmorMaterial} must name a texture, and an item that occupies the head slot is drawn
 * with it; there is no way to opt out. Rather than ship a missing-texture helmet - or hand-draw a 64x32
 * skin layout - the leather helmet's shape is kept and its brown palette is mapped onto a red one, so
 * what appears on the player's head reads as the goggles' item texture.
 *
 * <p>Run with: {@code java tools/RecolorArmorLayer.java <minecraft-client.jar> <output.png>}
 * It is a build-time tool, not part of the mod.
 */
public final class RecolorArmorLayer {

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: RecolorArmorLayer <minecraft-client.jar> <output.png>");
            System.exit(2);
        }
        BufferedImage source;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry entry = zip.getEntry("assets/minecraft/textures/models/armor/leather_layer_1.png");
            if (entry == null) {
                throw new IllegalStateException("no leather_layer_1.png in " + args[0]);
            }
            try (InputStream in = zip.getInputStream(entry)) {
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
                int red = (argb >> 16) & 0xFF;
                int green = (argb >> 8) & 0xFF;
                int blue = argb & 0xFF;
                float luminance = (0.299F * red + 0.587F * green + 0.114F * blue) / 255.0F;
                // Leather is a brown ramp: its shadows are the helmet's outlines and its highlights are
                // the rim light. Both are kept, mapped onto dark red -> bright red.
                int r = clamp(70 + 185 * luminance);
                int g = clamp(70 * luminance * luminance);
                int b = clamp(60 * luminance * luminance);
                out.setRGB(x, y, (alpha << 24) | (r << 16) | (g << 8) | b);
            }
        }

        File target = new File(args[1]);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(out, "png", target);
        System.out.println("wrote " + target + " (" + out.getWidth() + "x" + out.getHeight() + ")");
    }

    private static int clamp(float value) {
        return (int) Math.max(0, Math.min(255, value));
    }
}
