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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 看板下钻：商家经营排行（TDD-运营端看板下钻，P-16.1.2 / P-16.1.3）。
 *
 * <p>守的是「排行的数与订单明细同源」——方案 B（另存排行榜表）的失败方式是
 * 「榜上说 12 单、点进去只有 9 单」，而两边都不报错。所以这里的断言全部
 * <b>拿真实下单金额去对</b>，不是「接口返回 200 且有几行」。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsDashboardRankingFlowTest {

    /** 支付回调桩的签名，与 M7SettleFlowTest 同一个 */
    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 商家排行：按 GMV 降序，数字与真实下单对得上")
    void rankingMatchesRealOrders() throws Exception {
        // 两家店，一家卖 3 单 ×￥50，一家卖 1 单 ×￥20 —— 顺序与金额都可预期
        String big = merchant("12600390001", "排行·大店");
        String bigNo = merchantNoOf(big);
        String bigGoods = listedGoods(big, 50_00, 100);
        String bigSku = firstSku(bigGoods);

        String small = merchant("12600390010", "排行·小店");
        String smallNo = merchantNoOf(small);
        String smallGoods = listedGoods(small, 20_00, 100);
        String smallSku = firstSku(smallGoods);

        assertThat(buy("13000390001", bigGoods, bigSku, 1, "rk-1")).isEqualTo(0);
        assertThat(buy("13000390002", bigGoods, bigSku, 1, "rk-2")).isEqualTo(0);
        assertThat(buy("13000390003", bigGoods, bigSku, 1, "rk-3")).isEqualTo(0);
        assertThat(buy("13000390004", smallGoods, smallSku, 1, "rk-4")).isEqualTo(0);

        String body = mvc().perform(get("/ops/dashboard/merchants")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rows = json.readTree(body).get("data");

        JsonNode bigRow = findByMerchant(rows, bigNo);
        JsonNode smallRow = findByMerchant(rows, smallNo);

        // 名字必须下发 —— 只给编号的话运营还得再查一次「这家是谁」
        assertThat(bigRow.get("merchantName").asString()).isEqualTo("排行·大店");

        /*
         * 数字与真实下单对得上。**这几条是本文件的重点**：
         * 只断言「有两行」的话，一个返回假数据的实现同样能通过。
         */
        assertThat(bigRow.get("orderCount").asLong()).isEqualTo(3);
        assertThat(bigRow.get("gmv").asLong()).isEqualTo(150_00);
        assertThat(bigRow.get("avgOrderValue").asLong()).isEqualTo(50_00);
        assertThat(smallRow.get("orderCount").asLong()).isEqualTo(1);
        assertThat(smallRow.get("gmv").asLong()).isEqualTo(20_00);

        // 降序：大店必须排在小店前面
        int bigIdx = indexOf(rows, bigNo);
        int smallIdx = indexOf(rows, smallNo);
        assertThat(bigIdx).isLessThan(smallIdx);

        // 没有售后 → 率是 0，不是除零也不是 null
        assertThat(bigRow.get("afterSaleRate").asDouble()).isZero();
    }

    @Test
    @DisplayName("★★★ 商家后台的 GMV 与平台排行上的**逐分钱相等** —— 取消的单两边都不算")
    void merchantDashboardAndPlatformRankingAgree() throws Exception {
        String biz = merchant("12600390030", "口径·对账店");
        String merchantNo = merchantNoOf(biz);
        String goods = listedGoods(biz, 50_00, 100);
        String sku = firstSku(goods);

        assertThat(buy("13000390031", goods, sku, 1, "cal-1")).isZero();
        assertThat(buy("13000390032", goods, sku, 1, "cal-2")).isZero();

        /*
         * ★ 判别器：再下一单但**不付款，然后取消**。
         *
         * 这一单从头到尾没有一分钱进来。而商家侧此前的过滤条件是
         * `status != WAIT_PAY` —— 取消掉之后它就不再是 WAIT_PAY 了，于是**被算进 GMV**；
         * 平台侧用的是显式集合，不含取消。两边都不报错，商家后台比平台排行多 50 元，
         * 而商家的第一反应是平台少算了他的钱。
         *
         * （顺带说明为什么必须是「未付款的取消」：状态机里 WAIT_FULFILL 根本到不了
         * CANCELLED —— 付过款的单只能走退款。所以被误算的那些单，全都是从没收到过钱的。）
         */
        String unpaidBuyer = login("13000390033");
        String unpaid = placeWithoutPaying(unpaidBuyer, goods, sku, "cal-3");
        mvc().perform(post("/mp/order/" + unpaid + "/cancel")
                        .header("Authorization", "Bearer " + unpaidBuyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"不想要了\"}"))
                .andExpect(jsonPath("$.code").value(0));

        long merchantGmv = merchantMonthGmv(biz);
        JsonNode row = findByMerchant(json.readTree(mvc().perform(get("/ops/dashboard/merchants")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andReturn().getResponse().getContentAsString()).get("data"), merchantNo);

        assertThat(merchantGmv)
                .as("被取消的那一单不该出现在商家的 GMV 里 —— 钱从来没进来过")
                .isEqualTo(100_00);
        assertThat(row.get("gmv").asLong())
                .as("平台排行与商家后台必须是同一个数，差一分都要能解释")
                .isEqualTo(merchantGmv);
    }

    @Test
    @DisplayName("★ 售后率跟着真实售后单动 —— 这一列是用来挑「卖得多也赔得多」的店的")
    void afterSaleRateReflectsRealTickets() throws Exception {
        String biz = merchant("12600390020", "排行·售后店");
        String merchantNo = merchantNoOf(biz);
        // ¥30 低于极速退阈值（¥50）→ 申请即自动退款，子单转 REFUNDED。
        // **这正是要覆盖的形状**：退掉的单会离开成交态，若排行只按成交态取商家集合，
        // 这家店会从榜上凭空消失 —— 而它是「卖得多也赔得多」那一类的极端情形
        String goodsNo = listedGoods(biz, 30_00, 100);
        String skuNo = firstSku(goodsNo);

        String buyer = login("13000390021");
        String subOrderNo = buyAndGetSubOrder(buyer, goodsNo, skuNo, "rk-as-1");
        assertThat(subOrderNo).isNotNull();

        // 处置前：卖了 1 单、0 售后
        JsonNode before = findByMerchant(ranking(), merchantNo);
        assertThat(before.get("orderCount").asLong()).isEqualTo(1);
        assertThat(before.get("afterSaleCount").asLong()).isZero();

        // 真的申请一次售后（不是造数据）
        mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"不新鲜\"}"))
                .andExpect(jsonPath("$.code").value(0));

        /*
         * ★ 这家店必须**还在榜上**。
         *
         * 极速退把子单推到 REFUNDED，它离开了成交态 —— 若 merchantTotals 只扫成交态，
         * 这里会拿到空列表（第一版就是这样，实测抓到）。而「单子全退光」的商家
         * 正是这张表最该显示的一家。
         */
        JsonNode after = findByMerchant(ranking(), merchantNo);
        assertThat(after.get("afterSaleCount").asLong()).isEqualTo(1);
        // 退掉之后实收归零，但「卖过一单」这件事仍要看得见
        assertThat(after.get("gmv").asLong()).isZero();
        // 分母是总成交单数（在售 + 已退）：1 单 1 售后 = 100%，前端据此标红
        assertThat(after.get("afterSaleRate").asDouble()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("limit 与 days 被夹在合法区间，不会因为传 0 或超大值把接口打空/打爆")
    void paramsAreClamped() throws Exception {
        String ops = opsLogin();
        // limit=0 → 夹到 1（不是返回空列表：那会被读成「没有商家在做生意」）
        String one = mvc().perform(get("/ops/dashboard/merchants").param("limit", "0")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(one).get("data").size()).isLessThanOrEqualTo(1);

        // 超大值不报错
        mvc().perform(get("/ops/dashboard/merchants").param("days", "9999").param("limit", "9999")
                        .header("Authorization", "Bearer " + ops))
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------------------------------------------------------------- 装配

    /** 商家自己看板上的本月 GMV */
    private long merchantMonthGmv(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/dashboard/stats")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("monthGmvMinor").asLong();
    }

    /**
     * 下单**但不付款**，返回买家自己那张子单号。
     *
     * <p>与 {@link #buyAs} 的差别只有一步：不调支付回调。
     * 这一步是「取消单」的唯一造法 —— 付过款的单在状态机里到不了 CANCELLED。
     */
    private String placeWithoutPaying(String buyer, String goodsNo, String skuNo, String idem)
            throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":null}"))
                .andExpect(jsonPath("$.code").value(0));
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    private JsonNode ranking() throws Exception {
        String body = mvc().perform(get("/ops/dashboard/merchants")
                        .header("Authorization", "Bearer " + opsLogin()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode findByMerchant(JsonNode rows, String merchantNo) {
        for (JsonNode r : rows) {
            if (merchantNo.equals(r.get("merchantNo").asString())) {
                return r;
            }
        }
        throw new AssertionError("排行里没有 " + merchantNo + "：" + rows);
    }

    private int indexOf(JsonNode rows, String merchantNo) {
        for (int i = 0; i < rows.size(); i++) {
            if (merchantNo.equals(rows.get(i).get("merchantNo").asString())) {
                return i;
            }
        }
        throw new AssertionError("排行里没有 " + merchantNo);
    }

    /** @return 下单响应的 code，0 = 成功 */
    private int buy(String phone, String goodsNo, String skuNo, int qty, String idem) throws Exception {
        return buyAs(login(phone), goodsNo, skuNo, qty, idem);
    }

    /**
     * 下单后取自己最新那张子订单号。
     *
     * <p>走订单列表而不是解下单响应：下单返回的是**支付单**（一次结算可能拆出多张子单），
     * 而售后是挂在子订单上的 —— 这一层此前我写错过一次，测试当场 NPE。
     */
    private String buyAndGetSubOrder(String buyer, String goodsNo, String skuNo, String idem)
            throws Exception {
        assertThat(buyAs(buyer, goodsNo, skuNo, 1, idem)).isEqualTo(0);
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + buyer))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    /**
     * 下单**并支付**。
     *
     * <p>必须付掉：成交口径只算已支付的子单（{@code WAIT_FULFILL/FULFILLING/COMPLETED}），
     * 停在 WAIT_PAY 的单不进 GMV —— 这是对的，没付钱的单不是成交。
     * 我第一版漏了这一步，排行返回空列表。
     *
     * @return 下单响应的 code，0 = 成功
     */
    private int buyAs(String buyer, String goodsNo, String skuNo, int qty, String idem)
            throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + buyer)
                        .header("Idempotency-Key", idem)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        int code = root.get("code").asInt();
        if (code != 0) {
            return code;
        }
        String payOrderNo = root.get("data").get("payOrderNo").asString();
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idem
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
        return 0;
    }

    private String listedGoods(String token, int priceMinor, int stock) throws Exception {
        String body = mvc().perform(post("/biz/goods/save").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"排行测试品\",\"type\":\"NORMAL\","
                                + "\"skus\":[{\"optionValues\":[],\"price\":" + priceMinor
                                + ",\"stock\":" + stock + "}]}"))
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

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
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
