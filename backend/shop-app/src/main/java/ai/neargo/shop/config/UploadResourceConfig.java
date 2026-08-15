package ai.neargo.shop.config;

import ai.neargo.shop.media.SysMediaAsset;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.util.Locale;

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
 * <p>所以现在多了一道 {@link GoodsOnlyInterceptor}：只有 {@code goods} 那一层放行，
 * {@code qual} / {@code aftersale} 走 {@code /media/**} 的签名 URL。
 *
 * <p><b>换对象存储时这个类整个删掉</b>：那时 URL 指向 COS，不再经过本服务，
 * 公开与私有的分界改由 bucket 的前缀策略表达 —— 同一套模型，换个地方实现。
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

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new GoodsOnlyInterceptor(publicPrefix))
                .addPathPatterns(publicPrefix + "/**");
    }

    /**
     * 公开目录只放行商品图。
     *
     * <p><b>为什么用拦截器而不是把 handler 的 pattern 写成 {@code /uploads/*}{@code /*}{@code /goods/**}</b>：
     * Spring 会把 {@code **} 之前的部分当作 handler 前缀剥掉，剩下的才拿去解析文件位置 ——
     * 于是 {@code {主体}/{门店}/goods} 这三段会在定位文件时丢失，图反而打不开了。
     * 判断放在拦截器里，位置解析仍走完整路径。
     */
    static class GoodsOnlyInterceptor implements HandlerInterceptor {

        private final String prefix;

        GoodsOnlyInterceptor(String prefix) {
            this.prefix = prefix;
        }

        @Override
        public boolean preHandle(HttpServletRequest req, HttpServletResponse resp, Object handler) {
            String rest = req.getRequestURI();
            int at = rest.indexOf(prefix + "/");
            rest = at < 0 ? rest : rest.substring(at + prefix.length() + 1);
            String[] seg = rest.split("/");

            /*
             * 两种形态都要认：
             *   新：{主体}/{门店}/{用途}/{年月}/{名}  —— 看第 3 段
             *   旧：{商家}/{名}                      —— 存量，当时全是商品图，放行
             * 存量不搬家（TDD §L3-10），所以这两种会长期共存。
             */
            boolean ok = seg.length <= 2
                    || SysMediaAsset.GOODS.equalsIgnoreCase(seg[2]);
            if (!ok) {
                // 404 而不是 403：403 等于承认「这个路径下有东西」，对枚举者是一条信息
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                return false;
            }
            return true;
        }
    }

    /** 给测试与读取侧共用的判断，避免两处各写一遍大小写规则。 */
    public static boolean isPublicKey(String key) {
        String[] seg = key.split("/");
        return seg.length <= 2 || SysMediaAsset.GOODS.equalsIgnoreCase(seg[2].toLowerCase(Locale.ROOT));
    }
}
