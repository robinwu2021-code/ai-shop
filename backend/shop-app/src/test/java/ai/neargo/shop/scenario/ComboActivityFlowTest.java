package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityVO;
import ai.neargo.shop.promotion.dto.ActivityVOs.RuleItem;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.ActivityService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 自己组合（原型 s11 · 开发计划 P3b）：多条件（全部满足）× 多利益（按顺序叠加）。
 *
 * <p>下单走真 HTTP：买家会话下活动与组合行都有数据域（组合行挂在活动号上随活动读）。
 * 种子商品 G0001（M0001，SK0001 ¥49.80）。每条用例结束时删掉建的活动与组合行。
 */
@SpringBootTest
@ActiveProfiles("test")
class ComboActivityFlowTest {

    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0001";
    private static final String SKU = "SK0001";

    private static int seq = 6600;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private ActivityService activityService;
    @Autowired private ActivityPricingService pricing;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> made = new ArrayList<>();

    @AfterEach
    void drop() {
        for (String no : made) {
            jdbc.update("delete from pmt_activity_rule where activity_no=?", no);
            jdbc.update("delete from pmt_activity_goods where activity_no=?", no);
            jdbc.update("delete from pmt_activity where activity_no=?", no);
        }
        made.clear();
    }

    /** 买指定 G0001 满 3 件：8 折（封顶 ¥30），送 50 积分 */
    private static List<RuleItem> rules() {
        return List.of(
                new RuleItem("CONDITION", "GOODS", null, null, null, null, List.of(GOODS)),
                new RuleItem("CONDITION", "QTY", null, 3, null, null, null),
                new RuleItem("BENEFIT", "PERCENT", null, null, 8000, 3000L, null),
                new RuleItem("BENEFIT", "POINTS", null, 50, null, null, null));
    }

    @Test
    @DisplayName("★★★ 条件全部满足才减：3 件 G0001 打 8 折（没到封顶），2 件不减；组合行原样读得回来")
    void comboAppliesOnlyWhenAllConditionsHold() throws Exception {
        ActivityVO a = save(null, rules(), System.currentTimeMillis() - 60_000);
        assertThat(a.rules()).hasSize(4);
        assertThat(a.rules().get(2).bp()).isEqualTo(8000);
        assertThat(a.rules().get(0).goodsNos()).containsExactly(GOODS);

        String three = buy(GOODS, SKU, 3);   // 149.40 × 20% = 29.88，封顶 30 没到
        assertThat(discountOf(three)).as("★ 组合没生效：条件都满足了却一分没减").isEqualTo(2988L);
        assertThat(pricing.bonusPoints(three, ENTITY)).as("★ 送积分那一行没被读出来").isEqualTo(50L);

        String two = buy(GOODS, SKU, 2);
        assertThat(discountOf(two)).as("★ 件数不够也减了 —— 条件是「全部满足」").isZero();
        assertThat(pricing.bonusPoints(two, ENTITY)).isZero();
    }

    @Test
    @DisplayName("★★ 硬校验：没有优惠 / 打折不封顶 一律拒")
    void comboValidation() {
        long now = System.currentTimeMillis();
        assertThatThrownBy(() -> save(null, List.of(new RuleItem("CONDITION", "QTY", null, 2, null, null, null)), now))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> save(null, List.of(
                new RuleItem("CONDITION", "QTY", null, 2, null, null, null),
                new RuleItem("BENEFIT", "PERCENT", null, null, 8000, null, null)), now))
                .as("打折不封顶是不可控的敞口").isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★★ 开始了的组合活动改条件也被锁（A6）")
    void comboRulesAreLockedOnceStarted() {
        ActivityVO a = save(null, rules(), System.currentTimeMillis() - 60_000);
        List<RuleItem> changed = new ArrayList<>(rules());
        changed.set(1, new RuleItem("CONDITION", "QTY", null, 2, null, null, null));
        assertThatThrownBy(() -> save(a.activityNo(), changed, a.startAt()))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).errorCode()).isEqualTo(ErrorCode.ACTIVITY_RULE_LOCKED);
    }

    // ---------------------------------------------------------------- 夹具

    private ActivityVO save(String no, List<RuleItem> rules, long startAt) {
        ActivityVO v = activityService.save(ENTITY, new ActivityDraft(no, "会员囤货 · " + (++seq), null, null,
                PmtActivity.TRIGGER_COMBO, null, null,
                PmtActivity.BENEFIT_COMBO, null, null, null,
                PmtActivity.ONE_OFF, startAt, startAt + 86_400_000L, null, 100, null,
                List.of(), List.of(),
                null, null, null, null, null, null, null, rules), "TEST");
        if (!made.contains(v.activityNo())) {
            made.add(v.activityNo());
        }
        return v;
    }

    private String buy(String goodsNo, String skuNo, int qty) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "1370066" + String.format("%04d", ++seq % 10000));
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":" + qty + "}"))
                .andExpect(status().isOk());
        JsonNode r = json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "combo-" + (++seq))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as("下单失败：" + r).isZero();
        return r.get("data").get("payOrderNo").asString();
    }

    private long discountOf(String orderNo) {
        return jdbc.queryForObject("select discount_amount from ord_sub_order where order_no=? limit 1",
                Long.class, orderNo);
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
