package ai.neargo.shop.scenario;

import ai.neargo.shop.support.TestLogin;
import ai.neargo.shop.support.TestPlan;
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
 * 增值包 · 门店额度（P1，TDD-增值包与门店额度）。
 *
 * <p>这个文件守的第一条是**存量零行为变化**：额度从全局配置换成按主体的订阅档位，
 * 而上线当天所有商家的可开店数与今天逐字相同。改造期最容易出的事故不是「新功能不работа」，
 * 是「老功能悄悄变了」——而后者没有人会报上来，只会表现为商家突然开不了店。
 *
 * <p>第二条是**并发不越额**：「数一下现在几家 → 小于额度就建」在两个请求同时进来时
 * 会双双通过。额度是这个包卖的东西本身，能被并发绕开等于付费档和免费档没区别。
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantPlanQuotaFlowTest {

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper planMapper;

    @Autowired
    private ai.neargo.shop.merchant.service.MerchantPlanService planService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ FREE 是 3 家：第 4 家被拒，且报错要带够数让他知道下一步做什么")
    void freeMerchantIsCappedAtThreeStores() throws Exception {
        String biz = merchant("12600900001", "额度·免费店");

        // 入驻通过会自动建一家默认店（ensureDefaultStore），所以这里已经用掉 1/3
        assertThat(storeCount(biz)).isEqualTo(1);
        assertThat(createStore(biz, "第二家")).isZero();
        assertThat(createStore(biz, "第三家")).isZero();

        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"第四家\",\"address\":\"某路 4 号\"}"))
                .andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        assertThat(root.get("code").asInt()).isEqualTo(70020);

        /*
         * ★ 报错必须带三个数：现在几家、上限几家、当前档位。
         * 只说「额度不足」，商家的下一步是打客服电话 —— 而他要做的是升套餐或停一家。
         */
        String msg = root.get("msg").asString();
        assertThat(msg).contains("3").contains("FREE");
    }

    @Test
    @DisplayName("★ 额度跟着订阅走：把这家商家改成 PRO，第二、三家店就能开了")
    void quotaFollowsSubscription() throws Exception {
        String biz = merchant("12600900010", "额度·成长店");
        String merchantNo = merchantNoOf(biz);

        /*
         * 先把这家压到一个**只给 1 家**的额度上，好用最少的店把闸门顶出来 ——
         * 这条用例验的是「额度跟着订阅走」这个机制，不是 FREE 具体给几家
         * （那个由 freeMerchantIsCappedAtThreeStores 单独守）。
         */
        TestPlan.grantQuota(planMapper, merchantNo, 1);
        assertThat(createStore(biz, "被拒的第二家")).isEqualTo(70020);

        // 升到 PRO（10 家）—— 这里直接改库，运营端授予接口是 P3 的事
        upgradeToPro(merchantNo);

        assertThat(createStore(biz, "第二家")).isEqualTo(0);
        assertThat(createStore(biz, "第三家")).isEqualTo(0);
        assertThat(storeCount(biz)).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 只数 ACTIVE：停用一家之后能再开一家（默认店停不掉，所以单店商家不受影响）")
    void quotaCountsOnlyActiveStores() throws Exception {
        String biz = merchant("12600900020", "额度·停用再开");
        String merchantNo = merchantNoOf(biz);
        // 额度取 3 只为把闸门顶出来，与 PRO 的真实额度（10）无关
        TestPlan.grantQuota(planMapper, merchantNo, 3);

        assertThat(createStore(biz, "第二家")).isEqualTo(0);
        assertThat(createStore(biz, "第三家")).isEqualTo(0);
        assertThat(createStore(biz, "第四家")).isEqualTo(70020);

        // 停用一家非默认店 → 腾出一个额度
        String someNonDefault = nonDefaultStoreNo(biz);
        mvc().perform(post("/biz/store/" + someNonDefault + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(createStore(biz, "顶上来的第四家")).isEqualTo(0);
    }

    @Test
    @DisplayName("★ 并发建店不越额 —— 两个请求同时进来，只能成一个")
    void concurrentCreateDoesNotExceedQuota() throws Exception {
        String biz = merchant("12600900030", "额度·并发");
        String merchantNo = merchantNoOf(biz);
        // 额度取 3 只为把「只剩一个名额」这个局面造出来，与 PRO 的真实额度（10）无关
        TestPlan.grantQuota(planMapper, merchantNo, 3);
        // 先占到 2/3，只剩一个名额
        assertThat(createStore(biz, "第二家")).isEqualTo(0);

        /*
         * 两个线程同时抢最后一个名额。
         * 没有那把行锁的话，两边各自数出 2 < 3，双双通过 —— 结果是 4 家店。
         */
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var latch = new java.util.concurrent.CountDownLatch(1);
            java.util.List<java.util.concurrent.Future<Integer>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                fs.add(pool.submit(() -> {
                    latch.await();
                    return createStore(biz, "并发-" + n);
                }));
            }
            latch.countDown();
            int ok = 0;
            for (var f : fs) {
                if (f.get() == 0) {
                    ok++;
                }
            }
            assertThat(ok).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(storeCount(biz)).isEqualTo(3);
    }

    @Test
    @DisplayName("订阅行不存在时走配置兜底 —— 不是 NPE，也不是「一家都不能开」")
    void missingPlanRowFallsBackToConfig() throws Exception {
        String biz = merchant("12600900040", "额度·无订阅行");
        String merchantNo = merchantNoOf(biz);

        // 删掉订阅行，模拟「V150 的回填漏了这个主体」
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                planMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<ai.neargo.shop.merchant.entity.MchEntityPlan>lambdaQuery()
                        .eq(ai.neargo.shop.merchant.entity.MchEntityPlan::getEntityNo, merchantNo)));

        var plan = planService.current(merchantNo);
        // 兜底视图：FREE、额度取配置，来源标 CONFIG（**能看出它不是真订阅**）
        assertThat(plan.planCode()).isEqualTo("FREE");
        assertThat(plan.source()).isEqualTo("CONFIG");
        assertThat(plan.storeQuota()).isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- 装配

    /** 把主体升到 PRO。实现在 {@link ai.neargo.shop.support.TestPlan}，全仓库共用一份。 */
    private void upgradeToPro(String merchantNo) {
        ai.neargo.shop.support.TestPlan.grantPro(planMapper, merchantNo);
    }

    /** @return 建店响应的 code，0 = 成功 */
    private int createStore(String token, String name) throws Exception {
        String body = mvc().perform(post("/biz/store/create").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"address\":\"某路 9 号\"}"))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("code").asInt();
    }

    private int storeCount(String token) throws Exception {
        return stores(token).size();
    }

    private String nonDefaultStoreNo(String token) throws Exception {
        for (JsonNode s : stores(token)) {
            if (!s.get("isDefault").asBoolean()) {
                return s.get("storeNo").asString();
            }
        }
        throw new AssertionError("没有非默认店");
    }

    private JsonNode stores(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
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
                .header("Authorization", "Bearer " + TestLogin.admin(mvc(), json))
                .contentType(MediaType.APPLICATION_JSON).content("{\"approved\":true}"));
        // A7：/biz/** 只认 btk_，这里必须换 B 端令牌
        return TestLogin.merchantOwner(mvc(), json, otpStore, phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
