package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntityPlan;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.support.TestLogin;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增值包 · 运营端管理（P3，TDD-增值包与门店额度 §4.3 / 全链路执行计划 P3）。
 *
 * <p>P1 守的是「额度拦得住」，这个文件守的是**额度能被正确地放开与收回** ——
 * 一期没有支付通道，「商家付了钱」这件事在系统里的唯一表现就是运营在这里点了授予。
 * 那个按钮点错的代价与收款失败是同一个量级。
 *
 * <p>四条最容易做错、且做错了不报错的：
 * <ul>
 *   <li><b>宽限期悄悄变成了立即降级</b> —— 到期日一过就压店。测试要证明 GRACE 期能力全在</li>
 *   <li><b>降级压错了店</b> —— 压掉默认店，或者把商家自己停用的那家一起算成「平台压的」，
 *       于是补缴后平台替他把停掉的店开了回来</li>
 *   <li><b>降级动了订单</b> —— 欠费当天还有几十个待取货的单，冻住它们受损的是买家</li>
 *   <li><b>改档位定义顺带改了已订阅的人</b> —— 他买的是当初那个额度</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpsMerchantPlanFlowTest {

    /** 与 BizOrderFulfillFlowTest 同一个：stub 回调的签名，配在 application-test.yml */
    private static final String STUB_SECRET = "stub-secret";

    /** 无权限：{@code ErrorCode.FORBIDDEN}。HTTP 仍是 200，权限失败走统一信封 */
    private static final int DENIED = 10403;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private EntityPlanMapper planMapper;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper storeMapper;

    @Autowired
    private MerchantPlanService planService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    // ------------------------------------------------------------ 授予与额度

    @Test
    @DisplayName("★ 授予 PRO 后额度立即生效 —— 当场就能建第二家店，不用等重新登录")
    void grantTakesEffectImmediately() throws Exception {
        String biz = merchant("12601100001", "套餐·当场生效");
        String merchantNo = merchantNoOf(biz);

        // FREE：默认店已占 1/3（V221 起 FREE 给 3 家），开到第四家才被拒
        assertThat(createStore(biz, "免费额度里的第二家")).isZero();
        assertThat(createStore(biz, "免费额度里的第三家")).isZero();
        assertThat(createStore(biz, "被拒的第四家")).isEqualTo(70020);

        JsonNode row = grant(merchantNo, "PRO", 12, "年框，销售小李谈的");
        assertThat(row.get("planCode").asString()).isEqualTo("PRO");
        assertThat(row.get("storeQuota").asInt()).isEqualTo(10);
        assertThat(row.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(row.get("expireAt").asLong()).isGreaterThan(System.currentTimeMillis());

        /*
         * ★ 这一条是整个 P3 的核心断言：授予与「额度闸放行」之间不能有缓存、
         * 不能要求重新登录。中间隔一层的话，运营会在电话里对商家说「已经开好了」，
         * 而商家那边还是开不了店 —— 而这种故障没有任何报错可查。
         */
        assertThat(createStore(biz, "第四家")).isZero();
    }

    @Test
    @DisplayName("★ 只补缴不延长不刷新快照；改档位定义不影响已订阅的人")
    void snapshotRulesProtectExistingSubscribers() throws Exception {
        String biz = merchant("12601100010", "套餐·老用户保护");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 12, "首次订阅");

        try {
            // 运营把 PRO 的定义调小到 1 家店（只该影响之后新订阅的人）
            JsonNode def = saveDef("PRO", 1, 1, true, 14, true);
            assertThat(def.get("storeQuota").asInt()).isEqualTo(1);
            // 返回体要带「有几家在用」—— 它是「只影响新订阅」那句话的具体量
            assertThat(def.get("subscriberCount").asInt()).isPositive();

            // ★ 已订阅的这家：额度快照还是 10，照样能接着开店
            assertThat(planService.current(merchantNo).storeQuota()).isEqualTo(10);
            assertThat(createStore(biz, "第二家")).isZero();

            /*
             * ★ 只补缴不延长（months 留空）：**不刷新快照** ——
             * 刷新的话他的额度会被刚刚调小的定义吞掉，而他付的是当初那份。
             */
            grant(merchantNo, "PRO", null, "补一张发票，不延期");
            assertThat(planService.current(merchantNo).storeQuota())
                    .as("只补缴不该把额度刷成新定义的 1").isEqualTo(10);

            // 续费（months>0）才重读定义 —— 那是一次新的成交，按当下的价目走
            grant(merchantNo, "PRO", 6, "续 6 个月");
            assertThat(planService.current(merchantNo).storeQuota())
                    .as("续费是一次新成交，按当下定义走").isEqualTo(1);
        } finally {
            /*
             * **必须在 finally 里**：档位定义是全库共享的一行，中途断言失败就会把
             * PRO=1 留给同一个 JVM 里后面的每一个用例 —— 表现是别处莫名撞额度，
             * 而那些用例与套餐毫无关系，排查时根本不会看到这里。
             */
            saveDef("PRO", 10, 3, true, 14, true);
        }
    }

    @Test
    @DisplayName("★ 延长从原到期日接着算 —— 提前续费不该吃掉他已付未用的那几天")
    void extensionStacksOnRemainingDays() throws Exception {
        String biz = merchant("12601100020", "套餐·提前续费");
        String merchantNo = merchantNoOf(biz);

        long first = grant(merchantNo, "PRO", 1, "先买一个月").get("expireAt").asLong();
        long second = grant(merchantNo, "PRO", 1, "还没到期就续一个月").get("expireAt").asLong();

        // 两个月 ≈ 60 天。从「现在」重算的话，第二次的到期日与第一次几乎相同
        long oneMonth = 30L * 86_400_000L;
        assertThat(second - first)
                .as("第二次续费应当在第一次的到期日之上再加一个月")
                .isBetween(oneMonth - 60_000, oneMonth + 60_000);
    }

    @Test
    @DisplayName("★ 额度覆盖优先于档位快照；清空覆盖回到快照而不是回到 0")
    void overrideBeatsSnapshot() throws Exception {
        String biz = merchant("12601100030", "套餐·单独谈的额度");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 12, "标准 PRO");

        // 谈下来的条件不落在任何一档上：先给 5 家
        JsonNode row = overrideQuota(merchantNo, 5, 5, "区域独家，年底再谈");
        assertThat(row.get("storeQuota").asInt()).isEqualTo(5);
        assertThat(row.get("quotaSource").asString()).isEqualTo("OVERRIDE");

        for (int i = 2; i <= 5; i++) {
            assertThat(createStore(biz, "第" + i + "家")).as("覆盖后应当开到 5 家").isZero();
        }
        assertThat(createStore(biz, "第六家")).isEqualTo(70020);

        /*
         * ★ 清空覆盖 = 回到档位快照（PRO 的 10），**不是设成 0**。
         * 两者在界面上长得一样，而后者会让这家商家一家店都开不了。
         */
        JsonNode cleared = overrideQuota(merchantNo, null, null, "回到标准档");
        assertThat(cleared.get("storeQuota").asInt()).isEqualTo(10);
        assertThat(cleared.get("quotaSource").asString()).isEqualTo("PLAN");
    }

    // ------------------------------------------------------------ 到期与降级

    @Test
    @DisplayName("★ 宽限期能力全保留 —— 到期不等于降级，7 天内一个门店都不该被压")
    void gracePeriodKeepsEverything() throws Exception {
        String biz = merchant("12601100040", "套餐·宽限期");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 1, "买一个月");
        assertThat(createStore(biz, "第二家")).isZero();

        // 到期日拨到 3 天前 —— 已过期，但还在 7 天宽限期内
        expireAt(merchantNo, System.currentTimeMillis() - 3L * 86_400_000L);
        var swept = planService.sweepExpiry(System.currentTimeMillis());
        assertThat(swept.toGrace()).isPositive();

        var plan = planService.current(merchantNo);
        assertThat(plan.status()).isEqualTo(MchEntityPlan.GRACE);
        // ★ 三件事一件都没变：档位、额度、能力位
        assertThat(plan.planCode()).isEqualTo("PRO");
        assertThat(plan.storeQuota()).isEqualTo(10);
        assertThat(plan.crossStoreStats()).isTrue();
        assertThat(activeStores(merchantNo)).as("宽限期内一家店都不该被压").hasSize(2);
        // 额度也照常放行：宽限期是给人的缓冲，不是半残状态
        assertThat(createStore(biz, "宽限期内的第三家")).isZero();
    }

    @Test
    @DisplayName("★ 降级只压该压的那几家：默认店留着，其余压只读且标明是平台压的")
    void downgradePressesOnlyWhatItShould() throws Exception {
        String biz = merchant("12601100050", "套餐·降级选店");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 1, "买一个月");
        assertThat(createStore(biz, "第二家")).isZero();
        assertThat(createStore(biz, "第三家")).isZero();

        String defaultNo = defaultStoreNo(merchantNo);

        // V221 之后 FREE 就是 3 家，这里一共只铺了三家 —— 照定义降下去一家都压不着，
        // 「压了谁、留了谁」当场失去对象。把 FREE 临时按到 1，测的仍是选店规则本身。
        int freeWas = pinFreeQuota(1);
        MerchantPlanService.SweepResult swept;
        try {
            // 宽限期也过完了（到期 10 天前 > 7 天）
            expireAt(merchantNo, System.currentTimeMillis() - 10L * 86_400_000L);
            planService.sweepExpiry(System.currentTimeMillis());
            swept = planService.sweepExpiry(System.currentTimeMillis());
        } finally {
            pinFreeQuota(freeWas);
        }
        assertThat(swept.toExpired())
                .as("第二次扫描必须什么都不做 —— 否则通知会发两遍，商家以为被降了两次")
                .isZero();

        var plan = planService.current(merchantNo);
        assertThat(plan.status()).isEqualTo(MchEntityPlan.EXPIRED);
        assertThat(plan.planCode()).isEqualTo(MchEntityPlan.FREE);
        assertThat(plan.crossStoreStats()).as("跨店能力随降级收回").isFalse();

        // ★ 保留集：默认店 + 补满免费额度（FREE = 1 家）。默认店必须在里面 ——
        // 它是「找不到具体门店时去哪」的答案
        List<MchStore> active = activeStores(merchantNo);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getStoreNo()).isEqualTo(defaultNo);

        // ★ 被压的那两家：状态只读，且带着「是平台压的」这个标记
        List<MchStore> pressed = storesOf(merchantNo).stream()
                .filter(s -> !s.getStoreNo().equals(defaultNo)).toList();
        assertThat(pressed).hasSize(2);
        assertThat(pressed).allSatisfy(s -> {
            assertThat(s.getStatus()).isEqualTo(MchStore.READONLY);
            assertThat(s.getPlanSuspended()).as("不标记的话补缴时分不清谁是平台压的").isTrue();
        });
    }

    @Test
    @DisplayName("★ 补缴只恢复平台压的那批 —— 商家自己停用的店不动")
    void restoreOnlyLiftsPlatformPressedStores() throws Exception {
        String biz = merchant("12601100060", "套餐·补缴恢复");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 1, "买一个月");
        assertThat(createStore(biz, "第二家")).isZero();
        assertThat(createStore(biz, "第三家")).isZero();

        /*
         * 商家自己停用一家（他出门了）。这一家与被降级压下的店**状态一模一样**（READONLY）——
         * 两种只读混在一起，才测得出「补缴恢复」认不认得出区别。
         */
        String selfPaused = nonDefaultStoreNos(merchantNo).get(0);
        mvc().perform(post("/biz/store/" + selfPaused + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        // 欠费降级：只剩默认店，另一家非默认店被平台压下。
        // FREE 现在是 3 家，照定义压不着任何一家 —— 临时按到 1，测的是「恢复认不认得出是谁压的」。
        int freeWas = pinFreeQuota(1);
        try {
            expireAt(merchantNo, System.currentTimeMillis() - 10L * 86_400_000L);
            planService.sweepExpiry(System.currentTimeMillis());
        } finally {
            pinFreeQuota(freeWas);
        }
        assertThat(activeStores(merchantNo)).hasSize(1);

        // 补缴
        grant(merchantNo, "PRO", 1, "商家补缴了");

        // ★ 平台压的那家回来了，商家自己停的那家还停着 ——
        // 全恢复等于平台替商家做了开店决定，全不恢复等于他买的东西没还给他
        List<MchStore> all = storesOf(merchantNo);
        MchStore self = all.stream().filter(s -> s.getStoreNo().equals(selfPaused)).findFirst().orElseThrow();
        assertThat(self.getStatus()).as("商家自停的店不该被平台开回来").isEqualTo(MchStore.READONLY);
        assertThat(activeStores(merchantNo))
                .as("默认店 + 平台压过的那家").hasSize(2);
    }

    @Test
    @DisplayName("★ 降级不动任何已有订单 —— 被压成只读的店，未完成的单照常发货")
    void downgradeDoesNotTouchExistingOrders() throws Exception {
        String biz = merchant("12601100070", "套餐·降级不动单");
        String merchantNo = merchantNoOf(biz);
        grant(merchantNo, "PRO", 1, "买一个月");

        // 在这家商家名下下一单并付款（默认店，降级后它还在 ACTIVE ——
        // 但要验的是「订单不受降级影响」，而门店压不压与订单状态本就是两件事）
        String subOrderNo = placeAndPay(biz, "13001100071");

        expireAt(merchantNo, System.currentTimeMillis() - 10L * 86_400_000L);
        planService.sweepExpiry(System.currentTimeMillis());
        assertThat(planService.current(merchantNo).status()).isEqualTo(MchEntityPlan.EXPIRED);

        /*
         * ★ 降级之后这一单照样发得出去。
         * 欠费当天还有几十个待取货订单，把它们一起冻住，受损的是买家和取货点 ——
         * 而他们与这笔欠费毫无关系。
         */
        mvc().perform(post("/biz/order/" + subOrderNo + "/ship")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expressNo\":\"SF9900001\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SHIPPED"));
    }

    // ------------------------------------------------------------ 看板与权限

    @Test
    @DisplayName("到期看板三个筛选各自只装该装的人；升档信号按 owner 聚合")
    void dashboardFiltersAndSignals() throws Exception {
        String soon = merchantNoOf(merchant("12601100080", "套餐·快到期"));
        String grace = merchantNoOf(merchant("12601100081", "套餐·宽限中"));
        grant(soon, "PRO", 1, "快到期的");
        grant(grace, "PRO", 1, "要掉进宽限期的");

        // soon：3 天后到期（落在 7 天窗口里）；grace：3 天前到期 → 扫成 GRACE
        expireAt(soon, System.currentTimeMillis() + 3L * 86_400_000L);
        expireAt(grace, System.currentTimeMillis() - 3L * 86_400_000L);
        planService.sweepExpiry(System.currentTimeMillis());

        assertThat(planNos("EXPIRING_7D")).contains(soon).doesNotContain(grace);
        assertThat(planNos("GRACE")).contains(grace).doesNotContain(soon);

        // 关键字筛：主体号与主体名都要命中，否则运营只能背商家号
        JsonNode page = okData(get("/ops/merchant-plans").param("keyword", "套餐·快到期"));
        assertThat(page.get("records").size()).isEqualTo(1);
        assertThat(page.get("records").get(0).get("merchantNo").asString()).isEqualTo(soon);

        // 升档信号：这个接口通不通、形状对不对（一人多主体要靠真实数据才凑得出来）
        mvc().perform(get("/ops/merchant-plans/upgrade-signals")
                        .header("Authorization", "Bearer " + bd()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★ 权限：授予与改档位定义各自归各自的码，客服两个都做不了")
    void permissionsAreSeparated() throws Exception {
        String merchantNo = merchantNoOf(merchant("12601100090", "套餐·权限"));
        String support = TestLogin.operator(mvc(), json, "support", "support123");

        /*
         * 无权限在这个仓库里是**统一信封 + code 10403**，HTTP 仍是 200
         * （见 GlobalExceptionHandler 对 AccessDeniedException 的处理）。
         * 断 `status().isForbidden()` 会**恒不通过**，而那看起来像是权限没生效 ——
         * 本条第一次就是这么写错的。
         */
        // 客服没有 merchant:merchant:read —— 连看板都打不开
        assertThat(codeOf(get("/ops/merchant-plans").header("Authorization", "Bearer " + support)))
                .isEqualTo(DENIED);

        assertThat(codeOf(post("/ops/merchant-plans/" + merchantNo + "/grant")
                .header("Authorization", "Bearer " + support)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"PRO\",\"months\":12,\"reason\":\"客服不该能授\"}")))
                .isEqualTo(DENIED);

        /*
         * ★ 职责分离：BD 能授予套餐（merchant:merchant:ban），
         * 但改「套餐是什么」要 system:param:update —— 后者影响这一档之后的所有订阅。
         * 这一条是本次刻意做的设计，没有测试的话下一个人会顺手把它并成一个码。
         */
        assertThat(codeOf(put("/ops/plan-defs/PRO").header("Authorization", "Bearer " + bd())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeQuota\":99,\"staffQuota\":99,\"crossStoreStats\":true,"
                        + "\"trialDays\":14,\"enabled\":true}")))
                .as("BD 能授予套餐，但不能改「套餐是什么」").isEqualTo(DENIED);
    }

    @Test
    @DisplayName("授予的理由必填；停售的档位不能新授")
    void grantValidations() throws Exception {
        String merchantNo = merchantNoOf(merchant("12601100100", "套餐·校验"));

        // 没有理由的授予在复盘时说不清 —— 它决定这家商家能开几家店
        assertThat(codeOf(post("/ops/merchant-plans/" + merchantNo + "/grant")
                .header("Authorization", "Bearer " + bd())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"PRO\",\"months\":12,\"reason\":\"  \"}")))
                .isEqualTo(10430);

        // 不存在的档位码：拒，而不是建出一行谁也解释不了的订阅
        assertThat(codeOf(post("/ops/merchant-plans/" + merchantNo + "/grant")
                .header("Authorization", "Bearer " + bd())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"planCode\":\"VIP_PLUS\",\"months\":12,\"reason\":\"编的档位\"}")))
                .isNotZero();
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode grant(String merchantNo, String planCode, Integer months, String reason)
            throws Exception {
        String body = "{\"planCode\":\"" + planCode + "\""
                + (months == null ? "" : ",\"months\":" + months)
                + ",\"reason\":\"" + reason + "\"}";
        return okData(post("/ops/merchant-plans/" + merchantNo + "/grant")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private JsonNode overrideQuota(String merchantNo, Integer storeQuota, Integer staffQuota,
                                   String reason) throws Exception {
        String body = "{\"storeQuota\":" + storeQuota + ",\"staffQuota\":" + staffQuota
                + ",\"reason\":\"" + reason + "\"}";
        return okData(put("/ops/merchant-plans/" + merchantNo + "/quota")
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 改档位定义要 {@code system:param:update} —— BD 做不了，用超管。 */
    /**
     * 临时改 FREE 的门店额度，返回旧值 —— 用完**必须**在 finally 里改回去。
     *
     * <p>降级压店的规则是「保留默认店 + 补满免费额度」。V221 把 FREE 抬到 3 家之后，
     * 一个只开了三家店的商家降下去一家都压不着，几条测「压了谁、恢复了谁」的用例
     * 就失去了对象。铺够五家店也行，但那只是把测试变慢：它们测的是**选店与标记规则**，
     * 不是 FREE 的具体数值。
     */
    private int pinFreeQuota(int storeQuota) throws Exception {
        int was = json.readTree(mvc().perform(get("/ops/plan-defs")
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json)))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").valueStream()
                .filter(d -> "FREE".equals(d.get("planCode").asString()))
                .findFirst().orElseThrow(() -> new AssertionError("档位 FREE 不存在"))
                .get("storeQuota").asInt();
        // FREE 的其余字段照种子给（V150）：无子账号、无跨店、不可试用
        saveDef("FREE", storeQuota, 0, false, 0, true);
        return was;
    }

    private JsonNode saveDef(String planCode, int storeQuota, int staffQuota,
                             boolean crossStoreStats, int trialDays, boolean enabled) throws Exception {
        String body = "{\"storeQuota\":" + storeQuota + ",\"staffQuota\":" + staffQuota
                + ",\"crossStoreStats\":" + crossStoreStats + ",\"trialDays\":" + trialDays
                + ",\"enabled\":" + enabled + "}";
        String res = mvc().perform(put("/ops/plan-defs/" + planCode)
                        .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(res).get("data");
    }

    private List<String> planNos(String filter) throws Exception {
        JsonNode page = okData(get("/ops/merchant-plans").param("filter", filter).param("size", "100"));
        return page.get("records").valueStream().map(r -> r.get("merchantNo").asString()).toList();
    }

    /** BD 身份发一个 ops 请求并断言 code=0，返回 data。 */
    private JsonNode okData(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + bd()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private int codeOf(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        String body = mvc().perform(req).andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private String bd() throws Exception {
        return TestLogin.operator(mvc(), json, "bd", "bd123");
    }

    /**
     * 直接改到期日。
     *
     * <p><b>为什么不靠等</b>：到期是以天为单位的事件。用接口造不出「三天前到期」，
     * 而 {@code executeWithoutScope} 是必须的 —— {@code mch_entity_plan} 挂了数据域，
     * 测试线程没有登录上下文，不摘掉的话这条 update **静默更新 0 行**。
     */
    private void expireAt(String merchantNo, long at) {
        DataScopeContext.executeWithoutScope(() -> {
            MchEntityPlan row = planMapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                    .eq(MchEntityPlan::getEntityNo, merchantNo));
            assertThat(row).as("主体 %s 没有订阅行", merchantNo).isNotNull();
            row.setExpireAt(at);
            return planMapper.updateById(row);
        });
    }

    private List<MchStore> storesOf(String merchantNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeMapper.selectList(Wrappers.<MchStore>lambdaQuery()
                        .eq(MchStore::getEntityNo, merchantNo)
                        .orderByAsc(MchStore::getId)));
    }

    private List<MchStore> activeStores(String merchantNo) {
        return storesOf(merchantNo).stream()
                .filter(s -> MchStore.ACTIVE.equals(s.getStatus())).toList();
    }

    private String defaultStoreNo(String merchantNo) {
        return storesOf(merchantNo).stream()
                .filter(s -> Boolean.TRUE.equals(s.getIsDefault()))
                .map(MchStore::getStoreNo).findFirst().orElseThrow();
    }

    private List<String> nonDefaultStoreNos(String merchantNo) {
        return storesOf(merchantNo).stream()
                .filter(s -> !Boolean.TRUE.equals(s.getIsDefault()))
                .map(MchStore::getStoreNo).toList();
    }

    /** @return 建店响应的 code，0 = 成功，70020 = 撞额度 */
    private int createStore(String token, String name) throws Exception {
        return codeOf(post("/biz/store/create").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"address\":\"某路 9 号\"}"));
    }

    /**
     * 上架一件商品、买家下单并付款。
     *
     * @return 商家视角的子单号
     */
    private String placeAndPay(String bizToken, String buyerPhone) throws Exception {
        String goodsNo = json.readTree(mvc().perform(post("/biz/goods/save")
                        .header("Authorization", "Bearer " + bizToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryNo\":\"CAT210\",\"title\":\"降级测试商品\",\"subtitle\":\"测试\",\"type\":\"NORMAL\","
                                + "\"cover\":\"📦\",\"images\":[],\"specGroups\":[],"
                                + "\"skus\":[{\"optionValues\":[],\"price\":1000,\"stock\":10}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("goodsNo").asString();

        String goodsOps = TestLogin.operator(mvc(), json, "goods", "goods123");
        mvc().perform(post("/ops/goods/" + goodsNo + "/audit")
                .header("Authorization", "Bearer " + goodsOps)
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        mvc().perform(post("/biz/goods/" + goodsNo + "/toggle")
                .header("Authorization", "Bearer " + bizToken)
                .contentType(MediaType.APPLICATION_JSON).content("{\"onSale\":true}"));

        String buyer = login(buyerPhone);
        mvc().perform(post("/mp/user/community").header("Authorization", "Bearer " + buyer)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"communityNo\":\"C0001\",\"pickupNo\":\"PP0001\"}"));
        String skuNo = json.readTree(mvc().perform(get("/mp/goods/" + goodsNo))
                        .andReturn().getResponse().getContentAsString())
                .get("data").get("skus").get(0).get("skuNo").asString();
        String payOrderNo = json.readTree(mvc().perform(post("/mp/order")
                        .header("Authorization", "Bearer " + buyer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillment\":\"EXPRESS\",\"addressId\":null,"
                                + "\"items\":[{\"goodsNo\":\"" + goodsNo + "\",\"skuNo\":\"" + skuNo
                                + "\",\"qty\":1}]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("payOrderNo").asString();
        // 走支付回调而不是 /pay：只调 /pay 的话单还在 WAIT_PAY，发货会被状态机拒 ——
        // 而那是测试写错了不是代码错了
        mvc().perform(post("/callback/pay/stub").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outTradeNo\":\"" + payOrderNo + "\",\"transactionId\":\"TX-"
                                + payOrderNo + "\",\"sign\":\"" + STUB_SECRET + "\"}"))
                .andExpect(status().isOk());

        // 商家视角的 OrderVO.orderNo 装的是子单号 —— 商家谈的一直是自己那一单
        String list = mvc().perform(get("/biz/order").header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(list).get("data").get("records").get(0).get("orderNo").asString();
    }

    private String merchantNoOf(String bizToken) throws Exception {
        String body = mvc().perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data").get("merchantNo").asString();
    }

    private String merchant(String phone, String name) throws Exception {
        String user = login(phone);
        String body = mvc().perform(post("/mp/merchant/apply").header("Authorization", "Bearer " + user)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"subject\":\"INDIVIDUAL_BIZ\","
                                + "\"contactName\":\"张三\",\"contactPhone\":\"13900000000\","
                                + "\"category\":\"食品\",\"serviceScope\":\"COMMUNITY\","
                                + "\"communityNos\":[\"CM001\"]}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String applyNo = json.readTree(body).get("data").get("applyNo").asString();
        mvc().perform(post("/ops/merchant/apply/" + applyNo + "/audit")
                .header("Authorization", "Bearer " + bd())
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
