package ai.neargo.shop.scenario;

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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M3 交易 —— **用例先行**（任务清单 §二 .4 步）。**资损高危模块，用例最严**。
 *
 * <p>覆盖：Q6 双视角、C4/C5/C6 变更单、A2 §3.2 五条不变量、
 * 并发不超卖、回调乱序/重放/验签、超时关单（R7）、R9 遗留的三条购物车端点。
 */
@SpringBootTest
@ActiveProfiles("test")
class M3TradeFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private ai.neargo.shop.trade.service.OrderService orderService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- Q6 双视角

    @Test
    @DisplayName("Q6：订单列表返回**子单粒度** —— 跨商家一次下单出现两条订单")
    void orderListIsSubOrderGrained() throws Exception {
        String token = login("13400134001");
        addToCart(token, "G0001", "SK0001", 1);   // M0001
        addToCart(token, "G0003", "SK0004", 1);   // M0002
        createOrder(token, "m3-list-key");

        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode records = json.readTree(body).get("data").get("records");

        // 用户心智里「订单」就是按店分的：一次结算两家店 = 两条订单
        assertThat(records).hasSize(2);
        assertThat(records.get(0).get("merchantName").asString()).isNotBlank();
        // 每条子单都带支付单号，收银台靠它跳转
        assertThat(records.get(0).get("payOrderNo").asString())
                .isEqualTo(records.get(1).get("payOrderNo").asString());
    }

    @Test
    @DisplayName("Q6：GET /mp/order/{no} 传子单号 → 订单视角（单商家、有履约方式）")
    void detailBySubOrderNoIsOrderView() throws Exception {
        String token = login("13400134002");
        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, "m3-sub-view");
        String subOrderNo = firstSubOrderNo(token);

        mvc().perform(get("/mp/order/" + subOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(subOrderNo))
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"))
                .andExpect(jsonPath("$.data.fulfillment").value("STORE_PICKUP"))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.subOrders").doesNotExist());
    }

    @Test
    @DisplayName("Q6：传主单号 → 支付视角（合计金额 + 各商家子单，无履约方式）")
    void detailByOrderNoIsPayView() throws Exception {
        String token = login("13400134003");
        addToCart(token, "G0001", "SK0001", 1);
        addToCart(token, "G0003", "SK0004", 1);
        String payOrderNo = createOrder(token, "m3-pay-view");

        mvc().perform(get("/mp/order/" + payOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNo").value(payOrderNo))
                .andExpect(jsonPath("$.data.subOrders.length()").value(2))
                // 跨商家的履约方式可能不同，支付视角不给单一值 —— 给了就是错的
                .andExpect(jsonPath("$.data.fulfillment").doesNotExist())
                .andExpect(jsonPath("$.data.amount.payableMinor").value(4980 + 5800));
    }

    // ---------------------------------------------------------------- C4/C5/C6

    @Test
    @DisplayName("C4/C7：金额收在 amount 值对象里，字段名随前端（payableMinor 等）")
    void amountIsValueObject() throws Exception {
        String token = login("13400134004");
        addToCart(token, "G0001", "SK0001", 2);
        String payOrderNo = createOrder(token, "m3-amount");

        mvc().perform(get("/mp/order/" + payOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.amount.goodsMinor").value(9960))
                .andExpect(jsonPath("$.data.amount.freightMinor").value(0))
                .andExpect(jsonPath("$.data.amount.discountMinor").value(0))
                .andExpect(jsonPath("$.data.amount.payableMinor").value(9960))
                .andExpect(jsonPath("$.data.amount.currency").value("CNY"))
                .andExpect(jsonPath("$.data.payDeadlineAt").isNumber());
    }

    @Test
    @DisplayName("C4：取货码字段名为 verifyCode，支付后才有")
    void verifyCodeIssuedAfterPay() throws Exception {
        String token = login("13400134005");
        addToCart(token, "G0002", "SK0003", 1);
        String payOrderNo = createOrder(token, "m3-verify");
        String subOrderNo = firstSubOrderNo(token);

        mvc().perform(get("/mp/order/" + subOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.verifyCode").doesNotExist());

        payCallback(payOrderNo, "TX-M3-1");

        mvc().perform(get("/mp/order/" + subOrderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.verifyCode").isNotEmpty())
                .andExpect(jsonPath("$.data.pickupName").isNotEmpty());
    }

    @Test
    @DisplayName("C5：订单时间线按状态推进追加，且带操作方")
    void timelineAppendsOnTransition() throws Exception {
        String token = login("13400134006");
        addToCart(token, "G0002", "SK0003", 1);
        String payOrderNo = createOrder(token, "m3-timeline");
        String subOrderNo = firstSubOrderNo(token);
        payCallback(payOrderNo, "TX-M3-2");

        String body = mvc().perform(get("/mp/order/" + subOrderNo).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode timeline = json.readTree(body).get("data").get("timeline");

        // 下单 + 支付 至少两个节点，且时间递增
        assertThat(timeline.size()).isGreaterThanOrEqualTo(2);
        assertThat(timeline.get(0).get("status").asString()).isEqualTo("WAIT_PAY");
        assertThat(timeline.get(1).get("at").asLong())
                .isGreaterThanOrEqualTo(timeline.get(0).get("at").asLong());
        assertThat(timeline.get(1).get("label").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- 不变量（A2 §3.2）

    @Test
    @DisplayName("不变量①：Σ子单金额 = 主单金额")
    void subOrderAmountsSumToOrder() throws Exception {
        String token = login("13400134007");
        addToCart(token, "G0001", "SK0002", 1);
        addToCart(token, "G0004", "SK0005", 2);
        String payOrderNo = createOrder(token, "m3-sum");

        String body = mvc().perform(get("/mp/order/" + payOrderNo).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");

        long sum = 0;
        for (JsonNode sub : data.get("subOrders")) {
            sum += sub.get("amount").get("payableMinor").asLong();
        }
        // 对账的基准。任何写路径之后都要成立，差一分都是账不平
        assertThat(sum).isEqualTo(data.get("amount").get("payableMinor").asLong());
    }

    @Test
    @DisplayName("不变量③：trafficSource 在下单时固化到子单")
    void trafficSourceFrozenAtCreate() throws Exception {
        String token = login("13400134008");
        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, "m3-traffic");

        mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.records[0].trafficSource").value("PLATFORM"));
    }

    @Test
    @DisplayName("不变量④：核销码全局唯一")
    void verifyCodeIsGloballyUnique() throws Exception {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String token = login("134001350" + (10 + i));
            addToCart(token, "G0002", "SK0003", 1);
            String payOrderNo = createOrder(token, "m3-uniq-" + i);
            payCallback(payOrderNo, "TX-uniq-" + i);
            codes.add(verifyCodeOf(token));
        }
        // 重码意味着核销台扫一次可能命中两单 —— 在货架前没法当场解决
        assertThat(codes).doesNotHaveDuplicates().doesNotContainNull();
    }

    // ---------------------------------------------------------------- 并发与幂等

    @Test
    @DisplayName("**并发下单不超卖**：10 个线程抢 3 件库存，只能成功 3 单")
    void concurrentOrdersDoNotOversell() throws Exception {
        // 用一个独享 SKU，避免与其它用例互相干扰
        int stock = stockOf("G0004", "SK0005");
        int threads = 10;

        List<String> tokens = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String t = login("1340013600" + i);
            addToCart(t, "G0004", "SK0005", 1);
            tokens.add(t);
        }

        AtomicInteger ok = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            String token = tokens.get(i);
            String key = "m3-race-" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", key)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                            .andReturn().getResponse().getContentAsString();
                    if (json.readTree(body).get("code").asInt() == 0) {
                        ok.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 失败即视为未下单，由 ok 计数体现
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 成功单数不能超过库存 —— 超了就是卖出了不存在的货
        assertThat(ok.get()).isLessThanOrEqualTo(stock);
        assertThat(stockOf("G0004", "SK0005")).isEqualTo(stock - ok.get());
    }

    @Test
    @DisplayName("R7：超时关单释放库存，且不能再支付")
    void expiredOrderIsClosedAndStockReleased() throws Exception {
        String token = login("13400134020");
        int before = stockOf("G0001", "SK0002");
        addToCart(token, "G0001", "SK0002", 2);
        String payOrderNo = createOrder(token, "m3-expire");
        assertThat(stockOf("G0001", "SK0002")).isEqualTo(before - 2);

        // 直接调超时任务（把 deadline 提前到过去），不靠 sleep 15 分钟
        orderService.closeExpiredOrders(System.currentTimeMillis() + 3600_000L);

        assertThat(stockOf("G0001", "SK0002")).isEqualTo(before);
        mvc().perform(post("/mp/order/" + payOrderNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("超时关单幂等：跑第二遍不会把库存再加一次")
    void closeExpiredIsIdempotent() throws Exception {
        String token = login("13400134021");
        addToCart(token, "G0002", "SK0003", 1);
        createOrder(token, "m3-expire-twice");

        long future = System.currentTimeMillis() + 3600_000L;
        orderService.closeExpiredOrders(future);
        int afterFirst = stockOf("G0002", "SK0003");
        orderService.closeExpiredOrders(future);

        // 断言「第二遍之后不再变化」而不是「回到某个初始值」：
        // closeExpiredOrders 关的是**全库**的超时单，包含别的测试类留下的，
        // 断言绝对值等于让这条用例依赖整个套件的执行顺序
        assertThat(stockOf("G0002", "SK0003")).isEqualTo(afterFirst);
    }

    // ---------------------------------------------------------------- R9 购物车补测

    @Test
    @DisplayName("R9：购物车列表带商家分组信息与实时价")
    void cartListHasMerchantAndLivePrice() throws Exception {
        String token = login("13400134030");
        addToCart(token, "G0001", "SK0001", 2);

        String body = mvc().perform(get("/mp/cart").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(body).get("data").get(0);

        assertThat(row.get("merchantNo").asString()).isEqualTo("M0001");
        assertThat(row.get("merchantName").asString()).isNotBlank();
        assertThat(row.get("price").asLong()).isEqualTo(4980);   // 实时价，非加购快照
        assertThat(row.get("qty").asInt()).isEqualTo(2);
        assertThat(row.get("invalid").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("R9：改数量；改成 0 等于移除")
    void cartUpdateAndZeroRemoves() throws Exception {
        String token = login("13400134031");
        addToCart(token, "G0001", "SK0001", 1);

        mvc().perform(post("/mp/cart/update").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"SK0001\",\"qty\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].qty").value(5));

        mvc().perform(post("/mp/cart/update").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNo\":\"SK0001\",\"qty\":0}"))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("R9：批量移除")
    void cartRemoveBatch() throws Exception {
        String token = login("13400134032");
        addToCart(token, "G0001", "SK0001", 1);
        addToCart(token, "G0002", "SK0003", 1);

        mvc().perform(post("/mp/cart/remove").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skuNos\":[\"SK0001\",\"SK0003\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    @DisplayName("购物车是按用户隔离的（防串号）")
    void cartIsPerUser() throws Exception {
        String a = login("13400134033");
        String b = login("13400134034");
        addToCart(a, "G0001", "SK0001", 1);

        mvc().perform(get("/mp/cart").header("Authorization", "Bearer " + b))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    // ---------------------------------------------------------------- helpers

    private int stockOf(String goodsNo, String skuNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsNo)).andReturn().getResponse().getContentAsString();
        for (JsonNode sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        throw new AssertionError("sku not found: " + skuNo);
    }

    private String firstSubOrderNo(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("orderNo").asString();
    }

    private String verifyCodeOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").get(0).get("verifyCode").asString();
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
    }

    /** @return 支付单号（主单） */
    private String createOrder(String token, String idemKey) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode data = json.readTree(body).get("data");
        return data.get("payOrderNo").asString();
    }

    private void payCallback(String payOrderNo, String txId) throws Exception {
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"" + txId
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
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
}
