package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
import ai.neargo.shop.support.TestStoreCategory;
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

/**
 * 经营类目被拒时，**提示要指向他真正该做的那一步**。
 *
 * <p>2026-09-29 在生产上走到的那条路：商家有四家店，某件货归在「水果」下、
 * 只有 B 店经营水果。在 A 店的上下文里把它下架，再上架就被拒，
 * 提示是「本店经营类目里没有「水果」，请先添加」—— 照着做，他会给 A 店
 * 加上一个 A 店其实不卖的类目；<b>而他真正要做的只是把门店切回 B 店</b>。
 *
 * <p>所以这里断的不是「拒没拒」（那条早就有了），是<b>拒的那句话</b>：
 * 说没说清是哪家店、有没有告诉他这一类在哪家店下。判据落在 msg 上，
 * 因为这句话就是这次修复的全部交付物 —— 只断 code 的话，把门店名删掉也不会红。
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreCategoryHintFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context)
                    .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                            .springSecurity())
                    .build();
        }
        return mvc;
    }

    @Test
    @DisplayName("★★★ 类目在兄弟门店下：提示要说「切门店」，并且切过去就能上架（不是永久下架）")
    void categoryLivingInASiblingStoreTellsHimToSwitchStores() throws Exception {
        String biz = merchant("12600181001", "两家店各卖各的商家");
        TestPlan.grantQuota(planMapper, merchantNoOf(biz), 3);

        String storeA = defaultStoreNo(biz);
        // A 店只卖纸品清洁，B 店只卖水果 —— 复刻生产上那家商家的形状
        TestStoreCategory.open(mvc(), json, biz, storeA, "CAT210");
        String storeB = createStore(biz, "只有这家卖水果");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT120");

        String goodsNo = saveGoods(biz, storeB, "CAT120", "只有第二家店卖的苹果");
        approveGoods(goodsNo);
        toggle(biz, storeB, goodsNo, true).andExpect(jsonPath("$.code").value(0));

        // 他在 A 店的上下文里下架 —— 这一步是允许的，下架没有经营类目这道闸
        toggle(biz, storeA, goodsNo, false).andExpect(jsonPath("$.code").value(0));

        String msg = json.readTree(toggle(biz, storeA, goodsNo, true)
                        .andExpect(jsonPath("$.code").value(70075))
                        .andReturn().getResponse().getContentAsString())
                .get("msg").asString();

        assertThat(msg)
                .as("要说清是**哪家店**不经营 —— 「本店」在多门店商家那里指代不明")
                .contains(storeNameOf(biz, storeA))
                .as("要说清这一类在哪家店下，他才知道往哪儿切")
                .contains("只有这家卖水果")
                .contains("水果");
        assertThat(msg)
                .as("不能再让他去加类目：照做会把 A 店的经营范围凭空撑大一类")
                .doesNotContain("请先添加");

        /*
         * 而且这件货**不是回不去了** —— 切回 B 店就能上架。
         * 我一度把这条路读成「下架就永久下架」，是因为一直带着 A 店的 X-Store-No 在试。
         */
        toggle(biz, storeB, goodsNo, true).andExpect(jsonPath("$.code").value(0));
    }

    /*
     * <b>70068（「哪家店都不经营这一类」）这里造不出来</b>，所以没有第二条用例：
     * 新建的门店会带着主体已有的经营类目开张（实测 {@code /biz/store/{no}/categories}
     * 对刚建的店就返回 CAT210），而已有商品的类目又撤不掉
     * （{@code StoreCategoryService.replace} 守着「底下有商品删不掉」）。
     * 走得到 70068 的只有存量数据 —— 建品闸门立起来之前留下的那些。
     * 写一条造不出真实前提的用例，不如在这里说清它为什么不在。
     */

    // ── 夹具 ──────────────────────────────────────────────────────────

    private org.springframework.test.web.servlet.ResultActions toggle(
            String token, String storeNo, String goodsNo, boolean onSale) throws Exception {
        return mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .header("X-Store-No", storeNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"onSale\":" + onSale + "}"));
    }

    private String saveGoods(String token, String storeNo, String categoryNo, String title) throws Exception {
        return json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"" + categoryNo + "\",\"title\":\"" + title + "\","
                                + "\"subtitle\":\"\",\"cover\":\"🍎\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + opsLogin("goods", "goods123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private String createStore(String token, String name) throws Exception {
        return json.readTree(mvc().perform(post("/biz/store/create")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("storeNo").asString();
    }

    private String defaultStoreNo(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();
    }

    private String storeNameOf(String token, String storeNo) throws Exception {
        JsonNode stores = json.readTree(mvc().perform(get("/biz/store/list")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
        for (JsonNode s : stores) {
            if (storeNo.equals(s.get("storeNo").asString())) {
                return s.get("name").asString();
            }
        }
        throw new IllegalStateException("没有这家店：" + storeNo);
    }

    private String merchantNoOf(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
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
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        return TestLogin.operator(mvc(), json, username, password);
    }
}
