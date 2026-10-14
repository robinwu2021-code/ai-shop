package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.spi.settle.PointsPort;
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
import tools.jackson.databind.ObjectMapper;

/** Historical facts remain readable without enabling new points mutations. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:historical-points-read;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class PhaseOneHistoricalPointsReadTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @MockitoSpyBean private PointsPort points;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void historicalRefundAndRevokeRemainVisible() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13107110001");
        String payNo = create(token);
        String subNo = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no=?",
                String.class, payNo);
        jdbc.update("update ord_sub_order set status='REFUNDED', points_deduct=20, points_granted=1"
                + " where sub_order_no=?", subNo);
        String buyer = jdbc.queryForObject("select user_no from ord_order where order_no=?", String.class, payNo);
        ledger(buyer, subNo, "REFUND", 20);
        ledger(buyer, subNo, "REVOKE", -10);
        mvc().perform(get("/mp/order/" + subNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.returned.pointsReturned").value(20))
                .andExpect(jsonPath("$.data.returned.pointsClawedBack").value(10));
        verify(points).returnedOf(List.of(subNo));
        verifyNoMoreInteractions(points);
    }

    @Test
    void feeSnapshotAloneStillRequiresManualResolution() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13107110002");
        String payNo = create(token);
        jdbc.update("update ord_sub_order set points_fee_minor=10, points_granted=0 where order_no=?", payNo);
        mvc().perform(post("/mp/order/" + payNo + "/cancel").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"historical\"}"))
                .andExpect(jsonPath("$.code").value(20014));
        assertThat(jdbc.queryForObject("select status from ord_order where order_no=?", String.class, payNo))
                .isEqualTo("WAIT_PAY");
    }

    private String create(String token) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"goodsNo\":\"G0002\","
                                + "\"skuNo\":\"SK0003\",\"qty\":1}],\"fulfillment\":\"STORE_PICKUP\","
                                + "\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data").path("payOrderNo").asString();
    }

    private void ledger(String buyer, String subNo, String type, long amount) {
        jdbc.update("insert into pts_user_ledger (ledger_no,user_no,sub_order_no,biz_type,points,balance_after,"
                + "created_at,updated_at) values(?,?,?,?,?,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                UUID.randomUUID().toString(), buyer, subNo, type, amount);
    }
}
