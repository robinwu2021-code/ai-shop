package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.service.create.OrderCreationRecoveryService;
import java.util.List;
import ai.neargo.shop.invbridge.InventoryBackfillService;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import ai.neargo.shop.trade.service.OrderService;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Actual separate inventory database must survive and compensate an order transaction rollback. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:order-inventory-recovery;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "shop.inventory.datasource.url=jdbc:h2:mem:inv-order-recovery;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "shop.inventory.stock-authority=INVENTORY"
})
@ActiveProfiles("test")
class OrderCreateInventoryRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private InventoryBackfillService backfill;
    @Autowired private OrderService orders;
    @Autowired private OrderCreationRecoveryService recovery;
    @Autowired private JdbcTemplate jdbc;
    @Autowired @Qualifier("invDataSource") private DataSource inventory;
    @MockitoSpyBean private StoreOrderMapper subs;
    @MockitoSpyBean private StockPort stock;

    @BeforeEach
    void prepareInventory() {
        backfill.run(false, 500, null);
    }

    @Test
    void orderInsertFailureReleasesIndependentReservation() throws Exception {
        doThrow(new IllegalStateException("order insert fault")).when(subs).insert(any(OrdSubOrder.class));
        String key = UUID.randomUUID().toString();
        int before = held();
        failedCreate("13107210001", key);
        assertThat(held()).isEqualTo(before);
        assertThat(jdbc.queryForObject("select version from ord_create_intent where idem_key=?", Long.class, key))
                .isGreaterThanOrEqualTo(2L);
    }

    @Test
    void failedReleaseHasDurableRecoveryThroughExistingCloseSweep() throws Exception {
        doThrow(new IllegalStateException("order insert fault")).when(subs).insert(any(OrdSubOrder.class));
        doThrow(new IllegalStateException("release unavailable")).when(stock).release(anyString(), anyList());
        int before = held();
        failedCreate("13107210002", UUID.randomUUID().toString());
        assertThat(held()).isEqualTo(before + 1);
        reset(subs, stock);
        orders.closeExpiredOrders(System.currentTimeMillis() + 300_000L);
        assertThat(held()).isEqualTo(before);
    }

    @Test
    void crashBeforeReserveLeavesTombstoneRejectingLateReservation() {
        String payNo = "CRASH-" + UUID.randomUUID();
        List<StockPort.SkuQty> needs = List.of(new StockPort.SkuQty("SK0003", 1, "ST-M0001"));
        recovery.register(payNo, "TEST", UUID.randomUUID().toString(), "test-hash", needs);
        recovery.recoverAbandoned(System.currentTimeMillis() + 300_000L);
        assertThat(jdbc.queryForObject("select status from ord_create_intent where pay_no=?", String.class, payNo))
                .isEqualTo("RELEASED");
        assertThatThrownBy(() -> stock.lock(payNo, needs)).isInstanceOf(BizException.class)
                .extracting(failure -> ((BizException) failure).errorCode()).isEqualTo(ErrorCode.CONFLICT);
    }

    @Test
    void multiMerchantPaymentConfirmsEveryInventoryOwner() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, "13107210003");
        String body = mvc.perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"goodsNo\":\"G0002\","
                                + "\"skuNo\":\"SK0003\",\"qty\":1},{\"goodsNo\":\"G0003\","
                                + "\"skuNo\":\"SK0004\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).path("data").path("payOrderNo").asString();
        mvc.perform(post("/mp/order/" + payNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
        mvc.perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payNo
                        + "\",\"transactionId\":\"IR-" + payNo + "\",\"sign\":\"stub-secret\"}"));
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, payNo))
                .isEqualTo("PAID");
        assertThat(new JdbcTemplate(inventory).queryForObject("select count(*) from inv_reservation"
                + " where external_ref=? and status='COMMITTED'", Integer.class, payNo)).isEqualTo(2);
    }

    private int held() {
        return new JdbcTemplate(inventory).queryForObject(
                "select count(*) from inv_reservation where status='HELD'", Integer.class);
    }

    private void failedCreate(String phone, String key) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, phone);
        var result = json.readTree(mvc.perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}],"
                                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(result.path("code").asInt()).as(result.toString()).isNotZero();
        assertThat(jdbc.queryForObject("select count(*) from sys_idempotent where idem_key=?", Integer.class, key))
                .isZero();
    }
}
