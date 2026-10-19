package ai.neargo.shop.paybridge.port;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.paybridge.SettleGenerationOrchestrator;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.spi.settle.RefundRequest;
import ai.neargo.shop.spi.settle.RefundDispatchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.spi.settle.RefundDispatchResult.DispatchStatus;
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
    /** 退款时的结算侧编排（按额回退分账，口径 A） */
    private final ai.neargo.shop.pay.settle.refund.SettlementRefundCoordinator settlementRefund;
    private final ai.neargo.shop.pay.settle.refund.CashRefundSplitService cashRefundSplit;

    public SettlePortImpl(SettleService settleService, PaymentLedgerService paymentLedger,
                          SettleGenerationOrchestrator orchestrator,
                          ai.neargo.shop.pay.channel.PayGatewayRouter gatewayRouter,
                          UserIdentityPort identityPort,
                          @Value("${shop.pay.wechat.appid:}") String wechatAppId,
                          ai.neargo.shop.pay.settle.refund.SettlementRefundCoordinator settlementRefund,
                          ai.neargo.shop.pay.settle.refund.CashRefundSplitService cashRefundSplit) {
        this.settlementRefund = settlementRefund;
        this.cashRefundSplit = cashRefundSplit;
        this.settleService = settleService;
        this.paymentLedger = paymentLedger;
        this.orchestrator = orchestrator;
        this.gatewayRouter = gatewayRouter;
        this.identityPort = identityPort;
        this.wechatAppId = wechatAppId;
    }

    private RefundExecutionService refunds;
    private ObjectProvider<BasicRefundExecutionService> refundExecutions;

    @Autowired
    public void setRefundExecutionService(RefundExecutionService refunds) {
        this.refunds = refunds;
    }

    @Autowired
    public void setRefundExecutions(ObjectProvider<BasicRefundExecutionService> refundExecutions) {
        this.refundExecutions = refundExecutions;
    }

    @Override
    public RefundDispatchResult settleRefund(String no, String channel, long amount, String currency, String provider) {
        RefundDispatchResult confirmed = refunds.confirm(no, channel, amount, currency, provider);
        if (confirmed.status() == DispatchStatus.SUCCEEDED && confirmed.executionNo() != null) {
            try {
                refundExecutions.getObject().applyResult(confirmed);
            } catch (RuntimeException projectionFailure) {
                log.warn("refund {} cash confirmed; original execution awaits projection recovery",
                        confirmed.executionNo(), projectionFailure);
            }
        }
        return confirmed;
    }

    @Override
    public PaymentReceipt refundOrigin(String orderNo, String tradeNo, String payChannel) {
        return refunds.origin(orderNo, tradeNo, payChannel);
    }

    @Override
    public boolean prepareRefundReversal(SplitReversal request) {
        return refunds.reverseUnsplit(request);
    }

    @Override
    public boolean prepareRefundReversal(SplitReversal request, boolean zeroLiability) {
        return refunds.reverseUnsplit(request, zeroLiability);
    }

    @Override
    public RefundDispatchResult requestRefund(RefundRequest request) {
        return refunds.request(request);
    }

    @Override
    public RefundDispatchResult queryRefundExecution(String executionNo) {
        return refunds.query(executionNo);
    }

    @Override
    public RefundDispatchResult queryRefundExecution(RefundRequest request) {
        return refunds.query(request);
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
        cmd = paymentLedger.paymentRequest(outTradeNo).orElseThrow(
                () -> new IllegalArgumentException("PAY_REQUEST_SNAPSHOT_REQUIRED"));

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
         * 门店直连使用本次支付已固化的通道账户；归集支付仍由平台账户收款。
         * paymentLedger 已拒绝同一支付指令覆盖多个直连账户或混合资金路径。
         */
        String payerId = identityPort.wxPayerOpenIdMp(cmd.userNo()).orElse(null);
        String subMchId = !cmd.stores().isEmpty()
                && "DIRECT".equals(cmd.stores().getFirst().route().fundsPath())
                ? cmd.stores().getFirst().route().subMerchantId() : null;
        var r = gateway.prepay(new ai.neargo.shop.pay.channel.PayGateway.PrepayCommand(
                // description 由交易域拼好带过来（只有那一层有订单明细）。
                // 传 "订单 "+orderNo 的老写法会让用户在微信购物订单里认不出这一单
                outTradeNo, cmd.amountMinor(), cmd.currency(), cmd.subject(),
                PAY_METHOD_JSAPI, subMchId, payerId));

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

        /*
         * **免支付通道：就地结清，不等回调。**
         *
         * 其它通道靠异步回调把流水推成 SUCCESS，而 0 元单
         * <b>没有任何外部系统会回调我们</b>。不在这里结清的话，那笔流水会永远停在
         * PENDING，被收款对账轴每轮回查一次、每轮查不到 —— 而它本来就不存在于
         * 任何通道那边。
         *
         * 结清放在这里而不是交给交易域：**支付成功这件事的权威在支付域**
         * （SettlePort#settlePayment 的类注释），订单状态是它的下游投影。
         * 反过来先改订单，一旦这一步失败，库里就是「订单说付了、而支付域没有这笔钱」。
         */
        if (ai.neargo.shop.common.PayChannels.FREE.equals(cmd.payChannel())) {
            paymentLedger.settle(new PaymentSettled(outTradeNo, cmd.payChannel(),
                    r.tradeNo(), 0L, System.currentTimeMillis()));
            log.info("[pay-init] 0 元单已就地结清：{}（订单 {}）", outTradeNo, cmd.orderNo());
        }
        return new PayInitResult(true, outTradeNo, cmd.payChannel(), r.params(), null);
    }

    @Override
    public PaymentReceipt successPayment(String orderNo) {
        return paymentLedger.successPayment(orderNo);
    }

    @Override
    public PaymentReceipt successPayment(String orderNo, String channel, String tradeNo) {
        return paymentLedger.successPayment(orderNo, channel, tradeNo);
    }

    @Override
    public PaymentReceipt paymentByOutTradeNo(String outTradeNo) {
        return paymentLedger.paymentByOutTradeNo(outTradeNo);
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
    public boolean reverseSplit(SplitReversal cmd) {
        return settlementRefund.reverse(cmd);
    }

    @Override
    public boolean reverseCashRefund(SplitReversal cmd) {
        return cashRefundSplit.reverse(cmd);
    }

    @Override
    public boolean reversePreparedRefund(SplitReversal cmd) {
        return settlementRefund.reversePrepared(cmd) && cashRefundSplit.reversePrepared(cmd);
    }

    @Override
    public boolean confirmPreparedRefund(SplitReversal cmd, String preparationNo, String inputHash) {
        return settlementRefund.confirmPrepared(cmd, preparationNo, inputHash);
    }

    @Override
    public boolean cancelPreparedRefund(SplitReversal cmd, String preparationNo, String inputHash) {
        return settlementRefund.cancelPrepared(cmd, preparationNo, inputHash);
    }

    @Override
    public void refreshRefund(String paymentNo) {
        settleService.refreshRefund(paymentNo);
    }

    @Override
    public String refund(String subOrderNo, long amountMinor, String reason) {
        return settleService.refund(subOrderNo, amountMinor, reason);
    }

    @Override
    public String refund(String subOrderNo, String afterSaleNo, long amountMinor, String reason) {
        return settleService.refund(subOrderNo, afterSaleNo, amountMinor, reason);
    }

    @Override
    public String prepareRefund(String subOrderNo, String afterSaleNo, long amountMinor, String reason) {
        return settleService.prepareRefund(subOrderNo, afterSaleNo, amountMinor, reason);
    }

    @Override
    public void dispatchRefund(String paymentNo) {
        settleService.dispatchRefund(paymentNo);
    }

    @Override
    public String settleRefund(String outRefundNo,String providerNo,long verifiedMinor) {
        return paymentLedger.settleRefundByOutTradeNo(outRefundNo,providerNo,verifiedMinor);
    }
    @Override public RefundEvidence refundEvidence(String no) { return paymentLedger.refundEvidence(no); }
    @Override public boolean hasRefundForAfterSale(String no) { return paymentLedger.hasRefundForAfterSale(no); }
    @Override public String paymentCurrency(String orderNo) { return paymentLedger.paymentCurrency(orderNo); }
}
