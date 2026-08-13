package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 积分能不能开：判据是<b>资金路径</b>，不是主体类型。
 *
 * <p><b>此前这里判错了两处</b>：
 * <ol>
 *   <li><b>选错了轴</b> —— 判的是「他是什么主体」。而要不要补差取决于
 *       <b>钱在谁手里</b>：钱在商家二级户，积分抵扣让他少收，平台要补进去，
 *       而那是一次<b>平台付钱给自然人</b>（扣缴定性模糊）；钱在平台户则是
 *       平台自己少收，根本没有「补」这个动作。</li>
 *   <li><b>读错了字段</b> —— 读的是 {@code mch_payment_merchant.legalForm}，
 *       那是<b>通道进件档</b>（微信小微/个体户），不是主体的法律形态。
 *       通道给他开了小微户，不代表他就是无照。</li>
 * </ol>
 *
 * <p>两处叠加的后果：农产品农户（无照 + 归集，平台自开收购发票）会被误拒 ——
 * 而那正是花了整节论证要支持的场景。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("积分判据：钱在谁手里，不是他是什么主体")
class PointsDenyReasonFlowTest {

    @Autowired
    private MerchantQueryPort merchantQueryPort;

    @Autowired
    private MchEntityMapper entityMapper;

    @Test
    @DisplayName("★★ 无照 + 直连 → 拒，且提示语说明条件而不是「未开启」")
    void unlicensedDirectDenied() {
        String m = anEntity("NATURAL_PERSON", MerchantQueryPort.FUNDS_DIRECT);

        String reason = merchantQueryPort.pointsDenyReason(m);

        assertThat(reason).isNotNull();
        assertThat(reason).contains("无营业执照");
        // 「不可开」不是「关着」—— 说成「未开启」，商家会去后台找一个他打不开的开关
        assertThat(reason).doesNotContain("未开启");
    }

    @Test
    @DisplayName("★★ 无照 + 归集 → 放行 —— 平台自己少收，没有付款给自然人这个动作")
    void unlicensedAggregatedAllowed() {
        String m = anEntity("NATURAL_PERSON", MerchantQueryPort.FUNDS_AGGREGATED);

        assertThat(merchantQueryPort.pointsDenyReason(m)).isNull();
    }

    @Test
    @DisplayName("★ 有照主体两条路径都放行")
    void licensedAllowedOnBothPaths() {
        assertThat(merchantQueryPort.pointsDenyReason(
                anEntity("ENTERPRISE", MerchantQueryPort.FUNDS_DIRECT))).isNull();
        assertThat(merchantQueryPort.pointsDenyReason(
                anEntity("ENTERPRISE", MerchantQueryPort.FUNDS_AGGREGATED))).isNull();
    }

    @Test
    @DisplayName("★ 商家自己关掉时提示的是「未开启」—— 与「不可开」必须能分辨")
    void merchantSwitchOffSaysNotEnabled() {
        String m = anEntity("ENTERPRISE", MerchantQueryPort.FUNDS_AGGREGATED);
        MchEntity e = reload(m);
        e.setPointsEnabled(false);
        e.setPointsForced(false);
        ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                () -> entityMapper.updateById(e));

        assertThat(merchantQueryPort.pointsDenyReason(m)).contains("未开启");
    }

    // ---------------------------------------------------------------- fixtures

    private MchEntity reload(String entityNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("LIMIT 1")));
    }

    private String anEntity(String legalForm, String fundsMode) {
        String no = "PD" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("积分判据测试主体");
        m.setLegalForm(legalForm);
        m.setStatus("ACTIVE");
        m.setFundsMode(fundsMode);
        m.setPointsEnabled(true);
        entityMapper.insert(m);
        return no;
    }
}
