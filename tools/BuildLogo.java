import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Draws the mod's project icon, and the logo shown in the game's mod list.
 *
 * <h2>What it draws, and why not a photo of the mod</h2>
 * Both Modrinth and CurseForge show a square icon at sizes from 32px to 256px, so the picture has to
 * read at 32px: a grey block, the redstone line inside it, and the goggles that let you see it. All of
 * that is drawn here from flat shapes - only the goggles sprite is real art, and it is the mod author's
 * own - so the icon carries no third-party pixels.
 *
 * <p>Written as a tool rather than drawn by hand so it can be regenerated at any size, and so a change
 * to the mod's colours can be reflected in one command.
 *
 * <p>Run with: {@code java tools/BuildLogo.java <goggles.png> <output.png>}
 * A build-time tool, not part of the mod.
 */
public final class BuildLogo {

    /** The canvas both sites want; everything scales from it. */
    private static final int SIZE = 256;

    private static final Color BACK_TOP = new Color(0x2E2E33);
    private static final Color BACK_BOTTOM = new Color(0x17171A);
    private static final Color FRAME = new Color(0x8E1B10);
    private static final Color BLOCK = new Color(0x8B8B8B);
    private static final Color BLOCK_EDGE = new Color(0x5D5D5D);
    private static final Color WIRE = new Color(0xFF8C00);
    private static final Color WIRE_HOT = new Color(0xFFC15A);

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: BuildLogo <goggles.png> <output.png>");
            System.exit(2);
        }
        BufferedImage goggles = ImageIO.read(new File(args[0]));

        BufferedImage canvas = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        g.setPaint(new GradientPaint(0, 0, BACK_TOP, 0, SIZE, BACK_BOTTOM));
        g.fillRect(0, 0, SIZE, SIZE);
        g.setColor(FRAME);
        g.setStroke(new BasicStroke(6));
        g.drawRect(6, 6, SIZE - 13, SIZE - 13);

        int blockFrom = 44;
        int blockTo = SIZE - 44;
        g.setColor(BLOCK);
        g.fillRect(blockFrom, blockFrom, blockTo - blockFrom, blockTo - blockFrom);
        g.setColor(BLOCK_EDGE);
        g.setStroke(new BasicStroke(8));
        g.drawRect(blockFrom, blockFrom, blockTo - blockFrom, blockTo - blockFrom);
        speckle(g, blockFrom, blockTo);

        // Two pieces of wire running into the block from either side, behind the goggles: the orange is
        // what makes the icon read as redstone at a glance, and it stays visible past the goggles.
        int centre = SIZE / 2;
        int thickness = 16;
        g.setColor(WIRE);
        g.fillRect(blockFrom + 8, centre - thickness / 2, 46, thickness);
        g.fillRect(blockTo - 54, centre - thickness / 2, 46, thickness);
        g.setColor(WIRE_HOT);
        g.fillRect(blockFrom + 8, centre - thickness / 2, 46, 5);
        g.fillRect(blockTo - 54, centre + thickness / 2 - 5, 46, 5);

        // The goggles, large and centred, over everything: they are what the mod is about.
        int scale = 10;
        int drawn = goggles.getWidth() * scale;
        int offset = (SIZE - drawn) / 2;
        g.drawImage(goggles, offset, offset, drawn, drawn, null);

        g.dispose();

        File target = new File(args[1]);
        if (target.getParentFile() != null) {
            target.getParentFile().mkdirs();
        }
        ImageIO.write(canvas, "png", target);
        System.out.println("wrote " + target + " (" + SIZE + "x" + SIZE + ")");
    }

    /** A few darker specks, so the block does not read as a flat grey square at 32px. */
    private static void speckle(Graphics2D g, int from, int to) {
        Random random = new Random(20260919L);
        g.setColor(new Color(0x7A7A7A));
        for (int i = 0; i < 90; i++) {
            int size = 4 + random.nextInt(8);
            g.fillRect(from + 6 + random.nextInt(to - from - 12),
                    from + 6 + random.nextInt(to - from - 12), size, size);
        }
    }

}
