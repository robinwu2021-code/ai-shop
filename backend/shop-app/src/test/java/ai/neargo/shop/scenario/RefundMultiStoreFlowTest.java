package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntityPlan;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.promotion.dto.CouponVOs.CouponSaveCmd;
import ai.neargo.shop.promotion.service.CouponService;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.support.ZeroRefundCrashFixture;
import ai.neargo.shop.support.OrderJourneyFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import ai.neargo.shop.support.TestStoreCategory;
import java.util.List;
import java.util.Map;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.multi.test.url:jdbc:h2:mem:refund-multistore;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundMultiStoreFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityPlanMapper plans;
    @Autowired private CouponService coupons;
    @MockitoSpyBean private TestPayGateway gateway;

    @Test
    void oneChildRefundLeavesSiblingAndItsSharedCouponUntilBothChildrenFinish() throws Exception {
        String coupon = coupons.save("M0001", new CouponSaveCmd(null, "两店共用券", "CASH", 300L,
                null, null, 1000L, null, "ALL", List.of(), null, "RELATIVE", null, null, 7,
                "CENTER", "ORDER", 1, 10, 1, null), "TEST").couponNo();
        try (var journey = new OrderJourneyFixture(context, "13207072301", "13207072311");
             var f = new BasicRefundFixture(context)) {
            String buyer = f.login("13207072301");
            String held = f.request(buyer, "/mp/coupon/" + coupon + "/receive", "{}")
                    .path("data").path("userCouponNo").asString();
            Pair pair = purchase(journey.mvc(), f, buyer, "13207072311", held, false);
            String no = f.apply(pair.first(), "REFUND_ONLY");
            f.approve(pair.first(), no);
            f.confirmCash(no);
            assertThat(status(pair.second().subNo())).isEqualTo("WAIT_FULFILL");
            assertThat(jdbc.queryForObject("select refunded_minor from ord_sub_order where sub_order_no=?",
                    Long.class, pair.second().subNo())).isZero();
            assertThat(couponStatus(held)).isEqualTo("USED");
            assertThat(jdbc.queryForObject("select amount_minor from stl_payment where after_sale_no=?",
                    Long.class, no)).isEqualTo(paid(pair.first().subNo()));
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where sub_order_no=?"
                    + " and direction='REFUND'", Long.class, pair.second().subNo())).isZero();
            String second = f.apply(pair.second(), "REFUND_ONLY");
            f.approve(pair.second(), second);
            f.confirmCash(second);
            assertThat(couponStatus(held)).isEqualTo("UNUSED");
        } finally {
            coupons.setStatus("M0001", coupon, "ENDED");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void zeroCashChildWithinPaidParentRestoresOnlyThatChildWithoutGatewayRefund(boolean interrupted) throws Exception {
        try (var journey = new OrderJourneyFixture(context, "13207072302", "13207072312");
             var f = new BasicRefundFixture(context)) {
            Pair pair = purchase(journey.mvc(), f, f.login("13207072302"), "13207072312", null, true);
            assertThat(paid(pair.first().subNo())).isZero();
            assertThat(paid(pair.second().subNo())).isPositive();
            String no = f.apply(pair.first(), "REFUND_ONLY");
            if (interrupted) {
                ZeroRefundCrashFixture crash = new ZeroRefundCrashFixture(context);
                crash.approveWithInterruptedCompletion(f, pair.first(), no);
                crash.recoverAndAssert(no, 2);
            } else {
                f.approve(pair.first(), no);
            }
            assertThat(status(pair.first().subNo())).isEqualTo("REFUNDED");
            assertThat(status(pair.second().subNo())).isEqualTo("WAIT_FULFILL");
            Map<String, Object> execution = jdbc.queryForMap("select status,resource_status,resource_error_code,"
                    + "restore_stock,stock_authority from ord_refund_execution where after_sale_no=?", no);
            assertThat(execution.get("resource_status")).as(execution.toString()).isEqualTo("DONE");
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_restore where purpose_no=?",
                    Long.class, "RF-" + no)).isEqualTo(2L);
            assertThat(jdbc.queryForObject("select amount_minor from stl_payment where after_sale_no=?"
                    + " and status='SUCCESS'", Long.class, no)).isZero();
            verify(gateway, never()).refund(any(), anyLong(), anyString(), anyString());
            verify(gateway, never()).queryRefund(anyString());
        }
    }

    private Pair purchase(MockMvc mvc, BasicRefundFixture f, String buyer, String ownerPhone,
                          String held, boolean zero) throws Exception {
        String biz = TestLogin.merchantOwner(mvc, json, otp, ownerPhone);
        DataScopeContext.executeWithoutScope(() -> {
            if (!plans.exists(Wrappers.<MchEntityPlan>lambdaQuery().eq(MchEntityPlan::getEntityNo, "M0001"))) {
                MchEntityPlan plan = new MchEntityPlan();
                plan.setEntityNo("M0001");
                plan.setPlanCode(MchEntityPlan.FREE);
                plan.setStatus(MchEntityPlan.ACTIVE);
                plans.insert(plan);
            }
        });
        TestPlan.grantPro(mvc, json, plans, biz);
        String a = getData(mvc, biz, "/biz/store/list").get(0).path("storeNo").asString();
        String b = f.request(biz, "/biz/store/create", "{\"name\":\"退款分店\",\"address\":\"退款路2号\"}")
                .path("data").path("storeNo").asString();
        JsonNode first = goods(mvc, biz, a);
        JsonNode second = goods(mvc, biz, b);
        String skuA = first.path("skus").get(0).path("skuNo").asString();
        String skuB = second.path("skus").get(0).path("skuNo").asString();
        if (zero) {
            jdbc.update("update prd_sku set price=0 where sku_no=?", skuA);
        }
        String body = json.writeValueAsString(Map.of("fulfillment", "STORE_PICKUP", "pickupNo", "PP0001",
                "usePoints", 0, "couponNo", held == null ? "" : held, "items", List.of(
                        Map.of("goodsNo", first.path("goodsNo").asString(), "skuNo", skuA, "qty", 2),
                        Map.of("goodsNo", second.path("goodsNo").asString(), "skuNo", skuB, "qty", 2))));
        JsonNode response = f.request(buyer, "/mp/order", body);
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        String parent = response.path("data").path("payOrderNo").asString();
        f.pay(buyer, parent);
        List<String> stores = jdbc.queryForList("select store_no from ord_sub_order where order_no=?",
                String.class, parent);
        assertThat(stores).containsExactlyInAnyOrder(a, b);
        return new Pair(child(buyer, parent, a), child(buyer, parent, b));
    }

    private JsonNode goods(MockMvc mvc, String biz, String store) throws Exception {
        TestStoreCategory.open(mvc, json, biz, store, "CAT210");
        JsonNode saved = postData(mvc, biz, store, "/biz/goods/save", Map.of("categoryNo", "CAT210",
                "title", "退款门店商品", "type", "NORMAL", "fulfillments", List.of("STORE_PICKUP"),
                "skus", List.of(Map.of("optionValues", List.of(), "price", 1000, "stock", 10))));
        String no = saved.path("goodsNo").asString();
        postData(mvc, TestLogin.admin(mvc, json), store, "/ops/goods/" + no + "/audit", Map.of("approved", true));
        postData(mvc, biz, store, "/biz/goods/" + no + "/toggle", Map.of("onSale", true));
        return getData(mvc, biz, "/mp/goods/" + no);
    }

    private JsonNode postData(MockMvc mvc, String token, String store, String path, Object body) throws Exception {
        JsonNode response = json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header("X-Store-No", store).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
        assertThat(response.path("code").asInt()).as(response.toString()).isZero();
        return response.path("data");
    }

    private JsonNode getData(MockMvc mvc, String token, String path) throws Exception {
        return json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).path("data");
    }

    private BasicRefundFixture.Purchase child(String buyer, String parent, String store) {
        String sub = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=? and store_no=?",
                String.class, parent, store);
        return new BasicRefundFixture.Purchase(buyer, parent, sub);
    }
    private String status(String no) {
        return jdbc.queryForObject("select status from ord_sub_order where sub_order_no=?", String.class, no);
    }
    private long paid(String no) {
        return jdbc.queryForObject("select pay_amount from ord_sub_order where sub_order_no=?", Long.class, no);
    }
    private String couponStatus(String no) {
        return jdbc.queryForObject("select status from pmt_user_coupon where user_coupon_no=?", String.class, no);
    }
    private record Pair(BasicRefundFixture.Purchase first, BasicRefundFixture.Purchase second) { }
}
