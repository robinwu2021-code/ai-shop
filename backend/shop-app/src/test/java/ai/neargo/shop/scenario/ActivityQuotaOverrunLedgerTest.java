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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限量抢光之后仍命中的那一单（并发下必然有一点重叠）要<b>留在账上</b>。
 *
 * <p>订单已经按算价时的金额落库，不翻脸（类注释写着的既定口径），但此前只记一条 WARN、
 * 不写 {@code pmt_apply}：这一单确实减了钱，活动效果、对账、订单详情的「优惠依据」却都查不到它 ——
 * 超发了几份只能翻日志。现在记一行并标明是超发（{@code redeem_mode = QUOTA_OVERRUN}），
 * 取消时不退份数（它本来就没占份数）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityQuotaOverrunLedgerTest {

    private static int seq = 7800;

    @Autowired private ActivityService activityService;
    @Autowired private ActivityPricingService pricing;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 限量 1 份被两单同时命中：第二单记一行超发的账，份数不超；取消它不退份数")
    void overrunIsBookedAndFlagged() {
        String e = "M-OVR-" + (++seq);
        String activityNo = activityService.save(e, new ActivityDraft(null, "限量立减 · " + seq, "BASKET", null,
                PmtActivity.TRIGGER_NONE, null, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ALWAYS_ON, null, null, null, 1, null, List.of(), List.of()), "OP").activityNo();
        var groups = List.of(new CampaignPort.MerchantAmount(e, 6_000L, 1, null));
        CampaignPort.Discount first = pricing.autoDiscount("U-OVR", groups);
        CampaignPort.Discount second = pricing.autoDiscount("U-OVR", groups);   // 两单都在「还剩 1 份」时算的价

        pricing.commit("U-OVR", "O-OVR-1-" + seq, first);
        pricing.commit("U-OVR", "O-OVR-2-" + seq, second);

        assertThat(quotaUsed(activityNo)).as("份数不超").isEqualTo(1);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select redeem_mode, amount_minor from pmt_apply where order_no=? and promo_no=?",
                "O-OVR-2-" + seq, activityNo);
        assertThat(rows).as("★ 超发的那一单在账上查不到 —— 减了钱却只有一条 WARN").hasSize(1);
        assertThat(rows.get(0).get("redeem_mode")).isEqualTo("QUOTA_OVERRUN");
        assertThat(((Number) rows.get(0).get("amount_minor")).longValue()).isEqualTo(500L);

        pricing.release("O-OVR-2-" + seq);
        assertThat(quotaUsed(activityNo)).as("超发那一单本来没占份数，取消时不能退走别人的那一份").isEqualTo(1);
        pricing.release("O-OVR-1-" + seq);
        assertThat(quotaUsed(activityNo)).isZero();
    }

    private int quotaUsed(String activityNo) {
        return jdbc.queryForObject("select quota_used from pmt_activity where activity_no=?", Integer.class, activityNo);
    }
}
