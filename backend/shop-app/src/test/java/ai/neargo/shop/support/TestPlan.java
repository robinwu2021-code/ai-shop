package ai.neargo.shop.support;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.merchant.entity.MchEntityPlan;
import ai.neargo.shop.merchant.mapper.MerchantMappers.EntityPlanMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 测试里给商家「买增值包」的**唯一一份实现**。
 *
 * <p><b>为什么需要它</b>：多门店从来就是付费能力。主体入驻激活时自动落一行 FREE 订阅
 * （{@code MerchantPortImpl.ensureFreePlan}），而 FREE 只给 1 家店 —— 于是所有
 * 「建第二家店」的测试都撞在 {@code STORE_QUOTA_EXCEEDED}(70020) 上。
 *
 * <p>在这之前它们能过，靠的是 {@code application-testcfg.yml} 把
 * {@code shop.store.max-per-entity} 设成 3 —— 一个**碰巧宽松的全局配置**。
 * 那个配置现在退化成「订阅行不存在时的兜底」，对有订阅行的主体不再生效。
 *
 * <p><b>正确的修法是让测试说出这件事</b>：这个商家买了 PRO，所以它能开三家店。
 * 而不是把 test 库里 FREE 的额度调大、或者继续依赖一个宽松配置 ——
 * 那两种做法都会让测试环境的产品定义与生产分岔，测试就再也守不住
 * 「免费用户开不了第二家店」这条真正在卖的规则。
 *
 * <p><b>刻意做成静态方法而不是基类</b>：与 {@link TestLogin} 同理，
 * 各测试类的 {@code @SpringBootTest} 配置不同，强制继承会把差异挤没；
 * 升档只需要一个 mapper 和主体号，传参就够。
 *
 * <p><b>为什么直接改库而不是调接口</b>：运营端的授予接口是 P3 的事，现在还没有。
 * 等它有了，这里换成调接口即可 —— 一个文件，而不是十几个测试类。
 */
public final class TestPlan {

    private TestPlan() {
    }

    /**
     * PRO：10 家店 / 3 个子账号 / 有跨店总览。测试里最常用的那一档。
     *
     * <p><b>数值要与 {@code sys_merchant_plan_def} 的定义一致</b>（V221 起
     * FREE 3 / PRO 10 / CHAIN 30）—— 对不上的话测试环境的产品定义与生产分岔，
     * 而那正是这个类存在要防的事。想验「额度到顶」用 {@link #grantQuota}，
     * 别把这里的数改小。
     */
    public static void grantPro(EntityPlanMapper mapper, String merchantNo) {
        grant(mapper, merchantNo, MchEntityPlan.PRO, 10, 3, true);
    }

    /**
     * 手头只有 B 端 token 时的版本：自己去 {@code /biz/merchant/profile} 问主体号。
     *
     * <p>多数测试类不关心主体号，只想说「这家商家买了 PRO」——
     * 让它们各自抄一遍 {@code merchantNoOf} 没有意义。
     *
     * @return 主体号，偶尔后面还用得上
     */
    public static String grantPro(MockMvc mvc, ObjectMapper json, EntityPlanMapper mapper, String bizToken)
            throws Exception {
        String merchantNo = merchantNoOf(mvc, json, bizToken);
        grantPro(mapper, merchantNo);
        return merchantNo;
    }

    /** {@link #grantChain(EntityPlanMapper, String)} 的 token 版。 */
    public static String grantChain(MockMvc mvc, ObjectMapper json, EntityPlanMapper mapper, String bizToken)
            throws Exception {
        String merchantNo = merchantNoOf(mvc, json, bizToken);
        grantChain(mapper, merchantNo);
        return merchantNo;
    }

    private static String merchantNoOf(MockMvc mvc, ObjectMapper json, String bizToken) throws Exception {
        String body = mvc.perform(get("/biz/merchant/profile").header("Authorization", "Bearer " + bizToken))
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).get("data");
        if (data == null || data.get("merchantNo") == null) {
            throw new AssertionError("拿不到主体号，/biz/merchant/profile 响应：" + body);
        }
        return data.get("merchantNo").asString();
    }

    /** CHAIN：30 家店 / 20 个子账号。需要更多门店的场景用这个。 */
    public static void grantChain(EntityPlanMapper mapper, String merchantNo) {
        grant(mapper, merchantNo, MchEntityPlan.CHAIN, 30, 20, true);
    }

    /**
     * 只关心门店额度的场景：给 PRO 的能力位，额度按需给。
     *
     * <p>用于「就是要开 5 家店」这类不落在标准档位上的测试 ——
     * 比编个假档位码好，因为档位码会被断言和报错文案读到。
     */
    public static void grantQuota(EntityPlanMapper mapper, String merchantNo, int storeQuota) {
        grant(mapper, merchantNo, MchEntityPlan.PRO, storeQuota, Math.max(storeQuota, 3), true);
    }

    /**
     * 只把门店额度按住，**不动档位码** —— 「FREE 只能开一家」这类用例要的正是这个。
     *
     * <p>与 {@link #grantQuota} 的区别：那个会把主体顺手升到 PRO（它要的是 PRO 的能力位），
     * 而这里保持原档。V221 把 FREE 抬到 3 家之后，想在测试里造出「额度到顶」的局面，
     * 又不能改档位码（报错文案和断言都读它），就只剩这条路。
     */
    public static void capStores(EntityPlanMapper mapper, String merchantNo, int storeQuota) {
        DataScopeContext.executeWithoutScope(() -> {
            MchEntityPlan row = mapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                    .eq(MchEntityPlan::getEntityNo, merchantNo));
            if (row == null) {
                throw new AssertionError("主体 " + merchantNo + " 没有订阅行");
            }
            row.setStoreQuota(storeQuota);
            return mapper.updateById(row);
        });
    }

    /**
     * 改那一行订阅。
     *
     * <p>{@link DataScopeContext#executeWithoutScope} 是必须的：{@code mch_entity_plan}
     * 挂了数据域，测试线程没有登录上下文，不摘掉的话这条 update 会被拼上一个空的
     * 主体条件，**静默地更新 0 行** —— 表现为测试照样撞额度，却看不出为什么。
     */
    private static void grant(EntityPlanMapper mapper, String merchantNo, String planCode,
                              int storeQuota, int staffQuota, boolean crossStoreStats) {
        DataScopeContext.executeWithoutScope(() -> {
            MchEntityPlan row = mapper.selectOne(Wrappers.<MchEntityPlan>lambdaQuery()
                    .eq(MchEntityPlan::getEntityNo, merchantNo));
            if (row == null) {
                throw new AssertionError("主体 " + merchantNo + " 没有订阅行 —— "
                        + "入驻激活时应当自动建一行 FREE（ensureFreePlan），"
                        + "是不是升档调早了、商家还没审核通过？");
            }
            row.setPlanCode(planCode);
            row.setStoreQuota(storeQuota);
            row.setStaffQuota(staffQuota);
            row.setCrossStoreStats(crossStoreStats);
            return mapper.updateById(row);
        });
    }
}
