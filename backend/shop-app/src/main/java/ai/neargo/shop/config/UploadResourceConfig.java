package ai.neargo.shop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 把上传目录映射成可访问的 URL。
 *
 * <p>与 {@code BizUploadController} 是一对：上传只负责把文件落到磁盘并返回一个路径，
 * 而这个路径要真能打开，得有人把目录暴露出去。少了这半边，商家上传成功、
 * 商品页却是一张裂图 —— 而上传接口返回的是 200。
 *
 * <p><b>换对象存储时这个类整个删掉</b>：那时 URL 指向 OSS，不再经过本服务。
 */
@Configuration
public class UploadResourceConfig implements WebMvcConfigurer {

    private final String dir;
    private final String publicPrefix;

    public UploadResourceConfig(@Value("${shop.upload.dir:./data/uploads}") String dir,
                                @Value("${shop.upload.public-prefix:/uploads}") String publicPrefix) {
        this.dir = dir;
        this.publicPrefix = publicPrefix;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // addResourceLocations 收 Resource；末尾的 "/" 不能省，否则它会被当成文件名而不是目录
        var location = new org.springframework.core.io.FileSystemResource(
                Path.of(dir).toAbsolutePath().normalize() + "/");
        registry.addResourceHandler(publicPrefix + "/**").addResourceLocations(location);
    }
}
