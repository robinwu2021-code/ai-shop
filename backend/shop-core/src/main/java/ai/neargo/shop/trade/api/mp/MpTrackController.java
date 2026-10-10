package ai.neargo.shop.trade.api.mp;

import ai.neargo.shop.trade.dto.TrackVO;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.track.ShipTrackToken;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 免登录看件（TDD-收件人物流触达与分享裂变 §3）。
 *
 * <p><b>匿名端点，鉴权靠验票</b>：{@code /mp/**} 整段是 {@code permitAll}，
 * {@code t} 是发货短信里带的看件令牌，{@link ShipTrackToken} 验得过才给看。
 * 收件人没有账号，令牌本身就是授权 —— 所以它登记在 {@code MpEndpointAuthTest} 的
 * <b>ANONYMOUS</b> 桶：不需要登录。
 *
 * <p><b>验不过（伪造 / 过期 / 缺票）返回 {@code data=null}，不抛异常</b>：
 * 对一个匿名来访者没必要区分「假的」与「过期」，端上统一按 null 显示「链接已失效」。
 * 返回统一信封（而不是 404）也让 {@code t} 可选 —— 匿名探测不带票时同样是成功的空响应，
 * 这正是它能被归进 ANONYMOUS、被实弹判据钉住的前提。
 */
@Profile("api")
@RestController
public class MpTrackController {

    private final ShipTrackToken token;
    private final OrderService orderService;

    public MpTrackController(ShipTrackToken token, OrderService orderService) {
        this.token = token;
        this.orderService = orderService;
    }

    @GetMapping("/mp/track")
    public TrackVO track(@RequestParam(value = "t", required = false) String t,
                         @RequestHeader(value = "X-Client", required = false) String client) {
        return token.verify(t)
                .map(subOrderNo -> orderService.trackBySubOrder(subOrderNo, client))
                .orElse(null);
    }
}
