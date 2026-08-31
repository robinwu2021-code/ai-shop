package ai.neargo.shop.scenario;

import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.merchant.entity.MchViolation;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ViolationMapper;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.pay.entity.StlWithdraw;
import ai.neargo.shop.pay.mapper.SettleMappers.WithdrawMapper;
import ai.neargo.shop.support.TestLogin;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 运营端数据域**真的生效了**（TDD-运营端数据域接入，批①：{@code ord_sub_order}）。
 *
 * <p><b>此前的状态</b>：{@code sys_ops_staff} 上的 merchant_no / community_no / pickup_no
 * 存得下、算得出 {@code DataScopeSpec}、也签进了 {@code LoginUser} ——
 * 而每一条 ops 查询都 {@code executeWithoutScope} 主动绕开了它。
 * 于是「给客服配了只看城西片区，他照样看到全平台的单」，
 * 且配置页显示「已限定」。**一个看着生效、实际没有的限制，比没有限制更危险。**
 *
 * <p>这个文件按四个方向各钉一颗钉子，四条都是「界面正常、语义错」那一类，靠人点点不出来：
 * <ol>
 *   <li>配了商家域 / 社区域的运营，只看得到自己域里的单</li>
 *   <li><b>没配的人看全量</b>（Q3：空 = 不限定）—— 反过来改成「空 = 什么都看不到」，
 *       所有存量运营账号会一夜之间瞎掉</li>
 *   <li>超管恒 {@code ALL}（T1）</li>
 *   <li><b>写路径不受数据域影响</b>（T2）：域外主体的处置动作仍然是「明确的结果」，
 *       不是查不到那一行导致的静默 404</li>
 * </ol>
 *
 * <p><b>社区那一条是最关键的</b>：社区在主单上，子单没有 —— 数据域的锚点必须是
 * 本表上的一列，join 出来的够不着。所以 V137 给 {@code ord_sub_order} 加了冗余的
 * {@code community_no}。不加的话，`DataScopeHandler` 的 fail-closed 会让
 * 配了社区域的运营看到**整页空白且不报错**，而空白看起来像「这个片区今天没单」。
 * 下面 {@link #communityScopedSeesOnlyItsCommunity()} 撤掉那一列就会红。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsDataScopeFlowTest {

    /** 城西片区的买家（C0001 / M0001 的货）。号段与其他场景测试错开，避免发码限流互撞 */
    private static final String BUYER_WEST = "12600812001";
    /** 城东片区的买家（C0002 / M0002 的货） */
    private static final String BUYER_EAST = "13000813001";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;


    @Autowired
    private ObjectMapper json;

    @Autowired
    private OtpStore otpStore;

    @Autowired
    private WithdrawMapper withdrawMapper;

    @Autowired
    private ActivityMapper activityMapper;

    @Autowired
    private ViolationMapper violationMapper;

    @Autowired
    private AfterSaleMapper afterSaleMapper;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ─────────────────────────────────────────────────────── ① 域内可见、域外不可见

    @Test
    @DisplayName("★★★ 配了商家域的运营只看得到那一家的单 —— 此前他看到的是全平台")
    void merchantScopedSeesOnlyItsMerchant() throws Exception {
        seedOrders();
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-merchant", "M0001", null, null);

        List<String> merchants = merchantsOf(staff.token());
        assertThat(merchants)
                .as("配了 M0001 之后仍然看得到别家的单 —— 数据域没生效")
                .isNotEmpty()
                .containsOnly("M0001");
    }

    @Test
    @DisplayName("★★★ 配了社区域的运营只看得到本片区的单，且**不是空白**（V137 冗余列真的在用）")
    void communityScopedSeesOnlyItsCommunity() throws Exception {
        seedOrders();
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-community", null, "C0002", null);

        JsonNode records = ordersOf(staff.token());
        /*
         * **先断非空**再断内容。
         * 空列表是这条链路最可能的坏结局：`ord_sub_order` 上没有 community_no 时，
         * handler 拼的是 `1=0` —— 页面上看到的是「这个片区今天没单」，不报错、日志干净。
         */
        assertThat(records.size())
                .as("社区域运营看到空列表 —— 十有八九是 ord_sub_order 少了 community_no 锚点，"
                        + "handler 拼成了 1=0（fail-closed）")
                .isGreaterThan(0);
        for (JsonNode r : records) {
            assertThat(r.get("communityNo").asString())
                    .as("看到了不属于 C0002 的单")
                    .isEqualTo("C0002");
        }
    }

    @Test
    @DisplayName("★★★ 批① 提报队列按商家域收敛 —— 且**先断非空**，空列表才是这条链路最像成功的坏结局")
    void applyQueueIsScopedByMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);

        /*
         * 提报队列 `/ops/communities/applies` **不带商家参数** —— 它给的是全量待审，
         * 所以它正是数据域该起作用的地方（`cmt_community_apply` 2026-08-30 才登记）。
         *
         * <p>对照另一条：`adminService.appliesOf(merchantNo)` 是**按参数过滤**的，
         * 它验不到数据域 —— 拿它当验证会得到一个恒绿的假象。
         */
        /*
         * **用例自己造数据**：测试库里 cmt_community_apply 没有种子。
         * 造两家的各一条 —— 只造一家的话，「域内看得更少」会分不出
         * 「过滤生效了」和「本来就只有这一条」。
         */
        adminService.submitApply("M0001", "数据域测试·甲小区", "甲路 1 号",
                null, null, null, null, null, null);
        adminService.submitApply("M0003", "数据域测试·乙小区", "乙路 1 号",
                null, null, null, null, null, null);

        int all = applyCount(admin, null);
        /*
         * **先断非空。** 测试库里 cmt_community_apply 没有种子，全靠用例自己造；
         * 一条都没有的话，下面那句「域内运营看得更少」会因为 0 <= 0 恒真 ——
         * 一条永远不会失败的断言比没有断言更坏。
         */
        assertThat(all).as("超管看到的提报队列是空的 —— 这条用例此刻什么也没验到").isGreaterThan(0);

        // 配到一个不存在提报的商家上：域内应当看不到别人的单
        var scoped = staffWithScope(admin, "ds-apply", "M0002", null, null);
        assertThat(applyCount(scoped.token(), null))
                .as("配了 M0002 域的运营看到了不属于他的提报 —— 数据域没生效")
                .isLessThan(all);
    }

    @Test
    @DisplayName("★★★ 批② 提现队列按商家域收敛 —— 并且「看不见的单批不动」")
    void withdrawQueueIsScopedByMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);

        /*
         * `stl_withdraw` 今天没有生产者（B 端申请入口不在本批），测试库里也没有种子，
         * 所以用例自己造 —— 而且**必须造两家**：只造一家的话，
         * 「域内看得更少」分不出「过滤生效了」和「本来就只有这一条」。
         */
        String mine = seedWithdraw("M0002", "数据域测试·域内");
        seedWithdraw(OUTSIDE_MERCHANT, "数据域测试·域外");

        int all = withdrawCount(admin);
        // **先断非零对照**：0 的话下面那句 0 <= 0 恒真，一条永不失败的断言比没有更坏
        assertThat(all).as("超管看到的提现队列是空的 —— 这条用例此刻什么也没验到")
                .isGreaterThan(1);

        var scoped = staffWithScope(admin, "ds-withdraw", "FINANCE", "M0002", null, null);
        int seen = withdrawCount(scoped.token());
        assertThat(seen)
                .as("配了 M0002 域的财务看到了别家的提现申请 —— 而下一步就是审批打款")
                .isLessThan(all);
        assertThat(seen).as("域内那一张也看不到 —— 多半是 stl_withdraw 少了锚点、拼成了 1=0")
                .isGreaterThan(0);

        /*
         * **审批也要走数据域**。否则会变成「列表里看不到，但知道单号就能批」——
         * 那不是限制，那是障眼法。域外的单在这里查不到，落到 NOT_FOUND，
         * 与「列表里看不到」是同一个答案。
         */
        /*
         * 域外那一张挂在 **M0002 之外、但本身完全批得动**的商家上。
         * 一开始我用的是 M0003 —— 那张单即使被看见也会因为
         * `merchantPort.find(...).orElseThrow(NOT_FOUND)` 抛同一个错误码，
         * 于是断言**在两种情况下都绿**：消融掉数据域它照样通过。
         * 假绿比没有断言更坏，因为它会被当成「这条已经验过了」。
         */
        String outsider = seedWithdraw(OUTSIDE_MERCHANT, "数据域测试·域外二");
        mvc().perform(post("/ops/finance/withdrawals/" + outsider + "/decide")
                        .header("Authorization", "Bearer " + scoped.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":true}"))
                .andExpect(jsonPath("$.code").value(
                        ai.neargo.shop.common.ErrorCode.NOT_FOUND.code()));

        // 域内那一张仍然批得动 —— 否则上面那条只证明了「谁都批不动」
        mvc().perform(post("/ops/finance/withdrawals/" + mine + "/decide")
                        .header("Authorization", "Bearer " + scoped.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pass\":true}"))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 域外商家。挑它的条件是「被看见就一定批得动」—— 见 withdrawQueueIsScopedByMerchant 里的注释。 */
    private static final String OUTSIDE_MERCHANT = "M0001";

    @Test
    @DisplayName("★★★ 批③ 促销活动按商家域收敛 —— 券早就接上了，活动这页一直没有")
    void activityListIsScopedByMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);

        /*
         * `pmt_coupon` 2026-08-29 就接上了数据域，而**紧挨着的 activities 一直绕着**。
         * 两个方法在同一个 Service 里、两页长得一样 —— 于是从券那一页会得出
         * 「已经接了」的结论。这种不一致比整体都没接更难发现，所以单独钉一颗钉子。
         */
        seedActivity("M0002", "数据域测试·域内活动");
        seedActivity(OUTSIDE_MERCHANT, "数据域测试·域外活动");

        int all = activityCount(admin, null);
        // **先断非零对照**：0 的话下面那句 0 <= 0 恒真
        assertThat(all).as("超管看到的活动列表是空的 —— 这条用例此刻什么也没验到")
                .isGreaterThan(1);

        var scoped = staffWithScope(admin, "ds-activity", "CAMPAIGN_OPS", "M0002", null, null);
        int seen = activityCount(scoped.token(), null);
        assertThat(seen).as("配了 M0002 域的运营看到了别家的活动 —— 数据域没生效")
                .isLessThan(all);
        assertThat(seen).as("域内那一场也看不到 —— 多半是 pmt_activity 少了锚点、拼成了 1=0")
                .isGreaterThan(0);

        /*
         * **筛选参数与数据域是两回事**，一起验：参数答「我现在想看哪一家」，
         * 数据域答「你能看到哪些」。域外的商家号即使显式传进来也要看不到 ——
         * 否则「知道商家号就能绕过」，那不是限制。
         */
        assertThat(activityCount(scoped.token(), OUTSIDE_MERCHANT))
                .as("显式传域外商家号还能查到 —— 数据域被筛选参数绕过了")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 批③ 违规处置队列按商家域收敛 —— 这一页上是跨商家的经营信息")
    void violationQueueIsScopedByMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);

        seedViolation("M0002", "数据域测试·域内处置");
        seedViolation(OUTSIDE_MERCHANT, "数据域测试·域外处置");

        int all = violationCount(admin, null);
        assertThat(all).as("超管看到的处置队列是空的 —— 这条用例此刻什么也没验到")
                .isGreaterThan(1);

        var scoped = staffWithScope(admin, "ds-violation", "BD", "M0002", null, null);
        int seen = violationCount(scoped.token(), null);
        assertThat(seen)
                .as("配了 M0002 域的运营看到了别家的处置记录 —— 这一页有商家名、门店名与处置理由")
                .isLessThan(all);
        assertThat(seen).as("域内那一条也看不到 —— 多半是 mch_violation 少了锚点、拼成了 1=0")
                .isGreaterThan(0);

        // 筛选参数不能绕过数据域：知道商家号就能查，那不是限制
        assertThat(violationCount(scoped.token(), OUTSIDE_MERCHANT))
                .as("显式传域外商家号还能查到 —— 数据域被筛选参数绕过了")
                .isZero();
    }

    @Test
    @DisplayName("★★★ 批③ 平台仲裁工单池按商家域收敛 —— 下一步动作是裁决赔付")
    void afterSaleQueueIsScopedByMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);

        seedAfterSale("M0002", "数据域测试·域内工单");
        seedAfterSale(OUTSIDE_MERCHANT, "数据域测试·域外工单");

        int all = afterSaleCount(admin, null);
        assertThat(all).as("超管看到的工单池是空的 —— 这条用例此刻什么也没验到")
                .isGreaterThan(1);

        var scoped = staffWithScope(admin, "ds-aftersale", "CAMPAIGN_OPS", "M0002", null, null);
        int seen = afterSaleCount(scoped.token(), null);
        assertThat(seen)
                .as("配了 M0002 域的运营看到了别家的售后工单 —— 这一页有商家名与买家昵称，"
                        + "而下一步动作是裁决赔付")
                .isLessThan(all);
        assertThat(seen).as("域内那一条也看不到 —— 多半是 ord_after_sale 少了锚点、拼成了 1=0")
                .isGreaterThan(0);

        assertThat(afterSaleCount(scoped.token(), OUTSIDE_MERCHANT))
                .as("显式传域外商家号还能查到 —— 数据域被筛选参数绕过了")
                .isZero();
    }

    private void seedAfterSale(String entityNo, String reason) {
        OrdAfterSale a = new OrdAfterSale();
        a.setAfterSaleNo("AS-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        a.setSubOrderNo("SUB-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        a.setOrderNo("ORD-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        a.setUserNo("U-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        a.setEntityNo(entityNo);
        a.setType("REFUND");
        a.setStatus("PENDING");
        a.setReason(reason);
        a.setRefundMinor(100L);
        afterSaleMapper.insert(a);
    }

    private int afterSaleCount(String token, String merchantNo) throws Exception {
        var req = get("/ops/after-sales").header("Authorization", "Bearer " + token)
                .param("size", "200");
        if (merchantNo != null) {
            req = req.param("merchantNo", merchantNo);
        }
        String body = mvc().perform(req)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").size();
    }

    private void seedViolation(String entityNo, String detail) {
        MchViolation v = new MchViolation();
        v.setViolationNo("VIO-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        v.setEntityNo(entityNo);
        v.setType("SERVICE");
        v.setAction("WARN");
        v.setDetail(detail);
        v.setOperatorNo("OPS");
        v.setAt(System.currentTimeMillis());
        violationMapper.insert(v);
    }

    private int violationCount(String token, String merchantNo) throws Exception {
        var req = get("/ops/merchants/violations").header("Authorization", "Bearer " + token)
                .param("size", "200");
        if (merchantNo != null) {
            req = req.param("merchantNo", merchantNo);
        }
        String body = mvc().perform(req)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").size();
    }

    private void seedActivity(String entityNo, String name) {
        PmtActivity a = new PmtActivity();
        a.setActivityNo("ACT-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        a.setEntityNo(entityNo);
        a.setName(name);
        a.setTriggerType("NONE");
        a.setBenefitType("CUT");
        a.setBenefitAmountMinor(100L);
        a.setScheduleType("ALWAYS_ON");
        a.setStatus(PmtActivity.RUNNING);
        activityMapper.insert(a);
    }

    private int activityCount(String token, String entityNo) throws Exception {
        var req = get("/ops/promotion/activities").header("Authorization", "Bearer " + token);
        if (entityNo != null) {
            req = req.param("entityNo", entityNo);
        }
        String body = mvc().perform(req)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").size();
    }

    private String seedWithdraw(String entityNo, String name) {
        StlWithdraw w = new StlWithdraw();
        w.setWithdrawNo("WD-DS-" + java.util.UUID.randomUUID().toString().substring(0, 8));
        w.setEntityNo(entityNo);
        w.setMerchantName(name);
        w.setAmountMinor(10_000L);
        w.setAvailableBalanceMinor(100_000L);
        w.setBankAccountMasked("****8821");
        w.setStatus(StlWithdraw.PENDING);
        w.setAppliedAt(System.currentTimeMillis());
        withdrawMapper.insert(w);
        return w.getWithdrawNo();
    }

    private int withdrawCount(String token) throws Exception {
        String body = mvc().perform(get("/ops/finance/withdrawals")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "200"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").size();
    }

    private int applyCount(String token, String status) throws Exception {
        String body = mvc().perform(get("/ops/communities/applies")
                        .header("Authorization", "Bearer " + token)
                        .param("status", status == null ? "ALL" : status)
                        .param("size", "200"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records").size();
    }

    // ─────────────────────────────────────────────────────── ② 空 = 不限定（Q3）

    @Test
    @DisplayName("★★★ 没配数据域的运营看全量 —— 空 = 不限定，改成「空=看不到」会让存量账号一夜瞎掉")
    void unscopedStaffStillSeesEverything() throws Exception {
        seedOrders();
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-open", null, null, null);

        assertThat(merchantsOf(staff.token()))
                .as("没配数据域的账号看不到全量了 —— 空值语义被改成了「什么都看不到」")
                .contains("M0001", "M0002");
    }

    // ─────────────────────────────────────────────────────── ③ 超管恒 ALL（T1）

    @Test
    @DisplayName("★★ 超管恒 ALL —— 给超管配错一次数据域，平台就没人看得见全局了")
    void superAdminIsAlwaysAll() throws Exception {
        seedOrders();
        String admin = TestLogin.admin(mvc(), json);
        assertThat(merchantsOf(admin)).contains("M0001", "M0002");

        // 而且**根本不让配**：存下来的后果是配置页显示「已限定」、实际全量
        String adminNo = staffNoOf(admin, "admin");
        mvc().perform(post("/ops/staffs/" + adminNo + "/scope")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"merchantNo\":\"M0001\"}"))
                .andExpect(jsonPath("$.code").value(
                        ai.neargo.shop.common.ErrorCode.STAFF_SCOPE_ON_FULL_ACCESS.code()));

        assertThat(merchantsOf(admin))
                .as("超管被数据域限住了")
                .contains("M0001", "M0002");
    }

    // ─────────────────────────────────────────────────────── ④ 写路径不受影响（T2）

    @Test
    @DisplayName("★★★ 写路径不走数据域：域外主体的处置是**明确的结果**，不是静默的 404")
    void writePathIsNotSilencedByScope() throws Exception {
        String east = seedOrders().east();
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-write", "M0001", null, null);

        // 读：域外的单查不到（这是对的 —— 列表里不该出现别家的单）
        mvc().perform(get("/ops/orders/" + east).header("Authorization", "Bearer " + staff.token()))
                .andExpect(jsonPath("$.code").value(
                        ai.neargo.shop.common.ErrorCode.NOT_FOUND.code()));

        /*
         * 写：**同一张单**上的处置动作不能也变成 NOT_FOUND。
         *
         * 写路径也走数据域的话，「处置一家不在自己域内的商家」会变成静默失败 ——
         * 运营看到的是「查无此单」，会以为单号打错了，然后再试一遍。
         * 而正确的形状是明确的结果：要么按状态机执行、要么被 @PreAuthorize/归属校验明确拒绝。
         * 这里断的是「不是 404」，不断具体是哪一种 —— 具体哪一种由状态机决定，
         * 而这个测试守的是「数据域没把它吞掉」。
         */
        String body = mvc().perform(post("/ops/orders/" + east + "/intervene")
                        .header("Authorization", "Bearer " + staff.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"CANCELLED\",\"remark\":\"数据域写路径验证\"}"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(body).get("code").asInt())
                .as("域外主体的处置返回了 NOT_FOUND —— 写路径被数据域吞掉了，"
                        + "而静默失败比明确拒绝更坏（T2）")
                .isNotEqualTo(ai.neargo.shop.common.ErrorCode.NOT_FOUND.code());
    }

    // ─────────────────────────────────────────── 批③：商品（prd_goods）

    @Test
    @DisplayName("★★★ 批③ 商品池按商家域收敛 —— 配了 M0001 的运营看不到 M0002 的商品")
    void goodsPoolIsScopedToItsMerchant() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        // 商品页要 product:sku:read，SUPPORT 没有这个码 —— 用商品运营
        var staff = staffWithScope(admin, "ds-goods", "GOODS_OPS", "M0001", null, null);

        var mine = goodsPool(staff.token());
        assertThat(mine).as("配了商家域的运营，商品池里不该出现别家的货").isNotEmpty();
        assertThat(mine.values()).containsOnly("M0001");

        // 对照组：不设域的人看得到两家 —— 没有这一条，上面那句可能只是「查询坏了」
        var all = goodsPool(TestLogin.operator(mvc(), json, "goods", "goods123"));
        assertThat(all.keySet()).contains("G0001", "G0003");
    }

    @Test
    @DisplayName("★★ 批③ 待审队列同样收敛 —— 它是另一条查询，拆出来才接得上域")
    void auditQueueIsScopedToo() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-queue", "GOODS_OPS", "M0001", null, null);

        /*
         * 把 M0002 的一件商品置回待审。**直接改库是刻意的**：这里要验的是
         * 「队列这条查询接没接数据域」，而不是审核状态机 ——
         * 走真实链路会把这个测试绑在「商家怎么提审」上，那是另一件事的回归。
         */
        withAuditStatus("G0003", "AUDITING", () -> {
            assertThat(auditQueue(TestLogin.operator(mvc(), json, "goods", "goods123")))
                    .as("不设域的人应当看得到这件待审商品，否则下面那句断言是空的")
                    .contains("G0003");
            assertThat(auditQueue(staff.token()))
                    .as("配了 M0001 的审核员不该看到 M0002 的待审商品")
                    .doesNotContain("G0003");
        });
    }

    @Test
    @DisplayName("★★★ 批③ 的 T2：域外商品的详情是 404，而**强制下架仍然做得成**")
    void goodsWritePathIsNotSilencedByScope() throws Exception {
        String admin = TestLogin.admin(mvc(), json);
        var staff = staffWithScope(admin, "ds-goods-w", "GOODS_OPS", "M0001", null, null);

        // 读：域外商品查不到（返回 NOT_FOUND 而不是 403 —— 403 等于承认这个货号存在）
        mvc().perform(get("/ops/goods/G0004").header("Authorization", "Bearer " + staff.token()))
                .andExpect(jsonPath("$.code").value(
                        ai.neargo.shop.common.ErrorCode.NOT_FOUND.code()));

        /*
         * 写：**同一件商品**的强制下架不能也变成 NOT_FOUND。
         * 处置被数据域吞掉的后果不是拒绝，是静默失败 ——
         * 运营点了按钮、看到 200/404 各一半，而商品还在架上。
         *
         * 用 G0004（M0002 名下没人下单的那件）而不是 G0003：**这些用例共享一个 H2**，
         * 而强制下架是真的把商品压下去 —— 压掉 G0003 会让 seedOrders() 里的城东那笔单
         * 买不到货，然后**别的用例**红在一个与数据域毫无关系的地方（实测踩过）。
         * 即便如此也要还原：下一个用例可能正好要一件在售的 M0002 商品。
         */
        String was = auditStatusOf("G0004");
        String body;
        try {
            body = mvc().perform(post("/ops/goods/G0004/force-off")
                            .header("Authorization", "Bearer " + staff.token())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"数据域写路径验证\"}"))
                    .andReturn().getResponse().getContentAsString();
        } finally {
            restoreGoods("G0004", was);
        }
        assertThat(json.readTree(body).get("code").asInt())
                .as("域外商品的强制下架返回了 NOT_FOUND —— 写路径被数据域吞掉了（T2）")
                .isNotEqualTo(ai.neargo.shop.common.ErrorCode.NOT_FOUND.code());
    }

    // ────────────────────────────── 批④：结算（stl_bill）与履约（ord_sub_order）

    /*
     * ★ 履约（ord_sub_order）那两条查询（openPickupOrders / expressOrders）
     * **在批④ 里已经接上数据域**，同样没有对应的运行时用例，理由与结算那条一样、更具体：
     *
     * 分拣台只装「已签收批次覆盖到的自提点」的货 —— 要造出一行，得先建批次、
     * 发车、签收，再加上已付款的自提单。那整套夹具已经在 OpsFulfillmentFlowTest 里跑着。
     *
     * 第一版这里写过一个「配了 PP0001 的社区运营看不到 PP0002」的用例，
     * 而它的**对照组是空列表** —— 也就是说那条断言当时是恒真的。
     * 一个恒真的断言比没有断言更坏：它让人以为这块验过了。删掉，把缺口写在这里。
     *
     * 现在守着这两条的是 ops-data-scope.test.ts 的 G1（静态：方法体里不许再出现
     * executeWithoutScope）。补运行时验证时应当加在 OpsFulfillmentFlowTest。
     */

    /*
     * ★ 结算单（stl_bill）那两条查询（opsBills / opsPayables）**在批④ 里已经接上数据域**，
     * 但这里**没有**对应的运行时用例，理由要说清楚：
     *
     * 结算单不是下单就有的 —— 它在履约完成、进入结算那一刻才生成。
     * 在这个文件里造一笔真实结算单，等于把整条「下单 → 付款 → 核销 → 生成账单」
     * 搬过来，而那条链路已经在 OpsFinanceGovernFlowTest 里跑着。
     *
     * 现在守着这两条的是 `packages/shared/tests/ops-data-scope.test.ts` 的 G1：
     * 它静态地保证这两个方法里不再出现 executeWithoutScope。
     * **这比没有强，但比不上一次真实链路验证** —— 补的时候应当加在
     * OpsFinanceGovernFlowTest（那里账单是现成的），不是在这里重建一套账单夹具。
     */

    // ─────────────────────────────────────────────────────── 种子与工具

    /** 两笔真单：城西买家买 M0001 的货、城东买家买 M0002 的货。**自愈**：跑过一次也能再跑 */
    private record Seeded(String west, String east) {
    }

    private Seeded seedOrders() throws Exception {
        String west = buy(BUYER_WEST, "C0001", "PP0001", "G0001", "SK0001");
        String east = buy(BUYER_EAST, "C0002", "PP0002", "G0003", "SK0004");
        return new Seeded(west, east);
    }

    /**
     * 走**真实下单链路**而不是往 ord_sub_order 里直插一行。
     *
     * <p>这一点是刻意的：本轮改动里有一半在写路径上
     * （{@code OrderServiceImpl} 把主单的社区冗余进子单）。直插的话，
     * community_no 是测试自己填的 —— 于是「写路径忘了填」这个最可能的缺陷，
     * 测试反而看不见。
     *
     * @return 生成的子单号
     */
    private String buy(String phone, String communityNo, String pickupNo,
                       String goodsNo, String skuNo) throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, phone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"communityNo\":\"" + communityNo + "\",\"pickupNo\":\""
                                + pickupNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        mvc().perform(post("/mp/cart/add").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo + "\",\"qty\":1}"));
        mvc().perform(post("/mp/order").header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", "ds-" + phone + "-" + System.nanoTime())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"STORE_PICKUP\",\"pickupNo\":\"" + pickupNo + "\"}"))
                .andExpect(jsonPath("$.code").value(0));
        // 子单号从「我的订单」取：创建响应是支付视角，这一条才是子单视角
        String mine = mvc().perform(get("/mp/order").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(mine).get("data").get("records").get(0).get("orderNo").asString();
    }

    /** 商品池：goodsNo → merchantNo */
    private java.util.Map<String, String> goodsPool(String token) throws Exception {
        String body = mvc().perform(get("/ops/goods?page=1&size=200")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.LinkedHashMap<String, String>();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            out.put(r.get("goodsNo").asString(), r.get("merchantNo").asString());
        }
        return out;
    }

    private List<String> auditQueue(String token) throws Exception {
        String body = mvc().perform(get("/ops/goods/audit-queue?page=1&size=200")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        var out = new java.util.ArrayList<String>();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            out.add(r.get("goodsNo").asString());
        }
        return out;
    }

    /**
     * 临时把一件商品置成某个审核状态，跑完**一定改回去**。
     *
     * <p>场景测试共享一个 H2：不还原的话，下一个用例读到的 G0003 是待审的，
     * 而它的失败会指向一个与数据域毫无关系的地方。
     */
    private void withAuditStatus(String goodsNo, String status, ThrowingRunnable body) throws Exception {
        String was = setAuditStatus(goodsNo, status);
        try {
            body.run();
        } finally {
            setAuditStatus(goodsNo, was);
        }
    }

    private String auditStatusOf(String goodsNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo)
                        .last("limit 1")).getAuditStatus());
    }

    /** 把强制下架压下去的那件商品原样放回去（审核状态 + 在售位）。 */
    private void restoreGoods(String goodsNo, String auditStatus) {
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var g = goodsMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
            g.setAuditStatus(auditStatus);
            g.setOnSale(true);
            g.setAuditReason(null);
            return goodsMapper.updateById(g);
        });
    }

    private String setAuditStatus(String goodsNo, String status) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> {
            var g = goodsMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                    .eq(ai.neargo.shop.product.entity.PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
            assertThat(g).as("种子商品 %s 不存在", goodsNo).isNotNull();
            String was = g.getAuditStatus();
            g.setAuditStatus(status);
            goodsMapper.updateById(g);
            return was;
        });
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.community.service.CommunityAdminService adminService;

    private record Staff(String staffNo, String token) {
    }

    /**
     * 建一个客服账号并配上数据域，再用它登录。
     *
     * <p>用 SUPPORT 而不是超管：超管恒 ALL（T1），拿它测不出数据域生效没有。
     * **自愈**：同名账号已存在就复用，不让第二次跑因为「用户名重复」红掉。
     */
    private Staff staffWithScope(String adminToken, String username,
                                 String merchantNo, String communityNo, String pickupNo)
            throws Exception {
        return staffWithScope(adminToken, username, "SUPPORT", merchantNo, communityNo, pickupNo);
    }

    /**
     * 指定角色的版本。**商品页要 {@code product:sku:read}，而 SUPPORT 没有这个码** ——
     * 拿客服去测商品域的数据域，会先被权限拦住，然后看起来像「数据域生效了」。
     *
     * <p><b>这里要填后端的角色码，不是运营端界面上的那套</b>。两套并存是有意的
     * 历史遗留，`ops-web/lib/perm-map.test.ts` 里有别名表在对它们，三对异名同义：
     *
     * <pre>
     *   后端（这里填这个）   运营端界面
     *   BD                  MERCHANT_BD
     *   GOODS_OPS           PRODUCT_OPS
     *   SUPPORT             CS
     * </pre>
     *
     * 其余七个（ANALYST / AUDITOR / CAMPAIGN_OPS / COMMUNITY_OPS / FINANCE /
     * RISK / TECH_OPS）两边同名。填错的表现是建员工时 <b>10421 STAFF_ROLE_UNKNOWN</b>，
     * 而不是「这个人没权限」—— 认出这个码能省一轮排查。
     *
     * <p>填了个存在但缺权限码的角色更难认：请求会拿到 <b>10403</b>，
     * 看起来像「数据域把他挡住了」。角色的权限码见 {@code Perms.ROLE_PERMS}。
     */
    private Staff staffWithScope(String adminToken, String username, String role,
                                 String merchantNo, String communityNo, String pickupNo)
            throws Exception {
        /*
         * 用户名带一次性后缀。**不复用同名旧账号**：初始密码只在创建那一次返回，
         * 复用就得去改密码，而那要求先知道旧密码 —— 于是第二次跑必红。
         * 一次性后缀让这个测试对「跑过一遍的库」也是自愈的。
         */
        // 用户名必须是邮箱（STAFF_USERNAME_NOT_EMAIL）—— 初始密码就是发到这个地址的
        String unique = username + "-" + Long.toString(System.nanoTime() % 1_000_000L)
                + "@neargo.ai";
        String body = mvc().perform(post("/ops/staffs")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + unique + "\",\"realName\":\"数据域验证\","
                                + "\"roles\":[\"" + role + "\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = json.readTree(body).get("data");
        String staffNo = created.get("staff").get("staffNo").asString();
        String password = created.get("initialPassword").asString();
        mvc().perform(post("/ops/staffs/" + staffNo + "/scope")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{" + field("merchantNo", merchantNo) + ","
                                + field("communityNo", communityNo) + ","
                                + field("pickupNo", pickupNo) + "}"))
                .andExpect(jsonPath("$.code").value(0));
        return new Staff(staffNo, TestLogin.operator(mvc(), json, unique, password));
    }

    private static String field(String name, String value) {
        return "\"" + name + "\":" + (value == null ? "null" : "\"" + value + "\"");
    }

    private String staffNoOf(String adminToken, String username) throws Exception {
        String body = mvc().perform(get("/ops/staffs?page=1&size=200")
                        .header("Authorization", "Bearer " + adminToken))
                .andReturn().getResponse().getContentAsString();
        for (JsonNode r : json.readTree(body).get("data").get("records")) {
            if (username.equals(r.get("username").asString())) {
                return r.get("staffNo").asString();
            }
        }
        throw new AssertionError("没找到运营账号 " + username);
    }

    private JsonNode ordersOf(String token) throws Exception {
        String body = mvc().perform(get("/ops/orders?page=1&size=100")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("records");
    }

    private List<String> merchantsOf(String token) throws Exception {
        List<String> out = new java.util.ArrayList<>();
        for (JsonNode r : ordersOf(token)) {
            out.add(r.get("merchantNo").asString());
        }
        return out;
    }
}
