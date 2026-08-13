package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 资金路径（轴②）：钱先进谁的账户。
 *
 * <p><b>它与经营模式（轴③）是两件事</b>，此前被当成一件：
 * <pre>
 *   funds_mode     钱先进谁的账户   AGGREGATED 平台户 / DIRECT 商家二级户
 *   business_mode  谁是销售主体     SELF_OPERATED 平台 / THIRD_PARTY 商家
 * </pre>
 *
 * <p>「要不要补差」判的是<b>前者</b> —— 钱在商家账户才需要补进去。
 * 用后者判，在两者不一致时会判错，而库里恰好有一批这样的单（V23 回填的 54 张）。
 *
 * <p>最要紧的一条不变式：<b>归集路径上不存在补差动作</b>。
 * 归集下应付账款已经按全额算过（gross 里加回了积分抵扣），再补一次就是
 * <b>重复付款</b> —— 100 元的货平台会付出 110。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("资金路径：钱在谁手里，决定要不要补差")
class FundsModeFlowTest {

    @Autowired
    private MerchantGovernService governService;

    @Autowired
    private MchEntityMapper entityMapper;

    @Test
    @DisplayName("★★ 无照主体不得走归集 —— 开不出票，成本不可税前扣除，走一单亏一单")
    void unlicensedCannotAggregate() {
        String m = anEntity("NATURAL_PERSON", 0);
        // 先切到直连，再切回归集才能触发校验（默认值本来就是归集）
        governService.setFundsMode(m, MerchantQueryPort.FUNDS_DIRECT, "OPS");

        assertThatThrownBy(() -> governService.setFundsMode(m, MerchantQueryPort.FUNDS_AGGREGATED, "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.CONFLICT.name());
    }

    @Test
    @DisplayName("★★ 农业生产者例外 —— 平台可自开农产品收购发票，成本有凭证")
    void agriProducerMayAggregate() {
        String m = anEntity("NATURAL_PERSON", 1);
        governService.setFundsMode(m, MerchantQueryPort.FUNDS_DIRECT, "OPS");

        governService.setFundsMode(m, MerchantQueryPort.FUNDS_AGGREGATED, "OPS");

        assertThat(reload(m).getFundsMode()).isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
    }

    @Test
    @DisplayName("★ 有照主体两条路径都可以")
    void licensedMayUseBothPaths() {
        String m = anEntity("ENTERPRISE", 0);

        governService.setFundsMode(m, MerchantQueryPort.FUNDS_DIRECT, "OPS");
        assertThat(reload(m).getFundsMode()).isEqualTo(MerchantQueryPort.FUNDS_DIRECT);

        governService.setFundsMode(m, MerchantQueryPort.FUNDS_AGGREGATED, "OPS");
        assertThat(reload(m).getFundsMode()).isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
    }

    @Test
    @DisplayName("★ 非法取值直接拒 —— 枚举只有两个值，第三个只可能是笔误")
    void unknownModeRejected() {
        String m = anEntity("ENTERPRISE", 0);

        assertThatThrownBy(() -> governService.setFundsMode(m, "ESCROW", "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.BAD_REQUEST.name());
    }

    @Test
    @DisplayName("★ 默认值是归集 —— 与存量事实一致（全库都是平台收款）")
    void defaultsToAggregated() {
        assertThat(reload(anEntity("ENTERPRISE", 0)).getFundsMode())
                .isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
    }

    // ---------------------------------------------------------------- fixtures

    private MchEntity reload(String entityNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("LIMIT 1")));
    }

    private String anEntity(String legalForm, int agri) {
        String no = "FM" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("资金路径测试主体");
        m.setLegalForm(legalForm);
        m.setStatus("ACTIVE");
        m.setIsAgriProducer(agri);
        entityMapper.insert(m);
        return no;
    }
}
