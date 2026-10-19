package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:payment-impact;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class PaymentImpactFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantOrderService merchants;
    @MockitoSpyBean private StockPort stock;

    private MockMvc mvc() { return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build(); }
    private OrderImpactFixtures fixtures() { return new OrderImpactFixtures(mvc(), json, jdbc); }

    @Test
    void historicalNullStoresRemainReadableAndPreviewDoesNotWrite() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        for (boolean allNull : new boolean[]{true, false}) {
            String no = fixtures().payment("WAIT_PAY");
            jdbc.update("update ord_sub_order set store_no = null where sub_order_no = ?", no + "A");
            if (allNull) jdbc.update("update ord_sub_order set store_no = null where sub_order_no = ?", no + "B");
            JsonNode data = preview(admin, no + "A");
            assertThat(data.path("canCancel").asBoolean()).isTrue();
            assertThat(data.path("currency").asString()).isEqualTo("CNY");
            JsonNode legacy = data.get("affectedOrders").get(0);
            assertThat(legacy.has("storeNo")).isTrue();
            assertThat(legacy.get("storeNo").isNull()).isTrue();
            assertThat(legacy.has("storeName")).isTrue();
            assertThat(legacy.get("storeName").isNull()).isTrue();
            assertUnchanged(no, "WAIT_PAY");
            assertThat(jdbc.queryForObject("select version from ord_order where order_no = ?", Long.class, no)).isZero();
            verify(stock, never()).release(no);
        }
    }

    @Test
    void scopedPreviewDoesNotLeakHiddenSiblingOrWholeAmount() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        String scoped = fixtures().scopedSupport(admin);
        String no = fixtures().payment("WAIT_PAY");
        JsonNode data = preview(scoped, no + "A");
        assertThat(data.path("canCancel").asBoolean()).isFalse();
        assertThat(data.path("blockedCode").asString()).isEqualTo("FORBIDDEN");
        assertThat(data.has("affectedOrders")).isFalse();
        assertThat(data.has("totalPayableMinor")).isFalse();
        assertThat(data.has("payOrderNo")).isFalse();
    }

    @Test
    void scopedCancellationRejectsWholePaymentBeforeAnyWriteOrRelease() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        String scoped = fixtures().scopedSupport(admin);
        String no = fixtures().payment("WAIT_PAY");
        cancel(scoped, no + "A", 10403);
        assertUnchanged(no, "WAIT_PAY");
        verify(stock, never()).release(no);
    }

    @Test
    void fullyAuthorizedCancellationIncludesAllSiblingsAndReleasesOnce() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        String no = fixtures().payment("WAIT_PAY");
        JsonNode data = preview(admin, no + "A");
        assertThat(data.path("canCancel").asBoolean()).isTrue();
        assertThat(data.path("payOrderNo").asString()).isEqualTo(no);
        assertThat(data.path("totalPayableMinor").asLong()).isEqualTo(3000);
        assertThat(data.path("affectedOrders").size()).isEqualTo(2);
        cancel(admin, no + "A", 0);
        assertThat(status(no)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForList("select status from ord_sub_order where order_no = ?", String.class, no))
                .containsOnly("CANCELLED").hasSize(2);
        cancel(admin, no + "A", 20004);
        verify(stock, times(1)).release(no);
    }

    @Test
    void paymentAfterPreviewCannotBeCancelled() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        String no = fixtures().payment("WAIT_PAY");
        assertThat(preview(admin, no + "A").path("canCancel").asBoolean()).isTrue();
        jdbc.update("update ord_order set status = 'PAID', version = version + 1 where order_no = ?", no);
        cancel(admin, no + "A", 20004);
        assertUnchanged(no, "PAID");
        verify(stock, never()).release(no);
    }

    @Test
    void historicalCrossStoreOfflinePaymentCannotBeConfirmedByOneStore() {
        String no = fixtures().payment("WAIT_OFFLINE_PAY");
        Throwable failure = catchThrowable(() -> DataScopeContext.executeWithoutScope(() ->
                merchants.confirmOfflinePay("M0001", "ST-M0001", no + "A", "STORE-OWNER")));
        assertThat(failure).isInstanceOf(BizException.class);
        assertThat(((BizException) failure).errorCode()).isEqualTo(ErrorCode.PAY_MODE_NOT_SUPPORTED);
        assertUnchanged(no, "WAIT_OFFLINE_PAY");
        assertThat(jdbc.queryForObject("select offline_confirmed_at from ord_order where order_no = ?",
                Long.class, no)).isNull();
    }

    private JsonNode preview(String token, String no) throws Exception {
        String body = mvc().perform(get("/ops/orders/" + no + "/cancel-impact")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private void cancel(String token, String no, int code) throws Exception {
        mvc().perform(post("/ops/orders/" + no + "/proxy-cancel").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"买家请求取消\"}"))
                .andExpect(jsonPath("$.code").value(code));
    }

    private String status(String no) {
        return jdbc.queryForObject("select status from ord_order where order_no = ?", String.class, no);
    }
    private void assertUnchanged(String no, String mainStatus) {
        assertThat(status(no)).isEqualTo(mainStatus);
        assertThat(jdbc.queryForList("select status from ord_sub_order where order_no = ?", String.class, no))
                .containsOnly("WAIT_PAY").hasSize(2);
    }
}
