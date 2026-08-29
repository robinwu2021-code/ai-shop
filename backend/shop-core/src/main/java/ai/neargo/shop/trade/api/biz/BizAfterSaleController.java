package ai.neargo.shop.trade.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import jakarta.validation.Valid;
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

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @GetMapping("/biz/after-sale")
    public List<AfterSaleVO> list(@RequestParam(required = false) String status) {
        return afterSaleService.merchantList(BizContext.requireMerchantNo(), status);
    }

    /**
     * 同意。**收商家的说明**（可空）。
     *
     * <p>此前这个端点不收 body，而端上一直在发 `{remark}` —— 于是
     * <b>商家同意时写的那句话被丢在半路</b>：C 端订单页的「商家回复」永远是空的，
     * 买家只看到状态变了、不知道商家说了什么。驳回那条一直是收的，
     * 两条路一个收一个不收，看起来像「同意不需要解释」——
     * 而实际最需要解释的正是「同意退货、请寄回」这种。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/approve")
    public AfterSaleVO approve(@PathVariable String afterSaleNo,
                               @RequestBody(required = false) ApproveReq req) {
        return afterSaleService.approve(BizContext.requireMerchantNo(), afterSaleNo,
                req == null ? null : req.remark());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/reject")
    public AfterSaleVO reject(@PathVariable String afterSaleNo, @RequestBody @Valid RejectReq req) {
        return afterSaleService.reject(BizContext.requireMerchantNo(), afterSaleNo, req.remark());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.AFTERSALE + "')")
    @PostMapping("/biz/after-sale/{afterSaleNo}/receive")
    public AfterSaleVO confirmReturn(@PathVariable String afterSaleNo) {
        return afterSaleService.confirmReturn(BizContext.requireMerchantNo(), afterSaleNo);
    }

    public record RejectReq(@NotBlank String remark) {
    }

    /** @param remark 同意时的说明，可空 —— 驳回必须给理由，同意不强制 */
    public record ApproveReq(String remark) {
    }
}
