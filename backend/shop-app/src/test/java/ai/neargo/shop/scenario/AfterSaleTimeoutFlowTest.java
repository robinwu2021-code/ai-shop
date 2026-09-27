package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.job.AfterSaleTimeoutJob;
import ai.neargo.shop.trade.service.AfterSaleRuleService;
import ai.neargo.shop.trade.service.AfterSaleRuleService.AfterSaleRuleVO;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 售后时效：商家沉默到点了，系统替他做决定（TDD-C 端商品详情页·内容丰富度 §3）。
 *
 * <p><b>每个时限都双向断言</b>：到点要动、<b>差一点不能动</b>。只测前者证明的是「会退」，
 * 证明不了「不早退」—— 而后者才是资损方向。
 */
@SpringBootTest
@ActiveProfiles("test")
class AfterSaleTimeoutFlowTest {

    /** 独占号段：撞号会把别人的购物车/订单卷进来（PurchaseLimitFlowTest 踩过） */
    private static final String PHONE_1 = "13000330001";
    private static final String PHONE_2 = "13000330002";
    private static final String PHONE_3 = "13000330003";
    private static final String PHONE_4 = "13000330004";

    private static final long HOUR = 3_600_000L;
    private static final String MERCHANT = "M0001";
    private static final String SKU = "SK0003";
    /** 与 M5AfterSaleFlowTest 同一个桩密钥 */
    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private AfterSaleService afterSaleService;
    @Autowired
    private AfterSaleRuleService ruleService;
    @Autowired
    private JdbcTemplate jdbc;

    /** 规则与商家资金路径都是**共享种子**：改了不还原，后面的用例读到的是我改过的值 */
    private String savedRule;
    private String savedFundsMode;

    /**
     * 每个用例都从同一个确定状态开始：
     *
     * <ul>
     *   <li><b>极速退上限压到 1 分</b> —— 否则这一单会被秒退，而本类要测的是「等商家」那条路。
     *       不依赖种子商品的价格与环境阈值（{@code application-testcfg.yml} 里是 ¥50，
     *       生产默认 ¥100），那种依赖会在阈值一改动时静默失效。</li>
     *   <li><b>M0001 改成非自营</b> —— 测试库里所有商家默认 {@code AGGREGATED}（自营），
     *       而自营单一申请就进 {@code ARBITRATING}，根本没有「等商家」这一步。</li>
     * </ul>
     */
    @BeforeEach
    void deterministicRule() {
        savedRule = currentRuleJson();
        savedFundsMode = jdbc.queryForObject(
                "SELECT funds_mode FROM mch_entity WHERE entity_no = ?", String.class, MERCHANT);
        jdbc.update("UPDATE mch_entity SET funds_mode = ? WHERE entity_no = ?",
                MerchantQueryPort.FUNDS_DIRECT, MERCHANT);
        ruleService.save(new AfterSaleRuleVO(true, 1L, 24, List.of(),
                AfterSaleRuleService.DEFAULT_REPLY_HOURS, AfterSaleRuleService.DEFAULT_SHIP_BACK_DAYS,
                AfterSaleRuleService.DEFAULT_CONFIRM_HOURS,
                AfterSaleRuleService.DEFAULT_INTERVENE_WORK_DAYS, null, null), "TEST");
    }

    /**
     * 本类下单吃掉的是**共享种子的库存**（SK0003），而「仅退款」不回补库存
     * （只有退货退款会）。不还原的话，单独跑这一类是绿的，全量跑到后面几个类时
     * 下单开始报 20001 —— 而那个报错与它们自己毫无关系，指不到真因。
     */
    private Integer stockBefore;

    @BeforeEach
    void rememberStock() {
        stockBefore = jdbc.queryForObject(
                "SELECT stock FROM prd_sku WHERE sku_no = ?", Integer.class, SKU);
    }

    @AfterEach
    void restoreStock() {
        if (stockBefore != null) {
            jdbc.update("UPDATE prd_sku SET stock = ? WHERE sku_no = ?", stockBefore, SKU);
        }
    }

    @AfterEach
    void restoreSharedSeeds() {
        if (savedRule == null) {
            jdbc.update("DELETE FROM sys_setting WHERE setting_key = ?", "aftersale.fast-refund-rule");
        } else {
            jdbc.update("UPDATE sys_setting SET setting_value = ? WHERE setting_key = ?",
                    savedRule, "aftersale.fast-refund-rule");
        }
        jdbc.update("UPDATE mch_entity SET funds_mode = ? WHERE entity_no = ?",
                savedFundsMode == null ? MerchantQueryPort.FUNDS_AGGREGATED : savedFundsMode, MERCHANT);
    }

