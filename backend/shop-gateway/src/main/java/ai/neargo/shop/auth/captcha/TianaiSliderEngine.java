package ai.neargo.shop.auth.captcha;

import cloud.tianai.captcha.common.constant.CaptchaTypeConstant;
import cloud.tianai.captcha.generator.ImageCaptchaGenerator;
import cloud.tianai.captcha.generator.common.model.dto.ImageCaptchaInfo;
import cloud.tianai.captcha.generator.impl.StandardSliderImageCaptchaGenerator;
import cloud.tianai.captcha.resource.*;
import cloud.tianai.captcha.resource.common.model.dto.Resource;
import cloud.tianai.captcha.resource.common.model.dto.ResourceMap;
import cloud.tianai.captcha.resource.impl.DefaultImageCaptchaResourceManager;
import cloud.tianai.captcha.resource.impl.LocalMemoryResourceStore;
import cloud.tianai.captcha.resource.impl.provider.ClassPathResourceProvider;
import java.util.List;

/**
 * Uses Tianai core directly, without its Spring Boot starter or local challenge cache.
 *
 * <p>Each challenge picks one of the bundled 320x180 backgrounds and one of the cut-out shapes at
 * random (Tianai's two built-in puzzle pieces plus ours). Everything is read from the jar's
 * classpath; the URL and file providers Tianai registers by default are removed, so a challenge
 * can never fetch anything remote. The images are drawn by {@code scripts/captcha/GenerateSliderImages.java}.
 */
public class TianaiSliderEngine {
    static final String ROOT = "captcha/slider/";
    static final List<String> BACKGROUNDS = List.of(
            "bg-01-sunset-mountains", "bg-02-ocean-waves", "bg-03-city-skyline", "bg-04-pine-forest",
            "bg-05-desert-dunes", "bg-06-aurora", "bg-07-rice-terraces", "bg-08-lake-reflection",
            "bg-09-starry-night", "bg-10-geometric-tiles", "bg-11-flower-field", "bg-12-snow-peaks");
    static final List<String> SHAPES = List.of("circle", "rounded-square", "star", "hexagon", "heart", "shield");

    private final ImageCaptchaGenerator generator;

    public TianaiSliderEngine() {
        var resources = new LocalMemoryResourceStore();
        new DefaultBuiltInResources(DefaultBuiltInResources.PATH_PREFIX)
                .addDefaultTemplate(CaptchaTypeConstant.SLIDER, resources);
        for (String shape : SHAPES) {
            var template = new ResourceMap("default", 4);
            template.put("active.png", new Resource(ClassPathResourceProvider.NAME, ROOT + "templates/" + shape + "/active.png"));
            template.put("fixed.png", new Resource(ClassPathResourceProvider.NAME, ROOT + "templates/" + shape + "/fixed.png"));
            resources.addTemplate(CaptchaTypeConstant.SLIDER, template);
        }
        for (String background : BACKGROUNDS)
            resources.addResource(CaptchaTypeConstant.SLIDER,
                    new Resource(ClassPathResourceProvider.NAME, ROOT + "backgrounds/" + background + ".png"));
        var providers = new ResourceProviders();
        providers.listResourceProviders().stream()
                .map(ResourceProvider::getName)
                .filter(name -> !ClassPathResourceProvider.NAME.equals(name))
                .toList()
                .forEach(providers::deleteResourceProviderByName);
        generator = new StandardSliderImageCaptchaGenerator(new DefaultImageCaptchaResourceManager(resources, providers)).init();
    }

    public ImageCaptchaInfo generate() {
        return generator.generateCaptchaImage(CaptchaTypeConstant.SLIDER, "png", "png");
    }
}
