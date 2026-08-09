package ai.neargo.shop.scenario;

import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.user.mapper.UserMappers.MchEntityMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M4 履约 —— **用例先行**（任务清单 §二 .4 步）。B 端主战场。
 *
 * <p>最重要的一组是**越权防线④：字段级裁剪**（TDD-backend §5.2）。
 * 自提点承接方必然看到别家商家的货到自己点上核销，但**只能看到履约必需字段** ——
 * 金额与完整手机号在 VO 里根本不存在，不是靠条件序列化藏起来。
 */
@SpringBootTest
@ActiveProfiles("test")
class M4FulfillmentFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.user.service.OtpStore otpStore;

    @Autowired
    private MchEntityMapper merchantMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 身份与作用域

    @Test
    @DisplayName("普通用户没有经营侧作用域，/biz 一律 403（fail-closed）")
    void normalUserHasNoBizScope() throws Exception {
        String token = login("13300133001");
        // 业务异常走契约包（HTTP 200 + code），只有认证失败才用 HTTP 401
        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10403));
        mvc().perform(get("/biz/pickup/overview").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10403));
    }

    @Test
    @DisplayName("店主登录后拿到三个作用域：merchantNo + pickupNos + groupNos")
    void merchantOwnerGetsScopes() throws Exception {
        String token = loginAsOwnerOf("M0001", "13300133002");

        mvc().perform(get("/biz/context").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.merchantNo").value("M0001"))
                .andExpect(jsonPath("$.data.pickupNos.length()").value(2))   // PP0001 PP0002
                .andExpect(jsonPath("$.data.groupNos.length()").value(0));
    }

    // ---------------------------------------------------------------- 核销台

    @Test
    @DisplayName("扫码核销：子单进入 COMPLETED，核销码一次性失效")
    void verifySucceedsThenCodeIsSpent() throws Exception {
        Ordered o = placeAndPay("13300133010", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133011");

        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + o.verifyCode + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success").value(true))
                .andExpect(jsonPath("$.data.subOrderNo").value(o.subOrderNo));

        // 用户侧：订单走到 COMPLETED —— 这是自提线唯一的终态出口
        mvc().perform(get("/mp/order/" + o.subOrderNo).header("Authorization", "Bearer " + o.userToken))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // 再扫一次：明确告知已核销，而不是笼统失败
        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + o.verifyCode + "\"}"))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.reason").value("ALREADY_VERIFIED"));
    }

    @Test
    @DisplayName("非本点的码核销失败，原因明确（NOT_THIS_PICKUP）")
    void cannotVerifyOtherPickupCode() throws Exception {
        Ordered o = placeAndPay("13300133012", "G0002", "SK0003");   // 下到 PP0001
        // M0002 不承接任何自提点，给它临时挂一个别的点
        String other = loginAsOwnerOf("M0002", "13300133013");

        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + o.verifyCode + "\"}"))
                .andExpect(jsonPath("$.data.success").value(false))
                .andExpect(jsonPath("$.data.reason").value("NOT_THIS_PICKUP"));
    }

    @Test
    @DisplayName("未支付的单没有核销码，核销直接失败")
    void unpaidOrderHasNoCode() throws Exception {
        String biz = loginAsOwnerOf("M0001", "13300133014");
        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"000000\"}"))
                .andExpect(jsonPath("$.data.success").value(false));
    }

    @Test
    @DisplayName("代核销强制留痕：日志记下操作人与类型")
    void onBehalfVerifyIsLogged() throws Exception {
        Ordered o = placeAndPay("13300133015", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133016");

        mvc().perform(post("/biz/pickup/verify").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCode\":\"" + o.verifyCode + "\",\"onBehalf\":true}"))
                .andExpect(jsonPath("$.data.success").value(true));

        // 代核销是「货没到人手里但先点了核销」，出纠纷时唯一能查的就是这条日志
        String body = mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + biz)
                        .param("status", "COMPLETED"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt()).isZero();
    }

    @Test
    @DisplayName("批量核销：部分成功也要逐条给出失败原因")
    void batchVerifyReportsEachFailure() throws Exception {
        Ordered a = placeAndPay("13300133017", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133018");

        String body = mvc().perform(post("/biz/pickup/verify/batch").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"verifyCodes\":[\"" + a.verifyCode + "\",\"999999\"]}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("successCount").asInt()).isEqualTo(1);
        assertThat(data.get("failed")).hasSize(1);
        assertThat(data.get("failed").get(0).get("reason").asString()).isNotBlank();
    }

    // ---------------------------------------------------------------- 越权防线④（X4）

    @Test
    @DisplayName("★ 字段级裁剪：自提点看到的订单**不含金额、不含完整手机号**")
    void pickupViewHasNoMoneyAndNoFullPhone() throws Exception {
        placeAndPay("13300133020", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133021");

        String body = mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("data");
        assertThat(rows).isNotEmpty();
        JsonNode row = rows.get(0);

        // 履约必需的字段在
        assertThat(row.has("verifyCode")).isTrue();
        assertThat(row.has("buyerNickname")).isTrue();
        assertThat(row.get("items")).isNotEmpty();

        // 金额与完整手机号**根本不存在于 VO 里** —— 不是靠条件序列化藏起来
        assertThat(row.has("payAmount")).isFalse();
        assertThat(row.has("amount")).isFalse();
        assertThat(row.has("buyerPhone")).isFalse();
        String tail = row.get("buyerPhoneTail").asString();
        assertThat(tail).hasSize(4);   // 只有后四位，用于当面核对
    }

    @Test
    @DisplayName("★ 自提点能看到**别家商家**的货（这是必然的），但同样被裁剪")
    void pickupSeesOtherMerchantGoodsMasked() throws Exception {
        // M0002 的商品，下单自提到 M0001 承接的 PP0001
        placeAndPay("13300133022", "G0003", "SK0004");
        String biz = loginAsOwnerOf("M0001", "13300133023");

        String body = mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + biz))
                .andReturn().getResponse().getContentAsString();

        boolean seesOther = false;
        for (JsonNode row : json.readTree(body).get("data")) {
            if ("鲜果直供".equals(row.get("merchantName").asString())) {
                seesOther = true;
                assertThat(row.has("payAmount")).isFalse();
            }
        }
        // 看得到是对的（货确实到他这儿了），看到金额是错的
        assertThat(seesOther).isTrue();
    }

    @Test
    @DisplayName("自提点只能看到本 pickup_no 的单（行级过滤，防线③）")
    void pickupOnlySeesOwnPoint() throws Exception {
        String biz = loginAsOwnerOf("M0001", "13300133024");
        String body = mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + biz)
                        .param("pickupNo", "PP0002"))
                .andReturn().getResponse().getContentAsString();
        // PP0002 也归 M0001，可以看；换成不属于自己的点应当 403
        assertThat(json.readTree(body).get("code").asInt()).isZero();

        String other = loginAsOwnerOf("M0002", "13300133025");
        mvc().perform(get("/biz/pickup/orders").header("Authorization", "Bearer " + other)
                        .param("pickupNo", "PP0001"))
                .andExpect(jsonPath("$.code").value(10403));
    }

    // ---------------------------------------------------------------- 分拣与总览

    @Test
    @DisplayName("分拣单：按商品聚合件数，到货当日按它分堆")
    void pickingListAggregatesByGoods() throws Exception {
        placeAndPay("13300133030", "G0002", "SK0003");
        placeAndPay("13300133031", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133032");

        String body = mvc().perform(get("/biz/pickup/picking").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = json.readTree(body).get("data");
        JsonNode row = null;
        for (JsonNode r : rows) {
            if ("G0002".equals(r.get("goodsNo").asString())) {
                row = r;
            }
        }
        assertThat(row).isNotNull();
        // 聚合的意义：分拣时看的是「这个规格一共几件」，不是「有哪些订单」
        assertThat(row.get("totalQty").asInt()).isGreaterThanOrEqualTo(2);
        assertThat(row.get("buyerCount").asInt()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("履约总览：今日待核销数 + 服务费（NEIGHBOR 恒 0）")
    void overviewShowsPendingAndFee() throws Exception {
        placeAndPay("13300133033", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133034");

        mvc().perform(get("/biz/pickup/overview").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pickupNo").value("PP0001"))
                .andExpect(jsonPath("$.data.pendingVerify")
                        .value(org.hamcrest.Matchers.greaterThan(0)))
                // 服务费口径未定（R15/B9），一期恒 0 而不是编一个数字
                .andExpect(jsonPath("$.data.serviceFeeMinor").value(0));
    }

    @Test
    @DisplayName("输码搜索：按码后几位找单（扫码失败时的兜底）")
    void searchByCode() throws Exception {
        Ordered o = placeAndPay("13300133035", "G0002", "SK0003");
        String biz = loginAsOwnerOf("M0001", "13300133036");

        String body = mvc().perform(get("/biz/pickup/verify/search").header("Authorization", "Bearer " + biz)
                        .param("keyword", o.verifyCode.substring(2)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("data")).isNotEmpty();
    }

    // ---------------------------------------------------------------- 商家订单

    @Test
    @DisplayName("商家订单：只含本 merchantNo 的子单（数据域 MERCHANT）")
    void merchantOrdersAreScoped() throws Exception {
        placeAndPay("13300133040", "G0002", "SK0003");   // M0001
        placeAndPay("13300133041", "G0003", "SK0004");   // M0002

        String m1 = loginAsOwnerOf("M0001", "13300133042");
        String body = mvc().perform(get("/biz/order").header("Authorization", "Bearer " + m1))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (JsonNode row : json.readTree(body).get("data").get("records")) {
            assertThat(row.get("merchantNo").asString()).isEqualTo("M0001");
        }
    }

    @Test
    @DisplayName("C 端确认收货：非自提线的终态出口")
    void confirmReceipt() throws Exception {
        Ordered o = placeAndPay("13300133050", "G0002", "SK0003");

        mvc().perform(post("/mp/order/" + o.subOrderNo + "/confirm-receipt")
                        .header("Authorization", "Bearer " + o.userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    // ---------------------------------------------------------------- helpers

    private record Ordered(String userToken, String subOrderNo, String verifyCode) {
    }

    /** 下单 + 支付，返回可核销的单。 */
    private Ordered placeAndPay(String phone, String goodsNo, String skuNo) throws Exception {
        String token = login(phone);
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m4-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-m4-" + phone
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        JsonNode row = json.readTree(list).get("data").get("records").get(0);
        return new Ordered(token, row.get("orderNo").asString(), row.get("verifyCode").asString());
    }

    /** 把某个手机号登录出来的用户设为该商家的店主，从而获得 B 端作用域。 */
    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String userNo = userNoOf(token);
        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        // 作用域在登录时解析，改完属主要重新登录一次才生效
        return login(phone);
    }

    private String userNoOf(String token) throws Exception {
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("userNo").asString();
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
    /** 授予 B 端身份：写一条 owner 成员行（幂等）。 */
    private void grantOwner(String merchantNo, String userNo) {
        var existing = merchantStaffMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.user.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.user.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.user.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.user.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.user.mapper.UserMappers.MchAccountMapper merchantStaffMapper;

}
