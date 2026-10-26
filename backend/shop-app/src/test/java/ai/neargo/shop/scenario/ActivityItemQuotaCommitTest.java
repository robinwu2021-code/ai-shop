package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
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
 * 限时特价与买赠的限量真的会被扣。
 *
 * <p>这两种保存时必须设限量（ACTIVITY_QUOTA_REQUIRED），算价也按 {@code hasQuotaLeft} 筛，
 * 但特价走的是商品价、赠品走的是加行，从不进 {@code discounts.auto()} → {@code campaignPort.commit} ——
 * quota_used 永远是 0，「限量 2 份」卖多少单都照样特价。这里直接守营销侧的回执接口：
 * {@code commitItemUsage}（按活动扣）与 {@code CampaignPort.commitItemBenefits}（下单链路按货号回执）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ActivityItemQuotaCommitTest {

    private static final long DAY = 86_400_000L;
    private static int seq = 8000;

    @Autowired private ActivityService activityService;
    @Autowired private ActivityPricingService pricing;
    @Autowired private CampaignPort campaignPort;
    @Autowired private GoodsMapper goodsMapper;
    @Autowired private JdbcTemplate jdbc;

    @Test
    @DisplayName("★★★ 限时特价限量 2 份：两单扣满、活动到量结束、特价不再生效；第三单记超发不超扣；重复回执不重复扣；取消退回")
    void flashQuotaIsConsumedPerOrder() {
        String e = "M-IQ-" + (++seq);
        String goods = goods(e);
        long now = System.currentTimeMillis();
        String act = activityService.save(e, new ActivityDraft(null, "限量特价 " + seq, "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null, PmtActivity.BENEFIT_PRICE, 990L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 2, null, List.of(), List.of(goods)), "OP")
                .activityNo();
        assertThat(pricing.flashActivityOf("U-IQ", List.of(goods))).containsEntry(goods, act);

        assertThat(pricing.commitItemUsage("U-IQ", "O-IQ-1-" + seq, act, e, 1)).isTrue();
        assertThat(pricing.commitItemUsage("U-IQ", "O-IQ-1-" + seq, act, e, 1)).as("同一单重放").isTrue();
        assertThat(quotaUsed(act)).as("重复回执不重复扣").isEqualTo(1);

        assertThat(pricing.commitItemUsage("U-IQ", "O-IQ-2-" + seq, act, e, 1)).isTrue();
        assertThat(quotaUsed(act)).isEqualTo(2);
        assertThat(pricing.flashPrices("U-IQ", List.of(goods)))
                .as("★ 限量用完了还在按特价卖").doesNotContainKey(goods);

        assertThat(pricing.commitItemUsage("U-IQ", "O-IQ-3-" + seq, act, e, 1)).as("抢不到最后一份").isFalse();
        assertThat(quotaUsed(act)).as("不超扣").isEqualTo(2);
        assertThat(jdbc.queryForObject("select redeem_mode from pmt_apply where order_no=? and promo_no=?",
                String.class, "O-IQ-3-" + seq, act)).isEqualTo("QUOTA_OVERRUN");

        pricing.release("O-IQ-1-" + seq);
        assertThat(quotaUsed(act)).as("取消退回那一份").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 下单链路按货号回执（CampaignPort.commitItemBenefits）：特价与买赠各扣胜出那个活动的一份")
    void portCommitsFlashAndGiftByGoods() {
        String e = "M-IQ-" + (++seq);
        String flashGoods = goods(e);
        String giftGoods = goods(e);
        long now = System.currentTimeMillis();
        String flash = activityService.save(e, new ActivityDraft(null, "特价 " + seq, "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null, PmtActivity.BENEFIT_PRICE, 990L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 5, null, List.of(), List.of(flashGoods)), "OP")
                .activityNo();
        String gift = activityService.save(e, new ActivityDraft(null, "买二送一 " + seq, "BASKET", null,
                PmtActivity.TRIGGER_QTY, null, 2, PmtActivity.BENEFIT_GIFT, null, 1, giftGoods,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 5, null, List.of(), List.of(giftGoods)), "OP")
                .activityNo();

        campaignPort.commitItemBenefits("U-IQ", "O-IQP-" + seq, List.of(
                new CampaignPort.ItemBenefitUse(flashGoods, e, false),
                new CampaignPort.ItemBenefitUse(giftGoods, e, true)));

        assertThat(quotaUsed(flash)).as("★ 特价的限量从没被扣过").isEqualTo(1);
        assertThat(quotaUsed(gift)).as("★ 买赠的限量从没被扣过").isEqualTo(1);
    }

    private int quotaUsed(String activityNo) {
        return jdbc.queryForObject("select quota_used from pmt_activity where activity_no=?", Integer.class, activityNo);
    }

    private String goods(String entityNo) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo("G-IQ-" + (++seq));
        g.setEntityNo(entityNo);
        g.setTitle("限量测试货 " + seq);
        g.setType("STANDARD");
        g.setOnSale(true);
        g.setAuditStatus("APPROVED");
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        return g.getGoodsNo();
    }
}
