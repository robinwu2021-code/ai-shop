package ai.neargo.shop.pay.channel;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 按 {@code pay_channel} 路由到具体网关。
 *
 * <p>路由表由 Spring 注入的实现列表**自动构成**，不手写 switch ——
 * 手写的话每加一个通道要改两处（新实现 + switch），而漏改 switch 的表现是
 * 「新通道的单静默走了旧通道」，钱会进错账户。
 */
@Component
public class PayGatewayRouter {

    private final Map<String, PayGateway> byChannel;

    public PayGatewayRouter(List<PayGateway> gateways) {
        this.byChannel = gateways.stream()
                /*
                 * 同名通道有两个实现时**说人话再炸**。
                 *
                 * 默认的 toMap 抛的是 `IllegalStateException: Duplicate key WECHAT`，
                 * 而真实场景是「微信的直连网关与收付通网关被同时装配」——
                 * 从那句话看不出该去改哪个配置项。
                 * 让它自己失败没问题（**绝不能任选一个**：选错等于把钱发到
                 * 另一种商户号的接口上），但要说清楚原因。
                 */
                .collect(Collectors.toMap(PayGateway::payChannel, Function.identity(),
                        (a, b) -> {
                            throw new IllegalStateException("支付通道 " + a.payChannel()
                                    + " 有两个网关同时装配：" + a.getClass().getSimpleName()
                                    + " 与 " + b.getClass().getSimpleName()
                                    + " —— 检查 shop.pay.wechat.mode（direct / ecommerce）");
                        }));
    }

    /**
     * @throws BizException 通道没有实现时**直接失败**，不回退到默认通道 ——
     *                      回退等于把钱发到另一个通道的商户号，那是资金事故
     */
    public PayGateway of(String payChannel) {
        PayGateway g = byChannel.get(payChannel);
        if (g == null) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "支付通道未接入：" + payChannel);
        }
        return g;
    }

    public boolean supports(String payChannel) {
        return byChannel.containsKey(payChannel);
    }
}
