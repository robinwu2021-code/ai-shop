package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
 * M6a 门店主页 + 归因 —— **用例先行**。
 *
 * <p>这是 ADR-004 的**一期主获客路径**：店主把店铺码发给老客，老客扫码进店、
 * 点「我买过的」一键再来一单。三步之内完成复购，粮油副食不是逛出来的。
 *
 * <p>归因是本模块最容易出错的部分：它决定费率档（R16），商家会为
 * 「这个客户算不算我带来的」争执。因此**优先级判定**与**判定留痕**各有独立用例。
 */
@SpringBootTest
@ActiveProfiles("test")
class M6aStoreAttributionFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.SkuMapper skuMapper;

    /**
     * 把本类要买的 SKU 库存补满。
     *
     * <p><b>不是为了「多买几件」，是为了不依赖别人剩下多少。</b> 种子给 SK0003 的库存是 80，
     * 全套测试跑下来，前面的交易用例会把它买到见底 —— 于是本类在**单跑时全绿、
     * 全量时报 20001（库存不足）**，而失败信息指向归因逻辑，与真正的原因毫无关系。
     *
     * <p>这类假红比真 bug 更贵：它让人怀疑一个其实没坏的模块，
     * 而下一次真的坏了，也会被当成"又是那个老毛病"。
     */
    @org.junit.jupiter.api.BeforeEach
    void refillStock() {
        for (String skuNo : java.util.List.of("SK0001", "SK0003", "SK0004", "SK0005")) {
            var sku = skuMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdSku>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdSku::getSkuNo, skuNo).last("limit 1"));
            if (sku != null && sku.getStock() < 50) {
                sku.setStock(500);
                skuMapper.updateById(sku);
            }
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 门店主页

    @Test
    @DisplayName("门店主页：游客可访问，不需要先选社区（扫码/分享直达）")
    void storeHomeIsPublic() throws Exception {
        mvc().perform(get("/mp/store/M0001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.merchant.merchantNo").value("M0001"))
                .andExpect(jsonPath("$.data.merchant.name").value("老张粮油店"))
                // 字段名按契约：端上读的是 goods 与 store，不是 hotGoods / fulfillmentDesc
                .andExpect(jsonPath("$.data.goods").isArray())
                /*
                 * **门面文案要是店主填的那份**。此前公告写死成空串、履约文案写死成
                 * 一句「每晚 7 点前到货」—— 店主在 B 端认真填的公告与营业时间
                 * 一个字都到不了 C 端；而契约要的字段叫 `store`，页面读
                 * `store.announcement` 直接抛错，门店主页整页空白。
                 */
                .andExpect(jsonPath("$.data.store.announcement").exists())
                .andExpect(jsonPath("$.data.store.openHours").exists())
                .andExpect(jsonPath("$.data.store.address").exists());
    }

    @Test
    @DisplayName("扫码进店：短码解析到门店，码不存在给 404")
    void enterByStoreCode() throws Exception {
        String code = storeCodeOf("M0001");

        mvc().perform(get("/mp/store/by-code").param("storeCode", code))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchant.merchantNo").value("M0001"));

        mvc().perform(get("/mp/store/by-code").param("storeCode", "NOPE"))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("店铺码可取，且与 merchantNo 不同（印在纸上的码要能单独作废重发）")
    void merchantCanGetOwnQrcode() throws Exception {
        String biz = loginAsOwnerOf("M0001", "13100131001");
        String body = mvc().perform(get("/biz/store/qrcode").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("storeCode").asString()).isNotBlank().isNotEqualTo("M0001");
        assertThat(data.get("url").asString()).contains(data.get("storeCode").asString());
    }

    // ---------------------------------------------------------------- 归因优先级

    @Test
    @DisplayName("★ 进店归因：写入 STORE_CODE，下单时子单 trafficSource = MERCHANT_OWNED")
    void enterStoreThenOrderIsMerchantOwned() throws Exception {
        String token = login("13100131010");

        mvc().perform(post("/mp/store/M0001/enter").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCodeOf("M0001") + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("STORE_CODE"))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"))
                .andExpect(jsonPath("$.data.trafficSource").value("MERCHANT_OWNED"))
                .andExpect(jsonPath("$.data.expireAt").isNumber());

        // 下单时固化到子单 —— 这是 R4 占位实现被真实归因取代的验收点
        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, "m6a-owned");
        mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.records[0].trafficSource").value("MERCHANT_OWNED"));
    }

    @Test
    @DisplayName("★ 只有邀请人时归因给 INVITER，费率算平台客流")
    void inviterOnlyIsPlatformTraffic() throws Exception {
        String token = login("13100131011");

        mvc().perform(post("/mp/attribution/report").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviterNo\":\"U-INVITER-1\",\"channel\":\"wechat\"}"))
                .andExpect(jsonPath("$.data.source").value("INVITER"))
                // 邀请人带来的客户不算商家自带客流 —— 费率档不同（R16）
                .andExpect(jsonPath("$.data.trafficSource").value("PLATFORM"));
    }

    @Test
    @DisplayName("★ 优先级：店铺码 > 邀请人 > 渠道（三者同时出现时店铺码胜出）")
    void storeCodeBeatsInviterAndChannel() throws Exception {
        String token = login("13100131012");

        mvc().perform(post("/mp/attribution/report").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"M0001\",\"inviterNo\":\"U-X\",\"channel\":\"douyin\"}"))
                .andExpect(jsonPath("$.data.source").value("STORE_CODE"))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"));
    }

    @Test
    @DisplayName("★ 已归属 A 店的用户扫 B 店码：**覆盖**，且留痕可回放（P-9.1.5）")
    void laterStoreCodeOverridesEarlier() throws Exception {
        String token = login("13100131013");
        enterStore(token, "M0001");
        enterStore(token, "M0002");

        mvc().perform(get("/mp/store/mine").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                // 最后扫的那家成为常去店：用户用脚投票，不该由先到先得决定
                .andExpect(jsonPath("$.data[0].merchantNo").value("M0002"));

        addToCart(token, "G0003", "SK0004", 1);
        createOrder(token, "m6a-override");
        mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.records[0].trafficSource").value("MERCHANT_OWNED"));
    }

    @Test
    @DisplayName("★ 弱来源不覆盖强来源：已有店铺码归属时，再报邀请人不改归属")
    void weakerSourceDoesNotOverride() throws Exception {
        String token = login("13100131014");
        enterStore(token, "M0001");

        mvc().perform(post("/mp/attribution/report").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inviterNo\":\"U-LATE\"}"))
                .andExpect(jsonPath("$.data.source").value("STORE_CODE"))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"));
    }

    @Test
    @DisplayName("未登录不能上报归因（归因必须挂在具体的人身上）")
    void attributionRequiresLogin() throws Exception {
        mvc().perform(post("/mp/attribution/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"M0001\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- 常买与复购

    @Test
    @DisplayName("常买清单：按购买次数排序，带当前价与上次成交价")
    void frequentItemsRankedByBuyCount() throws Exception {
        String token = login("13100131020");
        buyAndPay(token, "G0002", "SK0003", "m6a-freq-1");
        buyAndPay(token, "G0002", "SK0003", "m6a-freq-2");
        buyAndPay(token, "G0001", "SK0001", "m6a-freq-3");

        String body = mvc().perform(get("/mp/store/M0001/frequent")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode items = json.readTree(body).get("data");
        assertThat(items.size()).isGreaterThanOrEqualTo(2);
        // 买得最多的排最前 —— 第一屏就是「我买过的」，顺序错了这个页面就没用了
        assertThat(items.get(0).get("skuNo").asString()).isEqualTo("SK0003");
        // 字段名按契约：端上读的是 times（页面上那句「买过 N 次」）
        assertThat(items.get(0).get("times").asInt()).isEqualTo(2);
        assertThat(items.get(0).get("lastAt").asLong()).isPositive();
        assertThat(items.get(0).get("price").asLong()).isPositive();
        assertThat(items.get(0).get("lastPrice").asLong()).isPositive();
    }

    @Test
    @DisplayName("一键再来一单：整单回填购物车")
    void rebuyAddsAllToCart() throws Exception {
        String token = login("13100131021");
        buyAndPay(token, "G0002", "SK0003", "m6a-rebuy-1");

        mvc().perform(post("/mp/store/M0001/rebuy").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.addedCount").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc().perform(get("/mp/cart").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("★ 一键再来一单：下架商品被跳过并**显式告知**，不能悄悄少加")
    void rebuySkipsInvalidAndReportsIt() throws Exception {
        String token = login("13100131022");
        buyAndPay(token, "G0004", "SK0005", "m6a-rebuy-2");   // M0002 的货

        offShelf("G0004");
        try {
            String body = mvc().perform(post("/mp/store/M0002/rebuy")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode data = json.readTree(body).get("data");
            // 悄悄少加是最糟的处理：用户以为买到了，到货才发现少东西
            assertThat(data.get("skipped")).isNotEmpty();
            assertThat(data.get("skipped").get(0).get("reason").asString()).isNotBlank();
        } finally {
            onShelf("G0004");
        }
    }

    @Test
    @DisplayName("收藏本店：出现在「我的常去店」，再点取消")
    void favoriteStore() throws Exception {
        String token = login("13100131023");

        mvc().perform(post("/mp/store/M0001/favorite").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));

        mvc().perform(post("/mp/store/M0001/favorite").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("常买清单需要登录（游客没有「我买过的」）")
    void frequentRequiresLogin() throws Exception {
        mvc().perform(get("/mp/store/M0001/frequent"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private void enterStore(String token, String merchantNo) throws Exception {
        mvc().perform(post("/mp/store/" + merchantNo + "/enter").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeCode\":\"" + storeCodeOf(merchantNo) + "\"}"))
                .andExpect(status().isOk());
    }

    private String storeCodeOf(String merchantNo) {
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        return m.getStoreCode();
    }

    private void offShelf(String goodsNo) {
        // 直接改库：本用例关心的是「复购遇到下架怎么办」，不是商家怎么下架的
        setOnSale(goodsNo, false);
    }

    private void onShelf(String goodsNo) {
        setOnSale(goodsNo, true);
    }

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;

    private void setOnSale(String goodsNo, boolean onSale) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var g = goodsMapper.selectOne(Wrappers
                    .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
            g.setOnSale(onSale);
            return goodsMapper.updateById(g);
        });
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
    }

    private void createOrder(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void buyAndPay(String token, String goodsNo, String skuNo, String idemKey) throws Exception {
        addToCart(token, goodsNo, skuNo, 1);
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(json.readTree(body).get("data").get("userNo").asString());
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), json.readTree(body).get("data").get("userNo").asString());
        merchantMapper.updateById(m);
        return login(phone);
    }

    private String login(String phone) throws Exception {
        mvc().perform(post("/mp/user/otp/send").contentType(MediaType.APPLICATION_JSON)
                .content("{\"phone\":\"" + phone + "\"}"));
        String code = otpStore.peek(phone).orElseThrow();
        String body = mvc().perform(post("/mp/user/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grantType\":\"PHONE_OTP\",\"principal\":\"" + phone
                                + "\",\"credential\":\"" + code + "\",\"agreed\":true}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("token").asString();
    }
    /** 授予 B 端身份：写一条 owner 成员行（幂等）。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;

}
