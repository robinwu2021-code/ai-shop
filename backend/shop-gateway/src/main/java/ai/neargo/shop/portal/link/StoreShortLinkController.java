package ai.neargo.shop.portal.link;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.service.StoreCodeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 店铺码短链 {@code GET /s/<码>} —— 印在包装袋上、发进客户群的那条链接
 * （TDD-店铺码与分享 §3.5）。
 *
 * <p><b>此前这条链接落不到店。</b>链接一直在发（{@code SHOP_WEB_BASE_URL} 生产早就配了，
 * {@code StoreLinkServiceImpl} 拼的就是 {@code /s/<码>}），而 nginx 那一侧是
 * {@code return 302 /c/$is_args$args} —— {@code $is_args$args} 只保住 query，
 * <b>路径里的码被丢掉</b>。实测 {@code /s/SMTBA2} → {@code Location: /c/}，
 * 而同一个码 {@code /mp/store/by-code} 解析得好好的。
 * 店主分享出去的链接、顾客扫的贴纸，点开都只到 C 端首页。
 *
 * <p><b>为什么这一跳要走后端而不是 nginx 正则</b>：纯 nginx 转发认不出码存在不存在，
 * 码印错、门店停业、代码改过都只能落到一个空页。这里先解析一次，
 * 顺带把 {@code from=QR} 带上 —— 它决定订单的 {@code trafficSource} 与商家费率档
 * （ADR-004 §6），少了它扫码来的单会按平台流量计费。
 *
 * <p><b>不在这里落访问记录</b>：采集挂在落地页那一侧（{@code /mp/store/by-code} 的注释
 * 写明它「只解析不落行」，行由 {@code mkt_store_visit} 那条路写）。
 * 两处都落会把一次扫码算成两次。
 */
@Profile("api")
@RestController
public class StoreShortLinkController {

    private static final Logger log = LoggerFactory.getLogger(StoreShortLinkController.class);

    /**
     * 码的字符集。**这是开放重定向的闸门**，不是格式洁癖：码被拼进 {@code Location} 头，
     * 放开字符就能构造出 {@code /s/..%2F..%2Fevil.com} 这类东西。
     * 白名单之外的一律当「码不认识」处理，不回显、不报错。
     */
    private static final String CODE_CHARS = "^[A-Za-z0-9_-]{1,64}$";

    /** C 端门店页。H5 是 hash 路由，所以参数在 {@code #} 之后 —— uni 从 onLoad 的 query 里读得到 */
    private static final String STORE_PAGE = "/c/#/pages/store/index";

    private final StoreCodeService storeCodeService;

    public StoreShortLinkController(StoreCodeService storeCodeService) {
        this.storeCodeService = storeCodeService;
    }

    /**
     * 码 → 门店页。
     *
     * <p><b>Location 用相对路径</b>：不读 {@code shop.web.base-url}。那个配置为空时
     * {@link ai.neargo.shop.merchant.service.StoreLinkService#linkOf} 会返 null，
     * 而这一跳不能因为少一条配置就坏掉 —— 请求本来就是打到这个域名上的，
     * 相对路径必然落回同一个 host，也省掉「配的域名与实际访问的域名不一致」那一类问题。
     *
     * <p><b>302 不是 301</b>：门店可以改代码、也可能停业，301 会被浏览器永久缓存住。
     *
     * @param code    印在物料上的码，或门店自己设的代码（§3.6）
     * @param g       商品号。老链接用的就是 {@code ?g=}（见 nginx 里那条注释），原样透传
     * @param inviter 邀请人。分享链路上的归因，门店页自己会接住
     */
    @GetMapping("/s/{code}")
    public ResponseEntity<Void> resolve(@PathVariable String code,
                                        @RequestParam(required = false) String g,
                                        @RequestParam(required = false) String inviter) {
        String target = homeFallback();
        if (code != null && code.matches(CODE_CHARS)) {
            try {
                storeCodeService.resolveTarget(code);
                StringBuilder sb = new StringBuilder(STORE_PAGE)
                        .append("?storeCode=").append(enc(code))
                        .append("&from=QR");
                append(sb, "g", g);
                append(sb, "inviter", inviter);
                target = sb.toString();
            } catch (BizException e) {
                /*
                 * 码不认识 —— **落首页，不给 404**。这些码可能已经印在包装上了，
                 * 一张打不开的贴纸给顾客看错误页是最差的结果。
                 * 但要留一条 warn：否则「码印错了」这件事永远没人发现，
                 * 店主会一直以为自己在带客。
                 */
                log.warn("店铺码短链解析不到门店，落首页 code={}", code);
            }
        } else if (code != null) {
            log.warn("店铺码短链的码不合字符集，落首页 len={}", code.length());
        }
        return ResponseEntity.status(HttpStatus.FOUND).header("Location", target).build();
    }

    /** 落首页。与这条路由接管之前 nginx 的行为一致（那时是无条件落首页） */
    private static String homeFallback() {
        return "/c/";
    }

    private static void append(StringBuilder sb, String key, String value) {
        if (value != null && !value.isBlank()) {
            sb.append('&').append(key).append('=').append(enc(value));
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
