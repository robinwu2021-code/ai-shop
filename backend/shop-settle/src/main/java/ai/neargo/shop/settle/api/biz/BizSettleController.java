package ai.neargo.shop.settle.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.settle.SettleService;
import ai.neargo.shop.settle.dto.RateCardVO;
import ai.neargo.shop.settle.dto.SettleBillVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端结算（[API 清单 §3.10]）。**一期只读**：提现在 P1。
 *
 * <p>金额三列都给出去（基数/佣金/实得）—— 商家要能自己核对，
 * 只给一个「实得」会让每一次结算都变成一次客服对话。
 */
@RestController
public class BizSettleController {

    private final SettleService settleService;

    public BizSettleController(SettleService settleService) {
        this.settleService = settleService;
    }

    @GetMapping("/biz/settle/bills")
    public List<SettleBillVO> bills() {
        return settleService.merchantBills(BizContext.requireMerchantNo());
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
