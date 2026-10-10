package ai.neargo.shop.link.api;

import ai.neargo.shop.link.ShortLinkService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链 302（TDD-收件人物流触达与分享裂变 §4.2）。
 *
 * <p>子域名 {@code s.hxmall.top} 的 nginx 把 {@code /<code>} 转到这里的 {@code /l/<code>}。
 * <b>与店铺码短链 {@code /s/<code>} 分开</b>：那条解的是门店代码、落 C 端门店页
 * （{@code StoreShortLinkController}），两套码的字符集重叠，合在一个路径上会互相解错 ——
 * 一个 7 位随机短码被当成门店代码去查，查不到就静默落首页，发货短信就点不开了。
 *
 * <p>短码查得到且没过期 → 302 到 {@code target}（通常是微信 URL Link）。
 * 查不到 / 过期 → 一张极简「链接已失效」页，不 302、不报错 ——
 * 收件人手上没有小程序，给他一个跳不动的 302 不如直说。
 *
 * <p><b>302 不是 301</b>：{@code target} 会变（URL Link 过期要换新的），301 会被浏览器永久缓存。
 */
@Profile("api")
@RestController
public class ShortLinkController {

    /** 码的字符集闸门：码被原样用于查询，放开字符无意义。白名单外一律当「不认识」 */
    private static final String CODE_CHARS = "^[A-Za-z0-9_-]{1,32}$";

    private static final String EXPIRED_PAGE = """
            <!doctype html><html lang="zh"><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <title>链接已失效</title></head>
            <body style="font-family:sans-serif;text-align:center;padding:48px 24px;color:#333">
            <h3>链接已失效</h3><p>这条物流链接可能已过期，请在微信里打开最新的发货通知。</p>
            </body></html>""";

    private final ShortLinkService service;

    public ShortLinkController(ShortLinkService service) {
        this.service = service;
    }

    @GetMapping("/l/{code}")
    public ResponseEntity<String> resolve(@PathVariable String code) {
        if (code != null && code.matches(CODE_CHARS)) {
            var target = service.resolve(code);
            if (target.isPresent()) {
                return ResponseEntity.status(HttpStatus.FOUND)
                        .header("Location", target.get()).build();
            }
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .contentType(MediaType.TEXT_HTML).body(EXPIRED_PAGE);
    }
}
