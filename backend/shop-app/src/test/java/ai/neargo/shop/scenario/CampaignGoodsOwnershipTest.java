package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityGoods;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityGoodsMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
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
 * 活动的参与商品必须是建活动那家商家自己的货（老模型 mkt_campaign 与新模型 pmt_activity 两边）。
 *
 * <p>此前两边都照单全收货号，算价又按货号反查活动、不看活动是谁建的：
 * A 店建一个 ¥0.01 的限时特价、填上 B 店的货号，B 的货就在全平台按 A 定的价卖。
 * 守两道：保存时拒（NOT_FOUND，不泄露货号归属），读时对库里已有的坏行不生效。
 */
@SpringBootTest
@ActiveProfiles("test")
class CampaignGoodsOwnershipTest {

    private static final long DAY = 86_400_000L;
    private static int seq = 7100;

    @Autowired private CampaignService campaignService;
    @Autowired private ActivityService activityService;
    @Autowired private CampaignPort campaignPort;
    @Autowired private GoodsMapper goodsMapper;
    @Autowired private CampaignMapper campaignMapper;
    @Autowired private ActivityMapper activityMapper;
    @Autowired private ActivityGoodsMapper activityGoodsMapper;

    @Test
    @DisplayName("★★★ 老模型：A 店拿 B 店的货建限时特价 ¥0.01 —— 保存被拒；拿自己的货照常能建")
    void legacyFlashOnForeignGoodsIsRejected() {
        String a = "M-OWN-A" + (++seq);
        String b = "M-OWN-B" + seq;
        String mine = goods(a);
        String theirs = goods(b);

        assertThatThrownBy(() -> campaignService.save(a, flash(List.of(theirs))))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.NOT_FOUND.name());
        assertThatThrownBy(() -> campaignService.save(a, flash(List.of(mine, theirs))))
                .as("夹带一件别家的货也不行")
                .isInstanceOf(BizException.class);
        assertThat(campaignService.save(a, flash(List.of(mine))).goodsNos()).containsExactly(mine);
    }

    @Test
    @DisplayName("★★★ 新模型：A 店拿 B 店的货建改价活动 —— 保存被拒")
    void activityPriceOnForeignGoodsIsRejected() {
        String a = "M-OWN-A" + (++seq);
        String b = "M-OWN-B" + seq;
        String mine = goods(a);
        String theirs = goods(b);

        assertThatThrownBy(() -> activityService.save(a, price(List.of(theirs)), "OP"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.NOT_FOUND.name());
        assertThat(activityService.save(a, price(List.of(mine)), "OP").goodsNos()).containsExactly(mine);
    }

    @Test
    @DisplayName("★★★ 库里已有的坏行（拦截之前存下的）不改 B 的价：两套模型的特价与买赠都不生效")
    void preExistingForeignRowsDoNotPriceOtherMerchantsGoods() {
        String a = "M-OWN-A" + (++seq);
        String b = "M-OWN-B" + seq;
        String theirs = goods(b);
        long now = System.currentTimeMillis();

        MktCampaign legacy = new MktCampaign();
        legacy.setCampaignNo("CP-OWN-" + seq);
        legacy.setEntityNo(a);
        legacy.setType(MktCampaign.FLASH);
        legacy.setName("别家货特价");
        legacy.setStatus(MktCampaign.RUNNING);
        legacy.setStartAt(now - DAY);
        legacy.setEndAt(now + DAY);
        legacy.setFlashPriceMinor(1L);
        legacy.setGoodsNos("[\"" + theirs + "\"]");
        legacy.setTakenCount(0);
        legacy.setUsedCount(0);
        DataScopeContext.executeWithoutScope(() -> campaignMapper.insert(legacy));

        badActivity(a, theirs, PmtActivity.BENEFIT_PRICE, now);
        badActivity(a, theirs, PmtActivity.BENEFIT_GIFT, now);

        assertThat(campaignPort.flashPrices(List.of(theirs)))
                .as("★ A 的 ¥0.01 特价改掉了 B 的货在全平台的价")
                .doesNotContainKey(theirs);
        assertThat(campaignPort.giftRules(List.of(theirs))).doesNotContainKey(theirs);
    }

    // ---------------------------------------------------------------- 夹具

    private void badActivity(String entityNo, String goodsNo, String benefit, long now) {
        PmtActivity act = new PmtActivity();
        act.setActivityNo("PA-OWN-" + benefit + "-" + seq);
        act.setEntityNo(entityNo);
        act.setName("别家货活动");
        act.setTriggerType(PmtActivity.BENEFIT_GIFT.equals(benefit) ? PmtActivity.TRIGGER_QTY
                : PmtActivity.TRIGGER_GOODS);
        act.setTriggerQty(PmtActivity.BENEFIT_GIFT.equals(benefit) ? 1 : null);
        act.setBenefitType(benefit);
        act.setBenefitAmountMinor(1L);
        act.setBenefitQty(PmtActivity.BENEFIT_GIFT.equals(benefit) ? 5 : null);
        act.setBenefitRef(PmtActivity.BENEFIT_GIFT.equals(benefit) ? goodsNo : null);
        act.setScheduleType(PmtActivity.ONE_OFF);
        act.setStartAt(now - DAY);
        act.setEndAt(now + DAY);
        act.setQuota(100);
        act.setQuotaUsed(0);
        act.setBudgetUsedMinor(0L);
        act.setStatus(PmtActivity.RUNNING);
        DataScopeContext.executeWithoutScope(() -> activityMapper.insert(act));
        PmtActivityGoods g = new PmtActivityGoods();
        g.setActivityNo(act.getActivityNo());
        g.setEntityNo(entityNo);
        g.setScopeType(PmtActivityGoods.GOODS);
        g.setRefNo(goodsNo);
        DataScopeContext.executeWithoutScope(() -> activityGoodsMapper.insert(g));
    }

    private String goods(String entityNo) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo("G-OWN-" + (++seq));
        g.setEntityNo(entityNo);
        g.setTitle("归属测试货 " + seq);
        g.setType("STANDARD");
        g.setOnSale(true);
        g.setAuditStatus("APPROVED");
        DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        return g.getGoodsNo();
    }

    private static CampaignService.SaveCommand flash(List<String> goodsNos) {
        long now = System.currentTimeMillis();
        return new CampaignService.SaveCommand(null, "FLASH", "一分钱特价 " + (++seq), now - 1000, now + DAY,
                null, null, 1L, null, null, goodsNos, null, null);
    }

    private static ActivityDraft price(List<String> goodsNos) {
        long now = System.currentTimeMillis();
        return new ActivityDraft(null, "一分钱特价 " + (++seq), "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, 1L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null,
                50, null, List.of(), goodsNos);
    }
}
