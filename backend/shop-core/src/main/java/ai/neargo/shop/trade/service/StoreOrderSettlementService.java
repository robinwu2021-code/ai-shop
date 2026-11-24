package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.pay.PaidCollectionPort.PaidCollection;

/**
 * 门店零售单的收款挂单与付清（TDD-统一订单与开票资料 AC7 / AC16）。
 *
 * <p><b>键是子单号</b>：现金记录的 {@code orderNo}、收款码 / 付款链接支付单的 {@code biz_no} 都填子单号（POS 已这么送）。
 * 挂上的收款 = 未作废的现金记录 + 已成功的收款码 / 付款链接；同币别加总 ≥ 订单总额即付清（多收不拒，POS 找零）。
 *
 * <p><b>只认门店零售单</b>（{@code admission_kind='STORE_SALE'}）：其他订单号一律不碰，现金记录对它们仍按决策 D3「只记、不改订单」。
 *
 * <p>付清 = 子单行锁下一次条件更新（{@code WAIT_PAY → COMPLETED}），与 {@code OrderServiceImpl#markPaidSource} 同样发
 * {@code ORDER_PAID} / {@code SUB_ORDER_PAID}（同一事务写 outbox），两笔收款同时到也只转一次、只发一次。
 */
public interface StoreOrderSettlementService {

    /**
     * 现金记录写进本店、挂在 {@code orderNo} 上之后（调用方事务内、记录已插入）：是门店零售单就校验状态与币别并跑付清判断。
     * 不是门店零售单什么都不做。门店零售单已付清或已取消回 {@code STORE_ORDER_STATE_INVALID}，币别不同回
     * {@code STORE_ORDER_CURRENCY_MISMATCH}（调用方事务随之回滚，记录不落）。
     */
    void afterOfflineRecorded(String entityNo, String storeNo, String orderNo, String currency);

    /**
     * 作废 / 更正一条挂单现金记录之前（调用方事务内、记录行已锁）：所挂的门店零售单已付清回 {@code STORE_ORDER_PAYMENT_LOCKED}
     * （退款是 P3）。未付清的放行，调用方改完再调 {@link #afterOfflineChanged} 重算。
     */
    void requireMutable(String entityNo, String storeNo, String orderNo);

    /** 作废 / 更正之后重算（更正成更大的金额可能就此付清）。不是门店零售单什么都不做。 */
    void afterOfflineChanged(String entityNo, String storeNo, String orderNo);

    /**
     * 收款码 / 付款链接收款成功：{@code bizNo} 是本店门店零售单的子单号就挂上（一张支付单只挂一次）并跑付清判断。
     * 自成事务。挂上返回 true；不是门店零售单、别店、币别不符或已挂过返回 false。
     */
    boolean attachCollection(PaidCollection collection);

    /**
     * 支付单号收款成功（回调 / 对账补单交给交易侧的是支付单号）：按支付域确认的事实挂单。内嵌形态才有事实来源，
     * 独立形态没有本地实现时什么都不做。
     */
    void onPayOrderSucceeded(String payOrderNo);

    /** 已挂收款合计（与订单同币别）：现金记录 + 收款码 / 付款链接。 */
    long paidMinor(String entityNo, String storeNo, String subOrderNo, String currency);
}
