package ai.neargo.shop.risk.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.risk.RiskService;
import ai.neargo.shop.risk.dto.BlacklistVO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端 · 被拉黑者提申诉（P-16.2.4 的另一半）。
 *
 * <p><b>ops-web 的契约里没有这条，它是本域补的。</b> 理由：运营端的
 * {@code decideBlacklistAppeal} 只在 {@code appealStatus=PENDING} 时可用，
 * 而没有任何东西能把状态置成 PENDING —— 那是一条**结构上不可达**的端点，
 * 也就是最难被发现的那种死接口：它有实现、有权限、有测试（如果测试自己造数据的话），
 * 唯独真实世界里永远不会被调到。
 *
 * <p>不做 {@code @Profile} 限定：C 端与运营端可能部署在不同 profile 上，
 * 而 mp 面是默认加载的那一套。
 */
@RestController
public class MpRiskController {

    private final RiskService riskService;

    public MpRiskController(RiskService riskService) {
        this.riskService = riskService;
    }

    /**
     * 对自己**当前生效中**的那条拉黑提申诉。
     *
     * <p>不收 {@code blackNo}：让用户从 URL 里挑一条别人的记录去申诉，
     * 是把主体校验交给了参数。当前生效的那条只有一条，直接按登录身份找。
     */
    @PostMapping("/mp/risk/appeal")
    public BlacklistVO appeal(@RequestBody AppealReq req) {
        return riskService.submitAppeal(SecurityUtils.currentUserNo(), req.reason());
    }

    public record AppealReq(String reason) {
    }
}
