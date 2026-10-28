package ai.neargo.shop.pay.api.terminal;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.terminal.service.InStorePaymentService;
import ai.neargo.shop.pay.terminal.service.InStorePaymentService.Charge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS 终端收款（pay-guard 开发计划 M6.4；代码清单 §4.1）：在本店建单 → 刷卡 / 扫顾客付款码 → 结果不明（{@code UNKNOWN}）时
 * 轮询查单 → 取消（关前必查单）。门店只取终端绑定的那一家。
 */
@RestController
@RequestMapping("/terminal/pay/v1/payments")
public class TerminalPaymentController {

    private final InStorePaymentService inStore;

    public TerminalPaymentController(InStorePaymentService inStore) {
        this.inStore = inStore;
    }

    record CreateReq(String outOrderNo, Long amountMinor, String currency, String subject) {
    }

    /** @param encryptedCardData 读卡套件密文，原样透传，不解析、不落日志（请求记录整段脱敏） */
    record CardReq(String encryptedCardData) {
    }

    record CustomerCodeReq(String authCode) {
    }

    @PostMapping
    public Charge create(PayCaller caller, @RequestBody CreateReq req) {
        return inStore.create(caller, req.outOrderNo(), req.amountMinor() == null ? 0 : req.amountMinor(), req.currency(),
                req.subject());
    }

    @PostMapping("/{payOrderNo}/card")
    public Charge card(PayCaller caller, @PathVariable String payOrderNo, @RequestBody CardReq req) {
        return inStore.chargeCard(caller, payOrderNo, req.encryptedCardData());
    }

    @PostMapping("/{payOrderNo}/customer-code")
    public Charge customerCode(PayCaller caller, @PathVariable String payOrderNo, @RequestBody CustomerCodeReq req) {
        return inStore.chargeCustomerCode(caller, payOrderNo, req.authCode());
    }

    @GetMapping("/{payOrderNo}")
    public Charge payment(PayCaller caller, @PathVariable String payOrderNo) {
        return inStore.payment(caller, payOrderNo);
    }

    @PostMapping("/{payOrderNo}/cancel")
    public Charge cancel(PayCaller caller, @PathVariable String payOrderNo) {
        return inStore.cancel(caller, payOrderNo);
    }
}
