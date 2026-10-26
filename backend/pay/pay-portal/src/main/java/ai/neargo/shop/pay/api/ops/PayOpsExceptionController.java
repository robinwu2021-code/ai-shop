package ai.neargo.shop.pay.api.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.acquiring.service.PayExceptionService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService;
import ai.neargo.shop.pay.acquiring.service.PayOpsQueryService.ExceptionRow;
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

/** 支付异常（关单后到账、重复支付、金额不符）：原路退回或判定无需处置（W3 E04 的运营入口） */
@Profile("ops")
@RestController
@RequestMapping("/pay-ops/v1/exceptions")
public class PayOpsExceptionController {

    private final PayOpsQueryService query;
    private final PayExceptionService exceptions;

    public PayOpsExceptionController(PayOpsQueryService query, PayExceptionService exceptions) {
        this.query = query;
        this.exceptions = exceptions;
    }

    record IgnoreReq(String note) {
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_VIEW + "')")
    @GetMapping
    public List<ExceptionRow> list(@RequestParam(required = false) String status,
                                   @RequestParam(defaultValue = "50") int limit) {
        return query.exceptions(status, limit);
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_MANAGE + "')")
    @PostMapping("/{id}/refund")
    public void refund(@PathVariable Long id) {
        exceptions.refund(id, SecurityUtils.currentUserNo());
    }

    @PreAuthorize("@perm.can('" + Perms.PAY_ORDER_MANAGE + "')")
    @PostMapping("/{id}/ignore")
    public void ignore(@PathVariable Long id, @RequestBody IgnoreReq req) {
        exceptions.ignore(id, SecurityUtils.currentUserNo(), req.note());
    }
}
