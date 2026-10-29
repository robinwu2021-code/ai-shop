package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.pay.channel.TestPayGateway;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.OrderService;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pause only an actual read; the financial success and stale cashier request use real production
 * controllers.
 */
@SpringBootTest(
        properties = {
            "spring.datasource.url=${order.phase1.init.race.test.url:jdbc:h2:mem:phase1-init-cash-race;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
        })
@ActiveProfiles("test")
class PaymentInitiationCashRaceFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private TestPayGateway gateway;
    @MockitoSpyBean private OrderService orders;
    @Autowired private TokenStores tokens;
    @MockitoSpyBean private SettlePort settlement;

    @Test
    void cashConfirmedAfterCashierReadCannotCreateAnotherChargeableAttempt() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13207031999");
        JsonNode created = request(token, "/mp/order", "{\"items\":[{\"goodsNo\":\"G0002\","
                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                + "\"pickupNo\":\"PP0001\",\"usePoints\":0}");
        assertThat(created.path("code").asInt()).isZero();
        String payNo = created.path("data").path("payOrderNo").asString();
        assertThat(request(token, "/mp/order/" + payNo + "/pay", "{}").path("code").asInt())
                .isZero();
        String outNo = jdbc.queryForObject("select out_trade_no from stl_payment where order_no=?"
                + " and direction='PAY'", String.class, payNo);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(call -> {
            Object actual = call.callRealMethod();
            read.countDown();
            assertThat(resume.await(5, TimeUnit.SECONDS)).isTrue();
            return actual;
        }).when(settlement).successPayment(payNo);
        CompletableFuture<JsonNode> stale = CompletableFuture.supplyAsync(() -> {
            try { return request(token, "/mp/order/" + payNo + "/pay", "{}"); }
            catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            assertThat(read.await(3, TimeUnit.SECONDS)).isTrue();
            AtomicBoolean receiptReadInsideTransaction = new AtomicBoolean();
            AtomicInteger receiptReads = new AtomicInteger();
            String acceptedTradeNo = gateway.query(outNo).tradeNo();
            doAnswer(
                            call -> {
                                receiptReads.incrementAndGet();
                                receiptReadInsideTransaction.set(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return call.callRealMethod();
                            })
                    .when(settlement)
                    .successPayment(payNo, "TEST", acceptedTradeNo);
            assertThat(gateway.markPaid(outNo)).isTrue();
            String ack =
                    mvc().perform(
                                    post("/pay/callback/stub")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    json.writeValueAsString(
                                                            Map.of(
                                                                    "outTradeNo",
                                                                    outNo,
                                                                    "transactionId",
                                                                    gateway.query(outNo).tradeNo(),
                                                                    "sign",
                                                                    "stub-secret"))))
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertThat(ack).isEqualTo("SUCCESS");
            assertThat(receiptReads.get()).isPositive();
            assertThat(receiptReadInsideTransaction).isFalse();
            assertThat(jdbc.queryForObject("select status from ord_order where order_no=?",
                    String.class, payNo)).isEqualTo("PAID");
            resume.countDown();
            assertThat(stale.get(5, TimeUnit.SECONDS).path("code").asInt()).isEqualTo(10409);
            assertThat(jdbc.queryForObject("select count(*) from stl_payment where order_no=?"
                    + " and direction='PAY'", Long.class, payNo)).isEqualTo(1L);
        } finally {
            resume.countDown();
            try { stale.get(5, TimeUnit.SECONDS); }
            finally { reset(settlement); }
        }
    }

    @Test
    void legacyOfflineOrderCannotInitiateChannelBeforeDuringOrAfterMerchantConfirmation()
            throws Exception {
        String entity =
                jdbc.queryForObject(
                        "SELECT entity_no FROM prd_goods WHERE goods_no='G0002'", String.class);
        String store =
                jdbc.queryForObject(
                        "SELECT store_no FROM mch_store WHERE entity_no=? AND status='ACTIVE'"
                                + " ORDER BY id LIMIT 1",
                        String.class,
                        entity);
        String oldModes =
                jdbc.queryForObject(
                        "SELECT pay_modes FROM prd_goods WHERE goods_no='G0002'", String.class);
        Integer oldSwitch =
                jdbc.queryForObject(
                        "SELECT offline_pay_enabled FROM mch_store WHERE store_no=?",
                        Integer.class,
                        store);
        String id = UUID.randomUUID().toString().substring(0, 8);
        String account = "LEGACY-" + id;
        String qualification = "LEGACY-Q-" + id;
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no='G0002'");
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "INSERT INTO"
                    + " mch_qualification(qual_no,entity_no,qual_type,qual_name,status,created_at,updated_at)"
                    + " VALUES (?,?,'BUSINESS_LICENSE','isolated license','VALID',NOW(),NOW())",
                qualification,
                entity);
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(mch_account_no,entity_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,1,'ACTIVE',NOW(),NOW())",
                account,
                entity);
        String merchantToken =
                tokens.of(Realm.MERCHANT)
                        .issue(
                                new TokenStore.SessionData(
                                        LoginUser.merchant(account, "old cashier")));
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        CompletableFuture<JsonNode> confirmation = null;
        try {
            String token = TestLogin.consumer(mvc(), json, otp, "13207031998");
            var created =
                    request(
                            token,
                            "/mp/order",
                            "{\"items\":[{\"goodsNo\":\"G0002\","
                                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"PP0001\",\"usePoints\":0,\"payMode\":\"OFFLINE\"}");
            assertThat(created.path("code").asInt()).isZero();
            String orderNo = created.path("data").path("payOrderNo").asString();
            String subNo =
                    jdbc.queryForObject(
                            "SELECT sub_order_no FROM ord_sub_order WHERE order_no=?",
                            String.class,
                            orderNo);
            assertThat(request(token, "/mp/order/" + orderNo + "/pay", "{}").path("code").asInt())
                    .isNotZero();
            doAnswer(
                            call -> {
                                entered.countDown();
                                assertThat(resume.await(8, TimeUnit.SECONDS)).isTrue();
                                return call.callRealMethod();
                            })
                    .when(orders)
                    .markPaid(orderNo, "OFFLINE", "OFFLINE:" + entity);
            confirmation =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try {
                                    return json.readTree(
                                            mvc().perform(
                                                            post("/biz/order/"
                                                                            + subNo
                                                                            + "/confirm-offline-pay")
                                                                    .header(
                                                                            "Authorization",
                                                                            "Bearer "
                                                                                    + merchantToken)
                                                                    .header("X-Store-No", store))
                                                    .andReturn()
                                                    .getResponse()
                                                    .getContentAsString());
                                } catch (Exception error) {
                                    throw new IllegalStateException(error);
                                }
                            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(request(token, "/mp/order/" + orderNo + "/pay", "{}").path("code").asInt())
                    .isNotZero();
            resume.countDown();
            assertThat(confirmation.get(10, TimeUnit.SECONDS).path("code").asInt()).isZero();
            assertThat(request(token, "/mp/order/" + orderNo + "/pay", "{}").path("code").asInt())
                    .isNotZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    orderNo))
                    .isEqualTo("PAID");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                    Long.class,
                                    orderNo))
                    .isZero();
            Mockito.verify(gateway, Mockito.never()).prepay(ArgumentMatchers.any());
        } finally {
            resume.countDown();
            try {
                if (confirmation != null) {
                    confirmation.get(10, TimeUnit.SECONDS);
                }
            } finally {
                reset(orders, gateway);
                tokens.of(Realm.MERCHANT).revoke(merchantToken);
                jdbc.update("UPDATE prd_goods SET pay_modes=? WHERE goods_no='G0002'", oldModes);
                jdbc.update(
                        "UPDATE mch_store SET offline_pay_enabled=? WHERE store_no=?",
                        oldSwitch,
                        store);
                jdbc.update("DELETE FROM mch_account WHERE mch_account_no=?", account);
                jdbc.update("DELETE FROM mch_qualification WHERE qual_no=?", qualification);
            }
        }
    }

    private JsonNode request(String token, String path, String body) throws Exception {
        return json.readTree(
                mvc().perform(
                                post(path)
                                        .header("Authorization", "Bearer " + token)
                                        .header("Idempotency-Key", UUID.randomUUID().toString())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andReturn()
                        .getResponse()
                        .getContentAsString());
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
}
