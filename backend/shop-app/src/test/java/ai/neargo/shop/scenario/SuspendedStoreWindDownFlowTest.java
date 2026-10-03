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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 停用一家门店之后，老板还进不进得去 —— 以及主体总闸算不算它。
 *
 * <p>线上撞到的（2026-09-29，可见性按门店算-方案 §9）：「虹选鲜果·福田店」停用后，
 * 它货架上的商品<b>店主用尽办法撤不下来</b>。带 {@code X-Store-No} 指向它会被
 * 静默忽略（请求落回默认店），下架请求于是打在一家本来就已下架的店上，
 * <b>还返回 code 0</b> —— 一次什么都没做的成功。
 *
 * <p>根因是把两件事绑在了一起：<b>营业状态是给买家看的，管理权限是给老板的</b>。
 * 停业之后老板正需要进去收尾（需求 B-11.12.4 明写「历史订单可查」）。
 *
 * <p>这里断的是收尾这条路走不走得通，以及走通之后<b>买家侧有没有跟着被打开</b> ——
 * 后者才是放开权限的代价，少断一条就等于把停业店偷偷开了一半。
 */
@SpringBootTest
@ActiveProfiles("test")
class SuspendedStoreWindDownFlowTest {

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
    @DisplayName("★★★ 门店停用后老板仍进得去，撤得下货；撤完主体总闸要关")
    void ownerCanStillWindDownASuspendedStore() throws Exception {
        String biz = merchant("12600182001", "会停用一家店的商家");
        TestPlan.grantQuota(planMapper, merchantNoOf(biz), 3);

        String storeA = defaultStoreNo(biz);
        TestStoreCategory.open(mvc(), json, biz, storeA, "CAT210");
        String storeB = createStore(biz, "要被停用的店");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String goodsNo = saveGoods(biz, storeB, "两家店都摆着的抽纸");
        approveGoods(goodsNo);
        toggle(biz, storeA, goodsNo, true).andExpect(jsonPath("$.code").value(0));
        toggle(biz, storeB, goodsNo, true).andExpect(jsonPath("$.code").value(0));

        setStatus(biz, storeB, "READONLY").andExpect(jsonPath("$.code").value(0));

        /*
         * ① 停用之后 X-Store-No 还认不认。
         * 这一条是整个修复的入口：不认的话，下面每一步都会打在默认店上，
         * 而且每一步都返回 code 0 —— 全绿，什么都没做。
         */
        assertThat(currentStoreNo(biz, storeB))
                .as("停用只是不对买家营业，老板必须还能切进去收尾（B-11.12.4「历史订单可查」）")
                .isEqualTo(storeB);

        /*
         * ② 主体总闸：**先撤 A 店、把 B 店那行留着在售**。
         * 顺序是判据的一部分 —— 反过来先撤 B 再撤 A，两行都成了 false，
         * 「总闸关了」就成了一句谁都能通过的话，量不到「停业店那行算不算数」。
         * 现在这个顺序下，B 店那行还挂着在售，总闸却必须关。
         */
        toggle(biz, storeA, goodsNo, false).andExpect(jsonPath("$.code").value(0));
        assertThat(storeOnSale(goodsNo, storeB))
                .as("前提：B 店那行此刻仍在售 —— 不然下一句断言没有区分力")
                .isTrue();
        assertThat(entityOnSale(goodsNo))
                .as("营业中的店全下架了，总闸就该关 —— 停业店那行不算数")
                .isFalse();

        // ③ 撤得下来：这一行就是线上撤不掉的那一行
        toggle(biz, storeB, goodsNo, false).andExpect(jsonPath("$.code").value(0));
        assertThat(storeOnSale(goodsNo, storeB)).isFalse();
    }

    @Test
    @DisplayName("★★★ 放开权限的代价：在停业门店上架，买家侧一个字节都不能变")
    void sellingInASuspendedStoreChangesNothingForBuyers() throws Exception {
        String biz = merchant("12600182002", "在停业店上架的商家");
        TestPlan.grantQuota(planMapper, merchantNoOf(biz), 3);

        String storeA = defaultStoreNo(biz);
        TestStoreCategory.open(mvc(), json, biz, storeA, "CAT210");
        String storeB = createStore(biz, "停业中的店");
        TestStoreCategory.open(mvc(), json, biz, storeB, "CAT210");

        String goodsNo = saveGoods(biz, storeB, "只在停业店上架的卷纸");
        approveGoods(goodsNo);
        setStatus(biz, storeB, "READONLY").andExpect(jsonPath("$.code").value(0));

        // 停业店上架：接口放行（记账上它是「重开时货还在架上」），但……
        toggle(biz, storeB, goodsNo, true).andExpect(jsonPath("$.code").value(0));

        assertThat(entityOnSale(goodsNo))
                .as("……主体总闸不能被停业店顶开 —— 顶开了 C 端无社区列表就会列出它")
                .isFalse();
        assertThat(poolRows(goodsNo))
                .as("社区池更不能多出一行：停业店的货不该出现在任何买家面前")
                .isZero();
    }

    // ── 夹具 ──────────────────────────────────────────────────────────

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper poolMapper;

    private boolean entityOnSale(String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                Boolean.TRUE.equals(goodsMapper.selectOne(
                        com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo))
                        .getOnSale()));
    }

    private boolean storeOnSale(String goodsNo, String storeNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                                .<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo)
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo))
                        .stream().anyMatch(r -> Boolean.TRUE.equals(r.getOnSale())));
    }

    private long poolRows(String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdCommunityPool>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdCommunityPool::getGoodsNo, goodsNo)));
    }

    private ResultActions setStatus(String token, String storeNo, String status) throws Exception {
        return mvc().perform(post("/biz/store/" + storeNo + "/status")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}"));
    }

    private ResultActions toggle(String token, String storeNo, String goodsNo, boolean onSale)
            throws Exception {
        return mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + token)
                .header("X-Store-No", storeNo)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"onSale\":" + onSale + "}"));
    }

    private String currentStoreNo(String token, String storeNo) throws Exception {
        return json.readTree(mvc().perform(get("/biz/context")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("currentStoreNo").asString();
    }

    private String saveGoods(String token, String storeNo, String title) throws Exception {
        return json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"" + title + "\","
                                + "\"subtitle\":\"\",\"cover\":\"🧻\",\"images\":[],"
                                + "\"specGroups\":[],\"skus\":[{\"optionValues\":[],\"price\":500,\"stock\":9}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();
    }

    private void approveGoods(String goodsNo) throws Exception {
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                        .header("Authorization", "Bearer " + TestLogin.operator(mvc(), json, "goods", "goods123"))
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

    private String merchantNoOf(String token) throws Exception {
        return json.readTree(mvc().perform(get("/biz/merchant/profile")
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
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
                        .header("Authorization", "Bearer " + TestLogin.operator(mvc(), json, "bd", "bd123"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
