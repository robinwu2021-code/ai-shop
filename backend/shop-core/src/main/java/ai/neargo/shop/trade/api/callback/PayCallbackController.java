package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.trade.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 支付回调（[API 清单 §5.2]）。**不走 Bearer**，靠验签。
 *
 * <p>S2 是 stub 通道：用一个共享密钥代替微信的 V3 签名验签，接口形态与幂等语义已经就位，
 * S4 换真微信支付时改的是 {@link #verify}，不是 {@link OrderService#markPaid}。
 *
 * <p><b>回调只推进不回退</b>：重复回调由 {@code markPaid} 的幂等保证，
 * 乱序回调由状态机保证（{@code CANCELLED} 不能变 {@code PAID}）。
 */
@RestController
@RequestMapping("/callback")
public class PayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PayCallbackController.class);

    private final OrderService orderService;

    /** stub 通道的共享密钥。生产用真实验签，这个属性届时删除。 */
    @Value("${shop.pay.stub-secret:stub-secret}")
    private String stubSecret;

    public PayCallbackController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/pay/stub")
    public String stubPaid(@RequestBody StubCallback body) {
        if (!verify(body)) {
            // 验签失败只回 FAIL，不透露原因 —— 回调端点是公网可达的
            log.warn("pay callback verify failed: {}", body.outTradeNo());
            return "FAIL";
        }
        orderService.markPaid(body.outTradeNo(), "STUB", body.transactionId());
        return "SUCCESS";
    }

    private boolean verify(StubCallback body) {
        return stubSecret.equals(body.sign());
    }

    public record StubCallback(String outTradeNo, String transactionId, String sign) {
    }
}
