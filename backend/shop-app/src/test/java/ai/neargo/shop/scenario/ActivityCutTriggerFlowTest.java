package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 减钱类活动的三种触发（TDD-满减的三种触发）。
 *
 * <p><b>为什么值得单独一份</b>：`NONE × CUT` 与 `QTY × CUT` 此前
 * **存得进去但下单一分不减** —— 校验放行、定价那侧硬判 `TRIGGER_AMOUNT`，
 * 中间没有任何一道闸门。拦住它的只是 B 端不给入口。
 * 所以这里测的不是「活动能不能建」，而是<b>建了之后金额有没有变</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityCutTriggerFlowTest {

    @Autowired
    private ActivityService activityService;

    @Autowired
    private ActivityPricingService pricing;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private static int seq = 7600;

    /** 建一个减 5 元的活动，触发方式由参数给 */
    private void cut(String entityNo, String trigger, Long amountMinor, Integer qty) {
        long now = System.currentTimeMillis();
        activityService.save(entityNo, new ActivityDraft(null, "减 5 · " + (++seq),
                "BASKET", null, trigger, amountMinor, qty,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                null, null, List.of(), List.of()), "OP");
    }

    private static List<CampaignPort.MerchantAmount> basket(String entityNo, long amount, int qty) {
        return List.of(new CampaignPort.MerchantAmount(entityNo, amount, qty, null));
    }

    @Test
    @DisplayName("★★★ 无门槛立减：下一件也减 —— 过去这种活动存得下但永远不生效")
    void noThresholdCutApplies() {
        String e = "M-CUT-" + (++seq);
        cut(e, PmtActivity.TRIGGER_NONE, null, null);

        assertThat(pricing.autoDiscount("U-X", basket(e, 500, 1)).total())
                .as("NONE 触发恒命中").isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ 关单要把配额退回去 —— 不退的话限量活动被没付款的单吃掉量")
    void quotaGoesBackOnRelease() {
        String e = "M-CUT-" + (++seq);
        cut(e, PmtActivity.TRIGGER_NONE, null, null);
        String orderNo = "SO-REL-" + seq;

        var d = pricing.autoDiscount("U-REL", basket(e, 500, 1));
        assertThat(d.total()).isEqualTo(500);
        pricing.commit("U-REL", orderNo, d);

        String activityNo = d.applied().get(0).activityNo();
        assertThat(quotaUsed(activityNo)).as("占用没记上，后面的断言就没有意义").isEqualTo(1);

        /*
         * **关单退配额**（执行计划 B6，用户 2026-09-21 拍板）。
         *
         * 此前关单释放了库存、券、积分、预约时段，唯独没退它 ——
         * 限量 100 份的活动被没付款的单吃掉量，而运营看到的「已用 N 份」里
         * 有几份从来没成交。
         */
        pricing.release(orderNo);
        assertThat(quotaUsed(activityNo)).as("关单没退配额").isZero();

        // 幂等：对账、重试都会再调一次，退两次会把配额退成负数，而那看起来完全正常
        pricing.release(orderNo);
        assertThat(quotaUsed(activityNo)).as("退了两次 —— 配额变成负数，这个活动此后谁都抢不到").isZero();
    }

    private int quotaUsed(String activityNo) {
        Integer n = jdbc.queryForObject(
                "select quota_used from pmt_activity where activity_no=?", Integer.class, activityNo);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("★★★ 满件减：2 件不减、3 件减 —— 门槛两侧都要测")
    void qtyThresholdBothSides() {
        String e = "M-CUT-" + (++seq);
        cut(e, PmtActivity.TRIGGER_QTY, null, 3);

        assertThat(pricing.autoDiscount("U-X", basket(e, 9_000, 2)).total())
                .as("差一件不该减 —— 只测命中那一侧的话，"
                        + "把判断写成恒真也是绿的").isEqualTo(0);
        assertThat(pricing.autoDiscount("U-X", basket(e, 9_000, 3)).total())
                .as("够 3 件就减").isEqualTo(500);
    }

    @Test
    @DisplayName("★★ 满件减看的是件数不是金额：一件贵货凑不出「满 3 件」")
    void qtyIsNotAmount() {
        String e = "M-CUT-" + (++seq);
        cut(e, PmtActivity.TRIGGER_QTY, null, 3);

        assertThat(pricing.autoDiscount("U-X", basket(e, 999_00, 1)).total())
                .as("金额再大也只有 1 件").isEqualTo(0);
    }

    @Test
    @DisplayName("★★ 满额减不受影响：件数这个新字段不该改变老行为")
    void amountTriggerUnchanged() {
        String e = "M-CUT-" + (++seq);
        cut(e, PmtActivity.TRIGGER_AMOUNT, 5_000L, null);

        assertThat(pricing.autoDiscount("U-X", basket(e, 4_900, 99)).total())
                .as("差一分不减，件数再多也不顶").isEqualTo(0);
        assertThat(pricing.autoDiscount("U-X", basket(e, 5_000, 1)).total())
                .as("够了就减").isEqualTo(500);
    }

    @Test
    @DisplayName("★★★ 算不出来的组合，存那一侧直接拒 —— 不能留一个不报错的死活动")
    void uncomputableCombosRejected() {
        String e = "M-CUT-" + (++seq);
        long now = System.currentTimeMillis();

        assertThatThrownBy(() -> activityService.save(e, new ActivityDraft(null, "下单送券",
                "BASKET", null, PmtActivity.TRIGGER_AMOUNT, 5_000L, null,
                PmtActivity.BENEFIT_COUPON, null, null, "CP-1",
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                null, null, List.of(), List.of()), "OP"))
                .as("发券没有任何一处定价分支读它").isInstanceOf(BizException.class);

        assertThatThrownBy(() -> activityService.save(e, new ActivityDraft(null, "选几件货减 5",
                "BASKET", null, PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_CUT, 500L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86400_000L, null,
                null, null, List.of(), List.of()), "OP"))
                .as("按商家汇总的金额摊不到某几件货上").isInstanceOf(BizException.class);
    }
}
