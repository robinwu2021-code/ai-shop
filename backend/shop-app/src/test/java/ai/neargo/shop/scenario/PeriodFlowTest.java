package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.PeriodVOs.Decision;
import ai.neargo.shop.promotion.dto.PeriodVOs.PeriodDetailVO;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.promotion.service.PeriodService;
import ai.neargo.shop.spi.trade.FulfillmentStatsPort;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 社区集单（TDD-营销域-详细设计 §1.3 · PRD AC-7 ～ AC-11）。
 *
 * <p><b>下单、付款、撤单都走真 HTTP</b>：买家会话下 {@code pmt_activity} / {@code pmt_period}
 * 有数据域，直接调 service 的用例没有请求上下文、域根本不生效 —— 绿了也证明不了
 * 下单那一侧真的取得到期（GroupRulePortImpl 上记着同一个教训）。
 *
 * <p>用种子商品 G0002（单规格，M0001）。<b>每条用例结束时把活动结束掉</b>：
 * 进行中的集单活动会让这件货对所有人按集单价卖，不还原会让别的用例的金额断言莫名失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class PeriodFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0002";
    private static final String SKU = "SK0003";
    private static final long BATCH_PRICE = 5000L;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static int seq = 8800;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private ActivityService activityService;
    @Autowired private PeriodService periodService;
    @Autowired private FulfillmentStatsPort statsPort;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> started = new ArrayList<>();

    @AfterEach
    void endActivities() {
        for (String no : started) {
            try {
                activityService.setStatus(ENTITY, no, PmtActivity.ENDED);
            } catch (RuntimeException ignored) {
                // 已结束的再结束一次会被拒，无妨
            }
        }
        started.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("★★★ 集单单挂到期上：子单有期号、提货日 = 截单日 + 1，按集单价收钱（AC-8）")
    void orderJoinsPeriodWithPickupDate() throws Exception {
        String activityNo = startBatch(null, null);
        String buyer = login(phone());
        String orderNo = buyAndPay(buyer, 2);

        Map<String, Object> sub = subOf(orderNo);
        assertThat(sub.get("period_no")).as("★ 集单商品下单没挂到期上 —— 期汇总与撤单判定都会漏掉它").isNotNull();
        String periodNo = (String) sub.get("period_no");
        String pickupDate = jdbc.queryForObject(
                "select pickup_date from pmt_period where period_no=?", String.class, periodNo);
        assertThat(sub.get("arrive_date"))
                .as("★ 提货日没写进子单 —— 履约批次会按下单日分，整批早一天").isEqualTo(pickupDate);
        assertThat(jdbc.queryForObject("select activity_no from pmt_period where period_no=?",
                String.class, periodNo)).isEqualTo(activityNo);
        assertThat(((Number) sub.get("goods_amount")).longValue())
                .as("集单价没生效：CUTOFF × PRICE 应当走 flashPrices").isEqualTo(BATCH_PRICE * 2);
    }

    @Test
    @DisplayName("★★★ 履约到货日取提货日，不取下单日（AC-8 看板那一侧）")
    void fulfillmentUsesArriveDate() throws Exception {
        startBatch(null, null);
        String orderNo = buyAndPay(login(phone()), 1);
        String arrive = (String) subOf(orderNo).get("arrive_date");

        assertThat(statsPort.pickupDays())
                .as("★ 自提点看板上这一堆的日子不对 —— 去掉 dayOf 里读 arrive_date 那两行，这条就红")
                .anyMatch(d -> "PP0001".equals(d.pickupNo()) && arrive.equals(d.arriveDate()));
    }

    @Test
    @DisplayName("★★★ 截单前买家能撤（全额退款），截单后不能（AC-7）")
    void cancelBeforeCutoffOnly() throws Exception {
        startBatch(null, null);
        String buyer = login(phone());

        String first = buyAndPay(buyer, 1);
        mvc().perform(post("/mp/order/" + first + "/cancel").header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不要了\"}"))
                .andExpect(status().isOk());
        assertThat(subOf(first).get("status"))
                .as("★ 截单前撤单没退款 —— 已付款的主单在状态机里没有出口，只能逐张子单走系统退款")
                .isEqualTo("REFUNDED");

        String second = buyAndPay(buyer, 1);
        String periodNo = (String) subOf(second).get("period_no");
        periodService.cutoffNow(ENTITY, periodNo, "OP");
        JsonNode r = json.readTree(mvc().perform(post("/mp/order/" + second + "/cancel")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不要了\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt())
                .as("★ 截单后还能撤 —— 商家已经按这一期的量去采购了").isEqualTo(ErrorCode.PERIOD_CUT_OFF.code());
        assertThat(subOf(second).get("status")).as("被拒的撤单不该动钱").isNotEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★★ 汇总与订单明细对得上：按商品、按自提点；退了的不算（AC-9）")
    void summaryMatchesOrders() throws Exception {
        startBatch(null, null);
        String buyer = login(phone());
        String kept = buyAndPay(buyer, 3);
        String refunded = buyAndPay(buyer, 2);
        mvc().perform(post("/mp/order/" + refunded + "/cancel").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"));

        String periodNo = (String) subOf(kept).get("period_no");
        PeriodDetailVO d = periodService.detail(ENTITY, periodNo);
        int goodsQty = d.byGoods().stream().filter(g -> GOODS.equals(g.goodsNo()))
                .mapToInt(g -> g.qty()).sum();
        int pickupQty = d.byPickup().stream().filter(p -> "PP0001".equals(p.pickupNo()))
                .mapToInt(p -> p.qty()).sum();
        int expected = jdbc.queryForObject(
                "select coalesce(sum(i.qty),0) from ord_item i join ord_sub_order s on s.sub_order_no=i.sub_order_no "
                        + "where s.period_no=? and s.status not in ('WAIT_PAY','CANCELLED','REFUNDED') "
                        + "and i.goods_no=?", Integer.class, periodNo, GOODS);
        assertThat(expected).as("对照量本身要非零，否则下面的相等证明不了什么").isGreaterThan(0);
        assertThat(goodsQty).as("★ 按商品的份数与订单明细对不上（退掉的那 2 件不该算）").isEqualTo(expected);
        assertThat(pickupQty).as("按自提点的份数").isEqualTo(expected);
        assertThat(d.period().qty()).isEqualTo(expected);
    }

    @Test
    @DisplayName("★★★ 未达起订量：商家取消本期 → 逐单全额退款（AC-10）")
    void shortPeriodMerchantCancels() throws Exception {
        startBatch(50, 6);
        String orderNo = buyAndPay(login(phone()), 1);
        String periodNo = (String) subOf(orderNo).get("period_no");

        periodService.cutoffNow(ENTITY, periodNo, "OP");
        assertThat(periodStatus(periodNo)).as("1 份 < 起订 50 份，截单后应待处理").isEqualTo("SHORT");
        assertThat(jdbc.queryForObject("select decide_deadline - cutoff_at from pmt_period where period_no=?",
                Long.class, periodNo)).as("处理时限取活动配置的 6 小时，不取全局缺省").isEqualTo(6 * 3600_000L);

        periodService.decide(ENTITY, periodNo, Decision.CANCEL, "OP");
        assertThat(periodStatus(periodNo)).isEqualTo("CANCELLED");
        assertThat(subOf(orderNo).get("status"))
                .as("★ 取消了期却没退钱 —— abortGroup 就是这么做的，钱一分没退").isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★★ 未达起订量且商家超时未处理：自动取消并退款；照常发货的不受影响（AC-10）")
    void shortPeriodAutoCancelsAfterDeadline() throws Exception {
        startBatch(50, 1);
        String a = buyAndPay(login(phone()), 1);
        String periodNo = (String) subOf(a).get("period_no");
        periodService.cutoffNow(ENTITY, periodNo, "OP");
        assertThat(periodStatus(periodNo)).isEqualTo("SHORT");

        long afterDeadline = jdbc.queryForObject(
                "select decide_deadline from pmt_period where period_no=?", Long.class, periodNo) + 1;
        periodService.cancelUndecided(afterDeadline);

        assertThat(periodStatus(periodNo)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select decided_by from pmt_period where period_no=?",
                String.class, periodNo)).as("超时取消要记成系统，商家问「谁取消的」要答得上").isEqualTo("SYSTEM");
        assertThat(subOf(a).get("status")).isEqualTo("REFUNDED");

        periodService.cancelUndecided(afterDeadline);
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no=?",
                Integer.class, (String) subOf(a).get("sub_order_no")))
                .as("★ 补扫又退了一次 —— 幂等靠「已有退款在路上 / 已退款」两道判断").isEqualTo(1);
    }

    @Test
    @DisplayName("★★ 够起订量（或没设）截单即成；提前截单后下的单进下一期")
    void cutoffConfirmsAndRollsToNextPeriod() throws Exception {
        startBatch(null, null);
        String buyer = login(phone());
        String a = buyAndPay(buyer, 1);
        String periodNo = (String) subOf(a).get("period_no");
        periodService.cutoffNow(ENTITY, periodNo, "OP");
        assertThat(periodStatus(periodNo)).isEqualTo("CONFIRMED");

        String b = buyAndPay(buyer, 1);
        String next = (String) subOf(b).get("period_no");
        assertThat(next).as("★ 提前截单后的新单还挂在已截的那一期上").isNotNull().isNotEqualTo(periodNo);
        assertThat(jdbc.queryForObject("select period_date from pmt_period where period_no=?", String.class, next))
                .isGreaterThan(jdbc.queryForObject("select period_date from pmt_period where period_no=?",
                        String.class, periodNo));
    }

    @Test
    @DisplayName("★★ 到点推进：任务把过了截单时刻的期推进，没到点的不动")
    void advanceDueOnlyTouchesPastCutoff() throws Exception {
        startBatch(null, null);
        String a = buyAndPay(login(phone()), 1);
        String periodNo = (String) subOf(a).get("period_no");
        long cutoff = jdbc.queryForObject("select cutoff_at from pmt_period where period_no=?", Long.class, periodNo);

        periodService.advanceDue(cutoff - 1);
        assertThat(periodStatus(periodNo)).as("没到点就推进了").isEqualTo("OPEN");
        periodService.advanceDue(cutoff);
        assertThat(periodStatus(periodNo)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("★★★ 预售中的商品不能进集单（AC-11）")
    void presaleGoodsRejected() {
        Integer before = jdbc.queryForObject("select presale_quota from prd_sku where sku_no=? limit 1",
                Integer.class, SKU);
        jdbc.update("update prd_sku set presale_quota=10 where sku_no=?", SKU);
        try {
            assertThatThrownBy(() -> activityService.save(ENTITY, draft(null, null), "OP"))
                    .as("★ 预售与集单两套截单与到货口径叠在一件货上")
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.GOODS_IN_PRESALE));
        } finally {
            jdbc.update("update prd_sku set presale_quota=? where sku_no=?", before, SKU);
        }
    }

    @Test
    @DisplayName("★★ 集单参数只在 CUTOFF 下落库；截单时刻不合法存不进")
    void batchParamsValidated() {
        long now = System.currentTimeMillis();
        ActivityDraft bad = new ActivityDraft(null, "集单 · 坏时刻", null, null,
                PmtActivity.TRIGGER_CUTOFF, null, null,
                PmtActivity.BENEFIT_PRICE, BATCH_PRICE, null, null,
                PmtActivity.ALWAYS_ON, now - 1000, null, null, 1000, null,
                List.of(), List.of(GOODS),
                "25:00", 1, "09:00", null, null, null, null);
        assertThatThrownBy(() -> activityService.save(ENTITY, bad, "OP")).isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 订单详情给提货日与「截单前可取消」；截单后不再给（s37）")
    void orderDetailShowsBatch() throws Exception {
        startBatch(null, null);
        String buyer = login(phone());
        String orderNo = buyAndPay(buyer, 1);
        String subNo = (String) subOf(orderNo).get("sub_order_no");

        JsonNode d = detail(buyer, subNo);
        assertThat(d.get("arriveDate").asString()).isEqualTo(subOf(orderNo).get("arrive_date"));
        assertThat(d.get("cancellableUntil").isNull()).as("收单中应给截单时刻").isFalse();

        periodService.cutoffNow(ENTITY, (String) subOf(orderNo).get("period_no"), "OP");
        assertThat(detail(buyer, subNo).get("cancellableUntil").isNull())
                .as("截单后还给可取消时刻，端上会显示一个点了必失败的按钮").isTrue();
    }

    @Test
    @DisplayName("★★ 商品详情的集单块：匿名可看，给截单时刻与已订份数（s26）")
    void goodsBatchView() throws Exception {
        startBatch(null, null);
        JsonNode before = json.readTree(mvc().perform(get("/mp/goods/" + GOODS + "/batch"))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(before.isNull()).as("匿名也要看得到截单时间，否则不会下单").isFalse();
        int ordered = before.get("orderedQty").asInt();

        buyAndPay(login(phone()), 2);
        JsonNode after = json.readTree(mvc().perform(get("/mp/goods/" + GOODS + "/batch"))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(after.get("orderedQty").asInt()).isEqualTo(ordered + 2);
        assertThat(after.get("batchPriceMinor").asLong()).isEqualTo(BATCH_PRICE);
    }

    // ---------------------------------------------------------------- 夹具

    /** 起一个集单活动：G0002，集单价 50 元，截单在 3 小时后 */
    private String startBatch(Integer minQty, Integer decideHours) {
        String no = activityService.save(ENTITY, draft(minQty, decideHours), "OP").activityNo();
        started.add(no);
        return no;
    }

    private ActivityDraft draft(Integer minQty, Integer decideHours) {
        long now = System.currentTimeMillis();
        String cutoff = LocalTime.now(ZONE).plusHours(3).format(DateTimeFormatter.ofPattern("HH:mm"));
        return new ActivityDraft(null, "集单 · " + (++seq), null, null,
                PmtActivity.TRIGGER_CUTOFF, null, null,
                PmtActivity.BENEFIT_PRICE, BATCH_PRICE, null, null,
                PmtActivity.ALWAYS_ON, now - 1000, null, null, 1000, null,
                List.of(), List.of(GOODS),
                cutoff, 1, "09:00", minQty, null, decideHours, null);
    }

    private String buyAndPay(String token, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "period-" + (++seq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode r = json.readTree(body);
        assertThat(r.get("code").asInt()).as("下单失败：" + body).isEqualTo(0);
        String orderNo = r.get("data").get("payOrderNo").asString();
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"TX-P" + (++seq)
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
        return orderNo;
    }

    private Map<String, Object> subOf(String orderNo) {
        return jdbc.queryForMap("select sub_order_no, period_no, arrive_date, status, goods_amount "
                + "from ord_sub_order where order_no=? limit 1", orderNo);
    }

    private String periodStatus(String periodNo) {
        return jdbc.queryForObject("select status from pmt_period where period_no=?", String.class, periodNo);
    }

    private JsonNode detail(String token, String subNo) throws Exception {
        return json.readTree(mvc().perform(get("/mp/order/" + subNo).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private String phone() {
        return "1370088" + String.format("%04d", ++seq % 10000);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
