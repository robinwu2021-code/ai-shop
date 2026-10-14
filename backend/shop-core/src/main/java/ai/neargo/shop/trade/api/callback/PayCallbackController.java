package ai.neargo.shop.trade.api.callback;

import ai.neargo.shop.spi.pay.PayMessagePort;
import ai.neargo.shop.trade.service.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.RestController;
import ai.neargo.shop.spi.settle.SettlePort;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 支付回调（[API 清单 §5.2]）。**不走 Bearer**，靠验签。
 *
 * <p>S2 是 stub 通道：用一个共享密钥代替微信的 V3 签名验签，接口形态与幂等语义已经就位，
 * S4 换真微信支付时改的是 {@link #verify}，不是 {@link OrderService#markPaid}。
 *
 * <p><b>回调只推进不回退</b>：重复回调由 {@code markPaid} 的幂等保证，
 * 乱序回调由状态机保证（{@code CANCELLED} 不能变 {@code PAID}）。
 */
@Profile("api")
/*
 * **生产里根本不装配**（2026-09-04）。此前它只挂 @Profile("api")，也就是
 * 生产**存在**这个端点，而它的共享密钥用的是默认值 stub-secret，线上从没覆盖过 ——
 * 挡住它的一直只是「nginx 没反代 /callback」这件偶然的事
 * （见 docs/qa/线上验收-总纲.md 第 3 条，那里把它记作「侥幸」）。
 *
 * 现在 nginx 整段放行 /pay/callback/，那道侥幸就没了。所以把保护换成装配条件：
 * 生产 SHOP_PAY_STUB=false（已核），这个 bean 不存在，路径直接 404。
 * 测试世界由 application-test.yml 开着——三十多个场景用例靠它推进「支付成功」。
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shop.pay.stub", havingValue = "true")
@RestController
@RequestMapping("/pay/callback")
public class PayCallbackController {

    private static final Logger log = LoggerFactory.getLogger(PayCallbackController.class);

    private final OrderService orderService;
    private final SettlePort settlePort;
    private final PayMessagePort payMessage;

    /** stub 通道的共享密钥。生产用真实验签，这个属性届时删除。 */
    @Value("${shop.pay.stub-secret:stub-secret}")
    private String stubSecret;

    public PayCallbackController(OrderService orderService,
                                 SettlePort settlePort,
                                 PayMessagePort payMessage) {
        this.orderService = orderService;
        this.settlePort = settlePort;
        this.payMessage = payMessage;
    }

    @PostMapping("/stub")
    public String stubPaid(@RequestBody StubCallback body) {
        /*
         * **报文先落，再处理**（与 ChannelPayCallbackController 同一套顺序）。
         * 独立事务：下面记账失败要回滚业务，而报文必须留下 ——
         * 处理失败的那一次，恰恰是最需要报文的那一次。
         *
         * 这个入口拿到的是**已经绑好的对象**而不是原始串（stub 是开发期通道，
         * 没有真通道那套签名头），所以直接按字段存，不走未验签报文那条路。
         */
        String msgNo = payMessage.callbackReceived("STUB", "/pay/callback/stub",
                Map.of(), null);

        if (!verify(body)) {
            // 验签失败只回 FAIL，不透露原因 —— 回调端点是公网可达的
            log.warn("pay callback verify failed: {}", body.outTradeNo());
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED, "验签失败",
                    body.outTradeNo(), null, fields(body));
            return "FAIL";
        }
        /*
         * **支付域的账先落，再改订单状态**（与 ChannelPayCallbackController 同一套顺序）。
         * 权威在支付域，订单状态是下游投影 —— 反过来一旦记账失败，
         * 库里就是「订单说付了、而支付域没有这笔钱」，没有任何东西能发现。
         *
         * <b>这里有两个回调入口，都要走这一步。</b>2026-09-01 补写流水时先只改了
         * 多通道那个，测试当场红在「一笔成功支付都没扫到」——
         * 因为今天真正在用的是这个 stub 入口。
         */
        String orderNo = settlePort.settlePayment(new SettlePort.PaymentSettled(
                body.outTradeNo(), "STUB", body.transactionId(), 0L, System.currentTimeMillis()));

        /*
         * **订单号由支付域给**，不要把 outTradeNo 当订单号用。
         *
         * 2026-09-01 起商户单号是「订单号 + 尝试序号」：第一次仍等于订单号，
         * 而**重试单带 -2、-3 后缀**。直接拿它去 markPaid，
         * 重试付成功的那些单会查不到订单 —— 而报错指向「订单不存在」，
         * 离真因隔了一层。只在重试路径上错，是最难在测试里撞见的那种。
         */
        if (orderNo == null) {
            // 这笔钱认领不了：流水里没有这个单号。回 FAIL 让通道重推，同时留下线索
            log.error("[callback] 收到无法认领的收款 outTradeNo={} —— 支付流水里没有这个单号",
                    body.outTradeNo());
            payMessage.callbackSettled(msgNo, PayMessagePort.REJECTED,
                    "流水里没有这个商户单号", body.outTradeNo(), null, fields(body));
            return "FAIL";
        }
        orderService.markPaid(orderNo, "STUB", body.transactionId());
        payMessage.callbackSettled(msgNo, PayMessagePort.ACCEPTED, null,
                body.outTradeNo(), null, fields(body));
        return "SUCCESS";
    }

    /**
     * 落进报文表的字段。
     *
     * <p><b>不含 sign</b>：{@code PayloadMasker} 按键名遮，{@code sign} 本来就会被遮掉 ——
     * 这里不放进来是第二道，理由是「不该出库的东西最好一开始就没进过管道」。
     */
    private static Map<String, Object> fields(StubCallback body) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("outTradeNo", body.outTradeNo());
        m.put("transactionId", body.transactionId());
        return m;
    }

    private boolean verify(StubCallback body) {
        return stubSecret.equals(body.sign());
    }

    public record StubCallback(String outTradeNo, String transactionId, String sign) {
    }
}
