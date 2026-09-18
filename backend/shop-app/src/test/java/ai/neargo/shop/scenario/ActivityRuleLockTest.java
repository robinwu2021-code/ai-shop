package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A6 · 开始了的活动只能改结束时间与上限（原型 s35 · PRD AC-4 · 错误码 40029）。
 *
 * <p>已有订单按旧规则算过价，这时再改门槛 / 优惠 / 商品，同一个活动就有了两种价。
 * 还没开始的活动照常全改 —— 锁的是「已经对订单生效过」的规则，不是「建好了」的规则。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityRuleLockTest {

    private static final String ENTITY = "M0001";

    @Autowired private ActivityService activityService;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void drop() {
        for (String no : made) {
            jdbc.update("delete from pmt_activity_goods where activity_no=?", no);
            jdbc.update("delete from pmt_activity_audience where activity_no=?", no);
            jdbc.update("delete from pmt_activity where activity_no=?", no);
        }
        made.clear();
    }

    @Test
    @DisplayName("★★★ 进行中：改结束时间与份数可以，改优惠金额拒（40029）")
    void startedActivityLocksRules() {
        long now = System.currentTimeMillis();
        ActivityVO a = save(draft(null, 500L, now - 60_000, now + 86_400_000L, 100));

        ActivityVO b = save(draft(a.activityNo(), 500L, now - 60_000, now + 2 * 86_400_000L, 300));
        assertThat(b.endAt()).isEqualTo(now + 2 * 86_400_000L);
        assertThat(b.quota()).isEqualTo(300);

        assertThatThrownBy(() -> save(draft(a.activityNo(), 800L, now - 60_000, now + 86_400_000L, 100)))
                .as("★ 进行中改了减多少 —— 已下的单按 5 元算过，新单按 8 元，同一活动两种价")
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode())
                .isEqualTo(ErrorCode.ACTIVITY_RULE_LOCKED);
    }

    @Test
    @DisplayName("★★ 还没开始的活动照常全改")
    void pendingActivityIsEditable() {
        long now = System.currentTimeMillis();
        ActivityVO a = save(draft(null, 500L, now + 3_600_000L, now + 86_400_000L, 100));
        assertThat(save(draft(a.activityNo(), 800L, now + 3_600_000L, now + 86_400_000L, 100))
                .benefitAmountMinor()).isEqualTo(800L);
    }

    private ActivityVO save(ActivityDraft d) {
        ActivityVO v = activityService.save(ENTITY, d, "TEST");
        if (!made.contains(v.activityNo())) {
            made.add(v.activityNo());
        }
        return v;
    }

    /** 满 100 减 N（全店、一次性） */
    private static ActivityDraft draft(String no, long cut, long start, long end, int quota) {
        return new ActivityDraft(no, "锁定测试", null, null,
                PmtActivity.TRIGGER_AMOUNT, 10_000L, null,
                PmtActivity.BENEFIT_CUT, cut, null, null,
                PmtActivity.ONE_OFF, start, end, null, quota, null,
                List.of(), List.of(),
                null, null, null, null, null, null, null);
    }
}
