package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.CreatePaymentCmd;
import ai.neargo.shop.pay.acquiring.entity.PayOrder;

/**
 * 三条路径（内部、开放 API、终端）共用的收款入口（代码清单 §2.2）。第一行一律 {@code callerPolicy.check}。
 * 选方式与发起（{@code methods} / {@code start}）随 I4 收款路由一起落 —— 今天的通道下单仍经原发起流程。
 */
public interface PaymentService {

    /** 幂等建单：同一调用方单号返回同一张；同号不同内容 → {@code PAY_ORDER_CONFLICT} */
    PayOrder create(PayCaller caller, CreatePaymentCmd cmd);

    /** 按支付单号取（不做调用方判定，内部装配用）；查无此单为空 */
    PayOrder find(String payOrderNo);

    /** 按 id 取（不做调用方判定，收银台按令牌找到单后用）；没有为空 */
    PayOrder findById(Long id);

    /** 按支付单号查，只能查自己范围内的 */
    PayOrder query(PayCaller caller, String payOrderNo);

    /** 按内部 app 与调用方单号取（不做调用方判定）；没有为空 */
    PayOrder findByOutOrderNo(Long appId, String outOrderNo);

    /**
     * 查单补账（pay-guard 开发计划 M6.4）：对未结束的交易尝试逐个向渠道查单，查到已付就按结果入口入账；不关单。
     * 终端拿到「结果不明」（{@code UNKNOWN}）后必须先调它 —— 「终端已扣、我方未知」靠这一步补上。返回查后的支付单。
     */
    PayOrder refresh(PayCaller caller, String payOrderNo);

    /**
     * 关前查单、关闭渠道尝试后再关本地单；已付走原入账路径。
     * 查询或关单结果不明时返回当前状态，调用方不能把 HTTP 成功当成已关闭。
     */
    PayOrder close(PayCaller caller, String payOrderNo, String reason);

    /** 业务已终止：先记录持久取消意图，再处理渠道；后续到账不再当正常支付。 */
    PayOrder cancel(PayCaller caller, String payOrderNo, String reason);

    /**
     * 通道尝试开出时挂到支付单上：写 {@code stl_payment.pay_order_id} 与市场，支付单进入 AWAITING_PAYMENT。
     * 由收款账本在开尝试的同一事务里调；找不到支付单（开关关、存量单）什么都不做。
     */
    void attachAttempt(String outOrderNo, Long stlPaymentId);

    /**
     * 选方式发起支付（TDD-支付服务-I5接入层 S4）：选路由 → 冻结到支付单（收款商户、渠道、接入模式、资金路径、密钥版本）→
     * 开交易尝试（冻结值随之落到尝试行）→ 调渠道下单，返回端上唤起收银台的参数。
     */
    StartPaymentResult start(PayCaller caller, String payOrderNo, StartPaymentCmd cmd);

    /** @param payerOpenId / payerAppId 微信 JSAPI 时必填（付款人在该公众号 / 小程序下的身份） */
    record StartPaymentCmd(String payMethod, String payerOpenId, String payerAppId) {
    }

    record StartPaymentResult(String payOrderNo, String outTradeNo, String payChannel, java.util.Map<String, String> payParams) {
    }
}
