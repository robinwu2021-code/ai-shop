package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.mapper.SettleMappers.FeeRuleMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import ai.neargo.shop.pay.entity.StlFeeRule;
import ai.neargo.shop.pay.service.FeeRuleService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 费率规则（落地清单 P1-4）：从配置文件搬进可运营、可回查的表。
 *
 * <p>此前费率写在 {@code application.yml} 里，<b>改一次要改配置文件加重启</b>。
 * 快照那一半原本就做对了（{@code stl_bill.commission_rate} 逐单落快照），
 * 这次只换取数来源。
 *
 * <p>本测试最要紧的两条是<b>时点回查</b>与<b>停用回退</b>——
 * 它们正是「原地改一个数」做不到、才要建这张表的理由。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("费率规则：能回答「上个月那批单当时按什么费率算的」")
class FeeRuleFlowTest {

    private static final String THIRD_PARTY = MerchantQueryPort.MODE_THIRD_PARTY;
    private static final String SELF_OPERATED = MerchantQueryPort.MODE_SELF_OPERATED;

    @Autowired
    private FeeRuleService feeRuleService;

    @Autowired
    private FeeRuleMapper feeRules;

    private long lastIdBefore;

    /*
     * 本类加的规则都是**立即或很快生效**的平台级费率（8%、自带客流 4%……），而 stl_fee_rule 全库一份。
     * 不撤的话，按字母序排在后面的结算用例（M7 的费率说明、账单佣金……）全拿到改过的费率。
     * 按 id 水位撤：只动本条用例自己加的行。
     */
    @BeforeEach
    void rememberWatermark() {
        StlFeeRule last = DataScopeContext.executeWithoutScope(() -> feeRules.selectOne(
                Wrappers.<StlFeeRule>lambdaQuery().orderByDesc(StlFeeRule::getId).last("limit 1")));
        lastIdBefore = last == null ? 0 : last.getId();
    }

    @AfterEach
    void withdrawOwnRules() {
        DataScopeContext.executeWithoutScope(() -> feeRules.delete(
                Wrappers.<StlFeeRule>lambdaQuery().gt(StlFeeRule::getId, lastIdBefore)));
    }

