package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import java.time.LocalDate;
import java.util.List;

/**
 * 商家端「收款」Tab 的只读视图（TDD-B端收款订单与商城接口对齐 P1，A1–A8）：本店支付订单的列表、时段汇总、查找、详情。
 *
 * <p>只放经过支付渠道的钱：收款码、代客收款码、POS、业务订单的线上支付。现金、储值不在支付订单里，自然不在；
 * 开放平台第三方（{@code source_type=PARTNER}）的单不进；只开了单、一次渠道尝试都没有的单不进（顾客只是打开了页面）。
 * 一律按门店圈，门店由调用方给出（业务服务按商家授权取的当前门店）。
 */
public interface MerchantPaymentQueryService {

    /** 列表：按创建时间倒序，游标翻页 */
    PaymentPage list(PayCaller caller, Long storeId, PaymentFilter filter);

    /** 时段汇总：以成功时间落在区间内为准 */
    Summary summary(PayCaller caller, Long storeId, LocalDate from, LocalDate to);

    /** 按金额 + 时间窗口找（within = 10m / 1h / today） */
    Lookup lookupByAmount(PayCaller caller, Long storeId, long amountMinor, String within);

    /** 按渠道交易号后几位找（≥6 位，只查今天和昨天） */
    Lookup lookupByTradeNoSuffix(PayCaller caller, Long storeId, String suffix);

    /** 本店能不能收款：有生效的收款商户绑定就算开通；channels 是已绑定的渠道 */
    CollectStatus status(PayCaller caller, Long storeId);

    /** 一笔的全貌；不是本店的单按「没有」处理 */
    Detail detail(PayCaller caller, Long storeId, String payOrderNo);

    /**
     * @param from   门店市场日期，空 = 今天
     * @param to     门店市场日期（含），空 = from
     * @param sources QR_CODE / COLLECT / POS / ORDER，空 = 全部
     * @param statuses SUCCEEDED / PARTIALLY_REFUNDED / REFUNDED / FAILED / PENDING，空 = 全部
     * @param q      支付单号、调用方单号、渠道交易号片段、付款人；只收字母数字与 - *
     */
    record PaymentFilter(LocalDate from, LocalDate to, List<String> sources, List<String> channels,
                         List<String> statuses, Long amountMin, Long amountMax, String q, String cursor, Integer size) {
    }

    record PaymentPage(List<PaymentRow> items, String nextCursor) {
    }

    /** @param bizRef 只有 source=ORDER 有：业务单类型与单号 */
    record PaymentRow(String payOrderNo, String source, String channel, String method, String currency,
                      long amountMinor, long paidMinor, long refundedMinor, String status, String payerMasked,
                      String cardLast4, String terminalLabel, BizRef bizRef, Long succeededAt, Long createdAt) {
    }

    record BizRef(String kind, String no) {
    }

    record Count(int count, long amountMinor) {
    }

    record ChannelCount(String channel, int count, long amountMinor) {
    }

    /** @param feeEstimated 有一笔还没对账（手续费按配置费率估）就是 true */
    record Summary(String currency, LocalDate from, LocalDate to, Count collected, Count refunded, long netMinor,
                   List<ChannelCount> byChannel, long feeMinor, boolean feeEstimated, Settlement expectedSettlement) {
    }

    record Settlement(String status, long amountMinor, LocalDate expectedDate, String accountMasked) {
    }

    /** @param pendingNearby 同一窗口里还在等渠道结果的（顾客说付了但还没到账时给店员看） */
    record Lookup(List<PaymentRow> matches, List<PaymentRow> pendingNearby) {
    }

    record CollectStatus(boolean enabled, List<String> channels) {
    }

    /** @param feeRateBps 费率（万分之几）：已对账按实扣反算，未对账是估算用的配置费率 */
    record Amounts(long paidMinor, long refundedMinor, long refundableMinor, long feeMinor, boolean feeEstimated,
                   Long feeRateBps, long expectedNetMinor) {
    }

    record Payer(String channel, String method, String payerMasked, String cardBrand, String cardLast4, String issuer,
                 String authCode) {
    }

    /** @param channelMerchantNo 渠道侧商户号（子商户号优先） */
    record Refs(String payOrderNo, String outTradeNo, String channelTradeNo, String channelMerchantNo) {
    }

    /** @param operatorNo 收银员 / 发起的商家账号（名字由业务服务换） */
    record Source(String kind, String qrKind, String tableRef, String terminalSn, String operatorNo, BizRef bizRef) {
    }

    record Failure(String code, String message) {
    }

    record RefundItem(String payRefundNo, long amountMinor, String reason, String requestedBy, String status,
                      Long createdAt, Long succeededAt) {
    }

    /** @param kind CREATED / PAID / FAILED / CLOSED / REFUND_REQUESTED / REFUND_SUCCEEDED；文案由端上按码出 */
    record Event(Long at, String kind, Long amountMinor, String ref) {
    }

    record Detail(PaymentRow row, Amounts amounts, Settlement settlement, Payer payer, Refs refs, Source source,
                  Failure failure, List<RefundItem> refunds, List<Event> timeline) {
    }
}
