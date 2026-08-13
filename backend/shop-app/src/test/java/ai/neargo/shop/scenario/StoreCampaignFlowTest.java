package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 营销活动分门店（V17）。
 *
 * <p>此前 {@code mkt_campaign} 没有门店维度，四种活动一律全主体生效 ——
 * 「新店开业第一周满减」做不了。
 *
 * <p><b>只有满减能限定门店</b>，判据是「这个活动在哪一刻生效」：
 * 满减在算价时生效，那一刻顾客已经选好自提点，货从哪家店出是确定的（V16）。
 * 限时特价与买赠改的是<b>商品页的展示</b>，而浏览商品时自提点还没选 ——
 * 允许限定门店就会出现「页面 ¥9.90、下单 ¥12.80」。
 *
 * <p>守四件事：
 * <ol>
 *   <li>全主体活动（store_no 为空）行为逐字不变 —— 存量活动都是它</li>
 *   <li>门店级满减只减那家店的单</li>
 *   <li>另外三种类型限定门店<b>当场被拒</b>，且给的是专用错误码</li>
 *   <li>门店级与全主体级同时命中时取最优，不叠加</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreCampaignFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private ai.neargo.shop.spi.marketing.CampaignPort campaignPort;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 全主体满减（store_no 为空）对哪家店都生效 —— 存量活动行为不变")
    void entityWideCampaignAppliesToAnyStore() throws Exception {
        String biz = merchant("12600230001", "全店满减铺");
        String entityNo = merchantNo(biz);
        saveFullCut(biz, "全店满减", 5000, 800, null);

        assertThat(discountOf(entityNo, 6000, storeA(biz))).isEqualTo(800);
        assertThat(discountOf(entityNo, 6000, "ST-ANY-OTHER")).isEqualTo(800);
        assertThat(discountOf(entityNo, 6000, null))
                .as("没有门店上下文时全主体活动照样生效")
                .isEqualTo(800);
    }

    @Test
    @DisplayName("★★ 门店级满减只减那家店的单 —— 别家店的单一分不减")
    void storeCampaignOnlyAppliesToItsStore() throws Exception {
        String biz = merchant("12600230010", "开业满减·总店");
        String entityNo = merchantNo(biz);
        String storeB = createStore(biz, "开业满减·分店");
        saveFullCut(biz, "新店开业满减", 5000, 800, storeB);

        assertThat(discountOf(entityNo, 6000, storeB)).isEqualTo(800);
        assertThat(discountOf(entityNo, 6000, storeA(biz)))
                .as("为一家店做的让利被全主体吃掉的话，商家要到对账时才发现")
                .isZero();
        assertThat(discountOf(entityNo, 6000, null))
                .as("没有门店上下文时**不认**门店级活动 —— 不是「全都认」")
                .isZero();
    }

    @Test
    @DisplayName("★★ 限时特价 / 买赠 / 店铺券限定门店当场被拒，且不是通用「参数有误」")
    void onlyFullCutAcceptsStoreScope() throws Exception {
        String biz = merchant("12600230020", "限定门店被拒铺");
        String storeB = createStore(biz, "被拒·分店");

        for (String type : java.util.List.of("FLASH", "BUY_GIFT", "COUPON")) {
            mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + biz)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(campaignBody(type, "限定门店的" + type, 5000, 800, storeB)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(70005));
        }
    }

    @Test
    @DisplayName("★ 门店级与全主体级同时命中：取最优，不叠加")
    void bestOfStoreAndEntityWide() throws Exception {
        String biz = merchant("12600230030", "两个满减铺");
        String entityNo = merchantNo(biz);
        String storeB = createStore(biz, "两个满减·分店");
        saveFullCut(biz, "全店满 50 减 8", 5000, 800, null);
        saveFullCut(biz, "分店满 50 减 15", 5000, 1500, storeB);

        assertThat(discountOf(entityNo, 6000, storeB))
                .as("叠加会让商家自己算不清成本 —— 建两个活动就变成减 23")
                .isEqualTo(1500);
        assertThat(discountOf(entityNo, 6000, storeA(biz))).isEqualTo(800);
    }

    // ---------------------------------------------------------------- 装配

    private long discountOf(String entityNo, long amount, String storeNo) {
        var d = campaignPort.autoDiscount(java.util.List.of(
                new ai.neargo.shop.spi.marketing.CampaignPort.MerchantAmount(entityNo, amount, storeNo)));
        return d.of(entityNo);
    }

    private String campaignBody(String type, String name, long threshold, long off, String storeNo) {
        long now = System.currentTimeMillis();
        return "{\"type\":\"" + type + "\",\"name\":\"" + name + "\",\"startAt\":" + (now - 1000L)
                + ",\"endAt\":" + (now + Duration.ofDays(7).toMillis())
                + ",\"thresholdMinor\":" + threshold + ",\"discountMinor\":" + off
                + ",\"flashPriceMinor\":100,\"buyN\":2,\"giftM\":1,\"goodsNos\":[]"
                + (storeNo == null ? "" : ",\"storeNo\":\"" + storeNo + "\"") + "}";
    }

    /** 建一个满减并启用 —— 只有 RUNNING 且在时间窗内的活动才参与算价 */
    private void saveFullCut(String token, String name, long threshold, long off, String storeNo)
            throws Exception {
        String body = mvc().perform(post("/biz/campaign").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(campaignBody("FULL_CUT", name, threshold, off, storeNo)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String campaignNo = json.readTree(body).get("data").get("campaignNo").asString();
        mvc().perform(post("/biz/campaign/" + campaignNo + "/toggle")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"running\":true}"))
                .andExpect(status().isOk());
    }

    private String storeA(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("storeNo").asString();
    }

    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 7 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
    }

    private String merchantNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return login(phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
