package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.RecurringRule;
import ai.neargo.shop.promotion.service.ActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 活动的排期与敞口（P5）。
 *
 * <p><b>这组用例守的是「活动总有停下来的那一天」</b>：长期活动必须有限量或预算，
 * 改价与送商品必须限量。没有这两条，商家建的时候想的是「一直有这个优惠」，
 * 而账上是「无论花多少」—— 这两句话在他心里是一回事。
 *
 * <p>周期规则按<b>市场时区</b>判：「每周三 8 点到 20 点」说的是顾客那边的周三。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityScheduleTest {

    @Autowired
    private ActivityService activityService;

    private static int seq = 5100;

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static ActivityDraft cut(String scheduleType, Long start, Long end,
                                     Integer quota, Long budget) {
        return new ActivityDraft(null, "满 50 减 5 · " + (++seq), "BASKET", null,
                PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                scheduleType, start, end, null, quota, budget, List.of(), List.of());
    }

    @Test
    @DisplayName("★★★ 长期活动没有限量也没有预算 —— 保存被拒，因为它没有停下来的那一天")
    void alwaysOnNeedsACap() {
        String e = "M-ACT-" + (++seq);
        assertThatThrownBy(() -> activityService.save(e,
                cut(PmtActivity.ALWAYS_ON, null, null, null, null), "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ACTIVITY_ALWAYS_ON_NEEDS_CAP.name());

        // 给了限量就能存
        ActivityVO ok = activityService.save(e,
                cut(PmtActivity.ALWAYS_ON, null, null, 100, null), "OP");
        assertThat(ok.quotaLeft()).isEqualTo(100);
        // 敞口要算给他看：100 张 × 5 元
        assertThat(ok.maxExposureMinor()).isEqualTo(50_000);
    }

    @Test
    @DisplayName("★★★ 改单价 / 送商品必须限量且必须选商品 —— 敞口随销量走，卖得越好亏得越多")
    void itemCostBenefitsNeedQuotaAndGoods() {
        String e = "M-ACT-" + (++seq);
        long now = System.currentTimeMillis();
        ActivityDraft price = new ActivityDraft(null, "限时特价", "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 990L, null, null,
                PmtActivity.ONE_OFF, now, now + 86400_000L, null,
                null, null, List.of(), List.of("G-1"));
        assertThatThrownBy(() -> activityService.save(e, price, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ACTIVITY_QUOTA_REQUIRED.name());

        ActivityDraft noGoods = new ActivityDraft(null, "限时特价", "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 990L, null, null,
                PmtActivity.ONE_OFF, now, now + 86400_000L, null,
                50, null, List.of(), List.of());
        assertThatThrownBy(() -> activityService.save(e, noGoods, "OP"))
                .as("全店改价那叫调价，走商品编辑")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ACTIVITY_GOODS_REQUIRED.name());
    }

    @Test
    @DisplayName("★★ 短期活动：还没开始 / 已经结束都不生效")
    void oneOffRespectsWindow() {
        String e = "M-ACT-" + (++seq);
        long now = System.currentTimeMillis();
        long day = 86400_000L;

        ActivityVO future = activityService.save(e,
                cut(PmtActivity.ONE_OFF, now + day, now + 2 * day, null, null), "OP");
        assertThat(future.liveNow()).as("明天才开始").isFalse();

        ActivityVO live = activityService.save(e,
                cut(PmtActivity.ONE_OFF, now - day, now + day, null, null), "OP");
        assertThat(live.liveNow()).isTrue();
    }

    @Test
    @DisplayName("★★★ 周期活动按**市场时区**判：每周三 8:00–20:00，周二晚上不生效")
    void recurringUsesMarketZone() {
        RecurringRule wed = RecurringRule.parse("{\"weekdays\":[3],\"from\":\"08:00\",\"to\":\"20:00\"}");

        // 直接构造几个市场时区里的时刻，避开「跑测试那天正好是周几」的偶然
        long wedNoon = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        long wedEarly = ZonedDateTime.of(2026, 8, 26, 7, 59, 0, 0, ZONE).toInstant().toEpochMilli();
        long wedEnd = ZonedDateTime.of(2026, 8, 26, 20, 0, 0, 0, ZONE).toInstant().toEpochMilli();
        long tueNight = ZonedDateTime.of(2026, 8, 25, 23, 0, 0, 0, ZONE).toInstant().toEpochMilli();

        assertThat(wed.matches(wedNoon, ZONE)).isTrue();
        assertThat(wed.matches(wedEarly, ZONE)).as("8 点前还没开始").isFalse();
        assertThat(wed.matches(wedEnd, ZONE)).as("20:00 整就结束了，不是还能再买一分钟").isFalse();
        assertThat(wed.matches(tueNight, ZONE)).as("周二不是周三").isFalse();
    }

    @Test
    @DisplayName("★★ 周期规则读不出来 = 保存被拒，**不能当成全天生效**")
    void badRecurringRuleIsRejected() {
        String e = "M-ACT-" + (++seq);
        ActivityDraft bad = new ActivityDraft(null, "周三特价", null, null,
                PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.RECURRING, null, null, "每周三",   // 不是 JSON
                100, null, List.of(), List.of());
        assertThatThrownBy(() -> activityService.save(e, bad, "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ACTIVITY_RECURRING_RULE_INVALID.name());
    }

    @Test
    @DisplayName("★★★ 已结束的活动不能改、也不能复活 —— ended_reason 被覆盖就再也查不到为什么停")
    void endedActivityIsImmutable() {
        String e = "M-ACT-" + (++seq);
        long now = System.currentTimeMillis();
        ActivityVO a = activityService.save(e,
                cut(PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null, null), "OP");

        activityService.setStatus(e, a.activityNo(), PmtActivity.ENDED);
        assertThat(activityService.detail(e, a.activityNo()).endedReason())
                .as("商家问「怎么停了」要有答案").isEqualTo(PmtActivity.ENDED_MANUAL);

        assertThatThrownBy(() -> activityService.setStatus(e, a.activityNo(), PmtActivity.RUNNING))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.ACTIVITY_ENDED_IMMUTABLE.name());
    }

    @Test
    @DisplayName("★★ 冲突提示：这件商品已经在另一个还在跑的活动里")
    void conflictsReportRunningOnly() {
        String e = "M-ACT-" + (++seq);
        long now = System.currentTimeMillis();
        ActivityVO flash = activityService.save(e, new ActivityDraft(null, "周三特价", "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 990L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                50, null, List.of(), List.of("G-9")), "OP");

        assertThat(activityService.conflicts(e, List.of("G-9")))
                .extracting(x -> x.activityName()).containsExactly("周三特价");

        activityService.setStatus(e, flash.activityNo(), PmtActivity.ENDED);
        assertThat(activityService.conflicts(e, List.of("G-9")))
                .as("已结束的不算冲突，报出来只会让人以为要处理").isEmpty();
    }
}
