package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import ai.neargo.shop.marketing.campaign.mapper.CampaignMappers.CampaignMapper;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.support.TestLogin;
import java.util.List;
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

@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.fallback.test.url:jdbc:h2:mem:order-stock-fallback;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}"
})
@ActiveProfiles("test")
class OrderCreateStockFallbackFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private OtpStore otp;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private CampaignMapper campaigns;
    @MockitoSpyBean private StockPort stock;

    @Test
    void shortageReturnedByPortCannotCreateUnreservedOrder() throws Exception {
        doReturn(List.of("SK0003")).when(stock).lock(anyString(), anyList());
        JsonNode result = create("13107200001");
        assertThat(result.path("code").asInt()).isEqualTo(20001);
    }

    @Test
    void missingReservationResultCannotBeTreatedAsSuccess() throws Exception {
        doReturn((List<String>) null).when(stock).lock(anyString(), anyList());
        assertThat(create("13107200004").path("code").asInt()).isNotZero();
        verify(stock, times(1)).lock(anyString(), anyList());
    }

    @Test
    void unknownReservationFailureNeverRetriesChangedContent() throws Exception {
        doThrow(new IllegalStateException("inventory response unknown")).when(stock).lock(anyString(), anyList());
        JsonNode result = create("13107200002");
        assertThat(result.path("code").asInt()).isNotZero();
        verify(stock, times(1)).lock(anyString(), anyList());
    }

    @Test
    void giftShortageFallsBackWithoutPoisoningOrderTransaction() throws Exception {
        jdbc.update("update prd_sku set stock=1, locked_stock=0, presale_quota=0 where sku_no='SK0003'");
        jdbc.update("update prd_store_stock set stock=1, locked_stock=0 where sku_no='SK0003'");
        MktCampaign gift = new MktCampaign();
        gift.setCampaignNo(UUID.randomUUID().toString());
        gift.setEntityNo("M0001");
        gift.setType(MktCampaign.BUY_GIFT);
        gift.setStatus(MktCampaign.RUNNING);
        gift.setName("buy one gift one");
        gift.setGoodsNos("[\"G0002\"]");
        gift.setBuyN(1);
        gift.setGiftM(1);
        gift.setStartAt(System.currentTimeMillis() - 1000);
        gift.setEndAt(System.currentTimeMillis() + 60_000);
        campaigns.insert(gift);
        try {
            JsonNode result = create("13107200003");
            assertThat(result.path("code").asInt()).as(result.toString()).isZero();
            String no = result.path("data").path("payOrderNo").asString();
            assertThat(jdbc.queryForObject("select sum(qty) from prd_stock_lock where lock_no=?", Integer.class, no))
                    .isEqualTo(1);
            assertThat(jdbc.queryForObject("select count(*) from ord_item where order_no=? and is_gift=1",
                    Integer.class, no)).isZero();
        } finally {
            campaigns.deleteById(gift.getId());
            jdbc.update("update prd_sku set stock=120, locked_stock=0 where sku_no='SK0003'");
            jdbc.update("update prd_store_stock set stock=120, locked_stock=0 where sku_no='SK0003'");
        }
    }

    private JsonNode create(String phone) throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String token = TestLogin.consumer(mvc, json, otp, phone);
        return json.readTree(mvc.perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"goodsNo\":\"G0002\","
                                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString());
    }
}
