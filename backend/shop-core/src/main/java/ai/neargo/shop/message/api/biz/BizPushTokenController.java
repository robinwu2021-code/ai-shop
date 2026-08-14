package ai.neargo.shop.message.api.biz;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.message.entity.MsgMessage;
import ai.neargo.shop.message.notify.PushTokenBinder;
import jakarta.validation.constraints.NotBlank;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * B 端设备绑定（ADR-018）。商家 App 登录后上报，登出前解绑。
 *
 * <p><b>不要求 BizPerms</b>（登记在 BizEndpointPermTest 的 PUBLIC 表）：
 * 绑定的是当前登录者自己的设备；要权限的话，收「新订单」提醒的店员反而绑不上。
 */
@Profile("api")
@RestController
@Validated
public class BizPushTokenController {

    private final PushTokenBinder pushTokenBinder;

    public BizPushTokenController(PushTokenBinder pushTokenBinder) {
        this.pushTokenBinder = pushTokenBinder;
    }

    @PostMapping("/biz/push-token")
    public void register(@RequestBody RegisterReq req) {
        pushTokenBinder.register(MsgMessage.RECEIVER_STAFF, SecurityUtils.currentUserNo(),
                req.platform(), req.clientId());
    }

    /**
     * 解绑。**共用设备换班的场景全靠它** —— 见 PushTokenBinder#unregister。
     * POST 而非 DELETE 的理由同 C 端（端上请求层只走 GET/POST）。
     */
    @PostMapping("/biz/push-token/unregister")
    public void unregister(@RequestBody(required = false) UnregisterReq req) {
        pushTokenBinder.unregister(MsgMessage.RECEIVER_STAFF, SecurityUtils.currentUserNo(),
                req == null ? null : req.clientId());
    }

    public record RegisterReq(@NotBlank String platform, @NotBlank String clientId) {
    }

    /** clientId 可空：端上取不到时按人解绑全部设备。 */
    public record UnregisterReq(String clientId) {
    }
}
