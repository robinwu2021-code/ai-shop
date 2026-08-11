package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.entity.MchAdmissionPolicy;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.spi.user.AdmissionPort;
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
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * S3 准入：<b>真实调用链</b>上的两条。
 *
 * <p>{@link S3AdmissionFlowTest} 验的是闸门本身的逻辑，用的是替身
 * （假的 {@code LongSupplier}、非空的类目号）。那两个替身太干净，
 * 各自盖住了一个真实缺陷：
 *
 * <ol>
 *   <li><b>日累计</b>：真实查询在买家会话里执行，而 {@code ord_sub_order} 注册了
 *       {@code ScopeDim.SELF → user_no} —— 不绕过数据域拦截器的话，
 *       「该商家当日成交额」被改写成「<b>该买家</b>在这家店的当日成交额」，
 *       日累计上限实际成了「每买家一份」。假 supplier 从不触发那次查询。</li>
 *   <li><b>未归类商品</b>：准入校验原先插在「没归类就 return」之后，
 *       而 {@code categoryNo} 是选填的 —— 少填一个字段就绕过整道闸。
 *       用例传的是 {@code "CAT_FREE"} 而不是 {@code null}。</li>
 * </ol>
 *
 * <p><b>所以这个类只走真入口</b>，且日累计那条构造成<b>能证伪</b>的形状：
 * 限额卡在「一单放得过、两单放不过」之间。若拦截器没绕过，
 * 第二个买家看到的是 0，那一单就会被放行 —— 用例随之变红。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("S3 准入 · 真实链路：替身盖住过的那两条")
class S3AdmissionRealPathTest {

    private static final String SEED_MERCHANT = "M0001";
    private static final String MICRO = "MICRO";
    /** 支付回调桩的签名 —— 漏了它回调静默失败，订单停在 WAIT_PAY 而不计入当日成交额 */
    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private OtpStore otpStore;

    @Autowired
    private AdmissionPort admissionPort;

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private MchEntityMapper merchantMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★★ 未归类商品也要过保证金闸门 —— categoryNo 选填，少填一个字段不能绕过")
    void uncategorizedGoodsStillGated() {
        String micro = aMicroMerchant();

        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, null, false))
                .as("上一版把校验插在「没归类就 return」之后，于是不填类目就能一分钱不缴上架")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DEPOSIT_INSUFFICIENT.name());

        assertThatThrownBy(() -> admissionPort.requireListingAllowed(micro, "", false))
                .as("空串与 null 是同一种情况，不能只堵一个")
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.DEPOSIT_INSUFFICIENT.name());
    }

    @Test
    @DisplayName("★★ 日累计跨买家累加：A 下过一单后，B 的同额单必须被 70011 拦下")
    void dailyCapCountsAcrossBuyers() throws Exception {
        MchEntity seed = merchant(SEED_MERCHANT);
        String originalForm = seed.getLegalForm();
        MchAdmissionPolicy original = snapshotMicroPolicy();

        try {
            // 让种子商家落到 MICRO 档，并先把限额放开，好让第一单顺利成交
            seed.setLegalForm(MICRO);
            merchantMapper.updateById(seed);
            setMicroLimits(0L, 0L);

            String buyerA = login("13100139001");
            long paid = placeAndPay(buyerA, "s3-real-a");
            assertThat(paid).as("第一单要真的成交，否则它不计入当日成交额").isPositive();

            /*
             * 限额卡在「一单放得过、两单放不过」之间 —— 这是本用例能证伪的关键。
             * 若数据域拦截器没被绕过，买家 B 查到的当日成交额是 0（A 的单不属于他），
             * 0 + paid < 1.5×paid，那一单就会被放行，断言随之失败。
             */
            setMicroLimits(0L, paid + paid / 2);

            String buyerB = login("13100139002");
            String body = placeOrderRaw(buyerB, "s3-real-b");

            assertThat(json.readTree(body).get("code").asInt())
                    .as("买家 B 的单必须看见买家 A 的成交额 —— 否则日累计上限就是「每买家一份」，"
                            + "100 个买家各下限额就是 100 倍敞口")
                    .isEqualTo(ErrorCode.DAILY_LIMIT_EXCEEDED.code());
        } finally {
            seed.setLegalForm(originalForm);
            merchantMapper.updateById(seed);
            restoreMicroPolicy(original);
        }
    }

    // ---------------------------------------------------------------- helpers

    private long placeAndPay(String token, String idemKey) throws Exception {
        String body = placeOrderRaw(token, idemKey);
        var data = json.readTree(body).get("data");
        assertThat(data).as("下单要成功，返回体：%s", body).isNotNull();
        String payOrderNo = data.get("payOrderNo").asString();
        long amount = data.get("amount").get("payableMinor").asLong();

        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                        + "\",\"sign\":\"" + STUB_SECRET + "\"}"));
        return amount;
    }

    private String placeOrderRaw(String token, String idemKey) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"G0001\",\"skuNo\":\"SK0001\",\"qty\":1}"));
        return mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString();
    }

    private void setMicroLimits(long single, long daily) {
        MchAdmissionPolicy patch = new MchAdmissionPolicy();
        patch.setSingleOrderLimitMinor(single);
        patch.setDailyAmountLimitMinor(daily);
        patch.setRequiredDepositMinor(0L);
        patch.setBanQualifiedCategory(0);
        admissionService.updatePolicy(MICRO, patch, "TEST");
    }

    private MchAdmissionPolicy snapshotMicroPolicy() {
        return admissionService.policies().stream()
                .filter(p -> MICRO.equals(p.getLegalForm())).findFirst().orElseThrow();
    }

    private void restoreMicroPolicy(MchAdmissionPolicy p) {
        MchAdmissionPolicy patch = new MchAdmissionPolicy();
        patch.setSingleOrderLimitMinor(p.getSingleOrderLimitMinor());
        patch.setDailyAmountLimitMinor(p.getDailyAmountLimitMinor());
        patch.setRequiredDepositMinor(p.getRequiredDepositMinor());
        patch.setBanQualifiedCategory(p.getBanQualifiedCategory());
        admissionService.updatePolicy(MICRO, patch, "TEST");
    }

    private MchEntity merchant(String merchantNo) {
        return merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
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

    private String aMicroMerchant() {
        String no = "MRP" + System.nanoTime() % 1_000_000;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("真实链路测试-小微");
        m.setLegalForm(MICRO);
        m.setStatus("ACTIVE");
        merchantMapper.insert(m);
        return no;
    }
}
