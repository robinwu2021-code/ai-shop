package ai.neargo.shop.scenario;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntityPlan;
import ai.neargo.shop.merchant.entity.SysMerchantPlanDef;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.PlanDefMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 增值包 · 商家侧（P4，执行计划 §P4）。
 *
 * <p>P1 拦得住、P3 放得开，这个文件守的是**商家自己看得懂**：
 * 在这条读接口之前，他建店被 70020 拦下、跨店总览被 70023 拦下，
 * 却<b>无处得知自己是哪一档、额度是几</b> —— 一道对商家不透明的闸门，在他那边就是故障。
 *
 * <p>四条最容易做错、且做错了不报错的：
 * <ul>
 *   <li><b>用量与闸门口径分岔</b> —— 页面说 3/3 满了，实际还能建一家（或反过来）。
 *       两边都觉得自己没错，而店主只会得出「这个数字不能信」</li>
 *   <li><b>试用能重开</b> —— 付费档就此变成免费档，且不需要任何技巧就能发现</li>
 *   <li><b>试用目标写死 PRO</b> —— 哪天中间插一档，试用会跳过它直接送出更贵的能力</li>
 *   <li><b>降级名单混进商家自停的店</b> —— 套餐页告诉他「补缴就能恢复」，
 *       补完那家店还是关着</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class BizPlanFlowTest {

    /**
     * B 端「角色不够」。
     *
     * <p><b>与运营端那个 10403 不是同一个码</b>：B 端判权走 {@code @perm.canBiz}，
     * 拒绝时抛的是业务异常（HTTP 仍是 200 + 统一信封）。
     * 端上正是靠它区分「让老板给你加角色」与「升套餐」（70023）——
     * 两者的下一步完全不同，合成一种处理就会让人去找一个不存在的开关。
     */
    private static final int DENIED = 70006;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private EntityPlanMapper planMapper;

    @Autowired
    private PlanDefMapper defMapper;

    @Autowired
    private MerchantPlanService planService;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 用量与建店闸同一口径 —— 停用一家店，已用数减一且真的能再建一家")
    void usageMatchesTheQuotaGate() throws Exception {
        String biz = merchant("12601200001", "商家套餐·用量口径");
        String merchantNo = merchantNoOf(biz);
        grantPro(merchantNo);

        JsonNode p = plan(biz);
        assertThat(p.get("planCode").asString()).isEqualTo("PRO");
        assertThat(p.get("planName").asString()).isNotBlank();
        assertThat(p.get("storeQuota").asInt()).isEqualTo(3);
        assertThat(p.get("storeUsed").asInt()).as("入驻自动建了默认店").isEqualTo(1);

        assertThat(createStore(biz, "第二家")).isZero();
        assertThat(createStore(biz, "第三家")).isZero();
        assertThat(plan(biz).get("storeUsed").asInt()).isEqualTo(3);
        assertThat(createStore(biz, "第四家")).isEqualTo(70020);

        /*
         * ★ 停用一家 → 已用数必须跟着减 1。
         *
         * 这一条是整个 P4 里最容易静默错的：闸门只数 ACTIVE，而「用量」如果按
         * 门店总数算，页面会一直显示 3/3 —— 于是他明明能再建一家，却以为满了。
         * 反过来（页面显示 2/3 而闸门拒绝）更糟：他会觉得系统在骗他。
         */
        String some = nonDefaultStoreNo(biz);
        mvc().perform(post("/biz/store/" + some + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(plan(biz).get("storeUsed").asInt())
                .as("停用的店不该继续占用量 —— 否则页面说满了而闸门放行").isEqualTo(2);
        assertThat(createStore(biz, "顶上来的一家")).isZero();
    }

    @Test
    @DisplayName("★ 试用当场生效：开通后立刻能建第二家店，且一主体只能试一次")
    void trialIsOneShotAndTakesEffectImmediately() throws Exception {
        String biz = merchant("12601200010", "商家套餐·试用");

        JsonNode before = plan(biz);
        assertThat(before.get("planCode").asString()).isEqualTo("FREE");
        assertThat(before.get("trialUsed").asBoolean()).isFalse();
        // 可试用的档位由后端给，端上不自己推 —— 这里断的就是那个字段
        assertThat(before.get("trialTier").asString()).isEqualTo("PRO");
        assertThat(before.get("trialDays").asInt()).isPositive();
        assertThat(createStore(biz, "试用前的第二家")).isEqualTo(70020);

        JsonNode after = okData(post("/biz/plan/trial"), biz);
        assertThat(after.get("planCode").asString()).isEqualTo("PRO");
        assertThat(after.get("trialUsed").asBoolean()).isTrue();
        assertThat(after.get("trialTier").isNull()).as("用过之后不该再给试用入口").isTrue();
        assertThat(after.get("expireAt").asLong()).isGreaterThan(System.currentTimeMillis());

        // ★ 额度立即生效：不必重新登录，也不等任何缓存
        assertThat(createStore(biz, "试用后的第二家")).isZero();

        // ★ 一主体一次，永不回退
        assertThat(codeOf(post("/biz/plan/trial").header("Authorization", "Bearer " + biz)))
                .as("第二次试用必须被拒 —— 能重开等于把付费档变成免费档").isNotZero();
    }

    @Test
    @DisplayName("★ 已经是付费档的人不给试用入口 —— 他要的是续费")
    void paidMerchantsGetNoTrialEntry() throws Exception {
        String biz = merchant("12601200020", "商家套餐·已付费");
        grantPro(merchantNoOf(biz));

        JsonNode p = plan(biz);
        assertThat(p.get("trialTier").isNull()).isTrue();
        assertThat(codeOf(post("/biz/plan/trial").header("Authorization", "Bearer " + biz))).isNotZero();
    }

    @Test
    @DisplayName("★ 试用目标跟着档位定义走，不是写死 PRO")
    void trialTargetFollowsTheDefinitions() throws Exception {
        String biz = merchant("12601200030", "商家套餐·试用目标");
        assertThat(plan(biz).get("trialTier").asString()).isEqualTo("PRO");

        /*
         * 把 PRO 的试用天数改成 0（= 这一档不再提供试用）。
         * 写死 PRO 的实现在这里仍然返回 PRO，然后开通出一个 0 天的试用 ——
         * 那是一次「开通即过期」，商家看到的是套餐页闪一下又回到免费档。
         */
        int original = withDef("PRO", d -> {
            int was = d.getTrialDays();
            d.setTrialDays(0);
            return was;
        });
        try {
            assertThat(plan(biz).get("trialTier").asString())
                    .as("PRO 不再可试用后，目标应当是下一个可试用的档位").isEqualTo("CHAIN");
        } finally {
            withDef("PRO", d -> {
                d.setTrialDays(original);
                return original;
            });
        }
    }

    @Test
    @DisplayName("★ 降级后套餐页写明是哪几家店 —— 商家自己停用的不在名单里")
    void suspendedListNamesOnlyPlatformPressedStores() throws Exception {
        String biz = merchant("12601200040", "商家套餐·降级名单");
        String merchantNo = merchantNoOf(biz);
        grantPro(merchantNo);
        assertThat(createStore(biz, "第二家")).isZero();
        assertThat(createStore(biz, "第三家")).isZero();

        // 商家自己停用一家 —— 它与被降级压下的店 status 一模一样，混在一起才测得出区别
        String selfPaused = nonDefaultStoreNo(biz);
        mvc().perform(post("/biz/store/" + selfPaused + "/status")
                        .header("Authorization", "Bearer " + biz)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(jsonPath("$.code").value(0));
        String selfPausedName = storeNameOf(biz, selfPaused);

        // 到期 + 宽限期过完 → 降级压店
        expireAt(merchantNo, System.currentTimeMillis() - 10L * 86_400_000L);
        planService.sweepExpiry(System.currentTimeMillis());

        JsonNode p = plan(biz);
        assertThat(p.get("status").asString()).isEqualTo(MchEntityPlan.EXPIRED);
        var names = p.get("suspendedStores").valueStream().map(JsonNode::asString).toList();
        assertThat(names).as("被平台压下的那一家要点名 —— 它正在丢单").hasSize(1);
        assertThat(names).doesNotContain(selfPausedName);

        /*
         * ★ 门店列表要能区分两种只读：`status` 都是 READONLY，
         * 靠 planSuspended 区分「谁压的」—— 而它决定端上给的下一步：
         * 补缴，还是点一下启用。
         */
        JsonNode stores = stores(biz);
        int pressed = 0;
        for (JsonNode s : stores) {
            if (s.get("storeNo").asString().equals(selfPaused)) {
                assertThat(s.get("planSuspended").asBoolean())
                        .as("商家自停的店不能被标成平台压的").isFalse();
            } else if (!s.get("isDefault").asBoolean()) {
                assertThat(s.get("planSuspended").asBoolean()).isTrue();
                pressed++;
            }
        }
        assertThat(pressed).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 权限：店长看不到套餐，也开不了试用")
    void onlyOwnerSeesThePlan() throws Exception {
        String owner = merchant("12601200050", "商家套餐·权限");
        String store = defaultStoreNo(owner);

        String staffNo = json.readTree(mvc().perform(post("/biz/staff")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginPhone\":\"12601200051\"}"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString())
                .get("data").get("mchAccountNo").asString();
        mvc().perform(post("/biz/staff/" + staffNo + "/store")
                        .header("Authorization", "Bearer " + owner)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeNo\":\"" + store + "\",\"role\":\"MANAGER\",\"granted\":true}"))
                .andExpect(jsonPath("$.code").value(0));

        String manager = TestLogin.merchantStaff(mvc(), json, otpStore, "12601200051");
        /*
         * 店长被拒是**设计**不是遗漏：这一页答的是「主体买了什么」，
         * 与建店、挂收款号同属主体结构面。他看到额度只会去催老板买单，
         * 而他不是做这个决定的人。端上按 can('biz:store:admin') 决定渲不渲染入口。
         */
        assertThat(codeOf(get("/biz/plan").header("Authorization", "Bearer " + manager)
                .header("X-Store-No", store)))
                .isEqualTo(DENIED);
        assertThat(codeOf(post("/biz/plan/trial").header("Authorization", "Bearer " + manager)
                .header("X-Store-No", store)))
                .isEqualTo(DENIED);
    }

    @Test
    @DisplayName("三档对比在同一个响应里给出，且标出当前那一档")
    void tiersComeWithTheCurrentFlag() throws Exception {
        String biz = merchant("12601200060", "商家套餐·档位对比");
        JsonNode tiers = plan(biz).get("tiers");
        assertThat(tiers.size()).as("在售档位应当都给出来").isGreaterThanOrEqualTo(3);
        // 顺序按 sort：免费档在最前，端上照原序渲染不再排一次
        assertThat(tiers.get(0).get("planCode").asString()).isEqualTo(MchEntityPlan.FREE);
        long current = tiers.valueStream().filter(t -> t.get("current").asBoolean()).count();
        assertThat(current).as("当前档位恰好一个 —— 端上不必拿 planCode 再比一次").isEqualTo(1);
    }

    // ---------------------------------------------------------------- 装配

    private JsonNode plan(String bizToken) throws Exception {
        return okData(get("/biz/plan"), bizToken);
    }

    private JsonNode okData(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
                            String bizToken) throws Exception {
        String body = mvc().perform(req.header("Authorization", "Bearer " + bizToken))
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

    /** 改一个档位定义并返回旧值，用完记得改回去（全库共享的一行）。 */
    private int withDef(String planCode, java.util.function.ToIntFunction<SysMerchantPlanDef> mutate) {
        return DataScopeContext.executeWithoutScope(() -> {
            SysMerchantPlanDef d = defMapper.selectOne(Wrappers.<SysMerchantPlanDef>lambdaQuery()
                    .eq(SysMerchantPlanDef::getPlanCode, planCode).last("limit 1"));
            assertThat(d).as("档位 %s 不存在", planCode).isNotNull();
            int was = mutate.applyAsInt(d);
            defMapper.updateById(d);
            return was;
        });
    }

    private void grantPro(String merchantNo) {
        ai.neargo.shop.support.TestPlan.grantPro(planMapper, merchantNo);
    }

    private void expireAt(String merchantNo, long at) {
        DataScopeContext.executeWithoutScope(() -> {
            MchEntityPlan row = planMapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                    .eq(MchEntityPlan::getEntityNo, merchantNo));
            assertThat(row).as("主体 %s 没有订阅行", merchantNo).isNotNull();
            row.setExpireAt(at);
            return planMapper.updateById(row);
        });
    }

    private int createStore(String token, String name) throws Exception {
        return codeOf(post("/biz/store/create").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"address\":\"某路 9 号\"}"));
    }

    private JsonNode stores(String token) throws Exception {
        String body = mvc().perform(get("/biz/store/list").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("data");
    }

    private String nonDefaultStoreNo(String token) throws Exception {
        for (JsonNode s : stores(token)) {
            if (!s.get("isDefault").asBoolean() && "ACTIVE".equals(s.get("status").asString())) {
                return s.get("storeNo").asString();
            }
        }
        throw new AssertionError("没有营业中的非默认店");
    }

    private String defaultStoreNo(String token) throws Exception {
        for (JsonNode s : stores(token)) {
            if (s.get("isDefault").asBoolean()) {
                return s.get("storeNo").asString();
            }
        }
        throw new AssertionError("没有默认店");
    }

    private String storeNameOf(String token, String storeNo) throws Exception {
        for (JsonNode s : stores(token)) {
            if (s.get("storeNo").asString().equals(storeNo)) {
                return s.get("name").asString();
            }
        }
        throw new AssertionError("找不到门店 " + storeNo);
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
        return login(phone);
    }

    private String login(String phone) throws Exception {
        return TestLogin.consumer(mvc(), json, otpStore, phone);
    }
}
