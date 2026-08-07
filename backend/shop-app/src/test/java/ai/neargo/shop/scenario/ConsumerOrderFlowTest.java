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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易闭环（S2 起）：加购 → 预览拆单 → 下单锁库存 → 支付回调 → 核销码。
 *
 * <p>M3 之后本文件按 Q6 新契约调整（`payOrderNo` / `amount.payableMinor` / `verifyCode`），
 * 并**删去与 {@link M3TradeFlowTest} 重复的用例**，只保留这里独有的四条：
 * 回调乱序、验签失败、下单幂等、越权读单。重复的用例不是多一层保险，
 * 是多一处改契约时要同步的地方。
 */
@SpringBootTest
@ActiveProfiles("test")
class ConsumerOrderFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private ai.neargo.shop.event.SysOutboxMapper outboxMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("跨商家购物车拆成两个子单，各自算钱（分账的前提 E3）")
    void crossMerchantCartSplitsIntoSubOrders() throws Exception {
        String token = login("13700137000");
        addToCart(token, "G0001", "SK0001", 2);   // M0001 老张粮油
        addToCart(token, "G0003", "SK0004", 1);   // M0002 鲜果直供

        String body = mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("subOrders")).hasSize(2);
        // 4980×2 + 5800 = 15760
        assertThat(data.get("amount").get("payableMinor").asLong()).isEqualTo(4980L * 2 + 5800L);
        assertThat(data.get("subOrders").get(0).get("amount").get("payableMinor").asLong()).isEqualTo(9960L);
        assertThat(data.get("subOrders").get(1).get("amount").get("payableMinor").asLong()).isEqualTo(5800L);
    }

    @Test
    @DisplayName("下单 → 支付回调 → 子单进入待履约并生成取货码")
    void payThenPickupCodeIssued() throws Exception {
        String token = login("13700137001");
        addToCart(token, "G0002", "SK0003", 1);
        String orderNo = createOrder(token, "idem-pay-001");

        // 支付前没有取货码 —— 未付款的订单不该有能核销的码
        mvc().perform(get("/mp/order/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"))
                .andExpect(jsonPath("$.data.subOrders[0].verifyCode").doesNotExist());

        mvc().perform(post("/mp/order/" + orderNo + "/pay").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.payParams.prepayId").isNotEmpty());

        payCallback(orderNo, "TX-001");

        mvc().perform(get("/mp/order/" + orderNo + "/pay-result").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("PAID"))
                .andExpect(jsonPath("$.data.subOrders[0].status").value("WAIT_FULFILL"))
                .andExpect(jsonPath("$.data.subOrders[0].verifyCode").isNotEmpty())
                // 归因在下单时固化（S2 恒为 PLATFORM，S4 接店铺码后才有 MERCHANT_OWNED）
                .andExpect(jsonPath("$.data.subOrders[0].trafficSource").value("PLATFORM"));
    }

    @Test
    @DisplayName("重复回调只产生一个 ORDER_PAID 事件（回调必然重发）")
    void duplicateCallbackIsIdempotent() throws Exception {
        String token = login("13700137002");
        addToCart(token, "G0002", "SK0003", 1);
        String orderNo = createOrder(token, "idem-dup-cb");

        payCallback(orderNo, "TX-002");
        payCallback(orderNo, "TX-002");   // 支付服务商必然重发

        // 用事件条数而不是库存来断言：库存的确认扣减本身带 `locked_stock >= qty` 守卫，
        // 重复执行也看不出差别；但**事件重复才是真正的灾难** ——
        // 下游 settle 会按 ORDER_PAID 生成结算单，两条事件 = 给商家分两次钱
        long paidEvents = outboxMapper.selectCount(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .lambdaQuery(ai.neargo.shop.event.SysOutbox.class)
                .eq(ai.neargo.shop.event.SysOutbox::getAggregateId, orderNo)
                .eq(ai.neargo.shop.event.SysOutbox::getEventType, "ORDER_PAID"));
        assertThat(paidEvents).isEqualTo(1);
    }

    @Test
    @DisplayName("同一 Idempotency-Key 重复提交只产生一个订单")
    void duplicateSubmitCreatesOneOrder() throws Exception {
        String token = login("13700137003");
        addToCart(token, "G0004", "SK0005", 1);

        String first = createOrder(token, "idem-same-key");
        // 第二次提交购物车已空，若幂等失效会因「无可下单商品」报错而不是返回原单
        String second = createOrder(token, "idem-same-key");

        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("下单锁定库存、取消释放库存（可售随之增减）")
    void stockLockedOnCreateAndReleasedOnCancel() throws Exception {
        String token = login("13700137004");
        int initial = availableOf("SK0002");

        addToCart(token, "G0001", "SK0002", 3);
        String orderNo = createOrder(token, "idem-stock-001");
        assertThat(availableOf("SK0002")).isEqualTo(initial - 3);

        mvc().perform(post("/mp/order/" + orderNo + "/cancel").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不想要了\"}"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        assertThat(availableOf("SK0002")).isEqualTo(initial);
    }

    @Test
    @DisplayName("已取消的订单不能被支付回调改成已支付（回调只推进不回退）")
    void cancelledOrderRejectsLatePayCallback() throws Exception {
        String token = login("13700137005");
        addToCart(token, "G0002", "SK0003", 1);
        String orderNo = createOrder(token, "idem-late-cb");

        mvc().perform(post("/mp/order/" + orderNo + "/cancel").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"改主意\"}"));

        // 迟到的支付回调：状态机拒绝 CANCELLED → PAID
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"TX-late\",\"sign\":\""
                                + STUB_SECRET + "\"}"))
                .andExpect(jsonPath("$.code").value(20004));

        mvc().perform(get("/mp/order/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));
    }

    @Test
    @DisplayName("别人的订单查不到（属主鉴权，防 IDOR）")
    void cannotReadOthersOrder() throws Exception {
        String owner = login("13700137006");
        addToCart(owner, "G0002", "SK0003", 1);
        String orderNo = createOrder(owner, "idem-idor");

        String stranger = login("13700137007");
        mvc().perform(get("/mp/order/" + orderNo).header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("验签失败的回调不改任何状态")
    void badSignCallbackIsRejected() throws Exception {
        String token = login("13700137008");
        addToCart(token, "G0002", "SK0003", 1);
        String orderNo = createOrder(token, "idem-badsign");

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"X\",\"sign\":\"wrong\"}"));

        mvc().perform(get("/mp/order/" + orderNo).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.status").value("WAIT_PAY"));
    }

    // ---------------------------------------------------------------- helpers

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String createOrder(String token, String idemKey) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("payOrderNo").asString();
    }

    private void payCallback(String orderNo, String txId) throws Exception {
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"" + txId
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
    }

    private int availableOf(String skuNo) throws Exception {
        String body = mvc().perform(get("/mp/goods/" + goodsOf(skuNo))).andReturn().getResponse().getContentAsString();
        for (JsonNode sku : json.readTree(body).get("data").get("skus")) {
            if (skuNo.equals(sku.get("skuNo").asString())) {
                return sku.get("stock").asInt();
            }
        }
        throw new AssertionError("sku not found: " + skuNo);
    }

    private String goodsOf(String skuNo) {
        return switch (skuNo) {
            case "SK0001", "SK0002" -> "G0001";
            case "SK0003" -> "G0002";
            case "SK0004" -> "G0003";
            default -> "G0004";
        };
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
