package ai.neargo.shop.link.api;

import ai.neargo.shop.link.ShortLinkService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短链 302（TDD-收件人物流触达与分享裂变 §4.2）。
 *
 * <p>子域名 {@code s.hxmall.top} 的 nginx 把 {@code /<code>} 转到这里的 {@code /l/<code>}。
 * <b>与店铺码短链 {@code /s/<code>} 分开</b>：那条解的是门店代码、落 C 端门店页
 * （{@code StoreShortLinkController}），两套码的字符集重叠，合在一个路径上会互相解错。
 *
 * <p>命中且没过期 → 302 到 {@code target}（微信 URL Link 或 H5 看件页）。
 *
 * <p><b>未命中 / 过期 / 根路径 → 302 到公网首页，而不是 404 失效页</b>（2026-10-10 改）：
 * 原来回一张 404「链接已失效」HTML。阿里云短信审核**会真的去访问模板里的示例短链**，
 * 404 被判成「链接为无效链接或无法打开」→ 带链接的模板过不了审（2026-10-10 实际被拒）。
 * 所以这个域名下**任何路径都要能打开**：最差也 302 到商城首页，而不是一个死页。
 * 对收件人也更友好 —— 点了过期链接落到商城，而不是一个跳不动的提示。
 *
 * <p><b>302 不是 301</b>：{@code target} 会变（URL Link 过期要换新的），301 会被浏览器永久缓存。
 */
@Profile("api")
@RestController
public class ShortLinkController {

    /** 码的字符集闸门：码被原样用于查询，放开字符无意义。白名单外一律当「不认识」→ 落首页 */
    private static final String CODE_CHARS = "^[A-Za-z0-9_-]{1,32}$";

    private final ShortLinkService service;
    /** 未命中 / 过期 / 根路径的落点。必须是**公网可访问的真实页**（审核会访问本域名） */
    private final String fallback;

    public ShortLinkController(ShortLinkService service,
                               @Value("${shop.ship.short-link-fallback:https://www.hxmall.top}") String fallback) {
        this.service = service;
        this.fallback = fallback;
    }

    @GetMapping("/l/{code}")
    public ResponseEntity<Void> resolve(@PathVariable String code) {
        String target = fallback;
        if (code != null && code.matches(CODE_CHARS)) {
            target = service.resolve(code).orElse(fallback);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", target).build();
    }

    /** 根路径 / 空码（{@code s.hxmall.top} 或 {@code s.hxmall.top/}）：同样落首页，别 404 */
    @GetMapping({"/l", "/l/"})
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", fallback).build();
    }
}
