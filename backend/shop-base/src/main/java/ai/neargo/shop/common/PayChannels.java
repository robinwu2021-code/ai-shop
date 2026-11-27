package ai.neargo.shop.common;

import java.util.Set;

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
 * <h2>这里只收「不在渠道字典里、代码却要认的」那几个</h2>
 * 运营可开关的渠道（微信、支付宝…）是字典 {@code DictType.PAY_CHANNEL}，名字与启停经 {@code DictionaryPort} 读；
 * 这里只放字典里没有的码（免支付、线下、假网关），它们由代码决定，不由运营配置。
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

    /** 线下收款（现金、自带设备）：钱不经过任何通道 */
    public static final String OFFLINE = "OFFLINE";

    /** 开发期“恒成功”假网关（{@code shop.pay.stub=true}） */
    public static final String STUB = "STUB";

    /** 测试渠道：走真实状态机的假网关；生产种子里是停用的 */
    public static final String TEST = "TEST";

    /**
     * 不对应任何收款号的渠道：结算单上的收款号按它们不过滤渠道，取「主体默认号」（F01 · Review R01）。
     * {@link #OFFLINE} / {@link #FREE} 钱不经过通道；{@link #STUB} / {@link #TEST} 不收真钱 ——
     * 按它们过滤会让开发 / 测试环境的结算单一律取不到号。原 {@code MerchantQueryPort.CHANNEL_FREE_PAY_CHANNELS}。
     */
    public static final Set<String> NO_CHANNEL_ACCOUNT = Set.of(OFFLINE, FREE, STUB, TEST);
}
