package ai.neargo.shop.auth.captcha;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.generator.ImageCaptchaGenerator;
import cloud.tianai.captcha.generator.common.model.dto.ImageCaptchaInfo;
import cloud.tianai.captcha.generator.impl.StandardSliderImageCaptchaGenerator;
import cloud.tianai.captcha.resource.*;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.impl.DefaultImageCaptchaResourceManager;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.security.SecureRandom;
import javax.imageio.ImageIO;

/** Uses Tianai core directly, without its Spring Boot starter or local challenge cache. */
public class TianaiSliderEngine {
    private final ImageCaptchaGenerator generator;

    public TianaiSliderEngine() {
        var resources = new LocalMemoryResourceStore();
        new DefaultBuiltInResources(DefaultBuiltInResources.PATH_PREFIX)
                .addDefaultTemplate(CaptchaTypeConstant.SLIDER, resources);
        resources.addResource(CaptchaTypeConstant.SLIDER, new Resource("private-generated", "fresh"));
        var providers = new ResourceProviders();
        providers.registerResourceProvider(new FreshBackground());
        generator = new StandardSliderImageCaptchaGenerator(new DefaultImageCaptchaResourceManager(resources, providers)).init();
    }

    public ImageCaptchaInfo generate() {
        return generator.generateCaptchaImage(CaptchaTypeConstant.SLIDER, "png", "png");
    }

    /** A fresh textured image per request; no original is saved or exposed by an HTTP URL. */
    private static class FreshBackground implements ResourceProvider {
        private final SecureRandom random = new SecureRandom();
        public InputStream getResourceInputStream(Resource resource) {
            var image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setPaint(new GradientPaint(0, 0, color(), 320, 180, color()));
                g.fillRect(0, 0, 320, 180);
                for (int i = 0; i < 90; i++) {
                    g.setColor(color());
                    int x = random.nextInt(360) - 20, y = random.nextInt(220) - 20;
                    int size = 12 + random.nextInt(70);
                    g.fillOval(x, y, size, size);
                }
                var bytes = new ByteArrayOutputStream();
                ImageIO.write(image, "png", bytes);
                return new ByteArrayInputStream(bytes.toByteArray());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally { g.dispose(); }
        }
        private Color color() { return new Color(50 + random.nextInt(170), 60 + random.nextInt(160), 70 + random.nextInt(150)); }
        public boolean supported(Resource resource) { return getName().equals(resource.getType()); }
        public String getName() { return "private-generated"; }
    }
}
