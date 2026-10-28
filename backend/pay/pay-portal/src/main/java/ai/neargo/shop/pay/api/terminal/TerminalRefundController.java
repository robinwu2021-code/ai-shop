package ai.neargo.shop.pay.api.terminal;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.terminal.service.TerminalVoidService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** POS 终端撤销 / 退款（pay-guard 开发计划 M6.4）：都要店长授权码，过单终端日限额与退款复核规则 */
@RestController
@RequestMapping("/terminal/pay/v1/payments")
public class TerminalRefundController {

    private final TerminalVoidService voids;

    public TerminalRefundController(TerminalVoidService voids) {
        this.voids = voids;
    }

    record RefundReq(Long amountMinor, String managerCode, String reason, String outRefundNo) {
    }

    record RefundVO(String payRefundNo, long amountMinor, String currency, String status, String authorizedBy) {

        static RefundVO of(PayRefund r) {
            return new RefundVO(r.getPayRefundNo(), r.getAmountMinor() == null ? 0 : r.getAmountMinor(), r.getCurrency(),
                    r.getStatus(), r.getAuthorizedBy());
        }
    }

    @PostMapping("/{payOrderNo}/void")
    public RefundVO voidCharge(PayCaller caller, @PathVariable String payOrderNo, @RequestBody RefundReq req) {
        return RefundVO.of(voids.refund(caller, "VOID", payOrderNo, req.amountMinor() == null ? 0 : req.amountMinor(),
                req.managerCode(), req.reason(), req.outRefundNo()));
    }

    @PostMapping("/{payOrderNo}/refunds")
    public RefundVO refund(PayCaller caller, @PathVariable String payOrderNo, @RequestBody RefundReq req) {
        return RefundVO.of(voids.refund(caller, "REFUND", payOrderNo, req.amountMinor() == null ? 0 : req.amountMinor(),
                req.managerCode(), req.reason(), req.outRefundNo()));
    }
}
