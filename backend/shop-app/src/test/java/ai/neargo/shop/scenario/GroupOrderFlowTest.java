package ai.neargo.shop.scenario;

import ai.neargo.shop.marketing.group.GroupService;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拼团接通下单（TDD-营销域-详细设计 §1.4 · F3 · 开发计划 P1b · PRD AC-5 / AC-6）。
 *
 * <p>参团 = 带团号下单，按团价收钱，<b>付款成功才算成员</b>；
 * 到期没成、商家散团、平台中止，已付款的参团单逐张系统全额退款。
 *
 * <p>下单、付款、查团都走<b>真 HTTP</b>：买家会话下团与活动都有数据域，
 * 直接调 service 的用例没有请求上下文，绿了也证明不了下单那一侧真的取得到团。
 *
 * <p>种子商品 G0001（M0001，SK0001 ¥49.80）。<b>每条用例结束时删掉拼团活动</b>：
 * 在跑的拼团活动是共享状态，留着会让别的用例在这件货上开团时撞上「一件货只能在一个团购活动里」。
 */
@SpringBootTest
@ActiveProfiles("test")
class GroupOrderFlowTest {

    private static final String STUB_SECRET = "stub-secret";
    private static final String ENTITY = "M0001";
    private static final String GOODS = "G0001";
    private static final String SKU = "SK0001";
    private static final long GROUP_PRICE = 1000L;

    private static int seq = 4400;

    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private ai.neargo.shop.common.OtpStore otpStore;
    @Autowired private ActivityService activityService;
    @Autowired private GroupService groupService;
    @Autowired private JdbcTemplate jdbc;

    private String activityNo;

    @BeforeEach
    void startGroupActivity() {
        long now = System.currentTimeMillis();
        activityNo = activityService.save(ENTITY, new ActivityDraft(
                null, "两人团 · " + (++seq), "GROUP", null,
                PmtActivity.TRIGGER_GROUP, null, 2,
                PmtActivity.BENEFIT_PRICE, GROUP_PRICE, null, null,
                PmtActivity.ONE_OFF, now - 1000, now + 86_400_000L, null, 100, null,
                List.of(), List.of(GOODS),
                null, null, null, null, null, null, 6), "TEST").activityNo();
    }

    @AfterEach
    void dropGroupActivity() {
        jdbc.update("delete from pmt_activity_goods where activity_no=?", activityNo);
        jdbc.update("delete from pmt_activity where activity_no=?", activityNo);
    }

    // ---------------------------------------------------------------- 用例

    @Test
    @DisplayName("★★★ 参团按团价收钱，付款成功才算成员；付款回调重放不重复加人；够人数成团（AC-5）")
    void memberOnlyAfterPayment() throws Exception {
        String groupNo = merchantGroup();
        String a = login(phone());

        String orderA = order(a, groupNo, false);
        Map<String, Object> subA = subOf(orderA);
        assertThat(subA.get("group_no")).as("★ 子单没挂团号 —— 到期退款找不到这张单").isEqualTo(groupNo);
        assertThat(((Number) subA.get("goods_amount")).longValue())
                .as("★ 没按团价收：参团单要按活动里的成团价").isEqualTo(GROUP_PRICE);
        assertThat(joined(groupNo))
                .as("★ 下了单还没付钱就算进人数了 —— 没付钱的人不该让「还差 N 人」变少").isZero();

        pay(orderA, "TX-G1-" + seq);
        assertThat(joined(groupNo)).as("付款成功应当落成员").isEqualTo(1);
        assertThat(jdbc.queryForObject("select sub_order_no from mkt_group_member where group_no=?",
                String.class, groupNo)).isEqualTo(subA.get("sub_order_no"));

        pay(orderA, "TX-G1-" + seq);   // 渠道重发同一笔回调
        assertThat(joined(groupNo)).as("★ 回调重放把同一个人加了两次").isEqualTo(1);

        pay(order(login(phone()), groupNo, false), "TX-G2-" + seq);
        assertThat(groupStatus(groupNo)).as("两人团够两人应当成团").isEqualTo("FORMED");
        assertThat(joined(groupNo)).isEqualTo(2);
    }

