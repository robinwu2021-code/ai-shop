package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.CashReceiptFixture;
import ai.neargo.shop.trade.service.AfterSaleService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 5.5 行级申请与退款账本（TDD-订单状态机 AC28–AC31）。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("行级退款 · 申请与执行")
class LineRefundFlowTest {

    @Autowired
    private WebApplicationContext context;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AfterSaleService afterSales;
    @Autowired
    private ai.neargo.shop.trade.service.MerchantOrderService merchantOrders;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("一期拒绝新增部分退款；全子单退款确认到账后一次记完整件数")
    void partialRejectedThenFullConfirmedOnce() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155001");
        String sub = buy(token, 3, "line-full");
        long itemId = jdbc.queryForObject("select id from ord_item where sub_order_no=?", Long.class, sub);
        JsonNode partial = apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\","
                + "\"reasonCode\":\"NOT_WANTED\",\"items\":[{\"itemId\":" + itemId + ",\"qty\":1}]}");
        assertThat(partial.path("code").asInt()).isEqualTo(20011);
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no=?",
                Integer.class, sub)).isZero();
        JsonNode full = apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\","
                + "\"reasonCode\":\"NOT_WANTED\"}");
        assertThat(full.path("code").asInt()).isZero();
        String no = full.path("data").path("afterSaleNo").asString();
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                afterSales.approve(entityOf(sub), no, null, 1L, "CASH"))
                .isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?", String.class, no))
                .isEqualTo("APPLIED");
        afterSales.approve(entityOf(sub), no, null, null, null);
        assertThat(jdbc.queryForObject("select refunded_qty from ord_item where id=?", Integer.class, itemId))
                .isZero();
        CashReceiptFixture receipts = new CashReceiptFixture(context);
        receipts.confirmRefund(no);
        receipts.confirmRefund(no);
        assertThat(subStatus(sub)).isEqualTo("REFUNDED");
        assertThat(col(sub, "refund_progress")).isEqualTo("FULL");
        assertThat(jdbc.queryForObject("select refunded_qty from ord_item where id=?", Integer.class, itemId))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from stl_payment where sub_order_no=?"
                + " and direction='REFUND'", Integer.class, sub)).isOne();
    }

    @Test
    @DisplayName("★★ 售后期：完成 8 天后申请非质量原因 → 20012；质量原因 15 天内可申请")
    void windowGuard() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155002");
        String sub = buy(token, 1, "line-window");
        jdbc.update("update ord_sub_order set status = 'COMPLETED', completed_at = ? where sub_order_no = ?",
                System.currentTimeMillis() - 8 * 86_400_000L, sub);
        assertThat(apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}")
                .get("code").asInt()).isEqualTo(20012);
        assertThat(apply(token, sub, "{\"type\":\"RETURN_REFUND\",\"reason\":\"质量问题\",\"reasonCode\":\"QUALITY\"}")
                .get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("★ 选的件数超过剩余 → 20011，不建单")
    void overQtyRejected() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155003");
        String sub = buy(token, 1, "line-over");
        long itemId = jdbc.queryForObject("select id from ord_item where sub_order_no = ?", Long.class, sub);
        assertThat(apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\","
                + "\"items\":[{\"itemId\":" + itemId + ",\"qty\":2}]}").get("code").asInt()).isEqualTo(20011);
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no = ?", Integer.class, sub)).isZero();
    }

    @Test
    @DisplayName("★★★ 5.6 商家同意与驳回同时到达：恰一方成功，状态与退款流水一致（不会退了钱又显示已驳回）")
    void approveAndRejectRace() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155004");
        String sub = buy(token, 1, "line-race");
        String asNo = apply(token, sub, "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}")
                .get("data").get("afterSaleNo").asString();
        String merchant = entityOf(sub);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Boolean> approve = () -> {
            start.await();
            try {
                afterSales.approve(merchant, asNo, null, null, null);
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        java.util.concurrent.Callable<Boolean> reject = () -> {
            start.await();
            try {
                afterSales.reject(merchant, asNo, "不符合");
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        };
        var a = pool.submit(approve);
        var r = pool.submit(reject);
        start.countDown();
        boolean approved = a.get();
        boolean rejected = r.get();
        pool.shutdown();
        assertThat(approved ^ rejected).as("必须恰一方成功").isTrue();
        String status = jdbc.queryForObject("select status from ord_after_sale where after_sale_no = ?", String.class, asNo);
        int refunds = jdbc.queryForObject("select count(*) from stl_payment where after_sale_no = ? and direction = 'REFUND'",
                Integer.class, asNo);
        assertThat(status).isEqualTo(approved ? "REFUNDING" : "REJECTED");
        if (approved) {
            new CashReceiptFixture(context).confirmRefund(asNo);
        }
        assertThat(refunds).isEqualTo(approved ? 1 : 0);
    }

    @Test
    @DisplayName("★★★ 同一订单两次申请同时到达：只建成一张（库内占位，不靠先查后插）；驳回后可再申请，旧单再介入即冲突")
    void concurrentApplyOccupiesOnce() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13400155005");
        String sub = buy(token, 2, "line-slot");
        String body = "{\"type\":\"REFUND_ONLY\",\"reason\":\"不想要了\",\"reasonCode\":\"NOT_WANTED\"}";
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.Callable<Integer> task = () -> {
            start.await();
            return apply(token, sub, body).get("code").asInt();
        };
        var a = pool.submit(task);
        var b = pool.submit(task);
        start.countDown();
        int ca = a.get();
        int cb = b.get();
        pool.shutdown();
        assertThat((ca == 0) ^ (cb == 0)).as("必须恰一张建成：%s / %s", ca, cb).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from ord_after_sale where sub_order_no = ?", Integer.class, sub)).isOne();

        String first = jdbc.queryForObject("select after_sale_no from ord_after_sale where sub_order_no = ?", String.class, sub);
        afterSales.reject(entityOf(sub), first, "不符合");
        assertThat(col(sub, "open_after_sale_no")).as("驳回即释放").isNull();
        assertThat(apply(token, sub, body).get("code").asInt()).as("驳回后可再申请").isZero();
        assertThat(mvc().perform(post("/mp/after-sale/" + first + "/escalate").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appeal\":\"不服\"}"))
                .andReturn().getResponse().getContentAsString()).as("新单进行中，旧单再介入 → 冲突").contains("\"code\":10409");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode apply(String token, String sub, String body) throws Exception {
        return json.readTree(mvc().perform(post("/mp/order/" + sub + "/after-sale").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse().getContentAsString());
    }

    private String subStatus(String sub) {
        return col(sub, "status");
    }

    private String col(String sub, String column) {
        return jdbc.queryForObject("select " + column + " from ord_sub_order where sub_order_no = ?", String.class, sub);
    }

    private String entityOf(String sub) {
        return col(sub, "entity_no");
    }

    /** 买 qty 件同一个 SKU、自提、付款，返回子单号。商家设为直连：售后由商家审（归集的进平台裁决） */
    private String buy(String token, int qty, String key) throws Exception {
        jdbc.update("update mch_entity set funds_mode = 'DIRECT' where entity_no = 'M0001'");
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
        String sub = jdbc.queryForObject("select sub_order_no from ord_sub_order where order_no = ?", String.class, payNo);
        // 本组验证商家审核及驳回；未备货买家申请会直接进入合法退款例外。
        merchantOrders.prepare(entityOf(sub), jdbc.queryForObject(
                "select store_no from ord_sub_order where sub_order_no=?", String.class, sub), sub);
        return sub;
    }
}
