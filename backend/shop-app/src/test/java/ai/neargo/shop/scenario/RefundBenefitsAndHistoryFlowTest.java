package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/** Real coupon use, gift pricing, cash confirmation, and historical snapshot boundaries. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:refund-benefits;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundBenefitsAndHistoryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CouponService coupons;
    @Autowired private PlatformConfigService config;
    @Autowired private CampaignMapper campaigns;
    @Autowired private BasicRefundExecutionService refunds;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedFullRefundHonorsCouponReturnSwitch(boolean enabled) throws Exception {
        var original = config.featureFlags().stream().filter(f -> "refund.return-coupon".equals(f.key()))
                .findFirst().orElseThrow();
        String coupon = coupons.save("M0001", new CouponSaveCmd(null, "退款退券开关", "CASH", 300L,
                null, null, 1000L, null, "ALL", List.of(), null, "RELATIVE", null, null, 7,
                "CENTER", "ORDER", 1, 10, 1, null), "TEST").couponNo();
        config.saveFeatureFlag(original.key(), enabled, 100, "TEST");
        try (var f = new BasicRefundFixture(context)) {
            String phone = enabled ? "13207072001" : "13207072002";
            var received = f.request(f.login(phone), "/mp/coupon/" + coupon + "/receive", "{}");
            assertThat(received.path("code").asInt()).as(received.toString()).isZero();
            String held = received.path("data").path("userCouponNo").asString();
            var p = f.purchase(phone, held);
            assertThat(couponStatus(held)).isEqualTo("USED");
            String no = f.apply(p, "REFUND_ONLY");
            f.approve(p, no);
            f.confirmCash(no);
            refunds.recover("RF-" + no);
            assertThat(couponStatus(held)).isEqualTo(enabled ? "UNUSED" : "USED");
            assertThat(jdbc.queryForObject("select status from ord_refund_execution where after_sale_no=?",
                    String.class, no)).isEqualTo("SUCCEEDED");
        } finally {
            config.saveFeatureFlag(original.key(), original.enabled(), original.rolloutPercent(), "TEST");
            coupons.setStatus("M0001", coupon, "ENDED");
        }
    }

    @Test
    void fullCashRefundRestoresPaidAndGiftUnitsOfSameSkuOnce() throws Exception {
        MktCampaign gift = new MktCampaign();
        gift.setCampaignNo(UUID.randomUUID().toString());
        gift.setEntityNo("M0001");
        gift.setType(MktCampaign.BUY_GIFT);
        gift.setStatus(MktCampaign.RUNNING);
        gift.setName("refund original paid and gift units");
        gift.setGoodsNos("[\"G0002\"]");
        gift.setBuyN(1);
        gift.setGiftM(1);
        gift.setStartAt(System.currentTimeMillis() - 1000);
        gift.setEndAt(System.currentTimeMillis() + 300_000);
        campaigns.insert(gift);
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207072003");
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_lock where lock_no=?",
                    Integer.class, p.payNo())).isEqualTo(2);
            assertThat(jdbc.queryForObject("select sum(qty) from ord_item where order_no=? and is_gift=1",
                    Integer.class, p.payNo())).isEqualTo(1);
            String no = f.apply(p, "REFUND_ONLY");
            f.approve(p, no);
            f.confirmCash(no);
            refunds.recover("RF-" + no);
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("select sum(refunded_qty) from ord_item where order_no=?",
                    Long.class, p.payNo())).isEqualTo(2L);
        } finally {
            campaigns.deleteById(gift.getId());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"points_deduct", "points_deduct_minor", "points_fee_minor"})
    void negativeHistoricalPointsRequireManualResolution(String column) throws Exception {
        int suffix = List.of("points_deduct", "points_deduct_minor", "points_fee_minor").indexOf(column);
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("1320707210" + suffix);
            jdbc.update("update ord_sub_order set " + column + "=-1 where sub_order_no=?", p.subNo());
            var response = f.request(p.token(), "/mp/order/" + p.subNo() + "/after-sale",
                    "{\"type\":\"REFUND_ONLY\",\"reason\":\"历史积分\",\"reasonCode\":\"NOT_WANTED\"}");
            assertThat(response.path("code").asInt()).as(response.toString()).isEqualTo(20014);
            assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no=?",
                    Long.class, p.subNo())).isZero();
        }
    }

    @Test
    void historicalItemCashRefundWithoutQuantityCannotBeRefundedAgain() throws Exception {
        try (var f = new BasicRefundFixture(context)) {
            var p = f.purchase("13207072004");
            jdbc.update("update ord_item set refunded_minor=1 where sub_order_no=?", p.subNo());
            var response = f.request(p.token(), "/mp/order/" + p.subNo() + "/after-sale",
                    "{\"type\":\"REFUND_ONLY\",\"reason\":\"历史退款\",\"reasonCode\":\"NOT_WANTED\"}");
            assertThat(response.path("code").asInt()).as(response.toString()).isEqualTo(20011);
        }
    }

    private String couponStatus(String held) {
        return jdbc.queryForObject("select status from pmt_user_coupon where user_coupon_no=?", String.class, held);
    }
}