    @Test
    @DisplayName("★★ 预览就按团价算：确认页写的「参团 ¥10」，提交后不能变成原价")
    void previewUsesGroupPrice() throws Exception {
        String groupNo = merchantGroup();
        String a = login(phone());
        addToCart(a);
        JsonNode r = json.readTree(mvc().perform(post("/mp/order/preview").header("Authorization", "Bearer " + a)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":1}],"
                                + "\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"groupNo\":\"" + groupNo + "\"}"))
                .andReturn().getResponse().getContentAsString());
        assertThat(r.get("code").asInt()).as(r.toString()).isZero();
        assertThat(r.get("data").get("amount").get("goodsMinor").asLong()).isEqualTo(GROUP_PRICE);
    }

    @Test
    @DisplayName("★★★ 到期没成团：团置失败，已付款的参团单系统全额退款（AC-6）")
    void expiredGroupRefundsPaidMembers() throws Exception {
        String groupNo = merchantGroup();
        String orderNo = order(login(phone()), groupNo, false);
        pay(orderNo, "TX-G3-" + seq);

        jdbc.update("update mkt_group_buy set end_at=? where group_no=?", System.currentTimeMillis() - 1000, groupNo);
        groupService.expireOverdue(System.currentTimeMillis());

        assertThat(groupStatus(groupNo)).isEqualTo("FAILED");
        assertThat(subOf(orderNo).get("status"))
                .as("★ 团失败了钱没退 —— 此前到期只改状态，参团的人付的钱一分没回来").isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★ 商家散团退款；别家的团散不了（404，不说「无权」）")
    void dissolveRefunds() throws Exception {
        String groupNo = merchantGroup();
        String orderNo = order(login(phone()), groupNo, false);
        pay(orderNo, "TX-G4-" + seq);

        String outsider = TestLogin.merchantOwner(mvc(), json, otpStore, "126" + String.format("%08d", ++seq));
        mvc().perform(post("/biz/group/" + groupNo + "/dissolve").header("Authorization", "Bearer " + outsider)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"不想拼了\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(org.hamcrest.Matchers.oneOf(10403, 10404)));
        assertThat(groupStatus(groupNo)).as("外人不该散得了团").isEqualTo("OPEN");

        groupService.dissolve(ENTITY, groupNo, "货源不足");
        assertThat(groupStatus(groupNo)).isEqualTo("FAILED");
        assertThat(subOf(orderNo).get("status")).as("★ 散团没退款").isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★ 平台中止团也退款（此前只改状态，D5）")
    void opsAbortRefunds() throws Exception {
        String groupNo = merchantGroup();
        String orderNo = order(login(phone()), groupNo, false);
        pay(orderNo, "TX-G5-" + seq);

        groupService.abortGroup(groupNo, "团购价高于原价", "OP-1");
        assertThat(subOf(orderNo).get("status")).as("★ 中止团没退款").isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★ 团已散：带团号下单直接拒（付款前就知道），不是付完再退")
    void closedGroupRejectsOrder() throws Exception {
        String groupNo = merchantGroup();
        jdbc.update("update mkt_group_buy set status='FAILED' where group_no=?", groupNo);
        String a = login(phone());
        addToCart(a);
        JsonNode r = placeOrder(a, "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"groupNo\":\"" + groupNo + "\"}");
        assertThat(r.get("code").asInt()).isEqualTo(40030);
    }

    @Test
    @DisplayName("★★ 下单时团还在、付款时团已散：付进来的钱自动退掉")
    void paidAfterGroupFailedIsRefunded() throws Exception {
        String groupNo = merchantGroup();
        String orderNo = order(login(phone()), groupNo, false);
        jdbc.update("update mkt_group_buy set status='FAILED' where group_no=?", groupNo);

        pay(orderNo, "TX-G6-" + seq);
        assertThat(joined(groupNo)).as("散了的团不该再加人").isZero();
        assertThat(subOf(orderNo).get("status"))
                .as("★ 付款时团已散却没退 —— 钱付进来了，没有团可参").isEqualTo("REFUNDED");
    }

    @Test
    @DisplayName("★★ 已是成员的人不能再带团号下单（一人一团一份）")
    void memberCannotOrderAgain() throws Exception {
        String groupNo = merchantGroup();
        String a = login(phone());
        pay(order(a, groupNo, false), "TX-G7-" + seq);

        addToCart(a);
        JsonNode r = placeOrder(a, "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\",\"groupNo\":\"" + groupNo + "\"}");
        assertThat(r.get("code").asInt()).isEqualTo(10409);
    }

    @Test
    @DisplayName("★★★ 买家开团：下单即建团（挂活动、时限取活动），付了款才露在列表里")
    void buyerOpensGroupByOrder() throws Exception {
        String a = login(phone());
        String orderNo = order(a, null, true);
        String groupNo = (String) subOf(orderNo).get("group_no");
        assertThat(groupNo).as("★ 开团单没建出团").isNotNull();

        Map<String, Object> g = jdbc.queryForMap(
                "select activity_no, initiator_user_no, end_at, created_at from mkt_group_buy where group_no=?", groupNo);
        assertThat(g.get("activity_no")).as("团要挂上活动号（D6）").isEqualTo(activityNo);
        assertThat(g.get("initiator_user_no")).isNotNull();
        long hours = (((Number) g.get("end_at")).longValue() - System.currentTimeMillis() + 60_000) / 3_600_000L;
        assertThat(hours).as("★ 成团时限没取活动里的 6 小时").isEqualTo(6);

        String before = mvc().perform(get("/mp/group-buy")).andReturn().getResponse().getContentAsString();
        assertThat(before).as("没付钱的开团不该挂在列表里 —— 那是一个零人的团").doesNotContain(groupNo);

        pay(orderNo, "TX-G8-" + seq);
        assertThat(joined(groupNo)).as("发起人付了款才是第一人").isEqualTo(1);
        String after = mvc().perform(get("/mp/group-buy")).andReturn().getResponse().getContentAsString();
        assertThat(after).contains(groupNo);
        mvc().perform(get("/mp/group-buy/" + groupNo))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.data.activityName").isNotEmpty());
    }

    @Test
    @DisplayName("★ 商品详情拼团块：开团价与人数来自活动，只列还在拼、且有人付过款的团（s21）")
    void goodsPageGroupBlock() throws Exception {
        String groupNo = merchantGroup();
        JsonNode d = json.readTree(mvc().perform(get("/mp/goods/" + GOODS + "/group"))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(d.get("groupPrice").asLong()).isEqualTo(GROUP_PRICE);
        assertThat(d.get("minCount").asInt()).isEqualTo(2);
        assertThat(d.get("groupHours").asInt()).isEqualTo(6);
        assertThat(d.get("openGroups").toString()).contains(groupNo);

        jdbc.update("update mkt_group_buy set status='FAILED' where group_no=?", groupNo);
        assertThat(json.readTree(mvc().perform(get("/mp/goods/" + GOODS + "/group"))
                        .andReturn().getResponse().getContentAsString()).get("data").get("openGroups").toString())
                .as("散了的团不该还挂在商品页上").doesNotContain(groupNo);
    }

    // ---------------------------------------------------------------- 夹具

    /** 商家开团（s34），团价与人数从活动带出来 */
    @Test
    @DisplayName("★★★ 我的拼团（p12）：付了款的团进我的列表、最近在前；别人的团不混进来")
    void myJoinedGroupsListsOnlyMine() throws Exception {
        String a = login(phone());
        String g1 = merchantGroup();
        pay(order(a, g1, false), "TX-M1-" + seq);
        String g2 = (String) subOf(order(a, null, true)).get("group_no");
        pay(jdbc.queryForObject("select order_no from ord_sub_order where group_no=? limit 1", String.class, g2),
                "TX-M2-" + seq);
        // 对照：另一个人开的团，**而且付了款** —— 没付款不进成员表，
        // 那样「按人过滤」去掉了也看不出区别（第一版就是这样，消融没变红）
        String b = login(phone());
        String payB = order(b, null, true);
        String gB = (String) subOf(payB).get("group_no");
        pay(payB, "TX-MB-" + seq);
        assertThat(joined(gB)).as("对照量先验：b 真的进了成员表").isEqualTo(1);

        JsonNode mine = json.readTree(mvc().perform(get("/mp/group-buy/mine").header("Authorization", "Bearer " + a))
                .andReturn().getResponse().getContentAsString()).get("data");
        java.util.List<String> nos = new java.util.ArrayList<>();
        mine.forEach(n -> nos.add(n.get("groupNo").asString()));
        assertThat(nos).as("最近参的在前").containsExactly(g2, g1);
        assertThat(nos).doesNotContain(gB);
        closeOut(g1, g2, gB);
    }

    @Test
    @DisplayName("★★ 团详情的 myOrderNo：参团的人看到自己那一单，没参团的看到空")
    void myOrderNoOnlyForMembers() throws Exception {
        String groupNo = merchantGroup();
        String a = login(phone());
        String payNo = order(a, groupNo, false);
        pay(payNo, "TX-M3-" + seq);
        String sub = (String) subOf(payNo).get("sub_order_no");

        JsonNode mineView = json.readTree(mvc().perform(get("/mp/group-buy/" + groupNo)
                .header("Authorization", "Bearer " + a)).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(mineView.get("myOrderNo").asString()).isEqualTo(sub);

        // 订单详情带团号：支付页付完落团页、订单详情画拼团进度卡都靠它（此前 OrderVO 从没下发过）
        JsonNode payDetail = json.readTree(mvc().perform(get("/mp/order/" + payNo)
                .header("Authorization", "Bearer " + a)).andReturn().getResponse().getContentAsString()).get("data");
        String viaPay = payDetail.get("groupNo").isNull() && payDetail.get("subOrders").size() > 0
                ? payDetail.get("subOrders").get(0).get("groupNo").asString() : payDetail.get("groupNo").asString();
        assertThat(viaPay).isEqualTo(groupNo);
        JsonNode subDetail = json.readTree(mvc().perform(get("/mp/order/" + sub)
                .header("Authorization", "Bearer " + a)).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(subDetail.get("groupNo").asString()).isEqualTo(groupNo);

        String other = login(phone());
        JsonNode otherView = json.readTree(mvc().perform(get("/mp/group-buy/" + groupNo)
                .header("Authorization", "Bearer " + other)).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(otherView.get("myOrderNo").isNull()).as("没参团的人不该看到别人的单号").isTrue();
        closeOut(groupNo);
    }

    /**
     * 收掉本用例开的团。**不收的话会串到别的用例**：商品页的「正在拼」按差人最少排、最多列 3 个，
     * 这里留下的「还差 1 人」会把 goodsPageGroupBlock 新开的团（差 2 人）挤出前 3 —— 单独跑绿、全量红。
     */
    private void closeOut(String... groupNos) {
        for (String g : groupNos) {
            jdbc.update("update mkt_group_buy set status='FAILED' where group_no=?", g);
        }
    }

    private String merchantGroup() {
        return groupService.createMerchantGroup(ENTITY, GOODS, activityNo, null).groupNo();
    }

    /** 加购并下单（参团或开团），不付款。返回支付单号 */
    private String order(String token, String groupNo, boolean openGroup) throws Exception {
        addToCart(token);
        String body = "{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"PP0001\""
                + (groupNo == null ? "" : ",\"groupNo\":\"" + groupNo + "\"")
                + (openGroup ? ",\"openGroup\":true" : "") + "}";
        JsonNode r = placeOrder(token, body);
        assertThat(r.get("code").asInt()).as("下单失败：" + r).isZero();
        return r.get("data").get("payOrderNo").asString();
    }

    private void addToCart(String token) throws Exception {
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"goodsNo\":\"" + GOODS + "\",\"skuNo\":\"" + SKU + "\",\"qty\":1}"))
                .andExpect(status().isOk());
    }

    private JsonNode placeOrder(String token, String body) throws Exception {
        return json.readTree(mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "group-" + (++seq))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse().getContentAsString());
    }

    private void pay(String orderNo, String tx) throws Exception {
        mvc().perform(post("/pay/callback/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + orderNo + "\",\"transactionId\":\"" + tx
                                + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());
    }

    private Map<String, Object> subOf(String orderNo) {
        return jdbc.queryForMap("select sub_order_no, group_no, status, goods_amount "
                + "from ord_sub_order where order_no=? limit 1", orderNo);
    }

    private int joined(String groupNo) {
        return jdbc.queryForObject("select joined_count from mkt_group_buy where group_no=?", Integer.class, groupNo);
    }

    private String groupStatus(String groupNo) {
        return jdbc.queryForObject("select status from mkt_group_buy where group_no=?", String.class, groupNo);
    }

    private String phone() {
        return "1370044" + String.format("%04d", ++seq % 10000);
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
