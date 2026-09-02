package ai.neargo.shop.paybridge.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.paybridge.SettleGenerationOrchestrator;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.settle.SettlePort;
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

    private final SettleService settleService;
    private final PaymentLedgerService paymentLedger;

    private final SettleGenerationOrchestrator orchestrator;
    /** 向通道下单要它。**按 pay_channel 路由**，通道没接入时直接失败不回退 */
    private final ai.neargo.shop.pay.channel.PayGatewayRouter gatewayRouter;

    public SettlePortImpl(SettleService settleService, PaymentLedgerService paymentLedger,
                          SettleGenerationOrchestrator orchestrator,
                          ai.neargo.shop.pay.channel.PayGatewayRouter gatewayRouter) {
        this.settleService = settleService;
        this.paymentLedger = paymentLedger;
        this.orchestrator = orchestrator;
        this.gatewayRouter = gatewayRouter;
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

        var r = gateway.prepay(new ai.neargo.shop.pay.channel.PayGateway.PrepayCommand(
                outTradeNo, cmd.amountMinor(), null, "订单 " + cmd.orderNo(),
                null, null, null));

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
}
