package ai.neargo.shop.config;
import org.springframework.core.io.FileSystemResource;


import ai.neargo.shop.channel.media.LocalDiskMediaStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.media.MediaKeys;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.util.UriUtils;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/**
 * 把上传目录里<b>公开的那一部分</b>映射成可访问的 URL。
 *
 * <p>与 {@code BizUploadController} 是一对：上传只负责把文件落到磁盘并返回一个路径，
 * 而这个路径要真能打开，得有人把目录暴露出去。少了这半边，商家上传成功、
 * 商品页却是一张裂图 —— 而上传接口返回的是 200。
 *
 * <p><b>关键是「公开的那一部分」这几个字。</b> 在四层目录之前，这里映射的是整个上传根目录，
 * 而 {@code SecurityConfig} 又把 {@code /uploads/**} 整条链 permitAll，
 * 理由写的是「图本身不是秘密（它就是要给买家看的）」——
 * 这句话对商品图成立，<b>对营业执照不成立</b>，而证件当时和商品图落在同一个目录里。
 * UUID 文件名给的是「不可枚举」，不是访问控制：URL 一旦出现在数据库导出、
 * 运营端截图或日志里，谁都能拉到证件原件。
 *
 * <p>所以现在多了一道 {@link PublicImagesOnlyInterceptor}：商品图、用户头像和晒单放行，
 * {@code qual} / {@code aftersale} 走 {@code /media/**} 的签名 URL。
 *
 * <p><b>切换对象存储时本配置不装配</b>：那时 URL 指向 COS，不再经过本服务，
 * 公开与私有的分界改由 bucket 的前缀策略表达 —— 同一套模型，换个地方实现。
 */
@Configuration
@ConditionalOnProperty(name = "shop.media.provider", havingValue = "local", matchIfMissing = true)
public class UploadResourceConfig implements WebMvcConfigurer {

    private final String dir;
    private final String publicPrefix;
    private final LocalDiskMediaStore store;

    public UploadResourceConfig(@Value("${shop.upload.dir:${shop.storage.root:./data}/uploads}") String dir,
                                @Value("${shop.upload.public-prefix:/uploads}") String publicPrefix, LocalDiskMediaStore store) {
        this.dir = dir;
        this.publicPrefix = publicPrefix;
        this.store = store;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // addResourceLocations 收 Resource；末尾的 "/" 不能省，否则它会被当成文件名而不是目录
        var location = new FileSystemResource(
                Path.of(dir).toAbsolutePath().normalize() + "/");
        registry.addResourceHandler(publicPrefix + "/**").addResourceLocations(location);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new PublicImagesOnlyInterceptor(store))
                .addPathPatterns(publicPrefix + "/**");
    }

    /**
     * 公开目录按统一用途规则放行。
     *
     * <p><b>为什么用拦截器而不是把 handler 的 pattern 写成 {@code /uploads/*}{@code /*}{@code /goods/**}</b>：
     * Spring 会把 {@code **} 之前的部分当作 handler 前缀剥掉，剩下的才拿去解析文件位置 ——
     * 于是 {@code {主体}/{门店}/goods} 这三段会在定位文件时丢失，图反而打不开了。
     * 判断放在拦截器里，位置解析仍走完整路径。
     */
    static class PublicImagesOnlyInterceptor implements HandlerInterceptor {

        private final LocalDiskMediaStore store;

        PublicImagesOnlyInterceptor(LocalDiskMediaStore store) {
            this.store = store;
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
            // Use the same path selected for the resource handler.
            var mapped = (String) req.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
            if (mapped == null) return notFound(resp);
            try {
                var decoded = UriUtils.decode(mapped, StandardCharsets.UTF_8);
                var key = decoded.startsWith("/") ? decoded.substring(1) : decoded;
                if (!MediaKeys.isPublicKey(key)) return notFound(resp);
                store.objectPath(key);
                return true;
            } catch (IllegalArgumentException | BizException e) {
                return notFound(resp);
            }
        }

        private static boolean notFound(HttpServletResponse response) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return false;
        }
    }

    /** 给测试与读取侧共用的判断，避免两处各写一遍大小写规则。 */
    public static boolean isPublicKey(String key) {
        return MediaKeys.isPublicKey(key);
    }
}
