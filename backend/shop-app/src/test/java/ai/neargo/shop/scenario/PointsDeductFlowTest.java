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

/** Legacy unquoted requests fail closed; detached cash orders never mutate retired points tables.
 * Current quote/hold/release and store acceptance protections are in RedeemServiceTest,
 * PointsOrderLiveCreationTest and PointsOrderHoldRecoveryTest.
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

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // New positive redemption coverage lives in RedeemServiceTest and PointsOrderLiveCreationTest.
    // These HTTP scenarios now guard retirement of the old unquoted protocol.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(longs={0,500,100000})
    void legacy_balance_never_authorizes_unquoted_redemption(long balance) throws Exception {
        String token=login("13700137"+String.format("%03d",balance==0?104:balance==500?103:102));
        String user=userNoOf(token); givePoints(user,balance);
        addToCart(token,"G0001","SK0001",2);
        mvc().perform(post("/mp/order").header("Authorization","Bearer "+token)
                .header("Idempotency-Key",java.util.UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"usePoints\":99999}"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(0)));
        assertThat(balanceOf(user)).isEqualTo(balance);
        assertThat(useLedgersOf(user)).isEmpty();
    }

    @Test
    void detached_cash_order_does_not_read_or_debit_legacy_balance() throws Exception {
        String token=login("13700137005"); String user=userNoOf(token);
        givePoints(user,10000); addToCart(token,"G0001","SK0001",2);
        JsonNode order=placeOrder(token,0);
        assertThat(order.get("amount").get("pointsDeductMinor").asLong()).isZero();
        assertThat(order.get("amount").get("discountMinor").asLong()).isZero();
        assertThat(order.get("amount").get("payableMinor").asLong()).isEqualTo(9960);
        assertThat(balanceOf(user)).isEqualTo(10000);
        assertThat(useLedgersOf(user)).isEmpty();
    }

    @Test
    void repeated_cash_cancellation_does_not_create_points() throws Exception {
        String token=login("13700137006"); String user=userNoOf(token);
        givePoints(user,10000); addToCart(token,"G0001","SK0001",2);
        String order=placeOrder(token,0).get("orderNo").asString();
        cancel(token,order);
        mvc().perform(post("/mp/order/"+order+"/cancel").header("Authorization","Bearer "+token));
        assertThat(balanceOf(user)).isEqualTo(10000);
        assertThat(useLedgersOf(user)).isEmpty();
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
