package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.points.PointsOrderTermsPort;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import ai.neargo.shop.support.TestStoreCategory;
import ai.neargo.shop.trade.order.points.PointsIntegrationPolicyService;
import ai.neargo.shop.trade.order.points.PointsOrderPlacementCoordinator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

/** Real order creation/storage with synthetic merchant/store and simulated points kernel. */
@SpringBootTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:points-live-creation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.points.enabled=true"
        })
@ActiveProfiles("test")
class PointsOrderLiveCreationTest {
    @Autowired WebApplicationContext context;
    @Autowired ObjectMapper json;
    @Autowired OtpStore otp;
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.neargo.shop.spi.user.MerchantQueryPort merchants;
    @Autowired ai.neargo.shop.spi.pay.PayChannelMasterPort channels;
    @Autowired PointsIntegrationPolicyService policies;
    @Autowired PointsOrderPlacementCoordinator placement;
    @Autowired ai.neargo.shop.trade.service.OrderService orderService;
    @Autowired ai.neargo.shop.pay.service.PaymentLedgerService paymentLedger;
    @Autowired ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper plans;
    @MockitoBean PointsRedeemPort redeem;
    @MockitoBean PointsOrderTermsPort terms;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(
                        org.springframework.security.test.web.servlet.setup
                                .SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @BeforeEach
    void configurePorts() {
        reset(redeem, terms);
        when(terms.resolve(
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString(),
                        anyString()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            return Optional.of(
                                    new PointsOrderTermsPort.Terms(
                                            "TYPE",
                                            "CNY",
                                            2,
                                            1,
                                            true,
                                            true,
                                            new ai.neargo.shop.spi.points.PointsEarnPort.EarnRule(
                                                    ai.neargo.shop.spi.points.PointsEarnPort
                                                            .RuleMode.FIXED,
                                                    0),
                                            1,
                                            "SYNTHETIC_AGREEMENT"));
                        });
        when(redeem.quote(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var q = call.<PointsRedeemPort.RedeemQuery>getArgument(0);
                            var t = q.targets().getFirst();
                            return new PointsRedeemPort.RedeemQuote(
                                    "TYPE",
                                    2,
                                    1,
                                    20,
                                    10,
                                    null,
                                    List.of(
                                            new PointsRedeemPort.Share(
                                                    t.subOrderNo(),
                                                    t.merchantNo(),
                                                    t.storeNo(),
                                                    20,
                                                    10)));
                        });
    }

    @Test
    void live_creation_reuses_original_split_stock_and_coupon_boundary() throws Exception {
        var f = fixture("12600349010", "13000349010", "live");
        when(redeem.hold(any()))
                .thenAnswer(
                        call -> {
                            assertThat(
                                            TransactionSynchronizationManager
                                                    .isActualTransactionActive())
                                    .isFalse();
                            var h = call.<PointsRedeemPort.HoldCommand>getArgument(0);
                            var price = h.acceptedPrice();
                            return new PointsRedeemPort.HoldOutcome(
                                    "QA:" + h.orderNo(),
                                    price.typeCode(),
                                    price.points(),
                                    price.minor(),
                                    "HELD",
                                    null,
                                    price.shares());
                        });
        var response = create(f, "live-accepted");
        assertThat(response.get("status").asString()).isEqualTo("WAIT_PAY");
        String root = response.get("payOrderNo").asString();
        assertThat(placement.inspect(root).phase())
                .isEqualTo(PointsOrderPlacementCoordinator.Phase.READY);
        assertThat(
                        jdbc.queryForObject(
                                "select pay_amount from ord_order where order_no=?",
                                Long.class,
                                root))
                .isEqualTo(990);
        assertThat(
                        jdbc.queryForObject(
                                "select points_integrated from ord_order where order_no=?",
                                Integer.class,
                                root))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from ord_item where order_no=? and"
                                        + " supply_store_no=?",
                                Long.class,
                                root,
                                f.store))
                .isEqualTo(1);
        verify(redeem).hold(any());
    }

    @Test
    void payment_open_uses_the_route_accepted_with_the_original_store() throws Exception {
        var f = fixture("12600349013", "13000349013", "payment-route");
        when(redeem.hold(any()))
                .thenAnswer(
                        call -> {
                            var h = call.<PointsRedeemPort.HoldCommand>getArgument(0);
                            var price = h.acceptedPrice();
                            return new PointsRedeemPort.HoldOutcome(
                                    "QA:" + h.orderNo(),
                                    price.typeCode(),
                                    price.points(),
                                    price.minor(),
                                    "HELD",
                                    null,
                                    price.shares());
                        });
        String root = create(f, "live-route").get("payOrderNo").asString();
        var accepted =
                placement.inspect(root).draft().candidates().getFirst().target().acceptedRoute();
        String pay =
                mvc().perform(
                                post("/mp/order/" + root + "/pay")
                                        .header("Authorization", "Bearer " + f.buyer)
                                        .param("payChannel", "TEST"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(dataRoot(pay).get("code").asInt()).as(pay).isZero();
        String out =
                jdbc.queryForObject(
                        "SELECT out_trade_no FROM stl_payment WHERE order_no=?",
                        String.class,
                        root);
        assertThat(paymentLedger.paymentRequest(out)).isPresent();
        assertThat(paymentLedger.paymentRequest(out).orElseThrow().stores().getFirst().route())
                .isEqualTo(accepted);
    }

    @Test
    void only_a_verified_payment_creates_paid_and_earning_source_facts() throws Exception {
        var f = fixture("12600349014", "13000349014", "paid-fact");
        when(redeem.hold(any()))
                .thenAnswer(
                        call -> {
                            var h = call.<PointsRedeemPort.HoldCommand>getArgument(0);
                            var price = h.acceptedPrice();
                            return new PointsRedeemPort.HoldOutcome(
                                    "QA:" + h.orderNo(),
                                    price.typeCode(),
                                    price.points(),
                                    price.minor(),
                                    "HELD",
                                    null,
                                    price.shares());
                        });
        String root = create(f, "live-paid").get("payOrderNo").asString();
        String pay =
                mvc().perform(
                                post("/mp/order/" + root + "/pay")
                                        .header("Authorization", "Bearer " + f.buyer)
                                        .param("payChannel", "TEST"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(dataRoot(pay).get("code").asInt()).as(pay).isZero();
        String out =
                jdbc.queryForObject(
                        "SELECT out_trade_no FROM stl_payment WHERE order_no=?",
                        String.class,
                        root);
        assertThatThrownBy(() -> orderService.markPaid(root, "TEST", "client-claimed-trade"))
                .isInstanceOf(RuntimeException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                root))
                .isEqualTo("WAIT_PAY");
        paymentLedger.settle(
                new ai.neargo.shop.spi.settle.SettlePort.PaymentSettled(
                        out, "TEST", "verified-" + root, 990, System.currentTimeMillis()));
        assertThatThrownBy(() -> orderService.markPaid(root, "TEST", "forged-trade"))
                .hasMessageContaining("POINTS_PAYMENT_PROOF_MISMATCH");
        orderService.markPaid(root, null, null);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_order WHERE order_no=?",
                                String.class,
                                root))
                .isEqualTo("PAID");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_points_source_fact WHERE sub_order_no IN"
                                    + " (SELECT sub_order_no FROM ord_sub_order WHERE order_no=?)",
                                Long.class,
                                root))
                .isEqualTo(2);
    }

    @Test
    void unknown_hold_retains_original_amount_and_blocks_payment() throws Exception {
        var f = fixture("12600349011", "13000349011", "unknown");
        when(redeem.hold(any())).thenThrow(new IllegalStateException("SIMULATED_TIMEOUT"));
        var response = create(f, "live-unknown");
        assertThat(response.get("status").asString()).isEqualTo("POINTS_PROCESSING");
        String root = response.get("payOrderNo").asString();
        assertThat(placement.inspect(root).phase())
                .isEqualTo(PointsOrderPlacementCoordinator.Phase.UNKNOWN);
        assertThat(
                        jdbc.queryForObject(
                                "select pay_amount from ord_order where order_no=?",
                                Long.class,
                                root))
                .isEqualTo(1000);
        String pay =
                mvc().perform(
                                post("/mp/order/" + root + "/pay")
                                        .header("Authorization", "Bearer " + f.buyer)
                                        .param("payChannel", "WECHAT"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(dataRoot(pay).get("code").asInt()).isNotZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from prd_stock_lock where lock_no=? and"
                                        + " status='LOCKED'",
                                Long.class,
                                root))
                .isPositive();
        String cancelled =
                mvc().perform(
                                post("/mp/order/" + root + "/cancel")
                                        .header("Authorization", "Bearer " + f.buyer)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{\"reason\":\"synthetic cancellation\"}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(dataRoot(cancelled).get("code").asInt()).as(cancelled).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select status from ord_order where order_no=?",
                                String.class,
                                root))
                .isEqualTo("CANCELLED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from prd_stock_lock where lock_no=? and"
                                        + " status='LOCKED'",
                                Long.class,
                                root))
                .isZero();
        assertThat(placement.inspect(root).phase())
                .isEqualTo(PointsOrderPlacementCoordinator.Phase.CANCEL_REQUESTED);
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from ord_points_source_fact where sub_order_no in"
                                    + " (select sub_order_no from ord_sub_order where order_no=?)"
                                    + " and terminal_source=1",
                                Long.class,
                                root))
                .isEqualTo(1);
    }

    @Test
    void pending_hold_timeout_closes_the_original_order_and_releases_stock() throws Exception {
        var f = fixture("12600349012", "13000349012", "timeout");
        when(redeem.hold(any())).thenThrow(new IllegalStateException("SIMULATED_TIMEOUT"));
        String root = create(f, "live-timeout").get("payOrderNo").asString();
        jdbc.update(
                "update ord_order set pay_deadline_at=? where order_no=?",
                System.currentTimeMillis() - 60_000,
                root);
        orderService.closeUnpaid(root, "synthetic timeout");
        assertThat(
                        jdbc.queryForObject(
                                "select status from ord_order where order_no=?",
                                String.class,
                                root))
                .isEqualTo("CLOSED");
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from prd_stock_lock where lock_no=? and"
                                        + " status='LOCKED'",
                                Long.class,
                                root))
                .isZero();
        assertThat(placement.inspect(root).phase())
                .isEqualTo(PointsOrderPlacementCoordinator.Phase.CANCEL_REQUESTED);
    }

    private JsonNode create(Fixture f, String key) throws Exception {
        String body =
                mvc().perform(
                                post("/mp/order")
                                        .header("Authorization", "Bearer " + f.buyer)
                                        .header("X-Client", "H5")
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"items\":[{\"goodsNo\":\""
                                                        + f.goods
                                                        + "\",\"skuNo\":\""
                                                        + f.sku
                                                        + "\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                                        + "\"pickupNo\":\"PP0001\",\"usePoints\":20}"))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        assertThat(dataRoot(body).get("code").asInt()).as(body).isZero();
        return dataRoot(body).get("data");
    }

    private Fixture fixture(String merchantPhone, String buyerPhone, String tag) throws Exception {
        String applicant = TestLogin.consumer(mvc(), json, otp, merchantPhone);
        String application =
                mvc().perform(
                                post("/mp/merchant/apply")
                                        .header("Authorization", "Bearer " + applicant)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"name\":\"合成商家"
                                                        + tag
                                                        + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                                        + "\"contactName\":\"测试\",\"contactPhone\":\"13900000000\","
                                                        + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                                        + "\"communityNos\":[\"CM001\"]}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String applyNo = dataRoot(application).get("data").get("applyNo").asString();
        mvc().perform(
                        post("/ops/merchant/apply/" + applyNo + "/audit")
                                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approved\":true}"));
        String merchant = TestLogin.merchantOwner(mvc(), json, otp, merchantPhone);
        String entity =
                jdbc.queryForObject(
                        "select entity_no from mch_entity where name=?",
                        String.class,
                        "合成商家" + tag);
        TestPlan.grantPro(mvc(), json, plans, merchant);
        String stores =
                mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + merchant))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String store = dataRoot(stores).get("data").get(0).get("storeNo").asString();
        TestStoreCategory.open(mvc(), json, merchant, store, "CAT210");
        String saved =
                mvc().perform(
                                post("/biz/goods/save")
                                        .header("Authorization", "Bearer " + merchant)
                                        .header("X-Store-No", store)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                "{\"categoryNo\":\"CAT210\",\"title\":\"合成商品"
                                                        + tag
                                                        + "\",\"type\":\"NORMAL\",\"skus\":[{\"optionValues\":[],"
                                                        + "\"price\":1000,\"stock\":50}]}"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String goods = dataRoot(saved).get("data").get("goodsNo").asString();
        mvc().perform(
                        post("/ops/goods/" + goods + "/audit")
                                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approved\":true}"));
        mvc().perform(
                        post("/biz/goods/" + goods + "/toggle")
                                .header("Authorization", "Bearer " + merchant)
                                .header("X-Store-No", store)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"onSale\":true}"));
        String sku =
                jdbc.queryForObject(
                        "select sku_no from prd_sku where goods_no=?", String.class, goods);
        String payMerchant = "QA-PAY-" + tag;
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no=?", entity);
        jdbc.update("update mch_store set pay_merchant_no=? where store_no=?", payMerchant, store);
        jdbc.update("update sys_pay_channel set enabled=1 where pay_channel='TEST'");
        jdbc.update(
                "insert into"
                    + " mch_payment_merchant(entity_no,store_no,pay_merchant_no,pay_channel,sub_mchid,apply_status,version,created_at,updated_at)"
                    + " values(?,?,?,'TEST','QA-SUB','ACTIVE',0,"
                    + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                entity,
                store,
                payMerchant);
        assertThat(merchants.paymentRouteOf(entity, store, "TEST")).isPresent();
        assertThat(merchants.activeChannelsOf(entity, store)).contains("TEST");
        assertThat(channels.payableChannels(merchants.marketOf(entity))).contains("TEST");
        policies.change(
                new PointsIntegrationPolicyService.IntegrationPolicyChange(
                        "QA-ACTIVE-" + tag,
                        store,
                        "ONLINE",
                        PointsIntegrationPolicyService.Mode.ACTIVE,
                        true,
                        true,
                        -1,
                        "synthetic test"),
                "QA_OPS");
        String buyer = TestLogin.consumer(mvc(), json, otp, buyerPhone);
        return new Fixture(buyer, store, goods, sku);
    }

    private JsonNode dataRoot(String body) {
        return json.readTree(body);
    }

    private record Fixture(String buyer, String store, String goods, String sku) {}
}
