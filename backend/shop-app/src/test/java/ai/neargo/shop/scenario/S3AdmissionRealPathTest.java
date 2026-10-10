package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
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
    private static final String MICRO = "NATURAL_PERSON";
    /** 支付回调桩的签名 —— 漏了它回调静默失败，订单停在 WAIT_PAY 而不计入当日成交额 */
    private static final String STUB_SECRET = "stub-secret";

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;


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
    @DisplayName("★★ 单笔限额在真实下单链路上要真的拦 —— 它与日累计死于同一个原因")
    void singleOrderLimitFiresOnRealPath() throws Exception {
        MchEntity seed = merchant(SEED_MERCHANT);
        String originalForm = seed.getLegalForm();
        MchAdmissionPolicy original = snapshotMicroPolicy();

        try {
            seed.setLegalForm(MICRO);
            merchantMapper.updateById(seed);

            /*
             * **先要一个不设限的对照**：这一单必须过。
             * 少了它，下面那条断言在「下单因为别的原因失败」时也会绿 ——
             * 而这个用例的全部价值就在于分清「被限额拦下」与「根本没走到限额」。
             */
            setMicroLimits(0L, 0L);
            String control = placeOrderRaw(login("13100139003"), "s3-single-ok");
            assertThat(json.readTree(control).get("code").asInt())
                    .as("不设限时这一单要能下成，返回体：%s", control).isZero();
            long amount = json.readTree(control).get("data").get("amount").get("payableMinor").asLong();

            // 限额压到单价以下，同样的一单必须被拦
            setMicroLimits(amount - 1, 0L);
            String body = placeOrderRaw(login("13100139004"), "s3-single-blocked");
            assertThat(json.readTree(body).get("code").asInt())
                    .as("单笔限额在买家会话里同样要生效 —— 它与日累计上限死于同一个原因："
                            + "mch_entity 是 MERCHANT 维度，买家没有锚点，"
                            + "fail-closed 查不到主体，整条准入判断直接 return")
                    .isEqualTo(ErrorCode.ORDER_LIMIT_EXCEEDED.code());
        } finally {
            seed.setLegalForm(originalForm);
            merchantMapper.updateById(seed);
            restoreMicroPolicy(original);
        }
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

        /*
         * **回调的返回值必须断言**。验签失败时它只回 "FAIL" 且 HTTP 200 ——
         * 丢掉返回值的话，「这一单其实没付成」会一路静默下去：子单停在 WAIT_PAY，
         * 当日成交额算出来是 0，于是买家 B 那一单被放行，
         * 症状伪装成「日累计上限跨买家不累加」这个完全不相干的结论。
         */
        String ack = mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-" + idemKey
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(ack).as("支付回调要真的成功，否则这一单不计入当日成交额").contains("SUCCESS");
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

    /**
     * <b>「为什么这么定」要能改。</b>
     *
     * <p>其余六个字段都 patch，唯独 remark 被漏掉了 —— 运营填了理由、点保存、
     * 看到成功，回头一看还是旧的。而被回溯质问「那单当时为什么放行」时，
     * 有用的恰恰是这句话，不是数字。
     *
     * <p>可证伪：去掉 impl 里那段 {@code if (patch.getRemark() != null)}，这条立刻变红。
     */
    @Test
    @DisplayName("★ 准入策略的 remark 改得动 —— 此前只写不生效，且不报错")
    void policyRemarkIsPatchable() {
        MchAdmissionPolicy before = snapshotMicroPolicy();
        try {
            String reason = "上季度三起售后无人可追，2026-09 上调门槛";
            MchAdmissionPolicy patch = new MchAdmissionPolicy();
            patch.setRemark(reason);
            admissionService.updatePolicy(MICRO, patch, "TEST");

            assertThat(snapshotMicroPolicy().getRemark())
                    .as("填了理由、看到成功、其实没存 —— 最难查的那种坏法").isEqualTo(reason);
            // 只发 remark 不该动到数字：patch 语义是「没给的保持不变」
            assertThat(snapshotMicroPolicy().getSingleOrderLimitMinor())
                    .as("单发 remark 把限额带没了，比不生效更糟")
                    .isEqualTo(before.getSingleOrderLimitMinor());
        } finally {
            restoreMicroPolicy(before);
        }
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
        // remark 现在也可改，还原时就得带上 —— 否则这个类会把种子里的说明留成测试文本
        patch.setRemark(p.getRemark());
        admissionService.updatePolicy(MICRO, patch, "TEST");
    }

    private MchEntity merchant(String merchantNo) {
        return merchantMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                .eq(MchEntity::getEntityNo, merchantNo).last("LIMIT 1"));
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
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
