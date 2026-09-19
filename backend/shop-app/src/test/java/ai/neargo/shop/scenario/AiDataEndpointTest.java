package ai.neargo.shop.scenario;

import ai.neargo.shop.portal.internal.AiDataEndpoint;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI 取数接口（soukmind 标准业务契约 v1）· {@code docs/technical/TDD-AI取数接口.md} AC1–AC9。
 *
 * <p>经营数据的用例直接往 {@code ord_sub_order} 落行（与 {@code SelfOperatedAfterSaleFlowTest} 同法），
 * 再拿同一家店的工作台 {@code /biz/dashboard/stats} 对照 —— 这个接口唯一的信任来源就是「和工作台对得上」。
 */
@SpringBootTest
// "aidata" 放最后：独立内存库，见 application-aidata.yml（另一个上下文会在共享库上重跑 schema-test.sql）
@ActiveProfiles({"test", "aidata"})
@TestPropertySource(properties = {"shop.ai.internal-token=" + AiDataEndpointTest.TOKEN,
        "shop.ai.low-stock-threshold=10"})
class AiDataEndpointTest {

    static final String TOKEN = "ai-test-token-0123456789";
    private static final String BASE = "/internal/ai/v1";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private TradeMappers.SubOrderMapper subOrderMapper;
    @Autowired
    private TradeMappers.OrderItemMapper itemMapper;
    @Autowired
    private TradeMappers.AfterSaleMapper afterSaleMapper;
    @Autowired
    private ProductMappers.SkuMapper skuMapper;
    @Autowired
    private ProductMappers.GoodsMapper goodsMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- AC1 / AC9

