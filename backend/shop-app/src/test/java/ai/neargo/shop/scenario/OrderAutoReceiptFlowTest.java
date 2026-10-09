package ai.neargo.shop.scenario;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestStoreCategory;
import ai.neargo.shop.trade.job.OrderAutoReceiptJob;
import ai.neargo.shop.trade.service.OrderService;
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
 * 发货超时自动确认收货（TDD-快递100商家寄件 §9 · B 批）。
 *
 * <p>在此之前，发货之后**没有任何东西推动订单前进**：单子停在 FULFILLING，
 * 结算要等 COMPLETED，于是商家的货款永远提不出来，且全程不报错。
 *
 * <p>与 {@code OrderCloseRuleFlowTest} 同一条纪律：**调 Job 的入口，不直接调 service**。
 * 直接调 {@code autoConfirmReceipt} 的话，缺调度器这件事在测试里完全不可见 ——
 * 那正是这个缺口当初能存在这么久的原因。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("发货超时自动确认收货")
class OrderAutoReceiptFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JobSupport jobs;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 把这一单的「进入 FULFILLING」那条流水拨到 N 天前。
     *
     * <p><b>拨数据而不是拨时钟</b>（同 {@code OrderCloseRuleFlowTest#backdate}）：
     * 自动确认收货是不可逆写操作，为测试在生产代码上开一个「传进来的时间」入口，
     * 那个入口传错就是批量误确认。而且拨的正是 Job 真正读的那一列。
     */
    private void backdateShipped(String subOrderNo, int daysAgo) {
        long at = System.currentTimeMillis() - (long) daysAgo * 86_400_000L;
        int rows = jdbc.update(
                "update ord_status_log set at = ? where sub_order_no = ? and status = 'FULFILLING'",
                at, subOrderNo);
        assertThat(rows)
                .as("没有拨到任何一行 —— 子单 %s 没有 FULFILLING 流水？那说明它根本没发货", subOrderNo)
                .isGreaterThan(0);
    }

    private String statusOf(String subOrderNo) {
        return jdbc.queryForObject(
                "select status from ord_sub_order where sub_order_no = ?", String.class, subOrderNo);
    }

    /**
     * 手工 new，不 {@code @Autowired} —— Job 是 {@code @ConditionalOnProperty} 的，
     * 测试组里这个 Bean 不存在。**天数显式传 15**：它是构造参数正是为了这里，
     * 传 0 的话 {@code autoConfirmReceipt} 直接返回 0，而断言「没有误确认」的那几条会全绿。
     */
    private OrderAutoReceiptJob job(int shippedDays) {
        return job(shippedDays, 0);
    }

    /** @param signedDays 0 = 不启用签收判据（存量用例都走这一支，行为与接签收前逐字相同） */
    private OrderAutoReceiptJob job(int shippedDays, int signedDays) {
        return new OrderAutoReceiptJob(orderService, jobs, shippedDays, signedDays);
    }

    /** 把这张子单的运单标成「几天前签收」。签收时间落在 ful_shipment 上（V386） */
    private void backdateSigned(String subOrderNo, String waybillNo, int daysAgo) {
        long at = System.currentTimeMillis() - (long) daysAgo * 86_400_000L;
        jdbc.update("insert into ful_shipment (shipment_no, sub_order_no, carrier, waybill_no, status,"
                        + " signed_at, tenant_no, created_at, updated_at)"
                        + " values (?, ?, 'SF', ?, 'DELIVERED', ?, 'MAIN', now(), now())",
                "SH-" + subOrderNo, subOrderNo, waybillNo, at);
    }

    @Test
    @DisplayName("★★★ 签收满 7 天自动确认收货 —— 比「发货满 15 天」先到，按先到的算")
    void signedLongAgoIsAutoConfirmed() throws Exception {
        Ctx c = prepare("12600181007", "签收确认测试店", "12600181008", "EXPRESS");
        ship(c, "SF-SIGNED-1");
        // 发货才 3 天（按发货判据远没到期），但已签收 10 天
        backdateShipped(c.subOrderNo, 3);
        backdateSigned(c.subOrderNo, "SF-SIGNED-1", 10);

        job(15, 7).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("签收 10 天了，按行业惯例（签收后 7 天）该自动完成 —— "
                        + "只按发货算的话要再等 12 天，买家的钱也就在平台多压 12 天")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("★★★ 签收没满 7 天、发货也没满 15 天：两条都不到期，不许动")
    void neitherDeadlineReachedIsNotTouched() throws Exception {
        Ctx c = prepare("12600181009", "两条都没到店", "12600181010", "EXPRESS");
        ship(c, "SF-SIGNED-2");
        backdateShipped(c.subOrderNo, 3);
        backdateSigned(c.subOrderNo, "SF-SIGNED-2", 2);

        job(15, 7).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("签收才 2 天，买家还在验货期内")
                .isEqualTo("FULFILLING");
    }

    @Test
    @DisplayName("★★★ 没有签收回传的单仍按发货判据 —— 接签收不能让这类单变得永不到期")
    void withoutSignedStillFallsBackToShipped() throws Exception {
        Ctx c = prepare("12600181011", "无签收回传店", "12600181012", "EXPRESS");
        ship(c, "SF-NOSIGN-1");
        backdateShipped(c.subOrderNo, 20);
        // 故意不写 ful_shipment：承运商没回传签收（圆通缺凭据、或快递100 查不到）

        job(15, 7).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("查不到签收就回落发货判据；回落没接上的话这类单永远结算不出来")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("★★★ 发货满 15 天自动确认收货 —— 不做这一步货款永远结算不出来")
    void shippedLongAgoIsAutoConfirmed() throws Exception {
        Ctx c = prepare("12600181001", "超时确认测试店", "12600181002", "EXPRESS");
        ship(c, "SF-AUTO-1");
        assertThat(statusOf(c.subOrderNo)).as("前置：发完货应当是履约中").isEqualTo("FULFILLING");

        backdateShipped(c.subOrderNo, 20);
        job(15).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("发货 20 天了还没人点确认，应当自动完成 —— 否则结算单永远生不出来")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("★★★ 没到期的不许动 —— 这条红了就是在替买家提前签收")
    void recentlyShippedIsNotTouched() throws Exception {
        Ctx c = prepare("12600181003", "未到期测试店", "12600181004", "EXPRESS");
        ship(c, "SF-AUTO-2");
        backdateShipped(c.subOrderNo, 3);

        job(15).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("才发货 3 天，自动确认等于替买家签收，他还没机会验货")
                .isEqualTo("FULFILLING");
    }

    @Test
    @DisplayName("★★★ 自提单不许自动确认 —— 超时没来取要的是退款，不是替他签收")
    void pickupOrderIsNotAutoConfirmed() throws Exception {
        Ctx c = prepare("12600181005", "自提超时测试店", "12600181006", "STORE_PICKUP");
        // 自提单走核销，这里直接把状态推到 FULFILLING（到点待取）并拨老
        jdbc.update("update ord_sub_order set status = 'FULFILLING' where sub_order_no = ?", c.subOrderNo);
        jdbc.update("insert into ord_status_log (sub_order_no, status, label, operator_type, at,"
                + " tenant_no, created_at) values (?, 'FULFILLING', '已到点', 'MERCHANT', ?, 'MAIN', now())",
                c.subOrderNo, System.currentTimeMillis() - 30L * 86_400_000L);

        job(15).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("""
                        自提单到点 30 天没来取，这笔钱该退，不该被自动结算掉。
                        两件事挤进一个 job 的话，「超时未取」会静默变成「已完成」""")
                .isEqualTo("FULFILLING");
    }

    @Test
    @DisplayName("★★★ 售后未闭环的不许动 —— 争议中自动确认等于替一方把钱定下来")
    void orderWithOpenAfterSaleIsNotTouched() throws Exception {
        Ctx c = prepare("12600181007", "售后中测试店", "12600181008", "EXPRESS");
        ship(c, "SF-AUTO-3");
        backdateShipped(c.subOrderNo, 30);
        // 列名照 schema：金额列是 refund_minor（不是 amount_minor），entity_no 非空
        jdbc.update("insert into ord_after_sale (after_sale_no, sub_order_no, order_no, user_no,"
                        + " entity_no, type, status, reason, refund_minor, tenant_no, deleted, version,"
                        + " created_at, updated_at) values (?, ?, 'ORD-X', 'U-X', 'M-X', 'REFUND',"
                        + " 'APPLIED', '测试', 100, 'MAIN', 0, 0, now(), now())",
                "AS-AUTO-" + System.currentTimeMillis(), c.subOrderNo);

        job(15).confirm();

        assertThat(statusOf(c.subOrderNo))
                .as("售后还开着就自动确认收货，等于替买家认了这单没问题 —— 钱会照常放给商家")
                .isEqualTo("FULFILLING");
    }

    // ------------------------------------------------------------------ 辅助

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private record Ctx(String merchantToken, String subOrderNo) {
    }

    private void ship(Ctx c, String expressNo) throws Exception {
        mvc().perform(post("/biz/order/" + c.subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + c.merchantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"" + expressNo + "\",\"expressCompany\":\"SF\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private Ctx prepare(String merchantPhone, String shopName, String buyerPhone, String fulfillment)
            throws Exception {
        String user = login(merchantPhone);
        String applyNo = json.readTree(mvc().perform(post("/mp/merchant/apply")
                        .header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + shopName + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("applyNo").asString();

        String bd = opsLogin("bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        String token = TestLogin.merchantOwner(mvc(), json, otpStore, merchantPhone);

        TestStoreCategory.open(mvc(), json, token, "CAT210");
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"超时确认测试商品\",\"subtitle\":\"测试\","
                                + "\"type\":\"NORMAL\",\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        String ops = opsLogin("goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit").header("Authorization", "Bearer " + ops)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String buyer = login(buyerPhone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();

        String body;
        if ("EXPRESS".equals(fulfillment)) {
            String addressId = json.readTree(mvc().perform(post("/mp/user/address")
                            .header("Authorization", "Bearer " + buyer)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"name\":\"买家\",\"phone\":\"13600181099\",\"province\":\"浙江省\","
                                    + "\"city\":\"杭州市\",\"district\":\"西湖区\",\"detail\":\"文三路 1 号\","
                                    + "\"isDefault\":true,\"tag\":\"家\"}"))
                    .andReturn().getResponse().getContentAsString())
                    .get("data").get(0).get("addressId").asString();
            body = "{\"fulfillment\":\"EXPRESS\",\"addressId\":\"" + addressId + "\",\"items\":["
                    + "{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}";
        } else {
            // 自提单必须带自提点，否则 70025 PICKUP_POINT_REQUIRED —— 是夹具漏了，不是代码错
            body = "{\"fulfillment\":\"" + fulfillment + "\",\"pickupNo\":\"PP0001\",\"items\":["
                    + "{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}]}";
        }
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();

        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());

        String list = mvc().perform(get("/biz/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        var records = json.readTree(list).get("data").get("records");
        assertThat(records.size()).as("商家应当看得到刚下的单").isGreaterThan(0);
        return new Ctx(token, records.get(0).get("orderNo").asString());
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String opsLogin(String username, String password) throws Exception {
        return TestLogin.operator(mvc(), json, username, password);
    }
}
