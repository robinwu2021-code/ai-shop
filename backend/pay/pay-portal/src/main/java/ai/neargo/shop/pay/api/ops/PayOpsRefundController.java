package ai.neargo.shop.pay.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.port.InternalCallers;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.RefundRow;
import ai.neargo.shop.pay.acquiring.service.RefundService;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 退款列表与复核（F51）：运营<b>只复核、不发起</b>。两名不同的人先后通过才执行（申请人不能复核自己），
 * 规则在 {@code RefundService#review} 与审批服务里，这里只把「是谁」带进去。
 */
@Profile("ops")
@RestController
@RequestMapping("/pay-ops/v1/refunds")
public class PayOpsRefundController {

    private final PayOpsQueryService query;
    private final RefundService refunds;
    private final InternalCallers callers;

    public PayOpsRefundController(PayOpsQueryService query, RefundService refunds, InternalCallers callers) {
        this.query = query;
        this.refunds = refunds;
        this.callers = callers;
    }

    record ReviewReq(Boolean approve, String reason) {
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_REFUND_VIEW + "')")
    @GetMapping
    public List<RefundRow> list(@RequestParam(required = false) String status,
                                @RequestParam(defaultValue = "50") int limit) {
        return query.refunds(status, limit);
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_REFUND_REVIEW + "')")
    @PostMapping("/{payRefundNo}/review")
    public RefundRow review(@PathVariable String payRefundNo, @RequestBody ReviewReq req) {
        var r = refunds.review(callers.opsOperator(SecurityUtils.currentUserNo()), payRefundNo,
                Boolean.TRUE.equals(req.approve()), req.reason());
        return query.refunds(null, 200).stream().filter(x -> x.payRefundNo().equals(r.getPayRefundNo())).findFirst()
                .orElse(new RefundRow(r.getPayRefundNo(), null, r.getAmountMinor() == null ? 0 : r.getAmountMinor(),
                        r.getCurrency(), r.getStatus(), r.getReason(), r.getRequestedBy(), r.getSourceType(),
                        r.getHitReasons(), null));
    }
}
