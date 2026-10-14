package ai.neargo.shop.support;

import ai.neargo.shop.common.OtpStore;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Shipping tests use real merchant onboarding and B login instead of fabricated authorization contexts. */
public final class ShippingMerchantFixture {
    private ShippingMerchantFixture() {
    }

    public record Scope(String token, String merchant, String store) { }

    public static Scope open(MockMvc mvc, ObjectMapper json, OtpStore otp, String phone) throws Exception {
        String user = TestLogin.consumer(mvc, json, otp, phone);
        String body = mvc.perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"一期发货店\",\"subject\":\"INDIVIDUAL_BIZ\","
                        + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\",\"category\":\"食品\","
                        + "\"serviceScope\":\"COMMUNITY\",\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String apply = json.readTree(body).get("data").get("applyNo").asString();
        mvc.perform(post("/ops/merchant/apply/" + apply + "/audit")
                .header("Authorization", "Bearer " + TestLogin.admin(mvc, json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String token = TestLogin.merchantOwner(mvc, json, otp, phone);
        String merchant = json.readTree(mvc.perform(get("/biz/merchant/profile")
                .header("Authorization", "Bearer " + token)).andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();
        String store = json.readTree(mvc.perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data").get(0).get("storeNo").asString();
        return new Scope(token, merchant, store);
    }
}
