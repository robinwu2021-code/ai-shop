package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端售后（[API 清单 §3.7]）。
 *
 * <p>**极速退的单商家只能看，不能拒**（矩阵 6.2）—— 那是平台的规则决定的退款，
 * 拒绝入口交给状态机挡，而不是在这里判身份：判身份的写法迟早会漏掉某个入口。
 */
@Profile("api")
@RestController
@Validated
public class BizAfterSaleController {

    private final AfterSaleService afterSaleService;

    public BizAfterSaleController(AfterSaleService afterSaleService) {
        this.afterSaleService = afterSaleService;
    }

    @GetMapping("/biz/after-sale")
    public List<AfterSaleVO> list(@RequestParam(required = false) String status) {
        return afterSaleService.merchantList(BizContext.requireMerchantNo(), status);
    }

    @PostMapping("/biz/after-sale/{afterSaleNo}/approve")
    public AfterSaleVO approve(@PathVariable String afterSaleNo) {
        return afterSaleService.approve(BizContext.requireMerchantNo(), afterSaleNo);
    }

    @PostMapping("/biz/after-sale/{afterSaleNo}/reject")
    public AfterSaleVO reject(@PathVariable String afterSaleNo, @RequestBody RejectReq req) {
        return afterSaleService.reject(BizContext.requireMerchantNo(), afterSaleNo, req.remark());
    }

    @PostMapping("/biz/after-sale/{afterSaleNo}/receive")
    public AfterSaleVO confirmReturn(@PathVariable String afterSaleNo) {
        return afterSaleService.confirmReturn(BizContext.requireMerchantNo(), afterSaleNo);
    }

    public record RejectReq(@NotBlank String remark) {
    }
}
