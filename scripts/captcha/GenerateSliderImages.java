import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;
import javax.imageio.ImageIO;

/**
 * Draws the slider captcha backgrounds (320x180 scenes) and extra cut-out shapes (110x110 tianai
 * template pairs) that ship inside the jar. Deterministic: rerun to reproduce the committed files.
 *
 * <pre>java scripts/captcha/GenerateSliderImages.java backend/shop-app/src/main/resources/captcha/slider</pre>
 */
public class GenerateSliderImages {
  static final int W = 320, H = 180;

  public static void main(String[] args) throws Exception {
    File root = new File(args.length > 0 ? args[0] : "backend/shop-app/src/main/resources/captcha/slider");
    File bg = new File(root, "backgrounds");
    bg.mkdirs();
    String[] scenes = {
      "sunset-mountains", "ocean-waves", "city-skyline", "pine-forest", "desert-dunes", "aurora",
      "rice-terraces", "lake-reflection", "starry-night", "geometric-tiles", "flower-field", "snow-peaks"
    };
    for (int i = 0; i < scenes.length; i++) {
      var img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
      var g = img.createGraphics();
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      draw(scenes[i], g, new Random(1000 + i));
      g.dispose();
      ImageIO.write(img, "png", new File(bg, String.format("bg-%02d-%s.png", i + 1, scenes[i])));
    }
    File tpl = new File(root, "templates");
    String[] shapes = {"circle", "rounded-square", "star", "hexagon", "heart", "shield"};
    for (String s : shapes) template(new File(tpl, s), shape(s));
  }

  // ------------------------------------------------------------------ backgrounds

  static void sky(Graphics2D g, Color top, Color bottom, int horizon) {
    g.setPaint(new GradientPaint(0, 0, top, 0, horizon, bottom));
    g.fillRect(0, 0, W, horizon);
  }

  static Path2D ridge(Random r, int base, int amp, int step) {
    var p = new Path2D.Double();
    p.moveTo(0, H);
    double y = base;
    for (int x = 0; x <= W + step; x += step) {
      y = Math.max(base - amp, Math.min(base + amp / 3.0, y + r.nextGaussian() * amp / 3));
      p.lineTo(x, y);
    }
    p.lineTo(W, H);
    p.closePath();
    return p;
  }

