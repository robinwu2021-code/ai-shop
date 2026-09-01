package ai.neargo.shop.paybridge.port;

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

    private final SettleService settleService;
    private final PaymentLedgerService paymentLedger;

    private final SettleGenerationOrchestrator orchestrator;

    public SettlePortImpl(SettleService settleService, PaymentLedgerService paymentLedger,
                          SettleGenerationOrchestrator orchestrator) {
        this.settleService = settleService;
        this.paymentLedger = paymentLedger;
        this.orchestrator = orchestrator;
    }

    @Override
    public int generateForOrder(String orderNo) {
        // 编排在 SettleGenerationOrchestrator：组装子单构成 + 商家属性快照，
        // 那两样支付域都不该自己去查
        return orchestrator.generateForOrder(orderNo);
    }

    @Override
    public void openPayment(PaymentOpen cmd) {
        paymentLedger.open(cmd);
    }

    @Override
    public void settlePayment(PaymentSettled cmd) {
        paymentLedger.settle(cmd);
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
