package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Real retail orders pin item benefits before accounting; current activity rules cannot change their receipt. */
@SpringBootTest
@ActiveProfiles("test")
class RetailItemBenefitFlowTest {
    private static final AtomicInteger SEQ = new AtomicInteger();
    @Autowired private GoodsMapper goodsMapper;
    @Autowired private CampaignMapper legacyCampaigns;
    @Autowired private SkuMapper skuMapper;
    @Autowired private GoodsQueryPort goodsPort;
    @Autowired private CampaignPort campaigns;
    @Autowired private ActivityService activities;
    @Autowired private ActivityPricingService pricing;
    @Autowired private OrderService orders;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    private String goods;
    private String sku;
    private int sequence;

    @BeforeEach
    void ownGoods() {
        sequence = SEQ.incrementAndGet();
        goods = "G-R01-" + sequence;
        sku = "SK-R01-" + sequence;
        DataScopeContext.executeWithoutScope(() -> {
            PrdGoods source = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                    .eq(PrdGoods::getGoodsNo, "G0001"));
            PrdGoods row = new PrdGoods();
            BeanUtils.copyProperties(source, row);
            row.setId(null);
            row.setGoodsNo(goods);
            row.setTitle("R01 fixture " + sequence);
            goodsMapper.insert(row);
            addSku(sku);
            return null;
        });
    }

    private void addSku(String no) {
        DataScopeContext.executeWithoutScope(() -> {
            PrdSku source = skuMapper.selectOne(Wrappers.<PrdSku>lambdaQuery()
                    .eq(PrdSku::getSkuNo, "SK0001"));
            PrdSku row = new PrdSku();
            BeanUtils.copyProperties(source, row);
            row.setId(null);
            row.setSkuNo(no);
            row.setGoodsNo(goods);
            row.setMerchantSkuCode(null);
            row.setBarcode(null);
            row.setPrice(1_000L);
            row.setStock(100);
            row.setLockedStock(0);
            skuMapper.insert(row);
            return null;
        });
    }

    @Test
    void flashChargesActualDifferenceAndCancellationReturnsItsFrozenBudget() throws Exception {
        String act = activity(false, 700L, 2_000L);
        String order = order(List.of(sku), List.of(3));
        assertThat(used(act, "quota_used")).isEqualTo(1);
        assertThat(used(act, "budget_used_minor")).isEqualTo(900);
        assertThat(jdbc.queryForObject("select activity_no from ord_item where order_no=?", String.class, order))
                .isEqualTo(act);
        assertThat(jdbc.queryForObject("select activity_benefit_type from ord_item where order_no=?",
                String.class, order)).isEqualTo("PRICE");
        assertThat(jdbc.queryForObject("select activity_benefit_minor from ord_item where order_no=?",
                Long.class, order)).isEqualTo(900);
        assertThat(jdbc.queryForObject("select price from ord_item where order_no=?", Long.class, order))
                .isEqualTo(700);
        assertThat(jdbc.queryForObject("select amount_minor from pmt_apply where order_no=? and promo_no=?",
                Long.class, order, act)).isZero();
        assertThat(jdbc.queryForObject("select budget_minor from pmt_apply where order_no=? and promo_no=?",
                Long.class, order, act)).isEqualTo(900);
        campaigns.commitItemBenefits("U-R01", order,
                List.of(new CampaignPort.ItemBenefitUse(goods, "M0001", false, act, 900)));
        assertThat(used(act, "budget_used_minor")).isEqualTo(900);
        jdbc.update("update pmt_activity set benefit_amount_minor=100 where activity_no=?", act);
        orders.cancelByPlatform(order, "R01 cancellation", "OP");
        pricing.release(order);
        assertThat(used(act, "quota_used")).isZero();
        assertThat(used(act, "budget_used_minor")).isZero();
        campaigns.commitItemBenefits("U-R01", order,
                List.of(new CampaignPort.ItemBenefitUse(goods, "M0001", false, act, 900)));
        assertThat(used(act, "budget_used_minor")).as("cancelled receipt cannot occupy budget again").isZero();
        assertThat(jdbc.queryForObject("select activity_benefit_minor from ord_item where order_no=?",
                Long.class, order)).isEqualTo(900);
    }

    @Test
    void multiSkuGoodsWithoutEffectiveFlashDoesNotConsumeQuotaOrBudget() throws Exception {
        String act = activity(false, 700L, 2_000L);
        addSku(sku + "B"); // Historical activity created before the product gained another SKU.
        String order = order(List.of(sku), List.of(2));
        assertThat(used(act, "quota_used")).isZero();
        assertThat(used(act, "budget_used_minor")).isZero();
        assertThat(jdbc.queryForObject("select activity_no from ord_item where order_no=?", String.class, order))
                .isNull();
        assertThat(jdbc.queryForObject("select price from ord_item where order_no=?", Long.class, order))
                .isEqualTo(1_000);
    }

    @Test
    void giftAcrossTwoSkusChargesActualGiftsButOneOrderShare() throws Exception {
        addSku(sku + "B");
        String act = activity(true, 0L, 5_000L);
        String order = order(List.of(sku, sku + "B"), List.of(2, 4));
        assertThat(used(act, "quota_used")).isEqualTo(1);
        assertThat(used(act, "budget_used_minor")).isEqualTo(3_000);
        assertThat(jdbc.queryForObject("select sum(qty) from ord_item where order_no=? and is_gift=1",
                Long.class, order)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select sum(activity_benefit_minor) from ord_item "
                + "where order_no=? and activity_no=? and activity_benefit_type='GIFT'", Long.class, order, act))
                .isEqualTo(3_000);
        assertThat(jdbc.queryForObject("select sum(amount) from ord_item where order_no=? and is_gift=1",
                Long.class, order)).isZero();
    }

    @Test
    void noGiftActuallySentDoesNotConsumeGiftBudget() throws Exception {
        String act = activity(true, 0L, 5_000L);
        jdbc.update("update prd_sku set stock=2,locked_stock=0 where sku_no=?", sku);
        String order = order(List.of(sku), List.of(2));
        assertThat(jdbc.queryForObject("select count(*) from ord_item where order_no=? and is_gift=1",
                Integer.class, order)).isZero();
        assertThat(used(act, "quota_used")).isZero();
        assertThat(used(act, "budget_used_minor")).isZero();
    }

    @Test
    void frozenWinnerIsUsedEvenAfterActivityEnds() {
        String act = activity(false, 700L, 2_000L);
        var offer = goodsPort.snapshot(List.of(sku)).get(sku);
        assertThat(offer.priceActivityNo()).isEqualTo(act);
        jdbc.update("update pmt_activity set status='ENDED' where activity_no=?", act);
        campaigns.commitItemBenefits("U-R01", "O-R01-FROZEN-" + sequence,
                List.of(new CampaignPort.ItemBenefitUse(goods, "M0001", false,
                        offer.priceActivityNo(), offer.priceDiscountMinor() * 2)));
        assertThat(used(act, "quota_used")).isEqualTo(1);
        assertThat(used(act, "budget_used_minor")).isEqualTo(600);
    }

    @Test
    void budgetOverrunNeverConsumesOrReturnsAnotherOrdersBudget() {
        String act = activity(false, 700L, 1_000L);
        assertThat(pricing.commitItemUsage("U-R01", "O-R01-A-" + sequence, act, "M0001", 1, 400)).isTrue();
        assertThat(pricing.commitItemUsage("U-R01", "O-R01-B-" + sequence, act, "M0001", 1, 900)).isFalse();
        pricing.release("O-R01-B-" + sequence);
        assertThat(used(act, "quota_used")).isEqualTo(1);
        assertThat(used(act, "budget_used_minor")).isEqualTo(400);
        pricing.release("O-R01-A-" + sequence);
        assertThat(used(act, "budget_used_minor")).isZero();
    }

    @Test
    void flashWithoutARealPriceReductionDoesNotConsumeAnyShare() throws Exception {
        String act = activity(false, 1_000L, 2_000L);
        String order = order(List.of(sku), List.of(2));
        assertThat(used(act, "quota_used")).isZero();
        assertThat(jdbc.queryForObject("select activity_no from ord_item where order_no=?", String.class, order))
                .isNull();
    }

    @Test
    void aBetterLegacyFlashDoesNotConsumeTheNewActivityBudget() throws Exception {
        String act = activity(false, 700L, 2_000L);
        long now = System.currentTimeMillis();
        var old = new MktCampaign();
        old.setCampaignNo("CP-R01-" + sequence);
        old.setEntityNo("M0001");
        old.setName("Legacy R01");
        old.setType(MktCampaign.FLASH);
        old.setStatus(MktCampaign.RUNNING);
        old.setFlashPriceMinor(600L);
        old.setGoodsNos("[\"" + goods + "\"]");
        old.setStartAt(now - 1000);
        old.setEndAt(now + 86_400_000L);
        DataScopeContext.executeWithoutScope(() -> legacyCampaigns.insert(old));
        String order = order(List.of(sku), List.of(2));
        assertThat(jdbc.queryForObject("select price from ord_item where order_no=?", Long.class, order))
                .isEqualTo(600);
        assertThat(used(act, "quota_used")).isZero();
        assertThat(used(act, "budget_used_minor")).isZero();
    }

    @Test
    void ordinaryPriceOfferKeepsItsUnsupportedTriggerRejection() {
        String act = activity(false, 700L, 2_000L);
        jdbc.update("update pmt_activity set trigger_type='CUTOFF' where activity_no=?", act);
        assertThat(campaigns.flashPrices(List.of(goods))).containsEntry(goods, 700L);
        assertThatThrownBy(() -> campaigns.flashOffersFor("U-R01", List.of(goods)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION));
    }

    private String activity(boolean gift, long price, long budget) {
        long now = System.currentTimeMillis();
        return activities.save("M0001", new ActivityDraft(null, "R01 " + sequence, "BASKET", null,
                gift ? PmtActivity.TRIGGER_QTY : PmtActivity.TRIGGER_GOODS, null, gift ? 2 : null,
                gift ? PmtActivity.BENEFIT_GIFT : PmtActivity.BENEFIT_PRICE,
                gift ? null : price, gift ? 1 : null, gift ? goods : null,
                PmtActivity.ONE_OFF, now - 1000, now + 86_400_000L, null, 50, budget,
                List.of(), List.of(goods)), "OP").activityNo();
    }

    private String order(List<String> skus, List<Integer> quantities) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, "1300314" + String.format("%04d", sequence));
        var items = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (int i = 0; i < skus.size(); i++) {
            items.add(java.util.Map.of("goodsNo", goods, "skuNo", skus.get(i), "qty", quantities.get(i)));
        }
        var response = json.readTree(mvc.perform(post("/mp/order").with(csrf())
                .header("Authorization", "Bearer " + token).header("Idempotency-Key", "r01-" + sequence)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(java.util.Map.of("fulfillment", "STORE_PICKUP",
                        "pickupNo", "PP0001", "items", items)))).andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data").path("orderNo").asString();
    }

    private long used(String act, String field) {
        return jdbc.queryForObject("select " + field + " from pmt_activity where activity_no=?", Long.class, act);
    }
}
