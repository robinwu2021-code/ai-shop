package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.platform.PlatformConfigService;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.service.impl.PurchaseLimitGuard;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 每人限购（待办设计 P1）。
 *
 * <p><b>这条规则此前一处都没拦</b>：商品表有 {@code limit_per_user}、B 端能设、C 端详情页显示
 * 「每人限购 N 件」，而下单链路从未读过它 —— 只有 c-app 的 mock 在拦。
 * 本机点一遍是对的，线上买 50 件照样成交。这里每一条都走真的加购 / 预览 / 下单。
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchaseLimitFlowTest {

    private static final String GOODS = "G0001";
    private static final String SKU = "SK0001";
    private static final int LIMIT_EXCEEDED = 20007;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;
    @Autowired
    private PlatformConfigService platformConfig;

    /** G0001 是共享种子：动之前记下原值，用完放回（见 DeliveryRadiusFlowTest 的教训） */
    private Integer limitBackup;

    private long cartIdBefore;

    @BeforeEach
    void limitTwo() {
        var last = DataScopeContext.executeWithoutScope(() -> cartMapper.selectOne(
                Wrappers.<ai.neargo.shop.trade.entity.TrdCartItem>lambdaQuery()
                        .orderByDesc(ai.neargo.shop.trade.entity.TrdCartItem::getId).last("limit 1")));
        cartIdBefore = last == null ? 0L : last.getId();
        limitBackup = goods().getLimitPerUser();
        setLimit(2);
    }

    @Autowired
    private ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper cartMapper;

    /**
     * 号段 1300029xxxx 本类独占。**购物车也要清**：2026-09-21 第一版用了 1300017xxxx，
     * 与 CampaignDiscountFlowTest 撞号，本类留在车里的 1 件让那边「买 2 件」变成 3 件、
     * 凑出一份赠品 —— 单独跑都绿、全量才红。
     */
    @AfterEach
    void restore() {
        // 只删本用例期间加进去的行（id 比开始时大的），别人的车一行不碰
        DataScopeContext.executeWithoutScope(() -> cartMapper.delete(
                Wrappers.<ai.neargo.shop.trade.entity.TrdCartItem>lambdaQuery()
                        .gt(ai.neargo.shop.trade.entity.TrdCartItem::getId, cartIdBefore)));
        setLimit(limitBackup);
        platformConfig.saveFeatureFlag(PurchaseLimitGuard.FLAG, true, 0, "TEST");
    }

    @Test
    @DisplayName("★★★ 限购 2：买满 2 件后再买被拒 —— 加购与直接下单两条路都拦")
    void blocksBeyondLimit() throws Exception {
        String token = login("13000290001");
        assertThat(addToCart(token, 2).get("code").asInt()).isZero();
        assertThat(createFromCart(token, "pl-1").get("code").asInt()).isZero();

        JsonNode cart = addToCart(token, 1);
        assertThat(cart.get("code").asInt()).as("购物车那条路也要拦 —— 「立即购买」是先加购").isEqualTo(LIMIT_EXCEEDED);

        JsonNode direct = createDirect(token, 1, "pl-2");
        assertThat(direct.get("code").asInt()).as("绕过购物车直接下单也得拦住").isEqualTo(LIMIT_EXCEEDED);
        assertThat(direct.get("msg").asString()).as("要说还能买几件").contains("0");
    }

    @Test
    @DisplayName("★★★ 已取消的单不占名额 —— 退了还占着，用户会觉得被坑")
    void cancelledOrderFreesQuota() throws Exception {
        String token = login("13000290002");
        addToCart(token, 2);
        String orderNo = createFromCart(token, "pl-3").get("data").get("orderNo").asString();
        cancel(token, orderNo);

        assertThat(createDirect(token, 2, "pl-4").get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★★ 预览的 maxQty 随已买量变，并说清是限购挡住的")
    void previewTellsRemaining() throws Exception {
        String token = login("13000290003");
        createDirect(token, 1, "pl-5");
        addToCart(token, 1);

        JsonNode item = preview(token).get("subOrders").get(0).get("items").get(0);
        assertThat(item.get("maxQty").asInt()).as("限购 2、已买 1 → 这一行最多 1 件").isEqualTo(1);
        assertThat(item.get("limitReason").asString()).isEqualTo("PER_USER");
        assertThat(item.get("limitPerUser").asInt()).isEqualTo(2);
        assertThat(item.get("boughtQty").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 开关关掉 = 回到只显示不拦（默认关的那一半也要测）")
    void switchOffLetsThrough() throws Exception {
        platformConfig.saveFeatureFlag(PurchaseLimitGuard.FLAG, false, 0, "TEST");
        String token = login("13000290004");

        assertThat(createDirect(token, 3, "pl-6").get("code").asInt()).isZero();
        assertThat(addToCart(token, 3).get("code").asInt()).isZero();
        JsonNode item = preview(token).get("subOrders").get(0).get("items").get(0);
        assertThat(item.get("limitReason").asString()).as("关着时步进器只按库存").isEqualTo("STOCK");
    }

    @Test
    @DisplayName("没设限购的货不受影响")
    void unlimitedGoodsUntouched() throws Exception {
        setLimit(0);
        String token = login("13000290005");
        assertThat(createDirect(token, 5, "pl-7").get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("运营端开关列表里看得到新开关 —— 库里早存过一份时，默认登记表不会自己出现")
    void flagListedForOps() {
        platformConfig.saveFeatureFlag("category.gate.enforce", false, 0, "TEST");
        assertThat(platformConfig.featureFlags()).anyMatch(f -> PurchaseLimitGuard.FLAG.equals(f.key()));
    }

    // ------------------------------------------------------------------ helpers

    private PrdGoods goods() {
        return DataScopeContext.executeWithoutScope(() -> goodsMapper.selectOne(
                Wrappers.<PrdGoods>lambdaQuery().eq(PrdGoods::getGoodsNo, GOODS).last("limit 1")));
    }

    private void setLimit(Integer limit) {
        DataScopeContext.executeWithoutScope(() -> goodsMapper.update(null,
                Wrappers.<PrdGoods>lambdaUpdate().set(PrdGoods::getLimitPerUser, limit)
                        .eq(PrdGoods::getGoodsNo, GOODS)));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private JsonNode call(String path, String token, String body, String idem) throws Exception {
        var req = post(path).header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        if (idem != null) {
            req = req.header("Idempotency-Key", idem + System.nanoTime());
        }
        return json.readTree(mvc().perform(req).andReturn().getResponse().getContentAsString());
    }

    private JsonNode addToCart(String token, int qty) throws Exception {
        return call("/mp/cart/add", token,
                "{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":" + qty + "}", null);
    }

    private JsonNode createFromCart(String token, String idem) throws Exception {
        return call("/mp/order", token, "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}", idem);
    }

    private JsonNode createDirect(String token, int qty, String idem) throws Exception {
        return call("/mp/order", token, "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                + "\"items\":[{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":" + qty + "}]}", idem);
    }

    private JsonNode preview(String token) throws Exception {
        JsonNode r = call("/mp/order/preview", token,
                "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}", null);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        return r.get("data");
    }

    private void cancel(String token, String orderNo) throws Exception {
        JsonNode r = call("/mp/order/" + orderNo + "/cancel", token, "{\"reason\":\"test\"}", null);
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
