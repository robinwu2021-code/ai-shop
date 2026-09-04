package ai.neargo.shop.paybridge.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.paybridge.SettleGenerationOrchestrator;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.user.UserIdentityPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link SettlePort} 实现 —— <b>薄适配器，逻辑全在 {@link SettleService}</b>。
 *
 * <p>这个类只转发三个调用，看起来像多余的一层。留着它是因为两个接口的<b>受众不同</b>：
 *
 * <ul>
 *   <li>{@code SettleService} 还有 {@code merchantBills} / {@code rateCard} 这些
 *       给 B 端商家看账单的读方法 —— 那是本域自己的事</li>
 *   <li>{@code SettlePort} 只暴露 trade 域真正需要的三个写操作：生成、回退、退款</li>
 * </ul>
 *
 * <p>合成一个类的话，trade 会拿到整个 SettleService，包括商家账单查询 ——
 * 一个域能看到另一个域的全部能力，边界就只剩注释在维护了。
 *
 * <p>另一个实际好处：将来结算独立部署时，改的是这个适配器（换成 RPC 客户端），
 * {@code SettleServiceImpl} 与所有调用方都不动。
 *
 * <h2>2026-09-01：从 pay-domain 搬到 shop-app/paybridge</h2>
 * 因为 {@code generateForOrder} 不再是转发，而是<b>编排</b> ——
 * 它要把「子单构成」（trade）与「商家属性快照」（merchant）组装好再交给支付域。
 * 那两样支付域都不该自己去查（反向依赖），而这一层同时够得着两边。
 *
 * <p>其余四个方法仍是纯转发。<b>放在一起是因为它们实现同一个接口</b> ——
 * 拆成两个 bean 的话，调用方要知道「哪个方法找哪个实现」，
 * 而那正是这个 Port 存在要挡掉的事。
 */
@Component
public class SettlePortImpl implements SettlePort {

    private static final Logger log = LoggerFactory.getLogger(SettlePortImpl.class);

    /**
     * 币种与支付方式暂时是常量，<b>但要写成常量而不是字面量</b>：
     * 多市场（[TDD-支付域 · 多区域通道]）落地时，它们要从订单的市场属性里取，
     * 而那天要改的地方必须只有一处。散成字面量的话，改漏一处的表现是
     * 「某个市场的单按 CNY 结算」—— 金额对、币种错，账面上看不出来。
     */
    private static final String CURRENCY_CNY = "CNY";
    /** 一期只在小程序里收款。端形态扩到 App 时这里要按端传值，不能默认 */
    private static final String PAY_METHOD_JSAPI = "JSAPI";
    private static final String WECHAT = "WECHAT";

    private final SettleService settleService;
    private final PaymentLedgerService paymentLedger;

    private final SettleGenerationOrchestrator orchestrator;
    /** 向通道下单要它。**按 pay_channel 路由**，通道没接入时直接失败不回退 */
    private final ai.neargo.shop.pay.channel.PayGatewayRouter gatewayRouter;
    /**
     * 取付款人在通道侧的标识（小程序 openid）。
     *
     * <p><b>为什么在这一层取，而不是让 trade 传进来</b>：这个类的职责本来就是
     * 「把两边的事实组装好再交给支付域」（见 {@code generateForOrder} 的注释）。
     * 让 trade 传的话，`SettlePort.PaymentOpen` 要加一个只有微信 JSAPI 用得上的字段，
     * 而 `OrderServiceImpl` 要多一个构造参数 —— 一个通道的细节爬进了订单域的契约。
     */
    private final UserIdentityPort identityPort;
    /**
     * 记进 {@code stl_payment.wx_appid} 用。<b>与 openid 成对才有意义</b> ——
     * 同一个人在不同小程序下是不同的 openid，只存 openid 不存 appid，
     * 将来多一个应用时就分不清那一行属于谁。
     */
    private final String wechatAppId;

    public SettlePortImpl(SettleService settleService, PaymentLedgerService paymentLedger,
                          SettleGenerationOrchestrator orchestrator,
                          ai.neargo.shop.pay.channel.PayGatewayRouter gatewayRouter,
                          UserIdentityPort identityPort,
                          @Value("${shop.pay.wechat.appid:}") String wechatAppId) {
        this.settleService = settleService;
        this.paymentLedger = paymentLedger;
        this.orchestrator = orchestrator;
        this.gatewayRouter = gatewayRouter;
        this.identityPort = identityPort;
        this.wechatAppId = wechatAppId;
    }