    @Test
    @DisplayName("★★★ 商家 48 小时不处理 → 系统自动同意并退款；差一小时则一动不动")
    void autoApproveAfterReplyHours() throws Exception {
        String token = login(PHONE_1);
        String subOrderNo = placeAndPay(token, PHONE_1);
        String asNo = apply(token, subOrderNo, "REFUND_ONLY");
        assertThat(statusOf(asNo)).as("上限压到 1 分了，这一单本该停在等商家").isEqualTo(OrdAfterSale.APPLIED);

        int replyHours = ruleService.get().replyHours();

        // ① 差一小时：不许动
        backdate(asNo, replyHours - 1);
        assertThat(afterSaleService.idlePendingNos(System.currentTimeMillis() - replyHours * HOUR, 100))
                .as("还没到点就被捞出来 —— 会替商家提前把钱退掉").doesNotContain(asNo);
        sweep();
        assertThat(statusOf(asNo)).as("没到点却动了").isEqualTo(OrdAfterSale.APPLIED);

        // ② 过点一小时：必须自动同意并退款
        backdate(asNo, replyHours + 1);
        assertThat(afterSaleService.idlePendingNos(System.currentTimeMillis() - replyHours * HOUR, 100))
                .contains(asNo);
        sweep();
        assertThat(statusOf(asNo)).as("到点了还挂着 —— 用户在等一个不会来的结果")
                .isEqualTo(OrdAfterSale.REFUNDED);
        assertThat(timelineLabels(token, asNo))
                .as("时间线要说清是系统放的钱，不是商家")
                .anySatisfy(l -> assertThat(l).contains("系统自动同意"));
    }

    @Test
    @DisplayName("★★★ 自营单不受商家时效影响 —— 它在等平台裁决，不是在等商家")
    void selfOperatedUntouched() throws Exception {
        // 自营单申请后直接进 ARBITRATING（责任跟着钱走），因此不在 APPLIED 集合里。
        // 这条用「集合的定义」来断言：APPLIED 里永远不会出现 ARBITRATING 的单
        String token = login(PHONE_2);
        String subOrderNo = placeAndPay(token, PHONE_2);
        String asNo = apply(token, subOrderNo, "REFUND_ONLY");
        jdbc.update("UPDATE ord_after_sale SET status = ? WHERE after_sale_no = ?",
                OrdAfterSale.ARBITRATING, asNo);
        backdate(asNo, 24 * 365);

        assertThat(afterSaleService.idlePendingNos(System.currentTimeMillis(), 100))
                .as("等平台裁决的单被当成商家超时 —— 那等于平台自动同意自己").doesNotContain(asNo);
        sweep();
        assertThat(statusOf(asNo)).isEqualTo(OrdAfterSale.ARBITRATING);
    }

    @Test
    @DisplayName("★★★ 退货退款：买家逾期不寄回 → 关闭申请；没到点不关")
    void closeWhenBuyerNeverShips() throws Exception {
        String token = login(PHONE_3);
        String subOrderNo = placeAndPay(token, PHONE_3);
        String asNo = apply(token, subOrderNo, "RETURN_REFUND");
        // 商家同意 → 等买家寄回（状态是 REFUNDING，而货还没动）
        afterSaleService.autoApprove(asNo);
        assertThat(statusOf(asNo)).isEqualTo(OrdAfterSale.REFUNDING);

        int days = ruleService.get().shipBackDays();
        backdate(asNo, (days - 1) * 24);
        sweep();
        assertThat(statusOf(asNo)).as("没到寄回时限就把申请关了").isEqualTo(OrdAfterSale.REFUNDING);

        backdate(asNo, (days + 1) * 24);
        sweep();
        assertThat(statusOf(asNo)).as("逾期不寄回的单要关掉，否则永远悬着")
                .isEqualTo(OrdAfterSale.CLOSED);
    }

    @Test
    @DisplayName("★★★ 退款重试不许碰「等买家寄回」的退货单 —— 货没回来钱先退了")
    void refundRetryMustNotTouchAwaitingReturn() throws Exception {
        String token = login(PHONE_4);
        String subOrderNo = placeAndPay(token, PHONE_4);
        String asNo = apply(token, subOrderNo, "RETURN_REFUND");
        afterSaleService.autoApprove(asNo);
        assertThat(statusOf(asNo)).isEqualTo(OrdAfterSale.REFUNDING);

        // 退款重试任务只看「状态是 REFUNDING 且 30 分钟没动过」，而这张单正是那个样子。
        // 它此前会被捞起来退掉：**退货退款在商家同意后就是 REFUNDING**，与「退款卡住」同一个状态
        backdate(asNo, 24);
        assertThat(afterSaleService.stuckRefundNos(System.currentTimeMillis(), 100))
                .as("等买家寄回的退货单被退款重试捞走了 —— 这是一条会真的花钱的路")
                .doesNotContain(asNo);

        // 而买家寄回之后，它归时效任务管：商家不确认 → 到点自动退款
        jdbc.update("UPDATE ord_after_sale SET express_no = 'SF123', express_company = 'SF' "
                + "WHERE after_sale_no = ?", asNo);
        int confirmHours = ruleService.get().confirmHours();
        backdate(asNo, confirmHours - 1);
        sweep();
        assertThat(statusOf(asNo)).as("没到确认时限就退了").isEqualTo(OrdAfterSale.REFUNDING);

        backdate(asNo, confirmHours + 1);
        sweep();
        assertThat(statusOf(asNo)).as("买家寄回了、商家不确认，钱就得由系统退")
                .isEqualTo(OrdAfterSale.REFUNDED);
    }

