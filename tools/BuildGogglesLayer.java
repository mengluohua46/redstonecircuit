import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Builds the goggles' head-layer texture from the supplied art.
 *
 * <h2>Why the art has to be moved</h2>
 * An armour layer texture is not one picture: it is a sheet of body parts, and the renderer samples a
 * fixed cell for each one. In the 64x32 layout a helmet uses the head block at (0,0)-(31,15), and the
 * part drawn on the player's <em>face</em> is the 8x8 cell at (8,8)-(15,15). The supplied art sits at
 * (16,23)-(31,27), which in this sheet is the chest - so copying the file straight in would put the
 * goggles on the player's torso. It is therefore lifted onto the face cell, and halved horizontally
 * (16px of art into the 8px the face is wide) so the whole pair of lenses fits.
 *
 * <p>Run with: {@code java tools/BuildGogglesLayer.java <art.png> <output.png>}
 * A build-time tool, not part of the mod.
 */
public final class BuildGogglesLayer {

    /** Face cell of the head block: the 8x8 square the helmet model samples for the front. */
    private static final int FACE_X = 8;
    private static final int FACE_Y = 8;
    private static final int FACE_SIZE = 8;

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: BuildGogglesLayer <art.png> <output.png>");
            System.exit(2);
        }
        BufferedImage art = ImageIO.read(new File(args[0]));

        int minX = art.getWidth();
        int minY = art.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < art.getHeight(); y++) {
            for (int x = 0; x < art.getWidth(); x++) {
                if (opaque(art, x, y)) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            throw new IllegalStateException("the art has no opaque pixels");
        }
        int artWidth = maxX - minX + 1;
        int artHeight = maxY - minY + 1;
        System.out.println("art occupies " + artWidth + "x" + artHeight
                + " at (" + minX + "," + minY + ")");

        BufferedImage out = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        // Halved horizontally when it is wider than the face, which is the usual case: the art was
        // drawn at item size, and a face is only eight pixels across.
        float scaleX = artWidth > FACE_SIZE ? (float) FACE_SIZE / artWidth : 1.0F;
        int drawnWidth = Math.min(FACE_SIZE, Math.round(artWidth * scaleX));
        int drawnHeight = Math.min(FACE_SIZE, artHeight);
        int offsetX = FACE_X + (FACE_SIZE - drawnWidth) / 2;
        // Vertically centred too, so the goggles sit on the middle of the face rather than on the chin.
        int offsetY = FACE_Y + (FACE_SIZE - drawnHeight) / 2;

        for (int y = 0; y < drawnHeight; y++) {
            for (int x = 0; x < drawnWidth; x++) {
                // Nearest neighbour, and "any opaque source pixel wins" when shrinking: a thin dark
                // outline must not disappear, or the lenses lose their shape.
                int sourceX = minX + Math.min(artWidth - 1, (int) (x / scaleX));
                int sourceY = minY + Math.min(artHeight - 1, y);
                int argb = art.getRGB(sourceX, sourceY);
                if (scaleX < 1.0F) {
                    int secondX = minX + Math.min(artWidth - 1, (int) (((x + 1) / scaleX) - 1));
                    int second = art.getRGB(secondX, sourceY);
                    argb = moreOpaque(argb, second);
                }
                if (((argb >>> 24) & 0xFF) > 8) {
                    out.setRGB(offsetX + x, offsetY + y, argb);
                }
            }
        }

        File target = new File(args[1]);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(out, "png", target);
        System.out.println("wrote " + target + " with the art at ("
                + offsetX + "," + offsetY + ") size " + drawnWidth + "x" + drawnHeight);
    }

    private static boolean opaque(BufferedImage image, int x, int y) {
        return ((image.getRGB(x, y) >>> 24) & 0xFF) > 8;
    }

    private static int moreOpaque(int first, int second) {
        return ((first >>> 24) & 0xFF) >= ((second >>> 24) & 0xFF) ? first : second;
    }
}
