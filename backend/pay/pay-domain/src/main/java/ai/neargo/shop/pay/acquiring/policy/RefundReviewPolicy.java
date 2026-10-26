package ai.neargo.shop.pay.acquiring.policy;

import ai.neargo.shop.pay.acquiring.entity.PayRefundReviewRule;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 退款要不要两人复核（设计 v4 §2.8.8；代码清单 §2.2）。纯函数：规则与事实进，决定出。
 *
 * <ul>
 *   <li>取<b>最具体</b>的 ACTIVE 规则：门店级 &gt; 市场级（同市场、同币种；来源 / 方式为空串 = 全部）；</li>
 *   <li><b>没有 ACTIVE 规则 → 必须复核</b>（{@code NO_ACTIVE_RULE}）—— 漏配规则不能等于关掉复核；</li>
 *   <li>一条规则内的门槛是「或」：单笔、当日累计（含本笔）、占原单比例、原单支付超过 N 天、强制复核。</li>
 * </ul>
 */
public final class RefundReviewPolicy {

    private RefundReviewPolicy() {
    }

    /**
     * @param dailyRefundedMinor 门店当日（市场时区）已发起的退款合计，不含本笔
     * @param paidMinor          原支付单实收
     */
    public record RefundFacts(String market, String currency, Long storeId, String sourceType, String payMethod,
                              long amountMinor, long dailyRefundedMinor, long paidMinor, Instant paidAt, Instant now) {
    }

    /** @param ruleId 命中（或放行所依据）的规则版本；无规则为空 */
    public record Decision(boolean review, Long ruleId, List<String> hitReasons) {
    }

    public static Decision decide(List<PayRefundReviewRule> rules, RefundFacts f) {
        Optional<PayRefundReviewRule> rule = rules.stream()
                .filter(r -> "ACTIVE".equals(r.getStatus()) && Objects.equals(r.getMarket(), f.market())
                        && Objects.equals(r.getCurrency(), f.currency())
                        && (r.getStoreId() == null || Objects.equals(r.getStoreId(), f.storeId()))
                        && blankOr(r.getSourceType(), f.sourceType()) && blankOr(r.getPayMethod(), f.payMethod()))
                .max(Comparator.comparingInt(RefundReviewPolicy::specificity));
        if (rule.isEmpty()) {
            return new Decision(true, null, List.of("NO_ACTIVE_RULE"));
        }
        PayRefundReviewRule r = rule.get();
        List<String> hits = new ArrayList<>();
        if (Boolean.TRUE.equals(r.getAlwaysReview())) {
            hits.add("ALWAYS");
        }
        if (r.getSingleAmountMinor() != null && f.amountMinor() >= r.getSingleAmountMinor()) {
            hits.add("SINGLE_AMOUNT");
        }
        if (r.getDailyAmountMinor() != null && f.dailyRefundedMinor() + f.amountMinor() >= r.getDailyAmountMinor()) {
            hits.add("DAILY_AMOUNT");
        }
        if (r.getRefundRatioBp() != null && f.paidMinor() > 0
                && f.amountMinor() * 10_000L >= (long) r.getRefundRatioBp() * f.paidMinor()) {
            hits.add("RATIO");
        }
        if (r.getAfterDays() != null && f.paidAt() != null
                && Duration.between(f.paidAt(), f.now()).toDays() >= r.getAfterDays()) {
            hits.add("AFTER_DAYS");
        }
        return new Decision(!hits.isEmpty(), r.getId(), List.copyOf(hits));
    }

    private static boolean blankOr(String ruleValue, String actual) {
        return ruleValue == null || ruleValue.isEmpty() || ruleValue.equals(actual);
    }

    private static int specificity(PayRefundReviewRule r) {
        return (r.getStoreId() != null ? 4 : 0) + (r.getSourceType() != null && !r.getSourceType().isEmpty() ? 2 : 0)
                + (r.getPayMethod() != null && !r.getPayMethod().isEmpty() ? 1 : 0);
    }
}
