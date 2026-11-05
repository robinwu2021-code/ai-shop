package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import org.junit.jupiter.api.DisplayName;
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

/**
 * 零售商品订单的完整详情（TDD-B端收款订单与商城接口对齐 P2，B1–B2）：
 * 列表只列零售单；详情带币种、顾客备注、付款列表（方式 / 金额 / 时间 / 渠道交易号）、操作记录、发票。
 * 夹具同 {@link BizOrderFulfillFlowTest}：开店 → 上货 → 买家快递下单 → 桩通道回调付款。
 */
@SpringBootTest
@ActiveProfiles("test")
class BizRetailOrderDetailFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private record Ctx(String merchantToken, String subOrderNo, String payOrderNo) {
    }

    private JsonNode full(Ctx c) throws Exception {
        String body = mvc().perform(get("/biz/order/" + c.subOrderNo() + "/full")
                        .header("Authorization", "Bearer " + c.merchantToken()))
                .andReturn().getResponse().getContentAsString();
        JsonNode n = json.readTree(body);
        assertThat(n.get("code").asInt()).as(body).isZero();
        return n.get("data");
    }

    @Test
    @DisplayName("★★★ B2 完整详情：币种取主单（不再写死 CNY）、顾客备注、付款列表带渠道交易号、操作记录")
    void fullDetail() throws Exception {
        Ctx c = prepare("12600139101", "完整详情店", "12600139102");
        jdbc.update("UPDATE ord_sub_order SET remark=? WHERE sub_order_no=?", "放门口，不用按铃", c.subOrderNo());
        jdbc.update("UPDATE ord_order SET currency='TWD' WHERE order_no=(SELECT order_no FROM ord_sub_order"
                + " WHERE sub_order_no=?)", c.subOrderNo());
        mvc().perform(post("/biz/order/" + c.subOrderNo() + "/ship")
                        .header("Authorization", "Bearer " + c.merchantToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"SF1390001\",\"expressCompany\":\"SF\"}"))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode d = full(c);
        assertThat(d.get("order").get("orderNo").asString()).isEqualTo(c.subOrderNo());
        assertThat(d.get("currency").asString()).isEqualTo("TWD");
        assertThat(d.get("order").get("amount").get("currency").asString()).isEqualTo("TWD");
        assertThat(d.get("remark").asString()).isEqualTo("放门口，不用按铃");
        assertThat(d.get("source").get("caller").isNull() || "RETAIL".equals(d.get("source").get("caller").asString()))
                .isTrue();

        JsonNode pay = d.get("payments").get(0);
        assertThat(pay.get("kind").asString()).isEqualTo("ONLINE");
        assertThat(pay.get("amountMinor").asLong()).isPositive();
        assertThat(pay.get("channelTradeNo").asString()).isEqualTo("TX-" + c.payOrderNo());
        assertThat(pay.get("at").isNull()).isFalse();

        assertThat(d.get("operations").size()).isPositive();
        assertThat(d.get("operations").toString()).contains("FULFILLING");
    }

    @Test
    @DisplayName("★★ B1 列表按行业：caller=RETAIL 列得出零售单，caller=FOOD 列不出")
    void listByCaller() throws Exception {
        Ctx c = prepare("12600139103", "行业筛选店", "12600139104");
        String retail = mvc().perform(get("/biz/order").param("caller", "RETAIL")
                        .header("Authorization", "Bearer " + c.merchantToken()))
                .andReturn().getResponse().getContentAsString();
        assertThat(retail).contains(c.subOrderNo());
        String food = mvc().perform(get("/biz/order").param("caller", "FOOD")
                        .header("Authorization", "Bearer " + c.merchantToken()))
                .andReturn().getResponse().getContentAsString();
        assertThat(food).doesNotContain(c.subOrderNo());
    }

    @Test
    @DisplayName("★ 别家的单：完整详情同样回「不存在」")
    void foreignIsNotFound() throws Exception {
        Ctx mine = prepare("12600139105", "本店B", "12600139106");
        Ctx other = prepare("12600139107", "别家B", "12600139108");
        mvc().perform(get("/biz/order/" + other.subOrderNo() + "/full")
                        .header("Authorization", "Bearer " + mine.merchantToken()))
                .andExpect(jsonPath("$.code").value(10404));
    }

    // ------------------------------------------------------------------ 夹具（同 BizOrderFulfillFlowTest）

    private Ctx prepare(String merchantPhone, String shopName, String buyerPhone) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, merchantPhone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + shopName + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, merchantPhone);

        TestStoreCategory.open(mvc(), json, token, "CAT210");
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"详情测试商品\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String buyer = TestLogin.consumer(mvc(), json, otpStore, buyerPhone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"买家\",\"phone\":\"13600180013\",\"province\":\"浙江省\","
                                + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"文三路 1 号\","
                                + "\"isDefault\":true,\"tag\":\"家\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get(0).get("addressId").asString();
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\","
                                + "\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        mvc().perform(post("/mp/order/" + payOrderNo + "/pay").header("Authorization", "Bearer " + buyer))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());

        var records = json.readTree(mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data").get("records");
        assertThat(records.size()).isPositive();
        return new Ctx(token, records.get(0).get("orderNo").asString(), payOrderNo);
    }

    private String opsLogin(String username, String password) throws Exception {
        String body = mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
}
