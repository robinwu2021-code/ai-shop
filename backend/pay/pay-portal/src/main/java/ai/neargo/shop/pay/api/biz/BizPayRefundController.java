package ai.neargo.shop.pay.api.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import java.time.ZoneOffset;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家发起原路退款（F40）：与开放 API 同一条退款单流程（可退余额、复核规则、执行器）；命中复核时状态是「待平台复核」。
 * 操作员记在退款单上（{@code requested_by}）。
 */
@Profile("api")
@RestController
@RequestMapping("/biz/pay/refund")
public class BizPayRefundController {

    private final RefundService refunds;
    private final BizPayContext ctx;

    public BizPayRefundController(RefundService refunds, BizPayContext ctx) {
        this.refunds = refunds;
        this.ctx = ctx;
    }

    /** @param outRefundNo 可不传（每次点击各算一笔）；传了就按它幂等 —— 端上防连点时带同一个 */
    record RefundReq(String payOrderNo, Long amountMinor, String reason, String outRefundNo) {
    }

    record RefundView(String payRefundNo, Long payOrderId, long amountMinor, String currency, String status,
                      String reason, String requestedBy, Long createdAt, Long succeededAt) {

        static RefundView of(PayRefund r) {
            return new RefundView(r.getPayRefundNo(), r.getPayOrderId(), r.getAmountMinor() == null ? 0 : r.getAmountMinor(),
                    r.getCurrency(), r.getStatus(), r.getReason(), r.getRequestedBy(),
                    r.getCreatedAt() == null ? null : r.getCreatedAt().toInstant(ZoneOffset.UTC).toEpochMilli(),
                    r.getSucceededAt() == null ? null : r.getSucceededAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        }
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_REFUND + "')")
    @PostMapping
    public RefundView request(@RequestBody RefundReq req) {
        if (req.payOrderNo() == null || req.payOrderNo().isBlank() || req.amountMinor() == null || req.amountMinor() <= 0
                || (req.reason() != null && req.reason().length() > 255)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String out = req.outRefundNo() == null || req.outRefundNo().isBlank() ? BizKey.next("BZR") : req.outRefundNo();
        Long storeId = ctx.storeId();
        return RefundView.of(refunds.request(ctx.caller(storeId), new RefundRequestCmd(req.payOrderNo(), out, out,
                req.amountMinor(), req.reason())));
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.PAY_REFUND + "') or @perm.canBiz('" + BizPerms.PAY_STATEMENT + "')")
    @GetMapping
    public List<RefundView> recent() {
        Long storeId = ctx.storeId();
        return refunds.recentOfStore(ctx.caller(storeId), storeId, 50).stream().map(RefundView::of).toList();
    }
}
