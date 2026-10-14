package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollCommand;
import ai.neargo.shop.promotion.dto.PlatformVOs.EnrollRule;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformActivityVO;
import ai.neargo.shop.promotion.dto.PlatformVOs.PlatformDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.PlatformActivityService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 平台活动：发起、报名、审核占预算、下单按出资拆账（详细设计 §1.6 · PRD AC-14 · AC-16）。
 *
 * <p>下单与付款走<b>真 HTTP</b>：买家会话下报名与活动都有数据域，直接调 service 的用例
 * 看不出算价那一侧取不取得到已通过的报名。
 *
 * <p>种子商品 G0001（M0001，SK0001 ¥49.80）。每条用例结束时删掉建的平台活动与报名 ——
 * 进行中的平台活动会让这件货对所有人按满减算，留着会让别的用例的金额断言莫名失败。
 */
@SpringBootTest
@ActiveProfiles("test")
class PlatformActivityFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0001";
    private static final String SKU = "SK0001";
    private static final long HOUR = 3_600_000L;

    private static int seq = 5500;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private PlatformActivityService service;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void drop() {
        for (String no : made) {
            jdbc.update("delete from pmt_enrollment_goods where enrollment_no in "
                    + "(select enrollment_no from pmt_enrollment where activity_no=?)", no);
            jdbc.update("delete from pmt_enrollment where activity_no=?", no);
            jdbc.update("delete from pmt_activity where activity_no=?", no);
        }
        made.clear();
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("★★★ 报名 → 通过占预算 → 买家下单：满减照减，平台那一半落进 discount_platform，pmt_apply 按出资方两行（AC-16）")
    void platformShareIsBookedToPlatform() throws Exception {
        PlatformActivityVO a = publish(5000, 100_000L);
        var e = service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 2));
        assertThat(e.platformMaxMinor()).as("最多平台补贴 = 2 份 × 每单 10 元").isEqualTo(2000L);
        assertThat(e.merchantMaxMinor()).isEqualTo(2000L);

        service.review(e.enrollmentNo(), true, null, "OP-1");
        assertThat(jdbc.queryForObject("select enroll_reserved_minor from pmt_activity where activity_no=?",
                Long.class, a.activityNo())).as("通过即占预算").isEqualTo(2000L);

        startNow(a.activityNo());
        String orderNo = buy(login(phone()), GOODS, SKU, 3);   // 3 × 49.80 = 149.40，满 100 减 20
        Map<String, Object> sub = subOf(orderNo);
        assertThat(((Number) sub.get("discount_amount")).longValue()).isEqualTo(2000L);
        assertThat(((Number) sub.get("discount_platform")).longValue())
                .as("★ 平台出的那一半没落进 discount_platform —— 结算时不会补给商家，商家白白承担").isEqualTo(1000L);
        assertThat(((Number) sub.get("discount_merchant")).longValue()).isEqualTo(1000L);

        List<Map<String, Object>> applies = jdbc.queryForList(
                "select funder, amount_minor from pmt_apply where order_no=? and promo_no=? order by funder",
                orderNo, a.activityNo());
        assertThat(applies).hasSize(2);
        assertThat(applies.get(0).get("funder")).isEqualTo("MERCHANT");
        assertThat(applies.get(1).get("funder")).isEqualTo("PLATFORM");
        assertThat(jdbc.queryForObject("select quota_used from pmt_enrollment where enrollment_no=?",
                Integer.class, e.enrollmentNo())).as("份数扣在报名上").isEqualTo(1);
    }

    @Test
    @DisplayName("★★★ 没报名的货不减：平台活动只对报名里的那几件货生效，门槛按它们的小计判")
    void onlyEnrolledGoods() throws Exception {
        PlatformActivityVO a = publish(5000, 100_000L);
        var e = service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 5));
        service.review(e.enrollmentNo(), true, null, "OP-1");
        startNow(a.activityNo());

        String orderNo = buy(login(phone()), GOODS, SKU, 1);   // 49.80 < 100，报名的货不够门槛
        assertThat(((Number) subOf(orderNo).get("discount_platform")).longValue())
                .as("★ 报名的货只有 49.8 元，不该凑别的货去够满 100").isZero();
        assertThat(jdbc.queryForObject("select count(*) from pmt_apply where order_no=? and promo_no=?",
                Integer.class, orderNo, a.activityNo())).isZero();
    }

    @Test
    @DisplayName("★★★ 通过会超预算就拒（AC-14）：占预算是一条带条件的 UPDATE")
    void overBudgetIsRejected() {
        PlatformActivityVO a = publish(5000, 1_500L);   // 预算 15 元
        var e = service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 2));   // 要占 20 元
        assertThatThrownBy(() -> service.review(e.enrollmentNo(), true, null, "OP-1"))
                .isInstanceOf(BizException.class)
                .extracting(x -> ((BizException) x).errorCode()).isEqualTo(ErrorCode.ENROLLMENT_OVER_BUDGET);
        assertThat(jdbc.queryForObject("select status from pmt_enrollment where enrollment_no=?",
                String.class, e.enrollmentNo())).as("拒了就还是待审，没有半通过").isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("select enroll_reserved_minor from pmt_activity where activity_no=?",
                Long.class, a.activityNo())).isZero();
    }

    @Test
    @DisplayName("★★ 过了报名截止不能报（40031）；驳回要理由；驳回后可以改了再报")
    void deadlineAndReject() {
        PlatformActivityVO a = publish(0, null);
        var e = service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 1));
        assertThatThrownBy(() -> service.review(e.enrollmentNo(), false, " ", "OP-1"))
                .isInstanceOf(BizException.class);
        service.review(e.enrollmentNo(), false, "价格高于平台同类", "OP-1");
        var again = service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 3));
        assertThat(again.status()).isEqualTo("SUBMITTED");
        assertThat(again.rejectReason()).as("重报之后不该还挂着上一次的驳回理由").isNull();

        jdbc.update("update pmt_activity set enroll_deadline=? where activity_no=?",
                System.currentTimeMillis() - 1000, a.activityNo());
        assertThatThrownBy(() -> service.enroll(ENTITY, a.activityNo(), new EnrollCommand(List.of(GOODS), 1)))
                .isInstanceOf(BizException.class)
                .extracting(x -> ((BizException) x).errorCode()).isEqualTo(ErrorCode.ENROLLMENT_CLOSED);
    }

    @Test
    @DisplayName("★★ 商家端走真 HTTP：看得到已发布的平台活动；拿别家的货报名当不存在（404）")
    void merchantHttp() throws Exception {
        PlatformActivityVO a = publish(10000, 100_000L);
        String outsider = onboardedMerchant("126" + String.format("%08d", ++seq));

        String list = mvc().perform(get("/biz/platform-activity").param("tab", "ENROLLABLE")
                        .header("Authorization", "Bearer " + outsider))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(list).contains(a.activityNo());

        JsonNode r = json.readTree(mvc().perform(post("/biz/platform-activity/" + a.activityNo() + "/enrollment")
                        .header("Authorization", "Bearer " + outsider)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNos\":[\"" + GOODS + "\"],\"quota\":1}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as("★ 拿别家的货报了名，平台补贴就补到了别人的货上").isIn(10404, 40031);
    }

    // ---------------------------------------------------------------- 夹具

    /** 满 100 减 20，报名一小时后截止、两小时后开始 */
    private PlatformActivityVO publish(int shareBp, Long budget) {
        long now = System.currentTimeMillis();
        PlatformActivityVO v = service.save(new PlatformDraft(null, "平台满减 · " + (++seq),
                PmtActivity.TRIGGER_AMOUNT, 10_000L, null, 2_000L,
                now + 2 * HOUR, now + 48 * HOUR, now + HOUR,
                shareBp, budget, EnrollRule.none(), true), "OP-1");
        made.add(v.activityNo());
        return v;
    }

    /** 让活动此刻就在跑（报名在开始之前截止，所以要报完名再把开始时刻挪到过去） */
    private void startNow(String activityNo) {
        jdbc.update("update pmt_activity set start_at=? where activity_no=?",
                System.currentTimeMillis() - 1000, activityNo);
    }

    private String buy(String token, String goodsNo, String skuNo, int qty) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
        JsonNode r = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "plat-" + (++seq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as("下单失败：" + r).isZero();
        String orderNo = r.get("data").get("payOrderNo").asString();
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"TX-PL" + (++seq)
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
        return orderNo;
    }

    /** 走完入驻（申请 → 运营通过）的商家，拿 B 端令牌 —— 没入驻的号在 /biz 上一律 403 */
    private String onboardedMerchant(String phone) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"平台活动外人店" + seq + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        String bd = TestLogin.operator(mvc(), json, "bd", "bd123");
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                        .header("Authorization", "Bearer " + bd)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"))
                .andExpect(status().isOk());
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private Map<String, Object> subOf(String orderNo) {
        return jdbc.queryForMap("select discount_amount, discount_platform, discount_merchant "
                + "from ord_sub_order where order_no=? limit 1", orderNo);
    }

    private String phone() {
        return "1370055" + String.format("%04d", ++seq % 10000);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
