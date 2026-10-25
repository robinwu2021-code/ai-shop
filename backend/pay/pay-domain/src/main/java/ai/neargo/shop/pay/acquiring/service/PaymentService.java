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

    /** 按支付单号查，只能查自己范围内的 */
    PayOrder query(PayCaller caller, String payOrderNo);

    /**
     * 通道尝试开出时挂到支付单上：写 {@code stl_payment.pay_order_id} 与市场，支付单进入 AWAITING_PAYMENT。
     * 由收款账本在开尝试的同一事务里调；找不到支付单（开关关、存量单）什么都不做。
     */
    void attachAttempt(String outOrderNo, Long stlPaymentId);
}
