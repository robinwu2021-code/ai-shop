package ai.neargo.shop.scenario;

import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.promotion.dto.ActivityVOs.ActivityDraft;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.service.ActivityService;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 商品仅活动可售（TDD-商品仅活动可售 §7）。
 *
 * <p><b>全程走真实接口</b>：加购、下单、详情都打 HTTP —— 闸装在 split() 与购物车上，
 * 直接调服务会绕过控制器把 openGroup / groupNo 转成指令那一步，而那正是「开团放行、单买拒」的分水岭。
 *
 * <p>种子：M0001 / G0002 / SK0003 / PP0001（与 PeriodFlowTest 同一件货）。
 * 每条用例前把它改成仅活动，<b>用例后改回、并结束所有开过的活动</b> ——
 * 改了共享种子不还原，单独跑绿、全量跑红，报错永远不指向这里。
 */
@SpringBootTest
@ActiveProfiles("test")
class GoodsActivityOnlyFlowTest {

    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0002";
    private static final String SKU = "SK0003";
    private static final int ACTIVITY_ONLY = 70067;
    private static final long DAY = 24L * 3600 * 1000;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static int seq = 9400;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private ActivityService activityService;
    @Autowired private GoodsService goodsService;
    @Autowired private JdbcTemplate jdbc;

    private final List<String> started = new ArrayList<>();

    @BeforeEach
    void makeActivityOnly() {
        jdbc.update("update prd_goods set sale_mode='ACTIVITY_ONLY' where goods_no=?", GOODS);
    }

    @AfterEach
    void restore() {
        for (String no : started) {
            try {
                activityService.setStatus(ENTITY, no, PmtActivity.ENDED);
            } catch (RuntimeException ignored) {
                // 用例里已经结束过的，再结束一次会被拒，无妨
            }
        }
        started.clear();
        jdbc.update("update prd_goods set sale_mode='NORMAL' where goods_no=?", GOODS);
    }

    // ---------------------------------------------------------------- §3 判定表，一行一条

    @Test
    @DisplayName("★★★ 只有拼团在跑：开团放行、单买拒 —— 这正是要拦的「单买」")
    void groupOnlyBlocksSingleBuy() throws Exception {
        start(group());
        String buyer = login();

        assertThat(code(cartAdd(buyer)))
                .as("★ 开团也是先加购（goods 页 openGroupBuy）—— 在这里拒，开团就断了").isZero();
        assertThat(code(order(buyer, false)))
                .as("★★ 不带开团 / 团号的普通下单：只有拼团在跑，必须拒").isEqualTo(ACTIVITY_ONLY);
        assertThat(code(order(buyer, true)))
                .as("开团这条路照常").isZero();
    }

    @Test
    @DisplayName("★★★ 集单在跑：普通下单放行，并挂上当期")
    void batchOpensDirect() throws Exception {
        start(batch());
        String buyer = login();

        assertThat(code(cartAdd(buyer))).isZero();
        JsonNode r = order(buyer, false);
        assertThat(code(r)).as("集单走的就是普通下单：" + r).isZero();
        String payOrderNo = r.get("data").get("payOrderNo").asString();
        Object period = jdbc.queryForObject(
                "select period_no from ord_sub_order where order_no=? limit 1", Object.class, payOrderNo);
        assertThat(period).as("放行了但没挂上期 —— 那就成了一张仅活动的货的单买").isNotNull();
    }

    @Test
    @DisplayName("★★★ 特价在跑：普通下单放行，且按特价收")
    void flashOpensDirect() throws Exception {
        start(flash(1234L));
        String buyer = login();

        assertThat(code(cartAdd(buyer))).isZero();
        JsonNode r = order(buyer, false);
        assertThat(code(r)).as("特价走的就是普通下单：" + r).isZero();
        Long goodsAmount = jdbc.queryForObject(
                "select goods_amount from ord_sub_order where order_no=? limit 1", Long.class,
                r.get("data").get("payOrderNo").asString());
        assertThat(goodsAmount).as("放行了却按原价收 —— 仅活动的货按原价卖了出去").isEqualTo(1234L);
    }

    @Test
    @DisplayName("★★★ 什么都没在跑：加购拒、详情说不能买、货架上没有")
    void nothingLiveBlocksEverything() throws Exception {
        String buyer = login();

        assertThat(code(cartAdd(buyer))).as("加购就该拒，不等到结账").isEqualTo(ACTIVITY_ONLY);

        JsonNode d = detail();
        assertThat(d.get("saleMode").asString()).isEqualTo("ACTIVITY_ONLY");
        assertThat(d.get("directBuyable").asBoolean())
                .as("C 端底栏只看这个布尔 —— 它要是真，单买按钮就会出来").isFalse();

        assertThat(goodsService.suggest(title()))
                .as("货架（搜索联想）上不该有它 —— 顾客看得见、买不了").doesNotContain(title());
    }

