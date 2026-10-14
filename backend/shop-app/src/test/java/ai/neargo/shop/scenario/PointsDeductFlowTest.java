package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.community.entity.CmtCommunity;
import ai.neargo.shop.community.mapper.CommunityMappers.CommunityMapper;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.pay.entity.PtsUserAccount;
import ai.neargo.shop.pay.entity.PtsUserLedger;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsAccountMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PointsLedgerMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Cash checkout rejects legacy points requests even when independent points capabilities are enabled. */
@SpringBootTest
@ActiveProfiles("test")
class PointsDeductFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


    @Autowired
    private PointsAccountMapper accountMapper;

    @Autowired
    private PointsLedgerMapper ledgerMapper;

    @Autowired
    private CommunityMapper communityMapper;

    @Autowired
    private MchEntityMapper merchantMapper;

    /**
     * 打开积分的四级开关。
     *
     * <p><b>默认全是关的</b>（{@code points_enabled DEFAULT 0}）—— 那是设计如此，
     * L1 全局 / L2 社区 / L3 商家逐级灰度。所以这批代码上线后，
     * 在有人打开开关之前一分都不会抵 —— 这是特性不是缺陷，
     * 但也意味着**光看线上没抵扣，说明不了这条链路是通是断**。
     */
    private void openPointsSwitches() {
        for (CmtCommunity c : communityMapper.selectList(null)) {
            c.setPointsEnabled(true);
            communityMapper.updateById(c);
        }
        for (MchEntity m : merchantMapper.selectList(null)) {
            m.setPointsEnabled(true);
            merchantMapper.updateById(m);
        }
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 核心：试算 == 实扣

    @Test
    @DisplayName("独立积分试算保留，订单一期拒绝抵扣且不改余额")
    void phaseOneRejectsDeductionDespiteIndependentQuote() throws Exception {
        String phone = "13700137001";
        openPointsSwitches();
        String token = login(phone);
        givePoints(userNoOf(token), 100_000L);

        // 4980 × 2 = 9960
        addToCart(token, "G0001", "SK0001", 2);

        long maxPoints = deductible(token, 9960L).get("maxPoints").asLong();
        // 上限 = 9960 × 30% = 2988 分钱 = 2988 积分（perMinor = 1）
        assertThat(maxPoints).isEqualTo(2988L);

        // 独立积分试算不会重新打开订单的抵扣能力
        rejectPoints(token, 100_000L);
        assertThat(balanceOf(userNoOf(token))).isEqualTo(100_000L);
        assertThat(useLedgersOf(userNoOf(token))).isEmpty();
    }

    @Test
    @DisplayName("现金订单不含积分抵扣或积分优惠")
    void cashOrderNeverCountsPointsAsDiscount() throws Exception {
        openPointsSwitches();
        String token = login("13700137002");
        givePoints(userNoOf(token), 100_000L);
        addToCart(token, "G0001", "SK0001", 2);

        rejectPoints(token, 1000L);
        JsonNode order = placeOrder(token, 0L);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong()).isZero();
        assertThat(order.get("amount").get("discountMinor").asLong())
                .as("积分不是营销优惠，不该出现在 discountAmount 里")
                .isEqualTo(0L);
        assertThat(order.get("amount").get("payableMinor").asLong())
                .as("实付 = 商品额 + 运费 − 优惠 − 积分抵扣")
                .isEqualTo(9960L);
    }

    // ---------------------------------------------------------------- 四道闸

    @Test
    @DisplayName("超过余额的积分请求被拒绝且余额不变")
    void excessiveRequestedPointsNeverChangeBalance() throws Exception {
        String phone = "13700137003";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 500L);
        addToCart(token, "G0001", "SK0001", 2);

        // 一期明确拒绝，不截断成实际扣减
        rejectPoints(token, 99_999L);
        assertThat(balanceOf(userNo)).isEqualTo(500L);
        assertThat(useLedgersOf(userNo)).isEmpty();
    }

    @Test
    @DisplayName("零积分余额可以正常下现金订单")
    void zeroBalanceCashOrderStillPlaces() throws Exception {
        openPointsSwitches();
        String token = login("13700137004");
        addToCart(token, "G0001", "SK0001", 2);

        rejectPoints(token, 5000L);
        JsonNode order = placeOrder(token, 0L);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong()).isZero();
        assertThat(order.get("amount").get("payableMinor").asLong()).isEqualTo(9960L);
    }

    @Test
    @DisplayName("不传 usePoints 时一分不扣，也不产生流水")
    void noUsePointsMeansNoLedger() throws Exception {
        String phone = "13700137005";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 10_000L);
        addToCart(token, "G0001", "SK0001", 2);

        placeOrder(token, 0L);
        assertThat(balanceOf(userNo)).isEqualTo(10_000L);
        assertThat(useLedgersOf(userNo)).isEmpty();
    }

    // ---------------------------------------------------------------- 退回

    @Test
    @DisplayName("现金订单取消和重复取消均不产生积分流水")
    void cashCancellationNeverCreatesPointsRefund() throws Exception {
        String phone = "13700137006";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 10_000L);
        addToCart(token, "G0001", "SK0001", 2);

        rejectPoints(token, 1000L);
        JsonNode order = placeOrder(token, 0L);
        String orderNo = order.get("payOrderNo").asString();
        assertThat(balanceOf(userNo)).isEqualTo(10_000L);

        cancel(token, orderNo);
        assertThat(balanceOf(userNo)).isEqualTo(10_000L);

        // 重复取消按旧合同拒绝状态，余额与流水仍不改变
        mvc().perform(post("/mp/order/" + orderNo + "/cancel")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"repeat cash cancel\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(20004));
        assertThat(useLedgersOf(userNo)).isEmpty();
        assertThat(balanceOf(userNo))
                .as("重复退回等于凭空印钱")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("被拒绝的积分请求不产生 USE 流水")
    void rejectedDeductionNeverCreatesUseLedger() throws Exception {
        String phone = "13700137007";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 10_000L);
        addToCart(token, "G0001", "SK0001", 2);
        rejectPoints(token, 800L);
        assertThat(useLedgersOf(userNo)).isEmpty();
        assertThat(balanceOf(userNo)).isEqualTo(10_000L);
    }

    // ---------------------------------------------------------------- 助手

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
    }

    /** 直接塞余额：发放链路有自己的用例，这里只关心「有分之后花得对不对」 */
    private void givePoints(String userNo, long points) {
        PtsUserAccount a = new PtsUserAccount();
        a.setUserNo(userNo);
        a.setBalance(points);
        a.setPendingBalance(0L);
        a.setTotalEarn(points);
        a.setTotalUse(0L);
        a.setMarket("CN");
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        accountMapper.insert(a);
    }

    private long balanceOf(String userNo) {
        PtsUserAccount a = accountMapper.selectOne(Wrappers.<PtsUserAccount>lambdaQuery()
                .eq(PtsUserAccount::getUserNo, userNo).last("LIMIT 1"));
        return a == null || a.getBalance() == null ? 0L : a.getBalance();
    }

    private List<PtsUserLedger> useLedgersOf(String userNo) {
        return ledgerMapper.selectList(Wrappers.<PtsUserLedger>lambdaQuery()
                .eq(PtsUserLedger::getUserNo, userNo)
                .eq(PtsUserLedger::getBizType, "USE"));
    }

    private void addToCart(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":"
                        + qty + "}"));
    }

    private JsonNode deductible(String token, long payableMinor) throws Exception {
        String body = mvc().perform(get("/mp/points/deductible")
                        .param("merchantNo", "M0001")
                        .param("payableMinor", String.valueOf(payableMinor))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private JsonNode placeOrder(String token, long usePoints) throws Exception {
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"usePoints\":" + usePoints + ",\"idempotencyKey\":\""
                                + java.util.UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private void rejectPoints(String token, long usePoints) throws Exception {
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\","
                                + "\"usePoints\":" + usePoints + "}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    private void cancel(String token, String orderNo) throws Exception {
        mvc().perform(post("/mp/order/" + orderNo + "/cancel")
                        .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"phase one cash cancel\"}"))
                .andExpect(jsonPath("$.code").value(0));
    }
}
