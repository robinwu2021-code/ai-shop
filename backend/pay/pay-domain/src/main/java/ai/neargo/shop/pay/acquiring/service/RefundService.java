package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import ai.neargo.shop.pay.acquiring.dto.RefundRequestCmd;
import ai.neargo.shop.pay.acquiring.entity.PayRefund;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.spi.settle.RefundRequest;

/**
 * 退款单（代码清单 §2.2；TDD-支付服务-I3支付单退款流水 S6）：权限（只有商户侧、必须有操作员）→ 可退余额 → 占用 →
 * 复核规则 → 命中开两人复核单、未命中交执行。<b>执行沿用 {@code RefundExecutionService}</b>（先落库 → 抢发送权 →
 * 不明只查不重发），退款单挂在它落的那一行上。
 */
public interface RefundService {

    /** 新入口（{@code PaymentPort#requestRefund}）。幂等键是调用方退款单号；复核通过后再调一次即执行 */
    PayRefund request(PayCaller caller, RefundRequestCmd cmd);

    /**
     * 执行器落下退款行时挂退款单（现有售后路径按执行号进来，没有经过 {@link #request}）。
     * 原支付没有支付单（存量单）时什么都不做。在执行器准备行的同一事务里调。
     */
    void attachExecution(StlPayment origin, StlPayment refundRow, RefundRequest request);

    /** 按退款单号查，只能查自己范围内的（第三方只认自己发起的退款）；不存在或不归它 → {@code NOT_FOUND} */
    PayRefund query(PayCaller caller, String payRefundNo);

    /** 本店最近的退款单（商家端列表，F40）：调用方门店范围之外的门店一律拒绝 */
    java.util.List<PayRefund> recentOfStore(PayCaller caller, Long storeId, int limit);

    /** 能不能发渠道：待复核 / 已驳回不发 */
    boolean dispatchAllowed(String executionNo);

    /** 抢到发送权、即将发渠道 */
    void dispatched(String executionNo);

    /** 运营复核一步（两人）。驳回即失败、释放占用 */
    PayRefund review(PayCaller caller, String payRefundNo, boolean approve, String reason);
}
