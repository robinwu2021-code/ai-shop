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
 * 门店级库存。
 *
 * <p>此前 `prd_sku` 的唯一键是 (entity_no, sku_no, market)，**没有门店维度** ——
 * 一家主体开第二家店之后，A 店卖光了 B 店也显示无货；顾客在 A 店下单却从
 * 「全公司总量」扣，而货其实在 B 店。**自提场景下这是直接的履约事故**。
 *
 * <p>这个文件守三件事，按重要性排：
 * <ol>
 *   <li><b>单店行为逐字不变</b> —— 现在所有真实商家都是单店，
 *       任何一次多门店改造让单店行为变了，都是拿全量用户给一个还没人用的功能试错
 *   <li>启用分店库存后，两家店的数真的分得开
 *   <li><b>没设过库存的店卖不出去</b>（视为 0，不是回退到主体总量）——
 *       回退会让没设的店变成无限供应，比不分店更危险
 * </ol>
 */
@SpringBootTest
@ActiveProfiles("test")
class StoreStockFlowTest {

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
    @DisplayName("★ 单店商家：一条门店库存行都没有 → 走主体总量，行为与改造前逐字相同")
    void singleStoreBehaviourUnchanged() throws Exception {
        String biz = merchant("12600190001", "单店库存店");
        String goodsNo = listedGoods(biz, 3);
        String skuNo = firstSku(goodsNo);

        // 买 2 件成功，剩 1 件
        assertThat(buy("13000190001", goodsNo, skuNo, 2, "ss-1")).isEqualTo(0);
        // 再买 2 件 → 库存不足
        assertThat(buy("13000190002", goodsNo, skuNo, 2, "ss-2")).isNotEqualTo(0);
        // 买 1 件仍然可以
        assertThat(buy("13000190003", goodsNo, skuNo, 1, "ss-3")).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 启用分店库存后，A 店卖光不影响 B 店的数")
    void storesHaveIndependentStock() throws Exception {
        String biz = merchant("12600190010", "双店库存·总店");
        String goodsNo = listedGoods(biz, 100);
        String skuNo = firstSku(goodsNo);
        String storeA = defaultStoreNo(biz);
        // 多门店是 PRO 才有的能力 —— 这家商家买了包，所以它开得出第二家店
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "双店库存·分店");

        // 两家店各设 1 件。**一旦设了，这个 SKU 就整体转为按店管理**
        setStoreStock(biz, storeA, goodsNo, skuNo, 1);
        setStoreStock(biz, storeB, goodsNo, skuNo, 1);

        // 默认店（A）买 1 件成功，再买就没了
        assertThat(buy("13000190011", goodsNo, skuNo, 1, "ss-a1")).isEqualTo(0);
        assertThat(buy("13000190012", goodsNo, skuNo, 1, "ss-a2")).isNotEqualTo(0);

        /*
         * B 店的 1 件还在 —— 主体总量是 100，若还按总量扣，上一步不会失败。
         * 上一步失败本身就证明了「扣的是 A 店的数」。
         */
        assertThat(storeStock(biz, storeB, skuNo)).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 没设过库存的门店卖不出去 —— 视为 0，不是回退到主体总量")
    void storeWithoutStockRowSellsNothing() throws Exception {
        String biz = merchant("12600190020", "只给一家店设库存");
        String goodsNo = listedGoods(biz, 100);
        String skuNo = firstSku(goodsNo);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        createStore(biz, "没设库存的分店");

        // 只给默认店设 2 件；分店一条行都没有
        setStoreStock(biz, storeA, goodsNo, skuNo, 2);

        // 默认店照常卖
        assertThat(buy("13000190021", goodsNo, skuNo, 2, "ss-b1")).isEqualTo(0);
        /*
         * 再买就没了 —— 关键在这里：主体总量还有 100，
         * 若实现写成「这家店没有行就回退总量」，这一步会成功，
         * 而那意味着**没设库存的店等于无限供应**。
         */
        assertThat(buy("13000190022", goodsNo, skuNo, 1, "ss-b2")).isNotEqualTo(0);
    }

    @Test
    @DisplayName("★★ 已按店管理的 SKU，用主体级改库存**不能是空操作** —— 写要落到读的地方")
    void mainStockWriteLandsWhereTheReadLooks() throws Exception {
        String biz = merchant("12600190040", "写读要同一个数");
        String goodsNo = listedGoods(biz, 100);
        String skuNo = firstSku(goodsNo);
        String storeA = defaultStoreNo(biz);

        // 设一次分店库存 → 这个 SKU 整体转为按店管理，读取口径也跟着换
        setStoreStock(biz, storeA, goodsNo, skuNo, 33);
        assertThat(merchantSeesStock(biz, goodsNo, skuNo)).isEqualTo(33);

        /*
         * 关键一步：走**主体级**那条端点。
         *
         * 修之前它写的是主体总量，而页面读的是分店那份 ——
         * `code=0`、页面数字纹丝不动、没有任何提示。
         * 商家会以为自己改过了，实际货架上的数还是旧的。
         */
        setMainStock(biz, goodsNo, skuNo, 99);
        assertThat(merchantSeesStock(biz, goodsNo, skuNo))
                .as("主体级改库存返回成功却什么都没变 —— 「不报错、不生效」比报错更贵")
                .isEqualTo(99);

        // 落点必须是当前门店那一行，不是新造一份主体总量
        assertThat(storeStock(biz, storeA, skuNo)).isEqualTo(99);
    }

    /** 主体级改库存（`/stock`）—— b-app 单店模式走的就是它 */
    private void setMainStock(String token, String goodsNo, String skuNo, int stock)
            throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/stock").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"stock\":" + stock + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 商家在商品详情里看到的那个数（= 当前门店口径） */
    private int merchantSeesStock(String token, String goodsNo, String skuNo) throws Exception {
        String body = mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        for (var sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        return -1;
    }

    @Test
    @DisplayName("★ 商家看到的库存 = 当前门店的数，不是主体总量")
    void merchantSeesCurrentStoreStock() throws Exception {
        String biz = merchant("12600190030", "看得见分店库存");
        String goodsNo = listedGoods(biz, 100);
        String skuNo = firstSku(goodsNo);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "看得见·分店");

        setStoreStock(biz, storeA, goodsNo, skuNo, 7);
        setStoreStock(biz, storeB, goodsNo, skuNo, 3);

        /*
         * 主体总量是 100。若商品页仍显示 100，商家会以为还有货 ——
         * 而下单时按门店扣，第 8 单就会失败。
         * **「商家看到的数」与「下单扣的数」必须同一个口径**，
         * 两处不一致的症状是「页面显示有货、下单说库存不足」，最难查的那类。
         */
        assertThat(shownStock(biz, storeA, goodsNo, skuNo)).isEqualTo(7);
        assertThat(shownStock(biz, storeB, goodsNo, skuNo)).isEqualTo(3);
    }

    @Test
    @DisplayName("没设过库存的门店显示 0 —— 与下单侧同一口径，不回退主体总量")
    void storeWithoutRowShowsZero() throws Exception {
        String biz = merchant("12600190040", "只设一家店");
        String goodsNo = listedGoods(biz, 100);
        String skuNo = firstSku(goodsNo);
        String storeA = defaultStoreNo(biz);
        TestPlan.grantPro(mvc(), json, planMapper, biz);
        String storeB = createStore(biz, "没设的那家");

        setStoreStock(biz, storeA, goodsNo, skuNo, 5);

        assertThat(shownStock(biz, storeA, goodsNo, skuNo)).isEqualTo(5);
        // 不是 100 —— 显示总量会让商家以为这家店有货，而下单必然失败
        assertThat(shownStock(biz, storeB, goodsNo, skuNo)).isZero();
    }

    // ---------------------------------------------------------------- 装配

    /** B 端商品详情里这个 SKU 显示的可售量 */
    private int shownStock(String token, String storeNo, String goodsNo, String skuNo) throws Exception {
        String body = mvc().perform(get("/biz/goods/" + goodsNo)
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        for (var sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        return -1;
    }

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper;

    private int storeStock(String bizToken, String storeNo, String skuNo) {
        var row = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdStoreStock>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreStock::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreStock::getSkuNo, skuNo)));
        return row == null ? -1 : row.getStock();
    }

    private void setStoreStock(String token, String storeNo, String goodsNo, String skuNo, int stock)
            throws Exception {
        mvc().perform(post("/biz/goods/" + goodsNo + "/store-stock")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Store-No", storeNo)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"" + skuNo + "\",\"stock\":" + stock + "}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** @return 下单响应的 code，0 = 成功 */
    private int buy(String phone, String goodsNo, String skuNo, int qty, String idem) throws Exception {
        String buyer = login(phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private String listedGoods(String token, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"门店库存测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":" + stock + "}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String goodsNo = json.readTree(body).get("data").get("goodsNo").asString();
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + opsLogin())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));
        return goodsNo;
    }

    private String firstSku(String goodsNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("skus").get(0).get("skuNo").asString();
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
                        .content("{\"name\":\"" + name + "\",\"address\":\"某某路 3 号\"}"))
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
