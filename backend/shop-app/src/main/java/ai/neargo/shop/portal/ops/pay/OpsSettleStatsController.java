package ai.neargo.shop.portal.ops.pay;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.payclient.OpsSettleStatsAppService;
import ai.neargo.shop.payclient.OpsSettleStatsAppService.StatRowVO;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台端 · 结算口径的经营统计（P-12.1）。
 *
 * <p>门店 / 主体 / 收款商户号三个维度可切，按成交日区间聚合。
 *
 * <p><b>与「门店经营排行」（{@code /ops/store-ranking}）不是一回事</b>：
 * 那个读订单、是最近 N 天的 Top N，答的是「哪家店卖得好」；
 * 这里读结算单、是区间全量，答的是「这段时间各家该结多少」。
 * 金额口径也不同 —— GMV 没扣佣金与手续费。
 *
 * <p><b>返回裸数组而不是分页包</b>：维度值的条数由商家/门店数量决定，
 * 不会到需要翻页的量级，而一张要做合计的表分了页就没法在前端算总数。
 */
@Profile("ops")
@RestController
@Validated
public class OpsSettleStatsController {

    private final OpsSettleStatsAppService app;

    public OpsSettleStatsController(OpsSettleStatsAppService app) {
        this.app = app;
    }

    /**
     * @param dim          STORE / ENTITY / PAY_MERCHANT。**非法值直接拒**，
     *                     不回落到某个默认维度 —— 那会让传错的人拿到一份看似正常的数
     * @param businessMode 经营模式过滤，不传则两条轨道一起算
     */
    @GetMapping("/ops/settle-stats")
    @PreAuthorize("@perm.can('" + Perms.FINANCE_SETTLE_READ + "')")
    public List<StatRowVO> stats(@RequestParam @NotBlank String dim,
                                 @RequestParam @NotBlank String from,
                                 @RequestParam @NotBlank String to,
                                 @RequestParam(required = false) String businessMode) {
        return app.stats(dim, from, to, businessMode);
    }
}
