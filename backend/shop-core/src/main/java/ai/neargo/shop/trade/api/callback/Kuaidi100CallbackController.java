package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.trade.service.ExpressPickupService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 快递100 取件状态回调（TDD-快递100商家寄件 AC3）。
 *
 * <p><b>公网可达、不带我方令牌</b>（SecurityConfig 的 {@code /callback/**}），靠通道签名自证：
 * 验签在服务里做，不过就回失败、什么都不落。
 *
 * <p>返回 String：全局信封（ApiResponseWrapper）不包 String —— 通道认的是顶层 {@code result:true}，
 * 被包成 {@code {code:0,data:{…}}} 的话它会当成失败一直重推。
 */
@Profile("api")
@RestController
public class Kuaidi100CallbackController {

    static final String OK = "{\"result\":true,\"returnCode\":\"200\",\"message\":\"成功\"}";
    static final String RETRY = "{\"result\":false,\"returnCode\":\"500\",\"message\":\"未处理\"}";

    private final ExpressPickupService expressPickupService;

    public Kuaidi100CallbackController(ExpressPickupService expressPickupService) {
        this.expressPickupService = expressPickupService;
    }

    @PostMapping(value = "/callback/express/kuaidi100", produces = MediaType.APPLICATION_JSON_VALUE)
    public String callback(@RequestParam(required = false) String taskId,
                           @RequestParam(required = false) String sign,
                           @RequestParam(required = false) String param) {
        return expressPickupService.onCallback(taskId, sign, param) ? OK : RETRY;
    }
}
