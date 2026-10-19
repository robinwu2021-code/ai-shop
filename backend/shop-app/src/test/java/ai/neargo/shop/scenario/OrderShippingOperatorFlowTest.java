package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.ShippingMerchantFixture;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Real staff session identity must remain separate from the merchant/store authorization scope in shipment audit. */
@SpringBootTest
@ActiveProfiles("test")
class OrderShippingOperatorFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;

    @Test
    void staffIdentitySurvivesInitialShipAndPackageCorrection() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ShippingMerchantFixture.Scope scope = ShippingMerchantFixture.open(mvc, json, otp, "13400854002");
        String phone = "13600854002";
        String body = mvc.perform(post("/biz/staff").header("Authorization", "Bearer " + scope.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"loginPhone\":\"" + phone + "\",\"displayName\":\"发货员工\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String staff = json.readTree(body).get("data").get("mchAccountNo").asString();
        mvc.perform(post("/biz/staff/" + staff + "/store")
                .header("Authorization", "Bearer " + scope.token()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeNo\":\"" + scope.store() + "\",\"role\":\"CLERK\"}"))
                .andExpect(jsonPath("$.code").value(0));
        String token = TestLogin.merchantStaff(mvc, json, otp, phone);
        String sub = seed(scope);
        ship(mvc, token, scope.store(), sub, "SF20000001");
        assertThat(jdbc.queryForObject("SELECT operator_no FROM ord_status_log WHERE sub_order_no=?",
                String.class, sub)).isEqualTo(staff);
        ship(mvc, token, scope.store(), sub, "SF20000002");
        assertThat(jdbc.queryForList("SELECT operator_no FROM ord_status_log WHERE sub_order_no=? ORDER BY id",
                String.class, sub)).containsExactly(staff, staff);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=? AND status='VOIDED'",
                Long.class, sub)).isEqualTo(1);
    }

    private String seed(ShippingMerchantFixture.Scope scope) {
        String sub = "SOP" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,created_at,updated_at) "
                + "VALUES (?,'SHIP-OP-BUYER','PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,'SHIP-OP-BUYER',?,?,'EXPRESS','WAIT_FULFILL',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, scope.merchant(), scope.store());
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,qty,created_at,updated_at) "
                + "VALUES (?,?,'SHIP-GOODS','SHIP-SKU',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub);
        return sub;
    }

    private void ship(MockMvc mvc, String token, String store, String sub, String waybill) throws Exception {
        mvc.perform(post("/biz/order/" + sub + "/ship").header("Authorization", "Bearer " + token)
                .header("X-Store-No", store).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expressCompany\":\"SF\",\"expressNo\":\"" + waybill + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }
}
