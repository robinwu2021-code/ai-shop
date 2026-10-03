package ai.neargo.shop.scenario;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.trade.job.OrderAutoCloseJob;
import ai.neargo.shop.trade.service.CloseRuleService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 关单策略（P-4.2.3）：配置 → 下单盖章 → 定时任务真的关掉它。
 *
 * <p><b>这条旅程要证明的事只有一件：这份配置不是摆设。</b>
 * 在此之前，{@code /orders?tab=close} 是一个读得回来、能编辑、有保存按钮的表单，
 * 而两条端点都不存在（保存点下去 404）；<b>并且</b>
 * {@code OrderService.closeExpiredOrders} 早就写好了却<b>没有任何生产调用方</b> ——
 * 只有 {@code M3TradeFlowTest} 在手动调它。
 *
 * <p>所以这里刻意<b>不</b>直接调 {@code closeExpiredOrders}：那正是上一版测试
 * 替调度器站岗、把缺口盖住的写法。这里调的是 {@link OrderAutoCloseJob#close()}，
 * 也就是真实部署里唯一会被触发的那个入口。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("关单策略")
class OrderCloseRuleFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private CloseRuleService closeRuleService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private JobSupport jobs;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;

    /**
     * 把一笔单的 {@code pay_deadline_at} 拨到过去 —— 相当于「时间真的过去了」。
     *
     * <p><b>不改 Job 去接受一个可注入的 now</b>：那会为了测试在生产代码上开一个
     * 「传进来的时间」入口，而关单是不可逆写操作，那个入口传错就是批量误关。
     * 拨数据比拨时钟安全，而且拨的正是 Job 真正读的那一列。
     */
    private void backdate(String orderNo) {
        int rows = jdbc.update("update ord_order set pay_deadline_at = ? where order_no = ?",
                System.currentTimeMillis() - 60_000L, orderNo);
        assertThat(rows).as("没有拨到任何一行 —— 单号 %s 不在 ord_order 里？", orderNo).isEqualTo(1);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    /**
     * 手工 new 出 Job，而不是 {@code @Autowired}。
     *
     * <p>它是 {@code @Profile("worker")} 的 —— 测试跑的是 {@code test} 组
     * （h2db+testcfg+api+ops），这个 Bean 在容器里<b>根本不存在</b>。
     * 注入不到会直接启动失败，而<b>更坏的写法是加个 {@code required=false} 然后跳过</b>：
     * 那会得到一条永远不执行、永远绿的测试。
     *
     * <p>new 出来跑的是同一段方法体，验的是「Job 的入口真的会关单」这件事本身；
     * 「它有没有被调度器挂上」由 {@code ScheduledJobConventionTest} 那边守。
     */
    private OrderAutoCloseJob job() {
        return new OrderAutoCloseJob(orderService, jobs);
    }

    // ---------------------------------------------------------------- 读写

    @Test
    @DisplayName("★★ 没配过时返回出厂默认值 15 分钟 —— 参数表少一行不该让整个页面打不开")
    void defaultsWhenNeverConfigured() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        mvc().perform(get("/ops/payments/close-rule").header("Authorization", "Bearer " + ops))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unpaidMinutes")
                        .value(CloseRuleService.DEFAULT_UNPAID_MINUTES))
                .andExpect(jsonPath("$.data.autoRefundOnLateCallback").value(false));
    }

    @Test
    @DisplayName("★★ 保存后读回是新值，且 updatedBy 是操作人 —— 改参数会改变历史数据的呈现，留痕不是可选项")
    void savedValueIsReadBackWithOperator() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        save(ops, 30, 5, true).andExpect(status().isOk());

        String body = mvc().perform(get("/ops/payments/close-rule")
                        .header("Authorization", "Bearer " + ops))
                .andReturn().getResponse().getContentAsString();
        JsonNode d = json.readTree(body).get("data");
        assertThat(d.get("unpaidMinutes").asInt()).isEqualTo(30);
        assertThat(d.get("remindBeforeMinutes").asInt()).isEqualTo(5);
        assertThat(d.get("autoRefundOnLateCallback").asBoolean()).isTrue();
        assertThat(d.get("updatedBy").asString()).isNotBlank();
        assertThat(d.get("updatedAt").asString()).isNotBlank();
    }

    @Test
    @DisplayName("★★ 上下限：太短(4)、太长(9999)、提醒≥关单时长 都要拒绝")
    void boundsAreEnforced() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        // 4 分钟：再短会把正在输密码的人关掉，等于自己制造掉单
        save(ops, CloseRuleService.MIN_UNPAID_MINUTES - 1, 0, false)
                .andExpect(jsonPath("$.code").value(not200()));
        save(ops, CloseRuleService.MAX_UNPAID_MINUTES + 1, 0, false)
                .andExpect(jsonPath("$.code").value(not200()));
        // 提醒提前量 == 关单时长：提醒会发在下单那一刻，等于永远不提醒
        save(ops, 10, 10, false).andExpect(jsonPath("$.code").value(not200()));
        // 边界本身是允许的 —— 上下限要「含」，否则文案说 5~1440 而 5 存不进去
        save(ops, CloseRuleService.MIN_UNPAID_MINUTES, 0, false).andExpect(status().isOk());
        save(ops, CloseRuleService.MAX_UNPAID_MINUTES, 0, false).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- 真正的那条

    @Test
    @DisplayName("★★★ 改配置后新下的单按新时长盖章，Job 跑一轮真的把它关掉 —— 唯一能证明这份配置不是摆设的测试")
    void configActuallyDrivesClosing() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        save(ops, CloseRuleService.MIN_UNPAID_MINUTES, 0, false).andExpect(status().isOk());

        String token = TestLogin.consumer(mvc(), json, otpStore, "13400135001");
        addToCart(token, "G0001", "SK0001", 1);
        String orderNo = createOrder(token, "close-rule-drives");

        /*
         * 先证明「章」是按新配置盖的。
         *
         * 不省这一步直接跳到关单：那样的话，把 unpaidMinutes 接错成常量 15
         * 这条测试**照样绿** —— 因为下面推进的是一小时，15 分钟和 5 分钟都早就过了。
         * 断言必须能分辨出这两种实现，否则它只是在测「Job 会关过期单」，
         * 而那件事 M3TradeFlowTest 已经测过了。
         */
        long deadline = payDeadlineOf(token, orderNo);
        long ttlMinutes = Math.round((deadline - System.currentTimeMillis()) / 60_000.0);
        assertThat(ttlMinutes)
                .as("下单时的 payDeadlineAt 应按运营刚配的 %d 分钟盖章，实际约 %d 分钟 —— "
                        + "等于 %d 说明下单链路还在用写死的常量，配置存了但没人读",
                        CloseRuleService.MIN_UNPAID_MINUTES, ttlMinutes,
                        CloseRuleService.DEFAULT_UNPAID_MINUTES)
                .isEqualTo(CloseRuleService.MIN_UNPAID_MINUTES);

        // 到点前不该动它 —— 关早了就是在制造掉单
        job().close();
        assertThat(statusOf(token, orderNo))
                .as("还没到 %d 分钟就被关掉了", CloseRuleService.MIN_UNPAID_MINUTES)
                .isEqualTo("WAIT_PAY");

        /*
         * 到点后必须真的被关。
         *
         * **这一条是红检逼出来的**：上一版到上面那句就结束了，
         * 于是把 Job 的方法体换成 `int n = 0;`（什么都不做）——**整套测试照样全绿**。
         * 只断言「不该关的没关」，对一个什么都不做的实现是完全无害的，
         * 而那恰恰就是这次要修的缺陷（closeExpiredOrders 写好了没人调）本身的形状。
         */
        backdate(orderNo);
        job().close();
        assertThat(statusOf(token, orderNo))
                .as("到点了 Job 却没关它 —— 待支付单会永远堆着，库存/券/积分一并占住")
                .isEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★★ Job 只关到期的 WAIT_PAY —— 关单不可逆，扫错范围会关掉已付款的订单")
    void jobOnlyClosesExpiredUnpaidOrders() throws Exception {
        String ops = TestLogin.admin(mvc(), json);
        save(ops, CloseRuleService.DEFAULT_UNPAID_MINUTES, 0, false).andExpect(status().isOk());

        String token = TestLogin.consumer(mvc(), json, otpStore, "13400135002");
        addToCart(token, "G0002", "SK0003", 1);
        String fresh = createOrder(token, "close-rule-fresh");

        // 跑一轮：新单没到期，不该被碰
        job().close();
        assertThat(statusOf(token, fresh)).isEqualTo("WAIT_PAY");

        /*
         * 已支付的单必须活下来。
         *
         * 这一条比「未到期的不关」更重要：未到期只是早了几分钟，
         * 而**关掉一笔已付款的订单**意味着货没发、钱已收，且状态回不去。
         */
        String paid = createPaidOrder(token, "close-rule-paid");
        // **也拨到过去**：不拨的话它是靠「没到期」活下来的，
        // 而要证明的是它靠「已支付」活下来 —— 两者能分辨，测试才有意义
        backdate(paid);
        job().close();
        assertThat(statusOf(token, paid))
                .as("已支付的订单被关单任务关掉了 —— 这是扫描范围写错的典型症状")
                .isNotEqualTo("CLOSED");
    }

    @Test
    @DisplayName("★★ C 端令牌改不了平台配置 —— 两个令牌域必须是隔开的")
    void consumerTokenCannotWrite() throws Exception {
        /*
         * 这里测的是**令牌域隔离**（ctk_ 打不开 /ops 的门），不是细粒度权限码。
         *
         * 「这条端点该挂哪个权限码」由 scripts/perm-endpoint-map.mjs 那张登记表
         * 系统性地守着 —— 每条 /ops 端点都要在表里有一条规则，
         * 一条一条在 flow 里手写 403 既盖不全，也会和那张表分叉。
         *
         * 而令牌域隔离是那张表管不到的：它假设进来的已经是运营令牌。
         */
        String consumer = TestLogin.consumer(mvc(), json, otpStore, "13400135003");
        mvc().perform(put("/ops/payments/close-rule")
                        .header("Authorization", "Bearer " + consumer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unpaidMinutes\":30,\"remindBeforeMinutes\":0,"
                                + "\"autoRefundOnLateCallback\":false}"))
                .andExpect(result -> assertThat(result.getResponse().getStatus())
                        .as("C 端令牌改动了平台的关单配置")
                        .isIn(401, 403));
    }

    // ---------------------------------------------------------------- helpers

    private org.springframework.test.web.servlet.ResultActions save(
            String opsToken, int unpaid, int remind, boolean autoRefund) throws Exception {
        return mvc().perform(put("/ops/payments/close-rule")
                .header("Authorization", "Bearer " + opsToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"unpaidMinutes\":%d,\"remindBeforeMinutes\":%d,"
                        .formatted(unpaid, remind)
                        + "\"autoRefundOnLateCallback\":" + autoRefund + "}"));
    }

    /** 业务码断言用：只要不是 0（成功）即可，具体码由 ErrorCode 决定 */
    private static org.hamcrest.Matcher<Object> not200() {
        return org.hamcrest.Matchers.not(org.hamcrest.Matchers.is(0));
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"%s\",\"skuNo\":\"%s\",\"qty\":%d}"
                                .formatted(goodsNo, skuNo, qty)))
                .andExpect(status().isOk());
    }

    private String createOrder(String token, String idempotencyKey) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        // 主单号（支付单）—— 关单扫的是主单，payDeadlineAt 也盖在主单上
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    private String createPaidOrder(String token, String idempotencyKey) throws Exception {
        addToCart(token, "G0002", "SK0003", 1);
        String orderNo = createOrder(token, idempotencyKey);
        mvc().perform(post("/mp/order/" + orderNo + "/pay")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        /*
         * **必须走回调**。只调 /pay 的话订单还停在 WAIT_PAY —— 那一步只是发起支付。
         * 第一版漏了这句，于是「已支付的单被关掉了」那条断言真的红了，
         * 而红的原因是测试没把单付掉，不是扫描范围写错。
         * 差点被当成生产缺陷去改 closeExpiredOrders。
         */
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"tx-"
                                + idempotencyKey + "\",\"sign\":\"stub-secret\"}"))
                .andExpect(status().isOk());
        return orderNo;
    }

    private long payDeadlineOf(String token, String orderNo) throws Exception {
        return detail(token, orderNo).get("payDeadlineAt").asLong();
    }

    private String statusOf(String token, String orderNo) throws Exception {
        return detail(token, orderNo).get("status").asString();
    }

    private JsonNode detail(String token, String orderNo) throws Exception {
        String body = mvc().perform(get("/mp/order/" + orderNo)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }
}
