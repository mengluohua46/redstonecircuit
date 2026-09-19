import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Recolours a vanilla item texture by shifting its hue, keeping its shading.
 *
 * <p>Item art is a shading study in one colour: vanilla's redstone dust is a single hue at a dozen
 * brightnesses. Shifting the hue and leaving saturation and brightness alone therefore recolours it
 * exactly - every highlight and shadow keeps its relative depth - which a channel swap or a colour
 * replacement cannot do.
 *
 * <p>The source is read straight out of the Minecraft client jar so the art cannot drift from the
 * version being built against.
 *
 * <p>Run with: {@code java tools/RecolorItem.java <minecraft-client.jar> <entry.png> <output.png> <hueShiftDegrees>}
 * A build-time tool, not part of the mod.
 */
public final class RecolorItem {

    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("usage: RecolorItem <minecraft-client.jar> <entry.png> <output.png> <hueShiftDegrees>");
            System.exit(2);
        }
        float shift = Float.parseFloat(args[3]) / 360.0F;

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
        float[] hsb = new float[3];
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                int argb = source.getRGB(x, y);
                int alpha = (argb >>> 24) & 0xFF;
                if (alpha == 0) {
                    out.setRGB(x, y, 0);
                    continue;
                }
                Color.RGBtoHSB((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, hsb);
                // A grey pixel has no hue to shift; leave it grey rather than tinting it.
                float hue = hsb[1] < 0.02F ? hsb[0] : (hsb[0] + shift) % 1.0F;
                int rgb = Color.HSBtoRGB(hue, hsb[1], hsb[2]);
                out.setRGB(x, y, (alpha << 24) | (rgb & 0xFFFFFF));
            }
        }

        File target = new File(args[2]);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(out, "png", target);
        System.out.println("wrote " + target + " (" + out.getWidth() + "x" + out.getHeight()
                + ", hue +" + args[3] + " degrees)");
    }
}