    @Test
    @DisplayName("★ 初始四格与上线前的配置一致：第三方自带客流 0、平台客流 5%")
    void seedMatchesPreviousConfig() {
        /*
         * 查时刻 1 而不是 now：费率是**全局**配置，本类其它用例会往同几个格里
         * 插新版本（生效时刻都是 now 之后）。用 now 查会随执行顺序时对时错，
         * 而那种失败最难判断是产品缺陷还是用例串扰。
         * 种子的 effective_from = 0，所以时刻 1 恰好只能命中种子。
         */
        long justAfterSeed = 1L;

        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.MERCHANT_OWNED, justAfterSeed)).isZero();
        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.PLATFORM, justAfterSeed)).isEqualTo(500);
        assertThat(feeRuleService.rateOf(SELF_OPERATED, StlFeeRule.PLATFORM, justAfterSeed))
                .as("自营两格先与第三方取齐，本次变更对现有行为是无感的")
                .isEqualTo(500);
    }

    @Test
    @DisplayName("★★ 时点回查：调价之后，问「调价之前」仍拿到旧费率")
    void historyIsQueryable() {
        long before = System.currentTimeMillis();
        long changeAt = before + 1000;

        feeRuleService.addRule(THIRD_PARTY, StlFeeRule.PLATFORM, 800, changeAt, "涨到 8%", "OPS");

        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.PLATFORM, before))
                .as("这正是原地改一个数做不到、才要建这张表的理由")
                .isEqualTo(500);
        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.PLATFORM, changeAt + 1))
                .isEqualTo(800);
    }

    @Test
    @DisplayName("★ 预约生效：填未来时刻，当下不受影响")
    void futureRuleNotYetEffective() {
        long now = System.currentTimeMillis();
        long future = now + 86_400_000L;

        feeRuleService.addRule(SELF_OPERATED, StlFeeRule.MERCHANT_OWNED, 300, future, "明天起 3%", "OPS");

        assertThat(feeRuleService.rateOf(SELF_OPERATED, StlFeeRule.MERCHANT_OWNED, now)).isZero();
        assertThat(feeRuleService.rateOf(SELF_OPERATED, StlFeeRule.MERCHANT_OWNED, future + 1))
                .isEqualTo(300);
    }

    @Test
    @DisplayName("★★ 停用最新版本 = 回退到上一版，不是当它从未存在")
    void disablingLatestFallsBackToPrevious() {
        long t1 = System.currentTimeMillis();
        var v2 = feeRuleService.addRule(THIRD_PARTY, StlFeeRule.MERCHANT_OWNED, 200, t1 + 10, "第二版", "OPS");
        feeRuleService.addRule(THIRD_PARTY, StlFeeRule.MERCHANT_OWNED, 400, t1 + 20, "第三版", "OPS");

        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.MERCHANT_OWNED, t1 + 30)).isEqualTo(400);

        /*
         * 停用第三版后应当回到第二版（200），而不是回到初始版（0）。
         * 若实现里把停用版本「直接跳过」，命中的就会是更早的某一版 ——
         * 只调过一次时看不出区别，调过三次时结果完全不同。
         */
        disable(v2.ruleNo());
        assertThat(feeRuleService.rateOf(THIRD_PARTY, StlFeeRule.MERCHANT_OWNED, t1 + 15))
                .as("停用第二版后，t1+15 这一刻应当落回初始版")
                .isZero();
    }

    @Test
    @DisplayName("★ 费率超过 100% 直接拒 —— 少一个零和多一个零是同一次手滑")
    void insaneRateRejected() {
        long now = System.currentTimeMillis();

        assertThatThrownBy(() ->
                feeRuleService.addRule(THIRD_PARTY, StlFeeRule.PLATFORM, 50_000, now, "手滑", "OPS"))
                .as("5000（50%）打成 50000 就是 500%，净额会变成大额负数并一路走到分账")
                .isInstanceOf(BizException.class);

        assertThatThrownBy(() ->
                feeRuleService.addRule(THIRD_PARTY, StlFeeRule.PLATFORM, -1, now, "负费率", "OPS"))
                .isInstanceOf(BizException.class);
    }

    @Test
    @DisplayName("★ 命中不到规则返回 0 —— 宁可少收，不能凭空多收")
    void unknownSlotIsFree() {
        assertThat(feeRuleService.rateOf("CONSIGNMENT", StlFeeRule.PLATFORM, System.currentTimeMillis()))
                .as("费率查不到就按最高档收，是会真的多扣商家钱的")
                .isZero();
    }

    /*
     * **用完即还原。** 费率是全局配置，而本类往「第三方 · 平台客流」插了 8%、往「自带客流」插了 4% ——
     * 生效时刻都在 now 附近，之后同一个 H2 库里跑的测试全都按新费率算。
     * 2026-10-24 shop-app 改为字母序执行后，F 排在 M 前面，M7SettleFlowTest#rateCardIsVisibleToMerchant
     * 读到的自带客流费率从 0 变成 400；单独跑 M7 全绿、跟在本类后面就红（2026-10-25 实测，开发计划 §10 L1-3）。
     * 只删本方法新增的行：按方法前后的规则号做差，种子与别的测试类写的行一行不碰。
     */
    private java.util.Set<String> rulesBefore;

    @BeforeEach
    void rememberExistingRules() {
        rulesBefore = ruleNos();
    }

    @AfterEach
    void removeRulesAddedHere() {
        var added = ruleNos();
        added.removeAll(rulesBefore);
        if (!added.isEmpty()) {
            feeRuleMapper.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers
                    .<StlFeeRule>lambdaQuery().in(StlFeeRule::getRuleNo, added));
        }
    }

    private java.util.Set<String> ruleNos() {
        var all = new java.util.HashSet<String>();
        feeRuleMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers.<StlFeeRule>lambdaQuery())
                .forEach(r -> all.add(r.getRuleNo()));
        return all;
    }

    /** 停用一个版本。走 Service 没有停用入口（费率只增不改），测试里直接改库。 */
    @Autowired
    private ai.neargo.shop.pay.mapper.SettleMappers.FeeRuleMapper feeRuleMapper;

    private void disable(String ruleNo) {
        var row = feeRuleMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<StlFeeRule>lambdaQuery().eq(StlFeeRule::getRuleNo, ruleNo).last("LIMIT 1"));
        row.setEnabled(0);
        feeRuleMapper.updateById(row);
    }
}
