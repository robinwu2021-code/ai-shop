package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.message.nudge.MerchantNudgeService;
import ai.neargo.shop.message.nudge.MerchantNudgeService.NudgeResult;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 主动触达商家（M2）。
 *
 * <p>链条画像（M1）能指出「这家卡在哪一层」，此前指出来之后运营在系统里做不了任何事 ——
 * 商家域②里唯一的动作是违规处置与封禁。这是中间那一档。
 *
 * <p><b>一天一次。</b>结果里明说「今天已经提醒过了」，而不是靠底层幂等静默吞掉 ——
 * 静默的后果是运营看不出发没发出去，于是再点一次。
 */
@Profile("ops")
@RestController
public class OpsMerchantNudgeController {

    private final MerchantNudgeService nudge;

    public OpsMerchantNudgeController(MerchantNudgeService nudge) {
        this.nudge = nudge;
    }

    @PreAuthorize("@perm.can('" + Perms.MERCHANT_NUDGE + "')")
    @PostMapping("/ops/merchant/{entityNo}/nudge")
    public NudgeResult nudge(@PathVariable String entityNo, @RequestBody NudgeReq req) {
        return nudge.nudge(entityNo, req.reason(), req.note());
    }

    /**
     * @param reason 事由枚举，与链条画像的卡点一一对应。**不含 IN_AUDIT** ——
     *               那一档欠账的是平台，见 {@code MerchantNudgeService.Reason}
     * @param note   运营补充的一句话，可空。它会原样进商家的收件箱
     */
    public record NudgeReq(String reason, String note) {
    }
}