    @Test
    @DisplayName("★★ 满减单独在跑，不让仅活动的货变得可买 —— 它是整单级的，不点名商品")
    void orderLevelCutDoesNotCount() throws Exception {
        start(cut());
        String buyer = login();

        assertThat(code(cartAdd(buyer))).isEqualTo(ACTIVITY_ONLY);
        assertThat(detail().get("directBuyable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("★★★ 活动结束的那一刻：同一件货从可买变不可买；车里那件变失效行")
    void endingFlipsBack() throws Exception {
        String flashNo = start(flash(1234L));
        String buyer = login();
        assertThat(code(cartAdd(buyer))).isZero();
        assertThat(detail().get("directBuyable").asBoolean()).isTrue();
        assertThat(goodsService.suggest(title())).as("有活动时照常上货架").contains(title());

        activityService.setStatus(ENTITY, flashNo, PmtActivity.ENDED);

        assertThat(detail().get("directBuyable").asBoolean()).isFalse();
        assertThat(code(order(buyer, false)))
                .as("★ 购物车里那件在活动结束后结账 —— 按下单那一刻判").isEqualTo(ACTIVITY_ONLY);
        JsonNode cart = cartList(buyer);
        assertThat(invalidOf(cart)).as("与下架同一处理：留一行失效，不抹掉").isTrue();
        assertThat(goodsService.suggest(title())).as("没活动了就从货架上消失").doesNotContain(title());
    }

    @Test
    @DisplayName("★★ 正常售卖的货：一切照旧（存量回归）")
    void normalUnchanged() throws Exception {
        jdbc.update("update prd_goods set sale_mode='NORMAL' where goods_no=?", GOODS);
        start(group());
        String buyer = login();

        assertThat(code(cartAdd(buyer))).isZero();
        assertThat(code(order(buyer, false))).as("正常售卖 + 拼团：单买照旧能下").isZero();
        assertThat(detail().get("directBuyable").asBoolean()).isTrue();
        assertThat(detail().get("saleMode").asString()).isEqualTo("NORMAL");
    }

    // ---------------------------------------------------------------- 活动

    private ActivityDraft group() {
        long now = System.currentTimeMillis();
        return new ActivityDraft(null, "仅活动 · 团 " + (++seq), "CLEAR", null,
                PmtActivity.TRIGGER_GROUP, null, 2,
                PmtActivity.BENEFIT_PRICE, 1000L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 100, null,
                List.of(), List.of(GOODS));
    }

    private ActivityDraft flash(long price) {
        long now = System.currentTimeMillis();
        return new ActivityDraft(null, "仅活动 · 特价 " + (++seq), "CLEAR", null,
                PmtActivity.TRIGGER_GOODS, null, null,
                PmtActivity.BENEFIT_PRICE, price, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 100, null,
                List.of(), List.of(GOODS));
    }

    private ActivityDraft cut() {
        long now = System.currentTimeMillis();
        return new ActivityDraft(null, "仅活动 · 满减 " + (++seq), "BASKET", null,
                PmtActivity.TRIGGER_AMOUNT, 100L, null,
                PmtActivity.BENEFIT_CUT, 100L, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + DAY, null, 100, null,
                List.of(), List.of());
    }

    private ActivityDraft batch() {
        long now = System.currentTimeMillis();
        String cutoff = LocalTime.now(ZONE).plusHours(3).format(DateTimeFormatter.ofPattern("HH:mm"));
        return new ActivityDraft(null, "仅活动 · 集单 " + (++seq), null, null,
                PmtActivity.TRIGGER_CUTOFF, null, null,
                PmtActivity.BENEFIT_PRICE, 5000L, null, null,
                PmtActivity.ALWAYS_ON, now - 1000, null, null, 1000, null,
                List.of(), List.of(GOODS),
                cutoff, 1, "09:00", null, null, null, null);
    }

    private String start(ActivityDraft d) {
        String no = activityService.save(ENTITY, d, "OP").activityNo();
        started.add(no);
        return no;
    }

    // ---------------------------------------------------------------- 接口

    private JsonNode cartAdd(String token) throws Exception {
        return json.readTree(mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":1}"))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode cartList(String token) throws Exception {
        return json.readTree(mvc().perform(get("/mp/cart").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
    }

    private boolean invalidOf(JsonNode cart) {
        for (JsonNode row : cart.get("data")) {
            if (SKU.equals(row.get("skuNo").asString())) {
                return row.get("invalid").asBoolean();
            }
        }
        throw new AssertionError("购物车里没有这件货：" + cart);
    }

    private JsonNode order(String token, boolean openGroup) throws Exception {
        String body = "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\""
                + (openGroup ? ",\"openGroup\":true" : "") + "}";
        return json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ao-" + (++seq))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private JsonNode detail() throws Exception {
        return json.readTree(mvc().perform(get("/mp/goods/" + GOODS))
                .andReturn().getResponse().getContentAsString()).get("data");
    }

    private static int code(JsonNode r) {
        return r.get("code").asInt();
    }

    private String title() {
        return jdbc.queryForObject("select title from prd_goods where goods_no=?", String.class, GOODS);
    }

    private String login() throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, "1370094" + String.format("%04d", ++seq % 10000));
    }

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }
}
