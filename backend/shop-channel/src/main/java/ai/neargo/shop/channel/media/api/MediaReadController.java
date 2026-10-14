package ai.neargo.shop.channel.media.api;

import ai.neargo.shop.channel.media.LocalDiskMediaStore;
import ai.neargo.shop.media.SysMediaAsset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * 私有资产的读取口：证件与售后凭证。<b>凭签名放行，不看登录态。</b>
 *
 * <p>为什么不是「带鉴权的接口」：小程序的 {@code <image src>} 与浏览器的 {@code <img>}
 * <b>都没法带请求头</b>，鉴权接口在这两个地方根本用不了 —— 运营端打开商家档案，
 * 那张营业执照是用 {@code <img>} 渲染的。签名把凭证放进 URL 本身，
 * 有效期按分钟计，这也正是 COS 预签名的做法。
 *
 * <p><b>它与 {@link LocalDiskMediaStore} 是一对，将来一起删掉</b>：
 * 换 COS 之后签名由 COS/CDN 自己校验，请求根本不到本服务，这个类就没有存在理由了。
 * 所以它直接依赖本地实现而不是 {@code MediaStore} 端口 —— 依赖端口反而会掩盖
 * 「这是本地磁盘方案的专属零件」这个事实。
 */
@Profile({"api", "ops"})
// 本地磁盘方案的专属零件：切 COS（provider=cos）时 LocalDiskMediaStore 不存在，
// 这个控制器也随之不加载（否则构造函数注入 LocalDiskMediaStore 会启动失败）。COS 直接出图，不需要它。
@ConditionalOnProperty(name = "shop.media.provider", havingValue = "local", matchIfMissing = true)
@RestController
public class MediaReadController {

    private final LocalDiskMediaStore store;
    private final Path root;
    private final String privatePrefix;

    public MediaReadController(LocalDiskMediaStore store,
                               @Value("${shop.upload.dir:./data/uploads}") String dir,
                               @Value("${shop.upload.private-prefix:/media}") String privatePrefix) {
        this.store = store;
        this.root = Path.of(dir).toAbsolutePath().normalize();
        this.privatePrefix = privatePrefix;
    }

    @GetMapping("${shop.upload.private-prefix:/media}/**")
    public ResponseEntity<Resource> read(HttpServletRequest req,
                                         @RequestParam(value = "exp", required = false) Long exp,
                                         @RequestParam(value = "sig", required = false) String sig) {

        String uri = (String) req.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String key = uri == null ? "" : uri.substring(uri.indexOf(privatePrefix) + privatePrefix.length() + 1);

        /*
         * 签名不过一律 404，不是 403 ——
         * 403 等于承认「这个 key 下确实有东西」，对枚举者是一条免费的信息。
         * 过期与伪造在这里表现一致，也是同一个理由。
         */
        if (exp == null || sig == null || !store.verify(key, exp, sig)) {
            return ResponseEntity.notFound().build();
        }

        /*
         * 公开档不该走这条路：它有缓存头、有签名开销，而且混着走会让
         * 「哪些图是私有的」这个问题在两个地方各有一个答案。
         */
        if (SysMediaAsset.GOODS.equalsIgnoreCase(segment(key, 2))) {
            return ResponseEntity.notFound().build();
        }

        Path file = root.resolve(key).normalize();
        // 落点必须在根目录内。key 来自签名内容，改一个字签名就不对了，
        // 但这行的成本是零，而漏了它就是一个任意文件读
        if (!file.startsWith(root) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                // 私有资产**不能进共享缓存**：CDN 或反代把它缓存下来，
                // 签名过期之后仍然发得出去，等于签名白做
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePrivate().noTransform())
                .contentType(contentTypeOf(file))
                .body(new FileSystemResource(file));
    }

    private static String segment(String key, int i) {
        String[] seg = key.split("/");
        return i < seg.length ? seg[i] : "";
    }

    private static MediaType contentTypeOf(Path file) {
        String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (name.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        if (name.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        return MediaType.IMAGE_JPEG;
    }
}