  static Color mix(Color a, Color b, double t) {
    return new Color(
        (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
        (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
        (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
  }

  static void draw(String scene, Graphics2D g, Random r) {
    switch (scene) {
      case "sunset-mountains" -> {
        sky(g, new Color(255, 120, 80), new Color(255, 210, 120), H);
        g.setColor(new Color(255, 235, 170));
        g.fillOval(200, 70, 60, 60);
        Color[] layers = {new Color(150, 80, 110), new Color(110, 55, 95), new Color(70, 35, 70)};
        for (int i = 0; i < 3; i++) {
          g.setColor(layers[i]);
          g.fill(ridge(r, 95 + i * 25, 35, 16));
        }
      }
      case "ocean-waves" -> {
        sky(g, new Color(120, 190, 240), new Color(210, 235, 250), 70);
        for (int y = 70; y < H; y += 6) {
          g.setColor(mix(new Color(40, 120, 180), new Color(10, 60, 110), (y - 70) / 110.0));
          g.fillRect(0, y, W, 6);
          g.setColor(new Color(255, 255, 255, 90));
          for (int x = -20; x < W; x += 24 + r.nextInt(16))
            g.draw(new Arc2D.Double(x + r.nextInt(8), y, 22, 8, 0, 180, Arc2D.OPEN));
        }
      }
      case "city-skyline" -> {
        sky(g, new Color(30, 40, 90), new Color(240, 140, 90), H);
        for (int x = 0; x < W; ) {
          int w = 16 + r.nextInt(26), h = 40 + r.nextInt(100);
          g.setColor(new Color(25 + r.nextInt(20), 25 + r.nextInt(20), 45 + r.nextInt(25)));
          g.fillRect(x, H - h, w, h);
          g.setColor(new Color(255, 220, 120, 200));
          for (int wy = H - h + 6; wy < H - 6; wy += 9)
            for (int wx = x + 3; wx < x + w - 4; wx += 7) if (r.nextInt(3) == 0) g.fillRect(wx, wy, 3, 4);
          x += w + 2;
        }
      }
      case "pine-forest" -> {
        sky(g, new Color(170, 210, 230), new Color(230, 240, 235), H);
        for (int row = 0; row < 4; row++) {
          Color c = mix(new Color(90, 140, 120), new Color(20, 70, 45), row / 3.0);
          for (int i = 0; i < 18; i++) {
            int cx = r.nextInt(W + 40) - 20, base = 80 + row * 30 + r.nextInt(10), h = 50 + r.nextInt(40);
            g.setColor(c);
            g.fillPolygon(new int[] {cx - h / 4, cx, cx + h / 4}, new int[] {base, base - h, base}, 3);
          }
          g.fillRect(0, 80 + row * 30, W, H);
        }
      }
      case "desert-dunes" -> {
        sky(g, new Color(250, 200, 140), new Color(255, 235, 200), H);
        Color[] sand = {new Color(230, 170, 100), new Color(215, 145, 80), new Color(190, 120, 65), new Color(165, 100, 55)};
        for (int i = 0; i < sand.length; i++) {
          g.setColor(sand[i]);
          var p = new Path2D.Double();
          p.moveTo(0, H);
          int base = 90 + i * 22;
          for (int x = 0; x <= W; x += 4) p.lineTo(x, base + 14 * Math.sin((x + r.nextInt(3) + i * 60) / (40.0 + i * 8)));
          p.lineTo(W, H);
          p.closePath();
          g.fill(p);
        }
      }
      case "aurora" -> {
        sky(g, new Color(5, 10, 35), new Color(15, 40, 70), H);
        g.setColor(Color.WHITE);
        for (int i = 0; i < 80; i++) g.fillRect(r.nextInt(W), r.nextInt(110), 1, 1);
        for (int band = 0; band < 3; band++) {
          for (int x = 0; x < W; x++) {
            double y = 50 + band * 18 + 20 * Math.sin((x + band * 50) / 45.0);
            g.setPaint(new GradientPaint(x, (float) y, new Color(80, 255, 170, 140), x, (float) y + 45, new Color(80, 160, 255, 0)));
            g.fillRect(x, (int) y, 1, 45);
          }
        }
        g.setColor(new Color(10, 20, 30));
        g.fill(ridge(r, 155, 15, 20));
      }
      case "rice-terraces" -> {
        sky(g, new Color(200, 225, 240), new Color(235, 245, 240), H);
        for (int i = 0; i < 12; i++) {
          g.setColor(mix(new Color(170, 210, 100), new Color(70, 140, 60), i / 11.0));
          var p = new Path2D.Double();
          p.moveTo(0, H);
          int base = 40 + i * 12;
          for (int x = 0; x <= W; x += 5) p.lineTo(x, base + 8 * Math.sin((x + i * 23) / 55.0));
          p.lineTo(W, H);
          p.closePath();
          g.fill(p);
        }
      }
      case "lake-reflection" -> {
        sky(g, new Color(140, 190, 230), new Color(220, 235, 245), 90);
        var hills = ridge(r, 70, 30, 14);
        g.setColor(new Color(60, 110, 90));
        g.fill(hills);
        g.setPaint(new GradientPaint(0, 90, new Color(80, 140, 170), 0, H, new Color(30, 70, 100)));
        g.fillRect(0, 90, W, 90);
        g.setColor(new Color(255, 255, 255, 60));
        for (int i = 0; i < 40; i++) g.fillRect(r.nextInt(W), 95 + r.nextInt(80), 10 + r.nextInt(30), 1);
      }
      case "starry-night" -> {
        sky(g, new Color(10, 15, 50), new Color(50, 40, 100), H);
        for (int i = 0; i < 160; i++) {
          int s = r.nextInt(10) == 0 ? 2 : 1;
          g.setColor(new Color(255, 255, 220, 120 + r.nextInt(135)));
          g.fillOval(r.nextInt(W), r.nextInt(140), s, s);
        }
        g.setColor(new Color(250, 245, 200));
        g.fillOval(40, 25, 34, 34);
        g.setColor(new Color(15, 20, 55));
        g.fillOval(52, 20, 34, 34);
        g.setColor(new Color(15, 15, 30));
        g.fill(ridge(r, 150, 20, 12));
      }
      case "geometric-tiles" -> {
        for (int y = 0; y < H; y += 30)
          for (int x = 0; x < W; x += 30) {
            g.setColor(Color.getHSBColor(r.nextFloat(), 0.45f + r.nextFloat() * 0.3f, 0.7f + r.nextFloat() * 0.25f));
            g.fillRect(x, y, 30, 30);
            g.setColor(Color.getHSBColor(r.nextFloat(), 0.5f, 0.9f));
            int t = r.nextInt(3);
            if (t == 0) g.fillOval(x + 5, y + 5, 20, 20);
            else if (t == 1) g.fillPolygon(new int[] {x, x + 30, x}, new int[] {y, y + 30, y + 30}, 3);
            else g.fillRect(x + 8, y + 8, 14, 14);
          }
      }
      case "flower-field" -> {
        sky(g, new Color(150, 200, 245), new Color(220, 240, 250), 70);
        g.setPaint(new GradientPaint(0, 70, new Color(120, 180, 80), 0, H, new Color(60, 120, 50)));
        g.fillRect(0, 70, W, H);
        Color[] petals = {new Color(240, 80, 120), new Color(250, 200, 60), new Color(170, 110, 230), Color.WHITE};
        for (int i = 0; i < 260; i++) {
          int y = 72 + r.nextInt(108), s = 2 + (y - 70) / 20;
          g.setColor(petals[r.nextInt(petals.length)]);
          g.fillOval(r.nextInt(W), y, s, s);
        }
      }
      default -> { // snow-peaks
        sky(g, new Color(90, 150, 220), new Color(200, 225, 245), H);
        for (int i = 0; i < 3; i++) {
          var p = ridge(r, 80 + i * 30, 50 - i * 10, 24);
          g.setColor(mix(new Color(140, 160, 190), new Color(70, 85, 110), i / 2.0));
          g.fill(p);
          var old = g.getClip();
          g.setClip(p);
          g.setColor(new Color(250, 252, 255));
          g.fillRect(0, 0, W, 80 + i * 30 - 15);
          g.setClip(old);
        }
      }
    }
  }

  // ------------------------------------------------------------------ templates

  static Shape shape(String name) {
    double c = 55, s = 34; // centre and half-size inside a 110x110 tile
    return switch (name) {
      case "circle" -> new Ellipse2D.Double(c - s, c - s, 2 * s, 2 * s);
      case "rounded-square" -> new RoundRectangle2D.Double(c - s, c - s, 2 * s, 2 * s, 22, 22);
      case "star" -> polygon(10, c, c, s + 4, (s + 4) * 0.5, -Math.PI / 2);
      case "hexagon" -> polygon(6, c, c, s + 2, s + 2, 0);
      case "heart" -> {
        var p = new Path2D.Double();
        p.moveTo(c, c + s);
        p.curveTo(c - 2 * s, c - 0.2 * s, c - 0.9 * s, c - 1.4 * s, c, c - 0.6 * s);
        p.curveTo(c + 0.9 * s, c - 1.4 * s, c + 2 * s, c - 0.2 * s, c, c + s);
        p.closePath();
        yield p;
      }
      default -> { // shield
        var p = new Path2D.Double();
        p.moveTo(c - s, c - s);
        p.lineTo(c + s, c - s);
        p.lineTo(c + s, c);
        p.quadTo(c + s, c + 0.8 * s, c, c + 1.1 * s);
        p.quadTo(c - s, c + 0.8 * s, c - s, c);
        p.closePath();
        yield p;
      }
    };
  }

  static Shape polygon(int points, double cx, double cy, double outer, double inner, double start) {
    var p = new Path2D.Double();
    for (int i = 0; i < points; i++) {
      double rad = (points % 2 == 0 && inner != outer) ? (i % 2 == 0 ? outer : inner) : outer;
      double a = start + i * 2 * Math.PI / points;
      if (i == 0) p.moveTo(cx + rad * Math.cos(a), cy + rad * Math.sin(a));
      else p.lineTo(cx + rad * Math.cos(a), cy + rad * Math.sin(a));
    }
    p.closePath();
    return p;
  }

  /** Same convention as tianai's built-in slider_1/slider_2 pairs. */
  static void template(File dir, Shape shape) throws Exception {
    dir.mkdirs();
    var active = new BufferedImage(110, 110, BufferedImage.TYPE_INT_ARGB);
    var g = active.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(255, 255, 255, 26));
    g.fill(shape);
    g.setColor(new Color(255, 255, 255, 210));
    g.setStroke(new BasicStroke(2f));
    g.draw(shape);
    g.dispose();
    ImageIO.write(active, "png", new File(dir, "active.png"));
    var fixed = new BufferedImage(110, 110, BufferedImage.TYPE_INT_ARGB);
    g = fixed.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x66, 0x66, 0x66, 0xcc));
    g.fill(shape);
    g.dispose();
    ImageIO.write(fixed, "png", new File(dir, "fixed.png"));
  }
}