    @Override
    public int generateForOrder(String orderNo) {
        // 编排在 SettleGenerationOrchestrator：组装子单构成 + 商家属性快照，
        // 那两样支付域都不该自己去查
        return orchestrator.generateForOrder(orderNo);
    }

    @Override
    public String openPayment(PaymentOpen cmd) {
        return paymentLedger.open(cmd);
    }

    @Override
    public PayInitResult initPayment(PaymentOpen cmd) {
        /*
         * ① 先落流水。顺序不能反 —— 先下单再记账的话，两者之间进程挂掉，
         *    用户手上有一个能付的凭据而我方一无所知，那笔钱进来后没人认领它。
         */
        String outTradeNo = paymentLedger.open(cmd);

        /*
         * ② 通道没接入时**直接失败**，不回退到别的通道。
         *    回退等于把钱发到另一个通道的商户号 —— 那是资金事故。
         */
        ai.neargo.shop.pay.channel.PayGateway gateway;
        try {
            gateway = gatewayRouter.of(cmd.payChannel());
        } catch (RuntimeException e) {
            log.warn("[pay-init] 通道 {} 未接入，关掉刚落的流水 {}", cmd.payChannel(), outTradeNo);
            paymentLedger.close(outTradeNo, "通道未接入");
            return new PayInitResult(false, outTradeNo, cmd.payChannel(),
                    java.util.Map.of(), "支付通道未接入：" + cmd.payChannel());
        }

        /*
         * 四个此前传 null 的入参补上（2026-09-04）。
         *
         * **`payerId` 传 null 是真通道接上那天必炸的一处**：微信 JSAPI 下单
         * 的 `payer.openid` 是必填。此前不炸只因为下单一直走 STUB/TEST，
         * 而它们不看这个字段 —— 桩把缺件盖住了，正是这个仓库反复吃过亏的那种绿。
         *
         * `subMchId` 仍是 null：直连商户号（ADR-017 路径 A「归集」）没有二级商户，
         * 钱进平台商户号。收付通模式接上时它才有值，那时从主体属性里取。
         */
        String payerId = identityPort.wxOpenIdMp(cmd.userNo()).orElse(null);
        var r = gateway.prepay(new ai.neargo.shop.pay.channel.PayGateway.PrepayCommand(
                outTradeNo, cmd.amountMinor(), CURRENCY_CNY, "订单 " + cmd.orderNo(),
                PAY_METHOD_JSAPI, null, payerId));

        /*
         * ③ 下单失败就**关掉流水**，不留在 PENDING。
         *
         *    留着的话对账轴会反复回查一笔通道那边压根不存在的单 ——
         *    每轮查一次、每轮查不到，而「查询失败绝不关单」那条规则
         *    会让它永远留在那里。
         *    关掉之后用户重试会开一笔新的（带后缀的新单号），那正是重试该有的样子。
         */
        if (!r.success()) {
            log.warn("[pay-init] 通道 {} 下单失败：{}，关掉流水 {}",
                    cmd.payChannel(), r.message(), outTradeNo);
            paymentLedger.close(outTradeNo, r.message());
            return new PayInitResult(false, outTradeNo, cmd.payChannel(),
                    java.util.Map.of(), r.message());
        }
        /*
         * **下单成功之后才记付款人。** 那两列从建表起就没被写过（见
         * PaymentLedgerService#recordPayer）—— 用户报「我付了钱」时，
         * 客服手上只有订单号，而要去微信商户平台对上那一笔靠的是 openid。
         *
         * 放在这里而不是开流水那一步：开流水**先于**取 openid（顺序不能反），
         * 而下单失败的流水会被关掉，给关掉的流水记付款人没有意义。
         */
        paymentLedger.recordPayer(outTradeNo, payerId,
                WECHAT.equals(cmd.payChannel()) ? wechatAppId : null);
        return new PayInitResult(true, outTradeNo, cmd.payChannel(), r.params(), null);
    }

    @Override
    public String settlePayment(PaymentSettled cmd) {
        return paymentLedger.settle(cmd);
    }


    @Override
    public boolean reverseSplit(String subOrderNo) {
        return settleService.reverseSplit(subOrderNo);
    }

    @Override
    public String refund(String subOrderNo, long amountMinor, String reason) {
        return settleService.refund(subOrderNo, amountMinor, reason);
    }

    @Override
    public String settleRefund(String outRefundNo, String providerNo) {
        return paymentLedger.settleRefundByOutTradeNo(outRefundNo, providerNo);
    }
}
