package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.payclient.ReceivableSummaryService;
import ai.neargo.shop.payclient.ReceivableSummaryService.Summary;

import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** 业务单收款汇总（TDD-业务单收款汇总）：拆单收款后 App 据此判断是否收齐。读复用收款查看码。 */
@Profile("api")
@RestController
public class BizReceivableController {

    private final ReceivableSummaryService receivables;

    public BizReceivableController(ReceivableSummaryService receivables) {
        this.receivables = receivables;
    }

    /**
     * @param dueMinor 应收（App 从结算单 / 工单账单读出）；不传 10400
     * @param currency 应收币种，三位大写
     * @param relatedBizNo 关联单号（可空）：美业工单结账时传预约号，预约下记的订金一并计入（W1.4）
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_ORDER_VIEW + "')")
    @GetMapping("/biz/stores/{storeNo}/receivables/{bizNo}")
    public Summary summary(@PathVariable String storeNo, @PathVariable String bizNo,
                           @RequestParam(required = false) Long dueMinor,
                           @RequestParam(required = false) String currency,
                           @RequestParam(required = false) String relatedBizNo) {
        BizContext context = BizContext.current();
        if (!Objects.equals(storeNo, context.currentStoreNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (dueMinor == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return receivables.summarize(context.requireMerchantNo(), storeNo, SecurityUtils.currentUserNo(), bizNo,
                relatedBizNo, dueMinor, currency);
    }
}
