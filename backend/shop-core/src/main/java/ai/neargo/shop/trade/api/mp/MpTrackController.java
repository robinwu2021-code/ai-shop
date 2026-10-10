package ai.neargo.shop.trade.api.mp;

import ai.neargo.shop.spi.notify.WxUrlLinkPort;
import ai.neargo.shop.trade.dto.TrackVO;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.track.ShipTrackToken;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免登录看件（TDD-收件人物流触达与分享裂变 §3）。
 *
 * <p><b>链路：短信短链 → H5 看件页 → （H5 内点按钮）小程序看件页。</b>
 * 发货短信的短链永远指这张 H5 看件页（稳定可打开、没微信也能看）；H5 上再提供
 * 「在小程序中打开」入口 —— 从短信点开多在系统浏览器，所以跳小程序走微信 URL Link
 * （{@code wx-open-launch-weapp} 只在微信内浏览器生效，对短信场景不适用）。
 *
 * <p>两个端点都**匿名**，鉴权靠验票（{@code t} 是发货短信里的看件令牌）。验不过返回空 —— 都归
 * {@code MpEndpointAuthTest} 的 ANONYMOUS 桶。
 */
@Profile("api")
@RestController
public class MpTrackController {

    private final ShipTrackToken token;
    private final OrderService orderService;
    private final WxUrlLinkPort urlLink;
    /** 看件页在小程序里的路径，不带前导斜杠 */
    private final String miniPath;

    public MpTrackController(ShipTrackToken token, OrderService orderService, WxUrlLinkPort urlLink,
                             @Value("${shop.ship.track-mini-path:pages/track/index}") String miniPath) {
        this.token = token;
        this.orderService = orderService;
        this.urlLink = urlLink;
        this.miniPath = miniPath;
    }

    @GetMapping("/mp/track")
    public TrackVO track(@RequestParam(value = "t", required = false) String t,
                         @RequestHeader(value = "X-Client", required = false) String client) {
        return token.verify(t)
                .map(subOrderNo -> orderService.trackBySubOrder(subOrderNo, client))
                .orElse(null);
    }

    /**
     * H5 看件页的「在小程序中打开」用：生成一条指向小程序看件页的微信 URL Link。
     *
     * <p><b>生成不出来就返回 null，H5 据此不显示按钮</b>（优雅降级）：URL Link 要小程序正式版
     * 发布了看件页、且 {@code urllink} 真通道开着（{@code SHOP_WX_URLLINK_STUB=false}）才有；
     * 在那之前这里恒为空，H5 只展示看件内容、不露一个点不动的按钮。
     */
    @GetMapping("/mp/track/mini-link")
    public MiniLink miniLink(@RequestParam(value = "t", required = false) String t) {
        return token.verify(t)
                .flatMap(subOrderNo -> urlLink.generate(miniPath, "t=" + t))
                .map(MiniLink::new)
                .orElse(null);
    }

    /** @param url 形如 {@code https://wxaurl.cn/xxx} 的微信 URL Link */
    public record MiniLink(String url) {
    }
}
