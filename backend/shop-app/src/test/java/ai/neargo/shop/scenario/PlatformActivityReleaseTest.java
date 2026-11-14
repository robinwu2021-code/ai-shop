package ai.neargo.shop.scenario;

import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollCommand;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollRule;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.PlatformActivityService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 平台活动的单取消后，份数与平台预算原样退回。
 *
 * <p>扣的时候（commitPlatform）份数扣在<b>报名单</b>上、平台出的钱记到活动的 {@code budget_used_minor}；
 * 退的时候此前却按商家活动的口径去减<b>活动</b>上的 quota_used（平台活动那一列恒为 0，带条件的 UPDATE 一行不中），
 * 连带预算也没退 —— 每取消一单，报名就永久少一份、平台预算永久少一笔。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformActivityReleaseTest {

    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0001";
    private static final long HOUR = 3_600_000L;
    private static int seq = 7600;

    @Autowired private PlatformActivityService platform;
    @Autowired private ActivityPricingService pricing;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void drop() {
        // 进行中的平台活动会让种子货对所有人按满减算 —— 留着会让别的用例金额断言莫名失败
        for (String no : made) {
            jdbc.update("delete from pmt_enrollment_goods where enrollment_no in "
                    + "(select enrollment_no from pmt_enrollment where activity_no=?)", no);
            jdbc.update("delete from pmt_enrollment where activity_no=?", no);
            jdbc.update("delete from pmt_apply where promo_no=?", no);
            jdbc.update("delete from pmt_activity where activity_no=?", no);
        }
        made.clear();
    }

    @Test
    @DisplayName("★★★ 扣了报名 1 份、平台预算 10 元的单取消：两样都退回，再退一次不多退")
    void releaseReversesExactlyWhatCommitPlatformTook() {
        long now = System.currentTimeMillis();
        PlatformActivityVO a = platform.save(new PlatformDraft(null, "平台满减 · " + (++seq),
                PmtActivity.TRIGGER_AMOUNT, 10_000L, null, 2_000L,
                now + 2 * HOUR, now + 48 * HOUR, now + HOUR,
                5000, 100_000L, EnrollRule.none(), true), "OP-1");
        made.add(a.activityNo());
        var e = platform.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 2));
        platform.review(e.enrollmentNo(), true, null, "OP-1");
        jdbc.update("update pmt_activity set start_at=? where activity_no=?", now - 1000, a.activityNo());

        String orderNo = "O-PREL-" + seq;
        var hit = new CampaignPort.AppliedActivity(a.activityNo(), ENTITY, 2_000L, 1, 1_000L,
                e.enrollmentNo(), a.name());
        pricing.commit("U-PREL", orderNo, new CampaignPort.Discount(2_000L,
                List.of(new CampaignPort.MerchantDiscount(ENTITY, 2_000L, null)), List.of(hit)));
        assertThat(quotaUsed(e.enrollmentNo())).isEqualTo(1);
        assertThat(budgetUsed(a.activityNo())).isEqualTo(1_000L);

        pricing.release(orderNo);
        assertThat(quotaUsed(e.enrollmentNo())).as("★ 报名的份数没退 —— 每取消一单报名就永久少一份").isZero();
        assertThat(budgetUsed(a.activityNo())).as("★ 平台出的那 10 元没退回预算").isZero();

        pricing.release(orderNo);
        assertThat(quotaUsed(e.enrollmentNo())).as("重复取消不多退").isZero();
        assertThat(budgetUsed(a.activityNo())).isZero();
    }

    private int quotaUsed(String enrollmentNo) {
        return jdbc.queryForObject("select quota_used from pmt_enrollment where enrollment_no=?",
                Integer.class, enrollmentNo);
    }

    private long budgetUsed(String activityNo) {
        return jdbc.queryForObject("select budget_used_minor from pmt_activity where activity_no=?",
                Long.class, activityNo);
    }
}
