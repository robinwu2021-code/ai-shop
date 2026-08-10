package ai.neargo.shop.settle.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端结算（[API 清单 §3.10]）。**一期只读**：提现在 P1。
 *
 * <p>金额三列都给出去（基数/佣金/实得）—— 商家要能自己核对，
 * 只给一个「实得」会让每一次结算都变成一次客服对话。
 */
@Profile("api")
@RestController
public class BizSettleController {

    private final SettleService settleService;

    public BizSettleController(SettleService settleService) {
        this.settleService = settleService;
    }

    /**
     * 结算流水。作用域与订单页同一套惯例：默认当前门店，{@code allStores=true} 才看全部。
     *
     * <p>「全部」对老板和店员不是一回事 —— 老板的全部是主体名下所有店，
     * 店员的全部只是他被授权的那几家。这里跟订单页用<b>同一个</b>
     * {@code allowedStoresOrAll()}，不另写一套：钱的作用域比订单更不能出错，
     * 而两套实现迟早有一套忘了跟上授权模型的变化。
     *
     * <p>存量流水没有 {@code store_no}，按当前门店筛会把它们全部滤掉 ——
     * 所以只在<b>真的有多家店</b>时才收窄，单店商家永远看到全部（与今天逐字一致）。
     */
    @GetMapping("/biz/settle/bills")
    public List<SettleBillVO> bills(
            @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean allStores) {
        var ctx = BizContext.current();
        java.util.Collection<String> scope = Boolean.TRUE.equals(allStores)
                ? ctx.allowedStoresOrAll()
                : java.util.List.of(ctx.currentStoreNo() == null ? "" : ctx.currentStoreNo());
        return settleService.merchantBills(BizContext.requireMerchantNo(), scope);
    }

    @GetMapping("/biz/settle/bills/{settleNo}")
    public SettleBillVO bill(@PathVariable String settleNo) {
        return settleService.merchantBill(BizContext.requireMerchantNo(), settleNo);
    }

    @GetMapping("/biz/settle/rate-card")
    public RateCardVO rateCard() {
        return settleService.rateCard();
    }
}
