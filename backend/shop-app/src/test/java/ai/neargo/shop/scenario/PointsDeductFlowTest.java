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

/**
 * 积分抵扣接入下单。
 *
 * <p><b>这条链路此前整段空转</b>：{@code ord_sub_order.points_deduct_minor} 有列、
 * {@code OrderVO} 有字段、{@code /mp/points/deductible} 能算出「这单可抵 5 元」，
 * 而 {@code OrderServiceImpl} 里一次都没出现过 points——
 * c-app 甚至已经在传 {@code usePoints}，后端的 {@code CreateOrderReq} 没这个字段，
 * Jackson 静默丢掉。没人撞上只是因为 C 端的 {@code FEATURES.points} 关着。
 *
 * <p>所以这里守的不是「功能是否工作」，而是**钱是否对得上**，四条：
 * <ul>
 *   <li>试算说能抵多少，下单就真抵多少 —— 两处各算一次是这类缺陷的经典来源</li>
 *   <li>上限按整单算，不是逐个商家各算三成（否则拆单就能多抵一倍）</li>
 *   <li>意愿值超过余额时截断，而不是把余额扣成负数</li>
 *   <li>取消要退回，且退两次只退一次</li>
 * </ul>
 */
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
    @DisplayName("★★★ 结算页说能抵多少，下单就抵多少 —— 两处算不出同一个数是最难查的一类")
    void previewMatchesActualDeduction() throws Exception {
        String phone = "13700137001";
        openPointsSwitches();
        String token = login(phone);
        givePoints(userNoOf(token), 100_000L);

        // 4980 × 2 = 9960
        addToCart(token, "G0001", "SK0001", 2);

        long maxPoints = deductible(token, 9960L).get("maxPoints").asLong();
        // 上限 = 9960 × 30% = 2988 分钱 = 2988 积分（perMinor = 1）
        assertThat(maxPoints).isEqualTo(2988L);

        // 意愿值给满：服务端应当正好抵到上限
        JsonNode order = placeOrder(token, 100_000L);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong())
                .as("试算给的是 2988，实扣必须也是 2988")
                .isEqualTo(2988L);
    }

    @Test
    @DisplayName("★★ 抵扣从实付里扣，但不计进 discountAmount —— 那一列是营销优惠，混了会让分账拆错出资方")
    void deductionIsNotCountedAsMarketingDiscount() throws Exception {
        openPointsSwitches();
        String token = login("13700137002");
        givePoints(userNoOf(token), 100_000L);
        addToCart(token, "G0001", "SK0001", 2);

        JsonNode order = placeOrder(token, 1000L);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong()).isEqualTo(1000L);
        assertThat(order.get("amount").get("discountMinor").asLong())
                .as("积分不是营销优惠，不该出现在 discountAmount 里")
                .isEqualTo(0L);
        assertThat(order.get("amount").get("payableMinor").asLong())
                .as("实付 = 商品额 + 运费 − 优惠 − 积分抵扣")
                .isEqualTo(9960L - 1000L);
    }

    // ---------------------------------------------------------------- 四道闸

    @Test
    @DisplayName("★★ 意愿值超过余额时截断，不会把余额扣成负数")
    void wantMoreThanBalanceIsTruncated() throws Exception {
        String phone = "13700137003";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 500L);
        addToCart(token, "G0001", "SK0001", 2);

        // 上限 2988，余额只有 500 —— 取小
        JsonNode order = placeOrder(token, 99_999L);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong()).isEqualTo(500L);
        assertThat(balanceOf(userNo)).isZero();
    }

    @Test
    @DisplayName("★★ 一分不剩时照常下单，只是不抵扣 —— 抵不了不该把一笔真实成交挡掉")
    void zeroBalanceStillPlacesOrder() throws Exception {
        openPointsSwitches();
        String token = login("13700137004");
        addToCart(token, "G0001", "SK0001", 2);

        JsonNode order = placeOrder(token, 5000L);
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
    @DisplayName("★★★ 取消订单退回积分，且退两次只退一次 —— 退款链路会对同一单调多次")
    void cancelRefundsPointsIdempotently() throws Exception {
        String phone = "13700137006";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 10_000L);
        addToCart(token, "G0001", "SK0001", 2);

        JsonNode order = placeOrder(token, 1000L);
        String orderNo = order.get("orderNo").asString();
        assertThat(balanceOf(userNo)).isEqualTo(9000L);

        cancel(token, orderNo);
        assertThat(balanceOf(userNo)).isEqualTo(10_000L);

        // 再取消一次（幂等路径）：余额不能变成 11000
        mvc().perform(post("/mp/order/" + orderNo + "/cancel")
                .header("Authorization", "Bearer " + token));
        assertThat(balanceOf(userNo))
                .as("重复退回等于凭空印钱")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("USE 流水落 PENDING，且带收单方 —— 池子将来要付钱给它")
    void useLedgerCarriesAcceptor() throws Exception {
        String phone = "13700137007";
        openPointsSwitches();
        String token = login(phone);
        String userNo = userNoOf(token);
        givePoints(userNo, 10_000L);
        addToCart(token, "G0001", "SK0001", 2);
        placeOrder(token, 800L);

        List<PtsUserLedger> uses = useLedgersOf(userNo);
        assertThat(uses).hasSize(1);
        PtsUserLedger use = uses.get(0);
        assertThat(use.getPoints()).isEqualTo(-800L);
        assertThat(use.getAmountMinor()).isEqualTo(800L);
        assertThat(use.getStatus())
                .as("PENDING = 预占：订单还可能取消，池子这时不该付钱")
                .isEqualTo("PENDING");
        assertThat(use.getAcceptorMerchantNo()).isNotBlank();
        assertThat(use.getSubOrderNo())
                .as("挂在子单上，否则三家里退了一家时不知道退多少")
                .isNotBlank();
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

    private void cancel(String token, String orderNo) throws Exception {
        mvc().perform(post("/mp/order/" + orderNo + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));
    }
}
