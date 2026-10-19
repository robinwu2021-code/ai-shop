package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.service.StoreOrderQueryService;
import ai.neargo.shop.trade.order.contract.StoreOrderQuery;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import java.util.UUID;
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

/** Actual child-list and legacy JSON routes must use owned, batched production reads. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.query.test.url:jdbc:h2:mem:store-order-query;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class StoreOrderQueryWiringFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private StoreOrderQueryService storeQueries;
    @MockitoSpyBean private OrderItemMapper items;
    @MockitoSpyBean private OrderStatusLogMapper logs;
    @MockitoSpyBean private MerchantQueryPort merchants;

    @Test
    void buyerListBatchesItemsTimelineAndStoreNames() throws Exception {
        String token = buyer("13107220001");
        create(token);
        create(token);
        clearInvocations(items, logs, merchants);
        JsonNode result = json.readTree(mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString());
        assertThat(result.path("data").path("records").size()).isEqualTo(2);
        verify(items, times(1)).selectList(any());
        verify(logs, times(1)).selectList(any());
        verify(merchants, times(1)).storeNames(anyCollection());
    }

    @Test
    void corruptChildOwnershipCannotExposeAnotherBuyersPayment() throws Exception {
        String first = buyer("13107220002");
        String second = buyer("13107220003");
        String parentA = create(first);
        String parentB = create(second);
        String secondNo = jdbc.queryForObject("select user_no from ord_order where order_no=?", String.class, parentB);
        String sub = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, parentA);
        jdbc.update("update ord_sub_order set user_no=? where sub_order_no=?", secondNo, sub);
        mvc().perform(get("/mp/order/" + sub).header("Authorization", "Bearer " + second))
                .andExpect(jsonPath("$.code").value(10404));
        assertThat(DataScopeContext.executeWithoutScope(() -> storeQueries.detail(sub, secondNo))).isNull();
    }

    @Test
    void detailAndListKeepParentAndChildAmountsSeparateAndDoNotWrite() throws Exception {
        String token = buyer("13107220004");
        String parent = create(token);
        String sub = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, parent);
        long version = jdbc.queryForObject("select version from ord_sub_order where sub_order_no=?", Long.class, sub);
        JsonNode detail = json.readTree(mvc().perform(get("/mp/order/" + sub)
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString());
        assertThat(detail.path("data").path("orderNo").asString()).isEqualTo(sub);
        assertThat(detail.path("data").path("payOrderNo").asString()).isEqualTo(parent);
        assertThat(detail.path("data").path("items").size()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version from ord_sub_order where sub_order_no=?", Long.class, sub))
                .isEqualTo(version);
    }

    @Test
    void twoStoresOnePaymentKeepsEachStoreAmountAndReadRevision() throws Exception {
        String token = buyer("13107220005");
        long first = jdbc.queryForObject("select price from prd_sku where sku_no='SK0003'", Long.class);
        long second = jdbc.queryForObject("select price from prd_sku where sku_no='SK0004'", Long.class);
        try {
            jdbc.update("update prd_sku set price=1000, version=version+1 where sku_no='SK0003'");
            jdbc.update("update prd_sku set price=2000, version=version+1 where sku_no='SK0004'");
            String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                            .header("Idempotency-Key", UUID.randomUUID().toString())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"goodsNo\":\"G0002\","
                                    + "\"skuNo\":\"SK0003\",\"qty\":1},{\"goodsNo\":\"G0003\","
                                    + "\"skuNo\":\"SK0004\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                    + "\"pickupNo\":\"PP0001\"}"))
                    .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
            var created = json.readTree(body).path("data");
            assertThat(created.path("amount").path("payableMinor").asLong()).isEqualTo(3000);
            String payNo = created.path("payOrderNo").asString();
            String buyerNo = jdbc.queryForObject("select user_no from ord_order where order_no=?", String.class, payNo);
            var list = storeQueries.list(buyerNo, new StoreOrderQuery("WAIT_PAY", null), 1, 20);
            assertThat(list.records()).hasSize(2);
            assertThat(list.records()).extracting(row -> row.payAmountMinor()).containsExactlyInAnyOrder(1000L, 2000L);
            for (var row : list.records()) {
                var detail = storeQueries.detail(row.subOrderNo(), buyerNo);
                assertThat(detail.payOrderNo()).isEqualTo(payNo);
                assertThat(detail.payAmountMinor()).isEqualTo(row.payAmountMinor());
                assertThat(detail.items()).hasSize(1);
                assertThat(detail.version()).isEqualTo(row.version());
            }
        } finally {
            jdbc.update("update prd_sku set price=?,version=version+1 where sku_no='SK0003'", first);
            jdbc.update("update prd_sku set price=?,version=version+1 where sku_no='SK0004'", second);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private String buyer(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otp, phone);
    }

    private String create(String token) throws Exception {
        String result = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"goodsNo\":\"G0002\","
                                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return json.readTree(result).path("data").path("payOrderNo").asString();
    }
}