    @Test
    @DisplayName("AC1 ★ 没带 / 带错服务密钥 → 401；未配置密钥时连对的也拒（fail-closed）")
    void serviceTokenGate() throws Exception {
        mvc().perform(get(BASE + "/health")).andExpect(status().isUnauthorized());
        mvc().perform(get(BASE + "/health").header("X-Internal-Token", "wrong")).andExpect(status().isUnauthorized());
        mvc().perform(post(BASE + "/metrics/query").header("X-Internal-Token", "wrong")
                        .header("X-Merchant-Id", "M1").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        AiDataEndpoint unconfigured = new AiDataEndpoint("", 10, null, null, null, null, null);
        assertThat(unconfigured.health("").getStatusCode().value()).isEqualTo(401);
        assertThat(unconfigured.health(null).getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("AC9 正确密钥打 health → code=0（soukmind 运营端「连通性测试」）")
    void health() throws Exception {
        mvc().perform(get(BASE + "/health").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.meta.currency").value("CNY"));
    }

    // ---------------------------------------------------------------- AC2 / AC8

    @Test
    @DisplayName("AC2 ★ 店主 B 端令牌 → 身份（商户号·OWNER·门店）；无效令牌 / C 端令牌 → 40100")
    void sessionVerify() throws Exception {
        String btk = merchant("12600177001", "AI取数·身份");
        Ctx c = ctx(btk);

        JsonNode d = call(post(BASE + "/session/verify"), "{\"token\":\"" + btk + "\"}").get("data");
        assertThat(d.get("merchant_id").asString()).isEqualTo(c.merchantNo);
        assertThat(d.get("role").asString()).isEqualTo("OWNER");
        assertThat(d.get("account_id").asString()).isNotBlank();
        assertThat(d.get("stores").get(0).get("store_id").asString()).isEqualTo(c.storeNo);

        assertThat(call(post(BASE + "/session/verify"), "{\"token\":\"btk_nosuch\"}").get("code").asInt())
                .isEqualTo(40100);
        // ★ 店主本人的 C 端令牌：解析器按 user_no 也认得出他是店主，挡住它的只有 realm 这一道
        String consumer = TestLogin.consumer(mvc(), json, otpStore, "12600177001");
        assertThat(call(post(BASE + "/session/verify"), "{\"token\":\"" + consumer + "\"}").get("code").asInt())
                .as("C 端令牌不是商家身份，哪怕是店主本人的").isEqualTo(40100);
    }

    @Test
    @DisplayName("AC8 stores/list 回该商户的门店号与名称")
    void storesList() throws Exception {
        Ctx c = ctx(merchant("12600177011", "AI取数·门店"));
        JsonNode items = call(tenant(post(BASE + "/stores/list"), c.merchantNo, null), "{}").get("data").get("items");
        assertThat(items.size()).isEqualTo(1);
        assertThat(items.get(0).get("store_id").asString()).isEqualTo(c.storeNo);
        assertThat(items.get(0).get("name").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- AC3 / AC4 / AC5

    @Test
    @DisplayName("AC3 ★★★ 今天的营业额 / 单量 = 工作台首页的数（同口径：成交不含取消，金额换成元）")
    void metricsEqualDashboard() throws Exception {
        String btk = merchant("12600177021", "AI取数·对账");
        Ctx c = ctx(btk);
        LocalDateTime now = LocalDateTime.now();
        subOrder(c, OrdSubOrder.COMPLETED, 12_345L, now);
        subOrder(c, OrdSubOrder.WAIT_FULFILL, 1_000L, now);
        subOrder(c, OrdSubOrder.CANCELLED, 99_900L, now);                 // 取消：不算成交
        subOrder(c, OrdSubOrder.COMPLETED, 50_000L, now.minusDays(1));    // 昨天：不在今天

        JsonNode stats = json.readTree(mvc().perform(get("/biz/dashboard/stats")
                        .header("Authorization", "Bearer " + btk))
                .andReturn().getResponse().getContentAsString()).get("data");
        long ms = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        JsonNode r = call(tenant(post(BASE + "/metrics/query"), c.merchantNo, c.storeNo),
                "{\"metrics\":[\"SUCCESS_AMOUNT\",\"order_count\",\"fail_count\",\"income\"],"
                        + "\"date_from\":" + ms + ",\"date_to\":" + (ms + 86_399_999L) + "}");
        JsonNode v = r.get("data").get("series").get(0).get("values");

        assertThat(r.get("meta").get("data_basis").asString()).isEqualTo("ORDER_AGGREGATED");
        assertThat(v.get("order_count").get("value").asLong()).isEqualTo(stats.get("todayOrders").asLong()).isEqualTo(2);
        assertThat(v.get("success_amount").get("value").decimalValue())
                .isEqualByComparingTo(java.math.BigDecimal.valueOf(stats.get("todayGmvMinor").asLong(), 2))
                .isEqualByComparingTo("133.45");
        assertThat(v.get("fail_count").get("value").asLong()).isEqualTo(1);
        assertThat(v.has("income")).as("不支持的指标忽略，不报错也不编").isFalse();
    }

    @Test
    @DisplayName("AC3 退款按退款日归属、按子单归店；按日趋势 + 环比")
    void refundsTrendAndCompare() throws Exception {
        Ctx c = ctx(merchant("12600177031", "AI取数·退款"));
        LocalDateTime now = LocalDateTime.now();
        String sub = subOrder(c, OrdSubOrder.REFUNDED, 3_000L, now.minusDays(1));
        subOrder(c, OrdSubOrder.COMPLETED, 2_000L, now);
        subOrder(c, OrdSubOrder.COMPLETED, 1_000L, now.minusDays(2));   // WOW = 同长度的上一段：两天区间的今天对前天
        refund(c, sub, 3_000L, System.currentTimeMillis());             // 昨天的单，今天退

        LocalDate today = LocalDate.now();
        long from = today.minusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        long to = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 86_399_999L;
        JsonNode series = call(tenant(post(BASE + "/metrics/query"), c.merchantNo, c.storeNo),
                "{\"metrics\":[\"refund_amount\",\"success_count\"],\"time_granularity\":\"DAY\","
                        + "\"compare\":\"WOW\",\"date_from\":" + from + ",\"date_to\":" + to + "}")
                .get("data").get("series");
        assertThat(series.size()).isEqualTo(2);
        JsonNode y = series.get(0);
        JsonNode t = series.get(1);
        assertThat(y.get("period").asString()).isEqualTo(today.minusDays(1).toString());
        assertThat(y.get("values").get("refund_amount").get("value").decimalValue()).isEqualByComparingTo("0");
        assertThat(t.get("values").get("refund_amount").get("value").decimalValue()).isEqualByComparingTo("30.00");
        // 今天 1 单成功，对照日（前天）1 单 → 变化 0%
        assertThat(t.get("values").get("success_count").get("value").asLong()).isEqualTo(1);
        assertThat(t.get("values").get("success_count").get("prev").asLong()).isEqualTo(1);
        assertThat(t.get("values").get("success_count").get("pct").asDouble()).isZero();
    }

    @Test
    @DisplayName("AC4 ★★ 门店不属于该商户 / 员工号不属于该商户 → 40300，不回任何数据")
    void foreignStoreIsForbidden() throws Exception {
        Ctx a = ctx(merchant("12600177041", "AI取数·甲店"));
        Ctx b = ctx(merchant("12600177042", "AI取数·乙店"));
        subOrder(b, OrdSubOrder.COMPLETED, 8_800L, LocalDateTime.now());
        long ms = System.currentTimeMillis();
        String body = "{\"metrics\":[\"success_amount\"],\"date_from\":" + (ms - 86_400_000L) + ",\"date_to\":" + ms + "}";

        JsonNode r = call(tenant(post(BASE + "/metrics/query"), a.merchantNo, b.storeNo), body);
        assertThat(r.get("code").asInt()).isEqualTo(40300);
        assertThat(r.get("data").isNull()).isTrue();

        JsonNode s = call(tenant(post(BASE + "/metrics/query"), a.merchantNo, null)
                .header("X-Account-Id", b.accountNo), body);
        assertThat(s.get("code").asInt()).as("乙的账号冒充甲的员工").isEqualTo(40300);

        JsonNode own = call(tenant(post(BASE + "/metrics/query"), b.merchantNo, b.storeNo)
                .header("X-Account-Id", b.accountNo), body);
        assertThat(own.get("code").asInt()).as("属主看自己的店").isZero();
    }

    @Test
    @DisplayName("AC5 区间 > 400 天 → 41300；区间内没有成交 → data_basis=NONE（不是 0）")
    void rangeLimitAndNoData() throws Exception {
        Ctx c = ctx(merchant("12600177051", "AI取数·空店"));
        long ms = System.currentTimeMillis();
        JsonNode big = call(tenant(post(BASE + "/metrics/query"), c.merchantNo, c.storeNo),
                "{\"metrics\":[\"success_amount\"],\"date_from\":" + (ms - 401L * 86_400_000L) + ",\"date_to\":" + ms + "}");
        assertThat(big.get("code").asInt()).isEqualTo(41300);

        JsonNode empty = call(tenant(post(BASE + "/metrics/query"), c.merchantNo, c.storeNo),
                "{\"metrics\":[\"success_amount\"],\"date_from\":" + (ms - 86_400_000L) + ",\"date_to\":" + ms + "}");
        assertThat(empty.get("code").asInt()).isZero();
        assertThat(empty.get("meta").get("data_basis").asString()).isEqualTo("NONE");

        JsonNode none = call(tenant(post(BASE + "/metrics/query"), c.merchantNo, c.storeNo),
                "{\"metrics\":[\"net_profit\"],\"date_from\":" + (ms - 86_400_000L) + ",\"date_to\":" + ms + "}");
        assertThat(none.get("data").get("series").size()).as("一个有效指标都没有").isZero();
        assertThat(none.get("meta").get("data_basis").asString()).isEqualTo("NONE");
    }

    // ---------------------------------------------------------------- AC6 / AC7

    @Test
    @DisplayName("AC6 区间销量排行：按商品聚合已支付未退款的明细，销售额为元，DESC 多者在前")
    void salesRanking() throws Exception {
        Ctx c = ctx(merchant("12600177061", "AI取数·排行"));
        LocalDateTime now = LocalDateTime.now();
        item(subOrder(c, OrdSubOrder.COMPLETED, 1_500L, now), "G-A", "苹果", 3, 1_500L);
        item(subOrder(c, OrdSubOrder.WAIT_FULFILL, 4_000L, now), "G-B", "香蕉", 5, 4_000L);
        item(subOrder(c, OrdSubOrder.REFUNDED, 9_000L, now), "G-A", "苹果", 9, 9_000L);   // 退掉的不算

        long ms = System.currentTimeMillis();
        JsonNode items = call(tenant(post(BASE + "/goods/sales-ranking"), c.merchantNo, c.storeNo),
                "{\"date_from\":" + (ms - 86_400_000L) + ",\"date_to\":" + ms + ",\"limit\":10}")
                .get("data").get("items");
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(0).get("spuId").asString()).isEqualTo("G-B");
        assertThat(items.get(0).get("salesQty").asLong()).isEqualTo(5);
        assertThat(items.get(0).get("salesAmount").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(items.get(1).get("salesQty").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("AC7 ★ 他人商户的 SKU → 40400（与不存在同一个回答）；自己的 SKU 回价格为元")
    void skuIsMerchantScoped() throws Exception {
        Ctx a = ctx(merchant("12600177071", "AI取数·SKU甲"));
        Ctx b = ctx(merchant("12600177072", "AI取数·SKU乙"));
        String goodsNo = "GAI" + System.nanoTime() % 1_000_000_000L;
        String skuNo = goods(a, goodsNo, "AI取数测试商品", 500L, 10);

        JsonNode own = call(tenant(get(BASE + "/goods/sku").param("sku_id", skuNo), a.merchantNo, null), null);
        assertThat(own.get("code").asInt()).isZero();
        assertThat(own.get("data").get("spuId").asString()).isEqualTo(goodsNo);
        assertThat(own.get("data").get("price").decimalValue()).isEqualByComparingTo("5.00");
        assertThat(own.get("data").get("stock").asLong()).isEqualTo(10);

        JsonNode foreign = call(tenant(get(BASE + "/goods/sku").param("sku_id", skuNo), b.merchantNo, null), null);
        assertThat(foreign.get("code").asInt()).isEqualTo(40400);
        assertThat(foreign.get("data").isNull()).isTrue();

        JsonNode list = call(tenant(get(BASE + "/goods/list"), a.merchantNo, null), null).get("data");
        assertThat(list.get("total").asLong()).isEqualTo(1);
        assertThat(list.get("items").get(0).get("id").asString()).isEqualTo(goodsNo);
        JsonNode foreignList = call(tenant(get(BASE + "/goods/list"), b.merchantNo, null), null).get("data");
        assertThat(foreignList.get("total").asLong()).isZero();
    }

    // ---------------------------------------------------------------- 工具

    private record Ctx(String merchantNo, String storeNo, String accountNo) {
    }

    private Ctx ctx(String btk) throws Exception {
        JsonNode d = json.readTree(mvc().perform(get("/biz/context").header("Authorization", "Bearer " + btk))
                .andReturn().getResponse().getContentAsString()).get("data");
        JsonNode who = call(post(BASE + "/session/verify"), "{\"token\":\"" + btk + "\"}").get("data");
        return new Ctx(d.get("merchantNo").asString(), d.get("currentStoreNo").asString(),
                who.get("account_id").asString());
    }

    private MockHttpServletRequestBuilder tenant(MockHttpServletRequestBuilder b, String merchantNo, String storeNo) {
        b.header("X-Merchant-Id", merchantNo);
        if (storeNo != null) {
            b.header("X-Store-Id", storeNo);
        }
        return b;
    }

    private JsonNode call(MockHttpServletRequestBuilder b, String body) throws Exception {
        b.header("X-Internal-Token", TOKEN);
        if (body != null) {
            b.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return json.readTree(mvc().perform(b).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    private String subOrder(Ctx c, String status, long payMinor, LocalDateTime createdAt) {
        String no = "SUBAI" + System.nanoTime() % 1_000_000_000L;
        OrdSubOrder s = new OrdSubOrder();
        s.setSubOrderNo(no);
        s.setOrderNo("ORDAI" + System.nanoTime() % 1_000_000_000L);
        s.setUserNo("UAI" + System.nanoTime() % 100_000_000L);
        s.setEntityNo(c.merchantNo);
        s.setStoreNo(c.storeNo);
        s.setStatus(status);
        s.setPayAmount(payMinor);
        s.setCreatedAt(createdAt);
        subOrderMapper.insert(s);
        return no;
    }

    private void refund(Ctx c, String subOrderNo, long minor, long refundedAt) {
        OrdAfterSale a = new OrdAfterSale();
        a.setAfterSaleNo("ASAI" + System.nanoTime() % 1_000_000_000L);
        a.setSubOrderNo(subOrderNo);
        a.setOrderNo("ORDAI-R");
        a.setUserNo("UAI-R");
        a.setEntityNo(c.merchantNo);
        a.setType(OrdAfterSale.REFUND_ONLY);
        a.setStatus(OrdAfterSale.REFUNDED);
        a.setReason(OrdAfterSale.REASON_OTHER);
        a.setRefundMinor(minor);
        a.setRefundedAt(refundedAt);
        afterSaleMapper.insert(a);
    }

    private void item(String subOrderNo, String goodsNo, String title, int qty, long amount) {
        OrdItem it = new OrdItem();
        it.setSubOrderNo(subOrderNo);
        it.setOrderNo("ORDAI-I");
        it.setGoodsNo(goodsNo);
        it.setSkuNo(goodsNo + "-S");
        it.setTitle(title);
        it.setPrice(amount / qty);
        it.setQty(qty);
        it.setAmount(amount);
        itemMapper.insert(it);
    }

    /** 直接落商品 + 一个 SKU：走 /biz/goods/save 会被经营类目等上架规则拦住，而这里测的是取数不是上架 */
    private String goods(Ctx c, String goodsNo, String title, long priceMinor, int stock) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo(goodsNo);
        g.setEntityNo(c.merchantNo);
        g.setTitle(title);
        g.setType(PrdGoods.TYPE_NORMAL);
        g.setCategoryNo("CAT210");
        g.setSales(0);
        g.setOnSale(true);
        goodsMapper.insert(g);
        PrdSku s = new PrdSku();
        s.setSkuNo(goodsNo + "S1");
        s.setGoodsNo(goodsNo);
        s.setEntityNo(c.merchantNo);
        s.setPrice(priceMinor);
        s.setStock(stock);
        s.setLockedStock(0);
        skuMapper.insert(s);
        return s.getSkuNo();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = TestLogin.consumer(mvc(), json, otpStore, phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = json.readTree(mvc().perform(post("/ops/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bd\",\"password\":\"bd123\"}"))
                .andReturn().getResponse().getContentAsString()).get("data").get("token").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(jsonPath("$.code").value(0));
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }
}