    @Test
    @DisplayName("★★★ 运营配的极速退规则真的生效 —— 此前那一屏写进库里没人读")
    void opsRuleActuallyApplies() throws Exception {
        AfterSaleRuleVO base = ruleService.get();

        // ① 把上限抬到 1 万元：原本要等商家的 6980 应当变成秒退
        ruleService.save(new AfterSaleRuleVO(true, 1_000_000L, base.withinHours(), List.of(),
                base.replyHours(), base.shipBackDays(), base.confirmHours(),
                base.interveneWorkDays(), null, null), "OP");
        String t1 = login(PHONE_1);
        String sub1 = placeAndPay(t1, PHONE_1 + "-hi");
        String as1 = apply(t1, sub1, "REFUND_ONLY");
        assertThat(statusOf(as1)).as("运营抬高了上限却没生效 —— 那一屏又是白配的")
                .isEqualTo(OrdAfterSale.REFUNDED);

        // ② 总开关关掉：同样的单必须回到人工
        ruleService.save(new AfterSaleRuleVO(false, 1_000_000L, base.withinHours(), List.of(),
                base.replyHours(), base.shipBackDays(), base.confirmHours(),
                base.interveneWorkDays(), null, null), "OP");
        String sub2 = placeAndPay(t1, PHONE_1 + "-off");
        String as2 = apply(t1, sub2, "REFUND_ONLY");
        assertThat(statusOf(as2)).as("开关关了还在秒退 —— 那它不是开关")
                .isEqualTo(OrdAfterSale.APPLIED);

        // ③ 时限写成 0 会被挡住：replyHours=0 等于每笔申请下一分钟就自动同意
        assertThat(catchSave(new AfterSaleRuleVO(true, 10_000L, 24, List.of(),
                0, 7, 48, 5, null, null)))
                .as("0 小时的时限存进去了 —— 库里那个 0 会让系统立刻替商家同意所有单")
                .isNotNull();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * 跑一轮时效任务。
     *
     * <p><b>直接 new 而不是注入</b>：job bean 挂在 {@code shop.job.enabled=true} 上，
     * 测试环境关着 —— 而 {@code run()} 里没有用到 {@code JobSupport}，
     * 传 null 能跑到的正是生产那条路径（读规则 → 三次扫描 → 逐条处置）。
     */
    private void sweep() {
        new AfterSaleTimeoutJob(afterSaleService, ruleService, null).run(null);
    }

    /** 把「最后动过的时间」往前拨。时效判的就是它 */
    private void backdate(String afterSaleNo, int hours) {
        int n = jdbc.update("UPDATE ord_after_sale SET updated_at = ? WHERE after_sale_no = ?",
                java.time.LocalDateTime.now().minusHours(hours), afterSaleNo);
        assertThat(n).as("没拨到任何行 —— 后面的断言会变成空转").isOne();
    }

    private String statusOf(String afterSaleNo) {
        return jdbc.queryForObject(
                "SELECT status FROM ord_after_sale WHERE after_sale_no = ?", String.class, afterSaleNo);
    }

    private String currentRuleJson() {
        List<String> rows = jdbc.queryForList(
                "SELECT setting_value FROM sys_setting WHERE setting_key = ?", String.class,
                "aftersale.fast-refund-rule");
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RuntimeException catchSave(AfterSaleRuleVO bad) {
        try {
            ruleService.save(bad, "OP");
            return null;
        } catch (RuntimeException e) {
            return e;
        }
    }

    private List<String> timelineLabels(String token, String afterSaleNo) throws Exception {
        String body = mvc().perform(get("/mp/after-sale/" + afterSaleNo)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (JsonNode n : json.readTree(body).get("data").get("timeline")) {
            out.add(n.get("label").asString());
        }
        return out;
    }

    /** 6980 的油（M0001/G0002）：**高于极速退阈值**，所以申请后停在等商家 */
    private String placeAndPay(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ast-" + idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-ast-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    private String apply(String token, String subOrderNo, String type) throws Exception {
        String body = mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"reason\":\"QUALITY\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as(body).isZero();
        return r.get("data").get("afterSaleNo").asString();
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
