package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@ActiveProfiles("test")
class AddressSalutationTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otpStore;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private JsonNode save(String token, String id, String extra) throws Exception {
        String body = "{\"name\":\"称呼测试\",\"phone\":\"13900000900\",\"detail\":\"测试路\""
                + (id == null ? "" : ",\"addressId\":\"" + id + "\"") + extra + "}";
        String response = mvc().perform(post("/mp/user/address")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        for (JsonNode address : json.readTree(response).get("data")) {
            if ("称呼测试".equals(address.path("name").asText())) return address;
        }
        throw new AssertionError("Saved address missing");
    }

    @Test
    void roundTripOldClientAndExplicitClear() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000671");
        JsonNode saved = save(token, null, ",\"salutation\":\"MS\"");
        String id = saved.path("addressId").asText();
        assertThat(saved.path("salutation").asText()).isEqualTo("MS");
        assertThat(save(token, id, "").path("salutation").asText()).isEqualTo("MS");
        assertThat(save(token, id, ",\"salutation\":\"UNSPECIFIED\"").path("salutation").asText())
                .isEqualTo("UNSPECIFIED");
        assertThat(save(token, id, ",\"salutation\":\"MR\"").path("salutation").asText()).isEqualTo("MR");
    }

    @Test
    void orderKeepsSalutationSnapshotAfterAddressChanges() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000672");
        String id = save(token, null, ",\"salutation\":\"MS\"").path("addressId").asText();
        String originalFulfillments = jdbc.queryForObject(
                "SELECT fulfillments FROM prd_goods WHERE goods_no = ?", String.class, "G0001");
        try {
            jdbc.update("UPDATE prd_goods SET fulfillments = ? WHERE goods_no = ?", "[\"EXPRESS\"]", "G0001");
            String body = "{\"items\":[{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1}],"
                    + "\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + id
                    + "\",\"idempotencyKey\":\"salutation-snapshot\"}";
            mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(jsonPath("$.code").value(0));
            assertThat(jdbc.queryForList(
                    "SELECT receiver_salutation FROM ord_sub_order WHERE address_id = ?", String.class, id))
                    .containsExactly("MS");
            save(token, id, ",\"salutation\":\"MR\"");
            assertThat(jdbc.queryForList(
                    "SELECT receiver_salutation FROM ord_sub_order WHERE address_id = ?", String.class, id))
                    .containsExactly("MS");
        } finally {
            // The seed is shared with pickup-order tests; restore even if a snapshot assertion fails.
            jdbc.update("UPDATE prd_goods SET fulfillments = ? WHERE goods_no = ?",
                    originalFulfillments, "G0001");
        }
    }
    @Test
    void legacyAddressDefaultsAndInvalidSalutationIsRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900000673");
        String id = save(token, null, "").path("addressId").asText();
        assertThat(save(token, id, "").path("salutation").asText()).isEqualTo("UNSPECIFIED");
        String response = mvc().perform(post("/mp/user/address")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"测试\",\"phone\":\"13900000900\",\"detail\":\"路\",\"salutation\":\"INVALID\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("code").asInt()).isNotZero();
    }

    @Test
    void anotherUserCannotChangeSalutation() throws Exception {
        String owner = TestLogin.consumer(mvc(), json, otpStore, "13900000674");
        String other = TestLogin.consumer(mvc(), json, otpStore, "13900000675");
        String id = save(owner, null, ",\"salutation\":\"MS\"").path("addressId").asText();
        String response = mvc().perform(post("/mp/user/address")
                .header("Authorization", "Bearer " + other).contentType(MediaType.APPLICATION_JSON)
                .content("{\"addressId\":\"" + id + "\",\"name\":\"测试\",\"phone\":\"13900000900\","
                        + "\"detail\":\"路\",\"salutation\":\"MR\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(response).path("code").asInt()).isNotZero();
        assertThat(jdbc.queryForObject("SELECT salutation FROM usr_address WHERE address_id = ?", String.class, id))
                .isEqualTo("MS");
    }

}
