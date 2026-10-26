package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活动预算（{@code pmt_activity.budget_minor}）真的会停住活动。
 *
 * <p>此前预算只在保存时被要求填（长期活动「限量或预算」二选一），算价只看限量、
 * 扣量时预算无条件累加 —— 只设了预算的长期活动<b>永远不会停</b>，花多少都照减。
 * 商家以为「最多花 10 元」，账上是「无论花多少」。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityBudgetEnforcementTest {

    private static int seq = 7300;

    @Autowired private ActivityService activityService;
    @Autowired private ActivityPricingService pricing;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 长期立减 5 元、预算 10 元：两单用完预算，第三单不再减")
    void alwaysOnActivityStopsWhenBudgetIsUsedUp() {
        String e = "M-BUD-" + (++seq);
        String activityNo = activityService.save(e, cut(500L, 1_000L), "OP").activityNo();

        for (int i = 1; i <= 2; i++) {
            CampaignPort.Discount d = pricing.autoDiscount("U-BUD-" + seq, group(e));
            assertThat(d.of(e)).as("第 %d 单预算还够", i).isEqualTo(500L);
            pricing.commit("U-BUD-" + seq, "O-BUD-" + seq + "-" + i, d);
        }
        assertThat(budgetUsed(activityNo)).isEqualTo(1_000L);

        assertThat(pricing.autoDiscount("U-BUD-" + seq, group(e)).of(e))
                .as("★ 预算用完了还在减 —— 只设预算的长期活动永远停不下来")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 预算只剩一部分：按剩余封顶；扣预算是带条件的累加，过期的算价结果不能把预算扣穿")
    void budgetIsCappedAndCommitIsConditional() {
        String e = "M-BUD-" + (++seq);
        String activityNo = activityService.save(e, cut(600L, 1_000L), "OP").activityNo();

        CampaignPort.Discount first = pricing.autoDiscount("U-BUD-" + seq, group(e));
        CampaignPort.Discount stale = pricing.autoDiscount("U-BUD-" + seq, group(e));
        pricing.commit("U-BUD-" + seq, "O-BUD-" + seq + "-1", first);

        CampaignPort.Discount second = pricing.autoDiscount("U-BUD-" + seq, group(e));
        assertThat(second.of(e)).as("只剩 4 元，减 4 元而不是 6 元").isEqualTo(400L);

        // 并发下另一单拿着「预算还够」时算出的 6 元来扣：不能扣穿
        pricing.commit("U-BUD-" + seq, "O-BUD-" + seq + "-2", stale);
        assertThat(budgetUsed(activityNo)).as("★ 预算被扣穿").isLessThanOrEqualTo(1_000L);
    }

    // ---------------------------------------------------------------- 夹具

    private long budgetUsed(String activityNo) {
        return jdbc.queryForObject("select budget_used_minor from pmt_activity where activity_no=?",
                Long.class, activityNo);
    }

    private static List<CampaignPort.MerchantAmount> group(String entityNo) {
        return List.of(new CampaignPort.MerchantAmount(entityNo, 6_000L, 1, null));
    }

    private static ActivityDraft cut(long off, long budget) {
        return new ActivityDraft(null, "长期立减 · " + seq, "BASKET", null,
                PmtActivity.TRIGGER_NONE, null, null,
                PmtActivity.BENEFIT_CUT, off, null, null,
                PmtActivity.ALWAYS_ON, null, null, null, null, budget, List.of(), List.of());
    }
}
