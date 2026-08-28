package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 门店级上架关系。
 *
 * <p>此前 {@code prd_goods} 只有 {@code entity_no}，两家店必然卖同一批商品 ——
 * 而真实小店不是这样：文三路店卖早点、古墩路店卖生鲜；开新店时先上十来样试水。
 *
 * <p>守三件事：
 * <ol>
 *   <li><b>单店行为逐字不变</b> —— 所有真实商家都是单店</li>
 *   <li>在一家店下架不影响另一家</li>
 *   <li><b>没有上架行的店视为未上架</b>，不是回退主体级 ——
 *       回退会让「只在 A 店卖」变成「两家店都卖」，正好与商家刚做的事相反</li>
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreGoodsFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;


    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 单店商家：上下架仍走主体级，行为与改造前逐字相同")
    void singleStoreBehaviourUnchanged() throws Exception {
        String biz = merchant("12600220001", "单店上架铺");
        String goodsNo = approvedGoods(biz);
        String store = defaultStoreNo(biz);

        toggle(biz, store, goodsNo, true);
        assertThat(statusOf(biz, store, goodsNo)).isEqualTo("ON_SALE");
        toggle(biz, store, goodsNo, false);
        assertThat(statusOf(biz, store, goodsNo)).isEqualTo("OFF_SALE");
    }

    @Test
    @DisplayName("★★ 下架再上架不能 500 —— 逻辑删的池行还占着 uk_community_goods")
    void offThenOnAgainDoesNotBlowUp() throws Exception {
        String biz = merchant("12600220060", "反复上下架店");
        String goodsNo = approvedGoods(biz);
        String store = defaultStoreNo(biz);

        // 与多门店无关，是最基本的一条路径：下架 → 再上架
        toggle(biz, store, goodsNo, true);
        toggle(biz, store, goodsNo, false);
        toggle(biz, store, goodsNo, true);
        assertThat(statusOf(biz, store, goodsNo)).isEqualTo("ON_SALE");

        // 再来一轮，确认复活是幂等的而不是只能救一次
        toggle(biz, store, goodsNo, false);
        toggle(biz, store, goodsNo, true);
        assertThat(statusOf(biz, store, goodsNo)).isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("★ A 店下架不影响 B 店 —— 店长做的是「今天我这儿不卖」")
    void storesToggleIndependently() throws Exception {
        String biz = merchant("12600220010", "双店上架·总店");
        String goodsNo = approvedGoods(biz);
        String storeA = defaultStoreNo(biz);
        // 多门店是 PRO 才有的能力，测试要说出「这家商家买了包」
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "双店上架·分店");

        // 两家店各自上架
        toggle(biz, storeA, goodsNo, true);
        toggle(biz, storeB, goodsNo, true);
        assertThat(statusOf(biz, storeA, goodsNo)).isEqualTo("ON_SALE");
        assertThat(statusOf(biz, storeB, goodsNo)).isEqualTo("ON_SALE");

        // B 店下架 —— A 店不受影响
        toggle(biz, storeB, goodsNo, false);
        assertThat(statusOf(biz, storeB, goodsNo)).isEqualTo("OFF_SALE");
        assertThat(statusOf(biz, storeA, goodsNo))
                .as("在一家店下架，另一家的货不该跟着没")
                .isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("★★ 转成店级的那一刻：A 店下架不能把从没单独设过的 B 店一起带下去")
    void firstConversionKeepsOtherStoresAsThoseWere() throws Exception {
        String biz = merchant("12600220050", "转换时刻店");
        String goodsNo = approvedGoods(biz);
        String storeA = defaultStoreNo(biz);

        // 主体级上架（此时还是单店，走的就是改造前那条路）
        toggle(biz, storeA, goodsNo, true);
        // 然后才开第二家店 —— 两家店都在卖，但**谁都没有店级行**
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "转换时刻·分店");
        assertThat(statusOf(biz, storeB, goodsNo)).isEqualTo("ON_SALE");

        // A 店下架：这是「主体级 → 店级」的转换时刻
        toggle(biz, storeA, goodsNo, false);

        assertThat(statusOf(biz, storeA, goodsNo)).isEqualTo("OFF_SALE");
        assertThat(statusOf(biz, storeB, goodsNo))
                .as("商家做的只是「A 店今天不卖」，B 店的货不该跟着没")
                .isEqualTo("ON_SALE");
    }

    @Test
    @DisplayName("★★ 只在 B 店上架 → A 店视为未上架，不是回退主体级")
    void storeWithoutRowIsNotOnSale() throws Exception {
        String biz = merchant("12600220020", "只在分店卖·总店");
        String goodsNo = approvedGoods(biz);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "只在分店卖·分店");

        // 只给 B 店上架 —— 商家做的就是「这货只在分店卖」
        toggle(biz, storeB, goodsNo, true);

        assertThat(statusOf(biz, storeB, goodsNo)).isEqualTo("ON_SALE");
        assertThat(statusOf(biz, storeA, goodsNo))
                .as("回退主体级会让 A 店跟着一起上架 —— 正好与商家刚做的事相反")
                .isEqualTo("OFF_SALE");
    }

    @Test
    @DisplayName("★ 所有门店都下架后，主体级也关掉 —— 否则 C 端社区池里还留着")
    void allStoresOffClosesEntityToo() throws Exception {
        String biz = merchant("12600220030", "全下架店");
        String goodsNo = approvedGoods(biz);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "全下架·分店");

        toggle(biz, storeA, goodsNo, true);
        toggle(biz, storeB, goodsNo, true);
        toggle(biz, storeA, goodsNo, false);
        toggle(biz, storeB, goodsNo, false);

        // 主体视角（不带门店头）也应是下架
        assertThat(statusOf(biz, null, goodsNo)).isEqualTo("OFF_SALE");
    }

    @Test
    @DisplayName("★ 不传门店头 = 默认店，不是「主体视角」—— B 端没有主体视角这回事")
    void noStoreHeaderMeansDefaultStore() throws Exception {
        String biz = merchant("12600220040", "默认店口径店");
        String goodsNo = approvedGoods(biz);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "默认店口径·分店");

        // 只在 B 店上架
        toggle(biz, storeB, goodsNo, true);

        // 不带门店头看到的必须与「显式带默认店」完全一致。
        // 若它另有一套「任一店在售就算在售」的口径，同一个人换个入口看到的答案会不一样
        assertThat(statusOf(biz, null, goodsNo)).isEqualTo(statusOf(biz, storeA, goodsNo));
        assertThat(statusOf(biz, null, goodsNo)).isEqualTo("OFF_SALE");
    }

    // ---------------------------------------------------------------- 装配

    private void toggle(String token, String storeNo, String goodsNo, boolean onSale) throws Exception {
        var req = post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"onSale\":" + onSale + "}");
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        mvc().perform(req).andExpect(jsonPath("$.code").value(0));
    }

    private String statusOf(String token, String storeNo, String goodsNo) throws Exception {
        var req = get("/biz/goods/" + goodsNo).header("Authorization", "Bearer " + token);
        if (storeNo != null) {
            req = req.header("X-Store-No", storeNo);
        }
        String body = mvc().perform(req).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("status").asString();
    }

    private String approvedGoods(String token) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"门店上架测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        return goodsNo;
    }

    private String defaultStoreNo(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get(0).get("storeNo").asString();
    }

    private String createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 5 号\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("storeNo").asString();
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
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String opsLogin() throws Exception {
        return TestLogin.admin(mvc(), json);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
