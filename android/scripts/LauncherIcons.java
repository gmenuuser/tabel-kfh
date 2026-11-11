import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Draws the legacy launcher PNGs (`mipmap-*dpi/ic_launcher.png`, `ic_launcher_round.png`) from the iPhone icon's
 * geometry (`TabelKFH/Resources/Assets.xcassets/AppIcon.appiconset/icon-1024.png`: a white «T» and an orange
 * underline on dark green #1F5D3A). On API 26+ the adaptive icon (`mipmap-anydpi-v26`, vector foreground) is used
 * instead; these are the fallback for tools and launchers that ask for the bitmap.
 *
 *   cd android && java scripts/LauncherIcons.java app/src/main/res
 */
public class LauncherIcons {
    static final Color GREEN = new Color(0x1F5D3A);
    static final Color ORANGE = new Color(0xE8892B);

    public static void main(String[] args) throws Exception {
        File res = new File(args.length > 0 ? args[0] : "app/src/main/res");
        String[] dpi = {"mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"};
        int[] size = {48, 72, 96, 144, 192};
        for (int i = 0; i < dpi.length; i++) {
            File dir = new File(res, "mipmap-" + dpi[i]);
            dir.mkdirs();
            ImageIO.write(square(size[i]), "png", new File(dir, "ic_launcher.png"));
            ImageIO.write(round(size[i]), "png", new File(dir, "ic_launcher_round.png"));
            System.out.println(dir + ": " + size[i] + " px");
        }
    }

    /** The iPhone icon as is (rounded corners, a little transparent margin as legacy icons have). */
    static BufferedImage square(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        double inset = n * 0.04, side = n - 2 * inset;
        g.setColor(GREEN);
        g.fill(new RoundRectangle2D.Double(inset, inset, side, side, side * 0.22, side * 0.22));
        mark(g, inset + side / 2, inset + side / 2, side / 1024.0);
        g.dispose();
        return img;
    }

    /** The same on a circle, the mark a little smaller so its corners stay inside. */
    static BufferedImage round(int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = graphics(img);
        double inset = n * 0.04, d = n - 2 * inset;
        g.setColor(GREEN);
        g.fill(new Ellipse2D.Double(inset, inset, d, d));
        mark(g, n / 2.0, n / 2.0, d / 1024.0 * 0.84);
        g.dispose();
        return img;
    }

    /**
     * The «T» and the underline in the 1024-unit coordinates of the iPhone icon (bar 262..762 × 300..400, stem
     * 462..562 × 400..760, underline 262..762 × 800..830), centred on (cx, cy) at [k] px per unit.
     */
    static void mark(Graphics2D g, double cx, double cy, double k) {
        double ox = cx - 512 * k, oy = cy - 512 * k; // the whole 1024 canvas centred: the mark sits where the iPhone's does
        g.setColor(Color.WHITE);
        g.fill(new Rectangle2D.Double(ox + 262 * k, oy + 300 * k, 500 * k, 100 * k));
        g.fill(new Rectangle2D.Double(ox + 462 * k, oy + 400 * k, 100 * k, 360 * k));
        g.setColor(ORANGE);
        g.fill(new Rectangle2D.Double(ox + 262 * k, oy + 800 * k, 500 * k, 30 * k));
    }

    static Graphics2D graphics(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }
}
