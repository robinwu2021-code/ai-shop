package ai.neargo.shop.pay.acquiring.policy;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.pay.acquiring.entity.PayRefundReviewRule;
import ai.neargo.shop.pay.acquiring.policy.RefundReviewPolicy.Decision;
import ai.neargo.shop.pay.acquiring.policy.RefundReviewPolicy.RefundFacts;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 退款复核规则（设计 v4 §2.8.8；TDD-支付服务-I3支付单退款流水 AC7） */
class RefundReviewPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-25T08:00:00Z");

    private static PayRefundReviewRule rule(long id, Long storeId) {
        PayRefundReviewRule r = new PayRefundReviewRule();
        r.setId(id);
        r.setRuleCode("R" + id);
        r.setStatus("ACTIVE");
        r.setMarket("CN");
        r.setCurrency("CNY");
        r.setStoreId(storeId);
        r.setSourceType("");
        r.setPayMethod("");
        r.setAlwaysReview(false);
        return r;
    }

    private static RefundFacts facts(long amount, long dailyBefore, long paid, Instant paidAt) {
        return new RefundFacts("CN", "CNY", 7L, "BIZ", "", amount, dailyBefore, paid, paidAt, NOW);
    }

    @Test
    @DisplayName("★★★ 市场没有 ACTIVE 规则 → 必须复核（NO_ACTIVE_RULE）：漏配规则不能等于关掉复核")
    void noActiveRuleReviewsEverything() {
        Decision d = RefundReviewPolicy.decide(List.of(), facts(1, 0, 100, NOW));
        assertThat(d.review()).isTrue();
        assertThat(d.hitReasons()).containsExactly("NO_ACTIVE_RULE");
        assertThat(d.ruleId()).isNull();
    }

    @Test
    @DisplayName("★★★ 门槛是「或」：单笔 / 当日累计 / 比例 / 超 N 天任一命中即复核；都没命中放行，记下规则版本")
    void thresholdsAreOr() {
        PayRefundReviewRule r = rule(1, null);
        r.setSingleAmountMinor(1000L);
        r.setDailyAmountMinor(5000L);
        r.setRefundRatioBp(8000);
        r.setAfterDays(30);
        assertThat(RefundReviewPolicy.decide(List.of(r), facts(1000, 0, 5000, NOW)).hitReasons())
                .containsExactly("SINGLE_AMOUNT");
        assertThat(RefundReviewPolicy.decide(List.of(r), facts(100, 4900, 5000, NOW)).hitReasons())
                .containsExactly("DAILY_AMOUNT");
        assertThat(RefundReviewPolicy.decide(List.of(r), facts(900, 0, 1000, NOW)).hitReasons())
                .containsExactly("RATIO");
        assertThat(RefundReviewPolicy.decide(List.of(r), facts(10, 0, 5000, NOW.minus(Duration.ofDays(31))))
                .hitReasons()).containsExactly("AFTER_DAYS");
        Decision pass = RefundReviewPolicy.decide(List.of(r), facts(10, 0, 5000, NOW));
        assertThat(pass.review()).isFalse();
        assertThat(pass.ruleId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("★★ 门店级规则比市场级更具体，优先；强制全部复核 → ALWAYS")
    void storeRuleWinsAndAlwaysReview() {
        PayRefundReviewRule market = rule(1, null);
        market.setSingleAmountMinor(1_000_000L);
        PayRefundReviewRule store = rule(2, 7L);
        store.setAlwaysReview(true);
        Decision d = RefundReviewPolicy.decide(List.of(market, store), facts(10, 0, 5000, NOW));
        assertThat(d.ruleId()).isEqualTo(2L);
        assertThat(d.hitReasons()).containsExactly("ALWAYS");
    }

    @Test
    @DisplayName("★★ 别的市场 / 币种 / 门店 / 非 ACTIVE 的规则不算数")
    void irrelevantRulesIgnored() {
        PayRefundReviewRule tw = rule(1, null);
        tw.setMarket("TW");
        tw.setSingleAmountMinor(1_000_000L);
        PayRefundReviewRule draft = rule(2, null);
        draft.setStatus("DRAFT");
        PayRefundReviewRule otherStore = rule(3, 8L);
        otherStore.setSingleAmountMinor(1_000_000L);
        assertThat(RefundReviewPolicy.decide(List.of(tw, draft, otherStore), facts(10, 0, 5000, NOW)).hitReasons())
                .containsExactly("NO_ACTIVE_RULE");
    }
}
