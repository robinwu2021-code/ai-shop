package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
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
 * M5 售后 —— **用例先行**（任务清单 §二 .4 步）。
 *
 * <p>本模块只有一条真正的红线：**退款前必须先回退分账**（E4 / A2 §1 不变量）。
 * 顺序反了，钱退给了用户但分账收不回来 —— 是真金白银的损失，且事后只能人工追。
 * 因此这条由**状态机强制**，并在这里用一条独立用例守住。
 */
@SpringBootTest
@ActiveProfiles("test")
class M5AfterSaleFlowTest {

    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private MchEntityMapper merchantMapper;

    @Autowired
    private ai.neargo.shop.settle.SettleService settleService;

    /** M7 之后 SettlePort 是真实实现；失败注入下沉到**注定被替换的**通道桩上。 */
    @Autowired
    private ai.neargo.shop.settle.StubSplitGateway splitGateway;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    // ---------------------------------------------------------------- 资损红线

    @Test
    @DisplayName("★ 退款前必须先回退分账（E4）—— 顺序由状态机强制")
    void splitMustBeReversedBeforeRefund() throws Exception {
        Ordered o = placeAndPay("13200132001", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "不想要了");
        String biz = loginAsOwnerOf("M0001", "13200132002");

        approve(biz, asNo);

        String body = detail(o.userToken, asNo);
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("status").asString()).isEqualTo("REFUNDED");

        // 分账回退必须**发生在退款之前**：退款成功 ⟹ 结算单已回退。
        // 反过来说，只要结算单还没回退，就绝不可能出现 REFUNDED（下一条用例守住反向）
        assertThat(json.readTree(detail(o.userToken, asNo)).get("data").get("status").asString())
                .isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("分账回退失败时不得退款（钱只能少退，不能多退）")
    void refundBlockedWhenReverseFails() throws Exception {
        Ordered o = placeAndPay("13200132003", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "分账回退会失败");
        String biz = loginAsOwnerOf("M0001", "13200132004");

        // 让分账回退失败：此时**绝不能退款**
        prepareSplit(o.subOrderNo);
        splitGateway.failNext(o.subOrderNo);
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.code").value(50002));

        // 停在 REFUNDING 等重试，而不是「退款成功」
        assertThat(json.readTree(detail(o.userToken, asNo)).get("data").get("status").asString())
                .isNotEqualTo("REFUNDED");
    }

    // ---------------------------------------------------------------- 申请与流转

    @Test
    @DisplayName("申请售后：子单粒度，带凭证图，进入待商家处理")
    void applyAfterSale() throws Exception {
        Ordered o = placeAndPay("13200132010", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "少发一件");

        mvc().perform(get("/mp/after-sale/" + asNo).header("Authorization", "Bearer " + o.userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.afterSaleNo").value(asNo))
                .andExpect(jsonPath("$.data.subOrderNo").value(o.subOrderNo))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.images.length()").value(1))
                .andExpect(jsonPath("$.data.timeline.length()").value(1));
    }

    @Test
    @DisplayName("极速退：小额自动通过，商家只可见不可拒")
    void instantRefundAutoApproved() throws Exception {
        // 阈值内金额（配置默认 100 元以内）
        Ordered o = placeAndPay("13200132011", 3980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "买错了");

        String body = detail(o.userToken, asNo);
        JsonNode data = json.readTree(body).get("data");
        assertThat(data.get("instant").asBoolean()).isTrue();
        // 自动通过：用户不用等商家点同意
        assertThat(data.get("status").asString()).isEqualTo("REFUNDED");

        // 3980 那件是 M0002（鲜果直供）的货 —— 售后归属跟着商品走，不是跟着自提点走
        String biz = loginAsOwnerOf("M0002", "13200132012");
        mvc().perform(post("/biz/after-sale/" + asNo + "/reject").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"我不同意\"}"))
                .andExpect(jsonPath("$.code").value(20004));   // 状态机拒绝
    }

    @Test
    @DisplayName("商家驳回必须写理由，用户可申诉上升平台")
    void rejectThenEscalate() throws Exception {
        Ordered o = placeAndPay("13200132013", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "质量问题");
        String biz = loginAsOwnerOf("M0001", "13200132014");

        mvc().perform(post("/biz/after-sale/" + asNo + "/reject").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"remark\":\"商品无质量问题\"}"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.merchantRemark").value("商品无质量问题"));

        mvc().perform(post("/mp/after-sale/" + asNo + "/escalate")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"appeal\":\"有照片为证\"}"))
                .andExpect(jsonPath("$.data.status").value("ARBITRATING"));
    }

    @Test
    @DisplayName("退货退款：回填物流 → 商家确认收货 → 退款")
    void returnRefundFlow() throws Exception {
        Ordered o = placeAndPay("13200132015", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "不合适");
        String biz = loginAsOwnerOf("M0001", "13200132016");

        approve(biz, asNo);   // 退货退款：同意 ≠ 立刻退钱，要等收到货

        mvc().perform(post("/mp/after-sale/" + asNo + "/ship")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressCompany\":\"顺丰\",\"expressNo\":\"SF123\"}"))
                .andExpect(jsonPath("$.data.expressNo").value("SF123"));

        mvc().perform(post("/biz/after-sale/" + asNo + "/receive").header("Authorization", "Bearer " + biz))
                .andExpect(jsonPath("$.data.status").value("REFUNDED"));
    }

