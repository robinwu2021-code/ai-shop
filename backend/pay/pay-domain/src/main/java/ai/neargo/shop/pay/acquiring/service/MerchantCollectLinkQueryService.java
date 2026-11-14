package ai.neargo.shop.pay.acquiring.service;

import ai.neargo.shop.pay.access.PayCaller;
import java.util.List;

/**
 * 门店收银台的「链接记录」（TDD-门店收银台与付款链接 L3）：本店 {@code biz_type=BIZ_COLLECT} 的支付单，按创建时间倒序、游标翻页。
 *
 * <p>与「收款」Tab（{@link MerchantPaymentQueryService}）的区别：那边只列有过渠道尝试的单，这里连没付、过期、作废的链接都列。
 * 状态由支付单与收银会话推出：已付 / 部分退款 / 已退款 → PAID；CLOSED 且会话记为 VOIDED → VOIDED；
 * 未付且已关或已过截止 → EXPIRED；其余 → PENDING。
 */
public interface MerchantCollectLinkQueryService {

    /** @param status PENDING / PAID / EXPIRED / VOIDED，空 = 全部 */
    LinkPage list(PayCaller caller, Long storeId, String status, String cursor, Integer size);

    /** 一条；不是本店的付款链接按「没有」处理 */
    LinkRow row(PayCaller caller, Long storeId, String payOrderNo);

    /**
     * 本店带某个业务单号的付款链接（TDD-业务单收款汇总），按创建时间正序，最多 50 条。状态口径同 {@link #list}。
     */
    List<BizLink> ofBizNo(PayCaller caller, Long storeId, String bizNo);

    /** @param paidMinor / refundedMinor 支付单上的实收与已退；汇总按 {@code paid - refunded} 计 */
    record BizLink(String payOrderNo, String status, String currency, long amountMinor, long paidMinor,
                   long refundedMinor, Long createdAt, Long paidAt) {
    }

    record LinkPage(List<LinkRow> items, String nextCursor) {
    }

    /**
     * @param operatorNo 生成人（商家账号）
     * @param voidedBy   作废人；只有 VOIDED 有
     */
    record LinkRow(String payOrderNo, long amountMinor, String currency, String subject, String status, Long createdAt,
                   Long expireAt, Long paidAt, String channel, String operatorNo, String voidedBy) {
    }
}
