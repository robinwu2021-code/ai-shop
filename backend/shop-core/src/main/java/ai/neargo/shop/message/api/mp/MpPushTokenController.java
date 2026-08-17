package ai.neargo.shop.message.api.mp;

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
 * C 端设备绑定（ADR-018）。App 登录后上报 clientId，登出前解绑。
 *
 * <p>B 端有一套同形的（{@code /biz/push-token}）：两端账号池相同但收件箱不同，
 * 绑定也要分开 —— 一个人既是买家又是店员时，两边的推送互不相干。
 */
@Profile("api")
@RestController
@Validated
public class MpPushTokenController {

    private final PushTokenBinder pushTokenBinder;

    public MpPushTokenController(PushTokenBinder pushTokenBinder) {
        this.pushTokenBinder = pushTokenBinder;
    }

    @PostMapping("/mp/push-token")
    public void register(@RequestBody RegisterReq req) {
        pushTokenBinder.register(MsgMessage.RECEIVER_USER, SecurityUtils.currentUserNo(),
                req.platform(), req.provider(), req.clientId());
    }

    /**
     * 解绑。**登出流程必须调它** —— 不调的话，这台设备换人登录后，
     * 前一个账号的订单仍然推到这里。
     *
     * <p>用 POST 而不是 DELETE：端上的请求层只走 GET/POST 两条路
     * （c-app/b-app 的 {@code call()}），给 DELETE 会静默降级成 POST，
     * 表现是「解绑没报错但没生效」—— 而这正是最不能静默失败的一个动作。
     */
    @PostMapping("/mp/push-token/unregister")
    public void unregister(@RequestBody(required = false) UnregisterReq req) {
        pushTokenBinder.unregister(MsgMessage.RECEIVER_USER, SecurityUtils.currentUserNo(),
                req == null ? null : req.clientId());
    }

    /** provider 可空：uni-push 打包上报即个推，端上可不带，后端回落 GETUI。 */
    public record RegisterReq(@NotBlank String platform, String provider, @NotBlank String clientId) {
    }

    /** clientId 可空：端上取不到时按人解绑全部设备（宁可多解一台）。 */
    public record UnregisterReq(String clientId) {
    }
}
