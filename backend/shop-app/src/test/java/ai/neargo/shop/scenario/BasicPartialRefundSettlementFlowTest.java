package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.CashReceiptFixture;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

/**
 * 基础退款执行的部分退：结算回退的分母必须是<b>子单商品实付</b>，不是本次退的货款。
 *
 * <p>防住的是：退 3 件里的 1 件时把「本次货款」当分母 → 比例算成 100%，
 * 商家整单所得一次冲回、结算单直接转 REVERSED；同一张单的第二笔部分退随后要么
 * 卡在 REVERSE_UNCONFIRMED 反复重试（分账前），要么不落流水就放行退现金（分账后）。
 *
 * <p>部分退的买家入口要求来源系统绑定，这里沿用 {@code BasicRefundClosureBoundaryFlowTest} 的做法：
 * 先让整单退款在结算侧暂挂（SPLITTING），再把冻结方案改成「1 件」，由生产的续跑入口执行回退。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:basic-partial-refund-settlement;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class BasicPartialRefundSettlementFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OtpStore otp;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AfterSaleService afterSales;
    @Autowired private MerchantOrderService merchantOrders;
    @Autowired private BasicRefundExecutionService refunds;
    private String oldFundsMode;

    @BeforeEach
    void direct() {
        oldFundsMode = jdbc.queryForObject("select funds_mode from mch_entity where entity_no='M0001'", String.class);
        jdbc.update("update mch_entity set funds_mode='DIRECT' where entity_no='M0001'");
    }

    @AfterEach
    void restore() {
        jdbc.update("update mch_entity set funds_mode=? where entity_no='M0001'", oldFundsMode);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void oneOfThreeReversesAThirdAndASecondPartialStillReachesCash(boolean splitDone) throws Exception {
        String phone = splitDone ? "13207073102" : "13207073101";
        String token = TestLogin.consumer(mvc(), json, otp, phone);
        String sub = buy(token, 3, "partial-settle-" + phone);
        Map<String, Object> original = bill(sub);
        long goodsPaid = jdbc.queryForObject("select pay_amount - coalesce(freight_amount,0) from ord_sub_order"
                + " where sub_order_no=?", Long.class, sub);
        long merchantGoods = ((Number) original.get("net_minor")).longValue();

        String afterSale = heldFullRefund(sub, token);
        long oneItem = BasicRefundPolicy.prefix(goodsPaid, 1, 3);
        String no = "RF-" + afterSale;
        reviseToOneItem(no, oneItem);
        settled(sub, splitDone, (String) original.get("status"));

        refunds.recover(no);
        Map<String, Object> first = journal(afterSale);
        assertThat(first).as("第一笔部分退已落结算流水").isNotNull();
        assertThat(((Number) first.get("goods_minor")).longValue()).isEqualTo(oneItem);
        assertThat(((Number) first.get("merchant_reverse_minor")).longValue())
                .as("只冲回三分之一的商家所得").isEqualTo(merchantGoods * oneItem / goodsPaid);
        assertThat(flag(first.get("final_refund"))).as("不是最后一笔").isFalse();
        assertThat(bill(sub).get("status")).as("部分退不转 REVERSED")
                .isEqualTo(splitDone ? "SPLIT" : original.get("status"));
        assertThat(phase(no)).isEqualTo("CASH");

        // 同一张结算单上的第二笔部分退（另一张售后单）：必须能回退并走到退现金，不能永远卡在回退
        String second = afterSale + "-2";
        jdbc.update("update ord_refund_execution set after_sale_no=?,phase='REVERSAL',status='PROCESSING',"
                + "error_code=null,next_attempt_at=0 where execution_no=?", second, no);
        refunds.recover(no);
        Map<String, Object> next = journal(second);
        assertThat(next).as("第二笔部分退也落了结算流水").isNotNull();
        assertThat(flag(next.get("final_refund"))).isFalse();
        assertThat(phase(no)).isEqualTo("CASH");
        assertThat(((Number) bill(sub).get("refunded_goods_minor")).longValue()).isEqualTo(2 * oneItem);
    }

    /**
     * 分账后回退已提交、执行单还没来得及转 CASH 就崩了：续跑时那行 preSplit=false 的流水就是确认，
     * 必须继续退现金；不能当成「没确认」永远停在 REVERSE_UNCONFIRMED（买家的钱永远不退）。
     * 流水与本次方案对不上（同一售后单、不同金额）则不得放行。
     */
    @Test
    void replayAfterCommittedPostSplitReversalProceedsToCash() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otp, "13207073103");
        String sub = buy(token, 3, "partial-settle-replay");
        String afterSale = heldFullRefund(sub, token);
        String no = "RF-" + afterSale;
        jdbc.update("update ord_refund_execution set status='PROCESSING',error_code=null,next_attempt_at=0"
                + " where execution_no=?", no);
        settled(sub, true, null);
        refunds.recover(no);
        assertThat(phase(no)).isEqualTo("CASH");
        assertThat(flag(journal(afterSale).get("pre_split"))).isFalse();

        // 崩在 cashReady 之前：回退与流水已提交，执行单仍在回退阶段
        jdbc.update("update ord_refund_execution set phase='REVERSAL',status='PROCESSING',error_code=null,"
                + "next_attempt_at=0 where execution_no=?", no);
        refunds.recover(no);
        assertThat(phase(no)).as("已提交的分账后回退就是确认，继续退现金").isEqualTo("CASH");
        assertThat(jdbc.queryForObject("select count(*) from stl_split_log where sub_order_no=?"
                + " and split_action='REVERSE'", Integer.class, sub)).as("不重复发回退").isOne();

        jdbc.update("update stl_bill_refund set goods_minor=goods_minor-1 where after_sale_no=?", afterSale);
        jdbc.update("update ord_refund_execution set phase='REVERSAL',status='PROCESSING',error_code=null,"
                + "next_attempt_at=0 where execution_no=?", no);
        refunds.recover(no);
        assertThat(phase(no)).as("流水与方案对不上，不放行").isEqualTo("REVERSAL");
        assertThat(jdbc.queryForObject("select error_code from ord_refund_execution where execution_no=?",
                String.class, no)).isEqualTo("REVERSE_UNCONFIRMED");
    }

    /** 整单退款先在结算侧暂挂：分账进行中回退不成，执行单停在回退阶段，没有任何结算流水。 */
    private String heldFullRefund(String sub, String token) throws Exception {
        jdbc.update("update stl_bill set status='SPLITTING' where sub_order_no=?", sub);
        String afterSale = json.readTree(mvc().perform(post("/mp/order/" + sub + "/after-sale")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}"))
                .andReturn().getResponse().getContentAsString()).path("data").path("afterSaleNo").asString();
        afterSales.approve(col(sub, "entity_no"), afterSale, "approved", null, null);
        assertThat(phase("RF-" + afterSale)).isEqualTo("REVERSAL");
        assertThat(journal(afterSale)).isNull();
        return afterSale;
    }

    private void reviseToOneItem(String no, long amount) throws Exception {
        RefundPlan full = json.readValue(jdbc.queryForObject("select plan_json from ord_refund_execution"
                + " where execution_no=?", String.class, no), RefundPlan.class);
        RefundLine line = full.lines().getFirst();
        RefundPlan one = new RefundPlan(full.subOrderNo(), List.of(new RefundLine(line.itemId(), 1, amount)), 0);
        jdbc.update("update ord_refund_execution set plan_json=?,amount_minor=?,status='PROCESSING',"
                + "error_code=null,next_attempt_at=0 where execution_no=?", json.writeValueAsString(one), amount, no);
    }

    private void settled(String sub, boolean splitDone, String originalStatus) {
        if (splitDone) {
            // 分账真发出去过的单必有收款号快照；回退要打回同一个号
            jdbc.update("update stl_bill set status='SPLIT',split_at=1,pay_merchant_no='SUB-PARTIAL'"
                    + " where sub_order_no=?", sub);
        } else {
            jdbc.update("update stl_bill set status=? where sub_order_no=?", originalStatus, sub);
        }
    }

    /** H2 把 BOOLEAN 列按 MySQL 模式读成 0/1 */
    private static boolean flag(Object value) {
        return value instanceof Boolean b ? b : ((Number) value).intValue() != 0;
    }

    private Map<String, Object> journal(String afterSale) {
        return jdbc.queryForList("select goods_minor, merchant_reverse_minor, final_refund, pre_split from stl_bill_refund"
                + " where after_sale_no=?", afterSale).stream().findFirst().orElse(null);
    }

    private Map<String, Object> bill(String sub) {
        return jdbc.queryForMap("select status, net_minor, refunded_goods_minor from stl_bill where sub_order_no=?",
                sub);
    }

    private String phase(String executionNo) {
        return jdbc.queryForObject("select phase from ord_refund_execution where execution_no=?", String.class,
                executionNo);
    }

    private String col(String sub, String column) {
        return jdbc.queryForObject("select " + column + " from ord_sub_order where sub_order_no = ?", String.class, sub);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    /** 买 qty 件同一 SKU、自提、付款、备货（申请走商家审核，审核后才发起退款） */
    private String buy(String token, int qty, String key) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":%d}".formatted(qty)))
                .andExpect(status().isOk());
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String payNo = json.readTree(body).get("data").get("payOrderNo").asString();
        new CashReceiptFixture(context).pay(token, payNo);
        String sub = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no = ?",
                String.class, payNo);
        merchantOrders.prepare(col(sub, "entity_no"), col(sub, "store_no"), sub);
        return sub;
    }
}
