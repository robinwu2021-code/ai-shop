package ai.neargo.shop.scenario;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C 端「逛」的全链路（S1 的验收标准）：选社区 → 逛商品 → 看详情 → 登录 → 绑定归属 → 我的资料。
 *
 * <p>按业务链路而不是按类写测试（powerbank 的 scenario 模式）：
 * 这条链路是 c-app 设 {@code VITE_USE_MOCK=0} 后打开首页要走的全部请求，
 * 它绿了才说明「翻真后端」这件事真的成立。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsumerBrowseFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    /** 读回刚发出去的验证码：走真实发码-校验链路，不给生产代码开万能码后门。 */
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("游客：选社区 → 看到自提点与承接商家")
    void guestCanPickCommunity() throws Exception {
        String body = mvc().perform(get("/mp/community/nearby").param("lat", "30.2900").param("lng", "120.1100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode communities = json.readTree(body).get("data");
        assertThat(communities).isNotEmpty();
        JsonNode first = communities.get(0);
        assertThat(first.get("communityNo").asString()).isNotBlank();
        // 自提点必须带承接方信息，否则用户不知道去谁家取货
        assertThat(first.get("pickups")).isNotEmpty();
        assertThat(first.get("pickups").get(0).get("leaderName").asString()).isNotBlank();
        assertThat(first.get("pickups").get(0).get("arrivalDesc").asString()).isNotBlank();
        // 传了定位就该算出距离，且**近的排前面** —— 选点页的排序就是这个接口的全部价值
        assertThat(first.get("distance").asInt()).isGreaterThan(0);
        assertThat(first.get("communityNo").asString()).isEqualTo("C0002");   // 翡翠城离查询点更近
        assertThat(communities.get(1).get("distance").asInt())
                .isGreaterThan(first.get("distance").asInt());
    }

    @Test
    @DisplayName("游客：按社区逛商品 → 分页包字段是 {records,total,page,size}")
    void guestCanBrowseGoods() throws Exception {
        String body = mvc().perform(get("/mp/goods").param("communityNo", "C0001").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.records").isArray())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andReturn().getResponse().getContentAsString();

        JsonNode goods = json.readTree(body).get("data").get("records").get(0);
        // 商品卡要的字段一个都不能少，否则端上渲染出空白卡
        assertThat(goods.get("goodsNo").asString()).isNotBlank();
        assertThat(goods.get("price").asLong()).isPositive();
        assertThat(goods.get("merchant").get("name").asString()).isNotBlank();
    }

    @Test
    @DisplayName("店内搜索与平台逛用同一端点、同一价格（双入口同源 R17）")
    void storeAndPlatformSharePrice() throws Exception {
        long platformPrice = priceOf(mvc().perform(get("/mp/goods").param("communityNo", "C0001"))
                .andReturn().getResponse().getContentAsString(), "G0001");
        long storePrice = priceOf(mvc().perform(get("/mp/goods").param("merchantNo", "M0001"))
                .andReturn().getResponse().getContentAsString(), "G0001");

        // 价格只挂 (entity_no, sku_no)，社区池不存价 —— 两条入口读的是同一行，不可能不同
        assertThat(storePrice).isEqualTo(platformPrice);
    }

    @Test
    @DisplayName("商品详情：SKU 与规格齐全，库存已扣掉锁定量")
    void goodsDetailHasSkus() throws Exception {
        mvc().perform(get("/mp/goods/G0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skus.length()").value(2))
                .andExpect(jsonPath("$.data.skus[0].spec").value("10斤装"))
                .andExpect(jsonPath("$.data.skus[0].price").value(4980))
                // 不断言固定值：同一个 H2 库里别的用例会下单占用库存，
                // 写死数字等于让这条用例依赖执行顺序
                .andExpect(jsonPath("$.data.skus[0].stock").value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.data.specGroups[0].options.length()").value(2));
    }

    @Test
    @DisplayName("登录 → 绑定归属 → 我的资料（手机号脱敏）")
    void loginThenBindCommunity() throws Exception {
        String token = login("13800138000");

        mvc().perform(post("/mp/user/community")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.communityNo").value("C0001"))
                .andExpect(jsonPath("$.data.pickupNo").value("PP0001"));

        mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cUserNo").isNotEmpty())
                .andExpect(jsonPath("$.data.phone").value("138****8000"));
    }

    @Test
    @DisplayName("自提点与社区不匹配时拒绝绑定")
    void bindRejectsMismatchedPickup() throws Exception {
        String token = login("13800138001");

        // PP0002 属于 C0002，配给 C0001 是非法组合：存进去会得到一个永远到不了货的归属
        mvc().perform(post("/mp/user/community")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0002\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("未登录访问 /mp/user/profile 返回 401，端上据此清 token")
    void profileRequiresLogin() throws Exception {
        mvc().perform(get("/mp/user/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10401));
    }

    /** 走真实的 OTP 链路：发码 → 从日志拿不到码，所以这里直接调服务发码后用固定流程登录。 */
    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}")).andExpect(status().isOk());

        String code = otpStore.peek(phone).orElseThrow(() -> new AssertionError("otp not sent"));
        String body = mvc().perform(post("/mp/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }

    private long priceOf(String responseBody, String goodsNo) {
        JsonNode records = json.readTree(responseBody).get("data").get("records");
        for (JsonNode g : records) {
            if (goodsNo.equals(g.get("goodsNo").asString())) {
                return g.get("price").asLong();
            }
        }
        throw new AssertionError("goods not found: " + goodsNo);
    }
}
