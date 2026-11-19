package ai.neargo.shop.authcaptcha;

import static org.assertj.core.api.Assertions.*;

import ai.neargo.shop.auth.captcha.TianaiSliderEngine;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.HashSet;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** At least ten bundled 320x180 backgrounds and several shapes, picked at random per challenge. */
class TianaiSliderEngineTest {
  private static java.awt.image.BufferedImage decode(String dataUrl) throws Exception {
    return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(dataUrl.substring(dataUrl.indexOf(',') + 1))));
  }

  @Test
  void everyBundledImageIsOnTheClasspathAtTheExpectedSize() throws Exception {
    var loader = getClass().getClassLoader();
    int backgrounds = 0;
    for (String name : new String[] {"bg-01-sunset-mountains", "bg-02-ocean-waves", "bg-03-city-skyline",
        "bg-04-pine-forest", "bg-05-desert-dunes", "bg-06-aurora", "bg-07-rice-terraces", "bg-08-lake-reflection",
        "bg-09-starry-night", "bg-10-geometric-tiles", "bg-11-flower-field", "bg-12-snow-peaks"}) {
      try (var in = loader.getResourceAsStream("captcha/slider/backgrounds/" + name + ".png")) {
        var image = ImageIO.read(in);
        assertThat(image.getWidth()).as(name).isEqualTo(320);
        assertThat(image.getHeight()).as(name).isEqualTo(180);
        backgrounds++;
      }
    }
    assertThat(backgrounds).isGreaterThanOrEqualTo(10);
    for (String shape : new String[] {"circle", "rounded-square", "star", "hexagon", "heart", "shield"})
      for (String part : new String[] {"active.png", "fixed.png"})
        try (var in = loader.getResourceAsStream("captcha/slider/templates/" + shape + "/" + part)) {
          assertThat(ImageIO.read(in).getWidth()).as(shape + "/" + part).isEqualTo(110);
        }
  }

  @Test
  void challengesVaryBackgroundAndShape() throws Exception {
    var engine = new TianaiSliderEngine();
    var backgrounds = new HashSet<Integer>();
    var shapes = new HashSet<Integer>();
    for (int i = 0; i < 80; i++) {
      var info = engine.generate();
      var bg = decode(info.getBackgroundImage());
      assertThat(bg.getWidth()).isEqualTo(320);
      assertThat(bg.getHeight()).isEqualTo(180);
      // The top-left corner is never cut, so it fingerprints the background.
      backgrounds.add(bg.getRGB(2, 2) ^ bg.getRGB(30, 4) * 31);
      var piece = decode(info.getTemplateImage());
      int opaque = 0;
      for (int y = 0; y < piece.getHeight(); y++)
        for (int x = 0; x < piece.getWidth(); x++) if ((piece.getRGB(x, y) >>> 24) > 0) opaque++;
      shapes.add(opaque / 50);
    }
    assertThat(backgrounds).as("distinct backgrounds seen in 80 challenges").hasSizeGreaterThanOrEqualTo(8);
    assertThat(shapes).as("distinct cut-out shapes seen in 80 challenges").hasSizeGreaterThanOrEqualTo(4);
  }
}
