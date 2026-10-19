package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** 真实身份与跨门店支付夹具；业务状态不经mock。 */
final class OrderImpactFixtures {
    private final MockMvc mvc;
    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    OrderImpactFixtures(MockMvc mvc, ObjectMapper json, JdbcTemplate jdbc) {
        this.mvc = mvc; this.json = json; this.jdbc = jdbc;
    }

    String scopedSupport(String admin) throws Exception {
        String username = "impact-" + UUID.randomUUID() + "@neargo.ai";
        String body = mvc.perform(post("/ops/staffs").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"realName\":\"订单取消测试\",\"roles\":[\"SUPPORT\"]}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body).get("data");
        String staffNo = created.get("staff").get("staffNo").asString();
        mvc.perform(post("/ops/staffs/" + staffNo + "/scope").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"merchantNo\":\"M0001\"}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.operator(mvc, json, username, created.get("initialPassword").asString());
    }

    String payment(String status) {
        String no = "IP-" + UUID.randomUUID();
        jdbc.update("insert into ord_order (order_no, user_no, status, pay_amount, goods_amount, tenant_no, "
                + "created_at, updated_at) values (?, 'U-IMPACT', ?, 3000, 3000, 'MAIN', now(), now())", no, status);
        child(no, "A", "M0001", "ST-M0001", 1000);
        child(no, "B", "M0002", "ST-M0002", 2000);
        return no;
    }

    private void child(String pay, String suffix, String entity, String store, int amount) {
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, store_no, status, "
                + "fulfillment, pay_amount, goods_amount, tenant_no, created_at, updated_at) "
                + "values (?, ?, 'U-IMPACT', ?, ?, 'WAIT_PAY', 'STORE_PICKUP', ?, ?, 'MAIN', now(), now())",
                pay + suffix, pay, entity, store, amount, amount);
    }
}
