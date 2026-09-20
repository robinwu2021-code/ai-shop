package ai.neargo.shop.common;

/**
 * 支付通道名 —— {@code stl_payment.pay_channel} 的取值，也是
 * {@code PayGatewayRouter} 的路由键。
 *
 * <h2>为什么要这个类</h2>
 * 通道名此前是**散在各个网关里的字符串字面量**（{@code return "WECHAT";}）。
 * 交易域要判「这一单走哪个通道」时够不着它们 —— {@code shop-core} 不依赖
 * {@code pay-channel}，依赖方向是 pay → core。于是只能在交易域里再写一遍字面量，
 * 而两处写的字符串一旦有一个字母不同，表现是
 * <b>「通道未接入」而不是编译错误</b>：路由表按字符串查，查不到就抛。
 *
 * <p>放 {@code shop-base}：交易域与支付域都依赖它，是两边唯一的交汇处。
 *
 * <h2>这里只收「交易域需要认识的」那几个</h2>
 * 不是通道全集。{@code ALIPAY} 之类交易域从不按名字判，就不必进来 ——
 * 进来了反而像是在暗示「这些值各处都能用」。
 */
public final class PayChannels {

    private PayChannels() {
    }

    /**
     * 免支付：**应付金额为 0** 的单走它。
     *
     * <p>它是生产的真通道，不是假网关 —— 0 元本来就不需要向外部系统付款。
     * 见 {@code FreePayGateway} 与 TDD-零元订单支付。
     *
     * <p><b>端上不该按它判「要不要唤起收银台」</b>：那用 {@code PayResult.settled}。
     * 按通道名判的话，将来多一个免支付的来源（全额积分抵扣、全额券）就要改端上。
     */
    public static final String FREE = "FREE";
}