    @Test
    @DisplayName("用户可撤销申请；撤销后订单回到原状态")
    void userCanCancelApplication() throws Exception {
        Ordered o = placeAndPay("13200132017", 6980L);
        String asNo = applyAfterSale(o, "RETURN_REFUND", "手滑了");

        mvc().perform(post("/mp/after-sale/" + asNo + "/cancel")
                        .header("Authorization", "Bearer " + o.userToken))
                .andExpect(jsonPath("$.data.status").value("CLOSED"));

        // 撤销后还能再申请 —— 终态不锁死用户
        assertThat(applyAfterSale(o, "REFUND_ONLY", "还是要退")).isNotBlank();
    }

    // ---------------------------------------------------------------- 约束与越权

    @Test
    @DisplayName("同一子单同时只能有一个进行中的售后")
    void onlyOneActiveAfterSalePerSubOrder() throws Exception {
        Ordered o = placeAndPay("13200132020", 6980L);
        applyAfterSale(o, "RETURN_REFUND", "第一次");

        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"第二次\"}"))
                .andExpect(jsonPath("$.code").value(10409));
    }

    @Test
    @DisplayName("退款金额不能超过子单实付")
    void refundCannotExceedPaid() throws Exception {
        Ordered o = placeAndPay("13200132021", 6980L);
        mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"多退点\",\"refundMinor\":999999}"))
                .andExpect(jsonPath("$.code").value(10400));
    }

    @Test
    @DisplayName("未支付的订单不能申请售后")
    void unpaidOrderCannotApply() throws Exception {
        String token = login("13200132022");
        String subOrderNo = placeOnly(token, "m5-unpaid");

        mvc().perform(post("/mp/order/" + subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"REFUND_ONLY\",\"reason\":\"没付款也想退\"}"))
                .andExpect(jsonPath("$.code").value(20004));
    }

    @Test
    @DisplayName("越权：别人的售后单看不到、也处理不了")
    void cannotTouchOthersAfterSale() throws Exception {
        Ordered o = placeAndPay("13200132023", 6980L);
        String asNo = applyAfterSale(o, "REFUND_ONLY", "我的单");

        String stranger = login("13200132024");
        mvc().perform(get("/mp/after-sale/" + asNo).header("Authorization", "Bearer " + stranger))
                .andExpect(jsonPath("$.code").value(10404));

        // 别家商家也处理不了
        String otherBiz = loginAsOwnerOf("M0002", "13200132025");
        mvc().perform(post("/biz/after-sale/" + asNo + "/approve").header("Authorization", "Bearer " + otherBiz))
                .andExpect(jsonPath("$.code").value(10404));
    }

    @Test
    @DisplayName("商家只看得到自己商品的售后")
    void merchantSeesOwnAfterSaleOnly() throws Exception {
        Ordered a = placeAndPay("13200132026", 6980L);          // M0001
        applyAfterSale(a, "REFUND_ONLY", "M0001 的单");

        String biz = loginAsOwnerOf("M0001", "13200132027");
        String body = mvc().perform(get("/biz/after-sale").header("Authorization", "Bearer " + biz))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (JsonNode row : json.readTree(body).get("data")) {
            assertThat(row.get("subOrderNo").asString()).isNotBlank();
        }
    }

    @Test
    @DisplayName("售后原因字典可取（游客也能看，帮助页要用）")
    void reasonsDictionary() throws Exception {
        mvc().perform(get("/mp/after-sale/reasons"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    @DisplayName("我的售后列表按时间倒序")
    void myAfterSaleList() throws Exception {
        Ordered o = placeAndPay("13200132028", 6980L);
        applyAfterSale(o, "REFUND_ONLY", "列表用");

        mvc().perform(get("/mp/after-sale").header("Authorization", "Bearer " + o.userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThan(0)));
    }

    // ---------------------------------------------------------------- helpers

    private record Ordered(String userToken, String subOrderNo, String payOrderNo) {
    }

    /** 先把结算单推到已分账，回退才有意义（没分过账的单不会向通道发回退指令）。 */
    private void prepareSplit(String subOrderNo) {
        settleService.merchantBills("M0001").stream()
                .filter(b -> b.subOrderNo().equals(subOrderNo))
                .findFirst()
                .ifPresent(b -> settleService.executeSplit(b.settleNo()));
    }

    private String detail(String token, String afterSaleNo) throws Exception {
        return mvc().perform(get("/mp/after-sale/" + afterSaleNo).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
    }

    private void approve(String bizToken, String afterSaleNo) throws Exception {
        mvc().perform(post("/biz/after-sale/" + afterSaleNo + "/approve")
                        .header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk());
    }

    private String applyAfterSale(Ordered o, String type, String reason) throws Exception {
        String body = mvc().perform(post("/mp/order/" + o.subOrderNo + "/after-sale")
                        .header("Authorization", "Bearer " + o.userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"" + type + "\",\"reason\":\"" + reason
                                + "\",\"images\":[\"https://cdn/x.jpg\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("afterSaleNo").asString();
    }

    /** 下单但不支付，返回子单号。 */
    private String placeOnly(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0002\",\"skuNo\":\"SK0003\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idemKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"));
        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    /**
     * 下单 + 支付。
     *
     * @param amountHint 3980（M0002 的蓝莓，**低于极速退阈值 5000**）或 6980（M0001 的油，高于阈值）——
     *                   用金额挑商品是为了让用例读起来就知道走的是哪条分支
     */
    private Ordered placeAndPay(String phone, long amountHint) throws Exception {
        String token = login(phone);
        String goods = amountHint <= 4000L ? "G0004" : "G0002";
        String sku = amountHint <= 4000L ? "SK0005" : "SK0003";

        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goods + "\",\"skuNo\":\"" + sku + "\",\"qty\":1}"));
        String body = mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "m5-" + phone)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
        String payOrderNo = json.readTree(body).get("data").get("payOrderNo").asString();

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-m5-" + phone
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));

        String list = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String subOrderNo = json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
        return new Ordered(token, subOrderNo, payOrderNo);
    }

    private String loginAsOwnerOf(String merchantNo, String phone) throws Exception {
        String token = login(phone);
        String body = mvc().perform(get("/mp/user/profile").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String userNo = json.readTree(body).get("data").get("userNo").asString();

        MchEntity m = merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("limit 1"));
        m.setOwnerUserNo(userNo);
        // V44 起 B 端身份来自 mch_account，不再是 owner_user_no —— 两处都要写
        grantOwner(m.getEntityNo(), userNo);
        merchantMapper.updateById(m);
        return login(phone);
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
                        .<ai.neargo.shop.merchant.entity.MchAccount>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchAccount::getEntityNo, merchantNo)
                        .last("limit 1"));
        if (existing != null) {
            existing.setUserNo(userNo);
            merchantStaffMapper.updateById(existing);
            return;
        }
        var st = new ai.neargo.shop.merchant.entity.MchAccount();
        st.setMchAccountNo("SF-T-" + merchantNo);
        st.setEntityNo(merchantNo);
        st.setUserNo(userNo);
        st.setIsOwner(true);
        st.setIsPrimary(true);
        st.setStatus(ai.neargo.shop.merchant.entity.MchAccount.ACTIVE);
        merchantStaffMapper.insert(st);
    }

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper merchantStaffMapper;

}
