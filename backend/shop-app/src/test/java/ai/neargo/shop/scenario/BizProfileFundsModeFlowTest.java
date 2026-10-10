package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.portal.biz.BizMerchantController;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B 端 profile 要下发 {@code fundsMode} —— <b>价格字段叫什么由它决定</b>。
 *
 * <p>归集路径下平台是销售主体、最终售价平台定，商家填的是「期望收购价」；
 * 直连路径下他自己就是销售主体，那就是「售价」。
 * 不下发这个字段，B 端只能猜 —— 而猜错的表现是
 * <b>商家以为自己定了价，然后发现 C 端显示的是另一个数</b>，
 * 他会认为平台擅自改了他的价。
 *
 * <p><b>为什么这条要单独测</b>：契约字段少一个不会报错，
 * 前端读到 {@code undefined} 会安静地落到 else 分支 ——
 * 表现就是「归集商家看到的还是『价格』」，和没做这个需求一模一样。
 * 本轮「闸门写好了但数据源没接」已经出现五次，无一例外都是这个形状。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("B 端 profile：资金路径要下发，价格字段靠它命名")
class BizProfileFundsModeFlowTest {

    @Autowired
    private MerchantQueryPort merchantQueryPort;

    @Autowired
    private MchEntityMapper entityMapper;

    @Test
    @DisplayName("★★★ MerchantProfileVO 有 fundsMode —— 少了它 B 端只能猜")
    void profileCarriesFundsMode() {
        RecordComponent[] cs = BizMerchantController.MerchantProfileVO.class.getRecordComponents();

        assertThat(Arrays.stream(cs).map(RecordComponent::getName))
                .as("B 端价格字段的命名依赖它；不下发的话前端读到 undefined，"
                        + "安静地落到「售价」分支，与没做这个需求一样")
                .contains("fundsMode");
    }

    @Test
    @DisplayName("★★ 归集主体查出 AGGREGATED，直连查出 DIRECT")
    void fundsModeReflectsEntity() {
        String agg = anEntity(MerchantQueryPort.FUNDS_AGGREGATED);
        String dir = anEntity(MerchantQueryPort.FUNDS_DIRECT);

        assertThat(merchantQueryPort.fundsModeOf(agg))
                .isEqualTo(MerchantQueryPort.FUNDS_AGGREGATED);
        assertThat(merchantQueryPort.fundsModeOf(dir))
                .isEqualTo(MerchantQueryPort.FUNDS_DIRECT);
    }

    // ---------------------------------------------------------------- fixtures

    private String anEntity(String fundsMode) {
        String no = "BP" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("价格字段测试主体");
        m.setLegalForm("ENTERPRISE");
        m.setStatus("ACTIVE");
        m.setFundsMode(fundsMode);
        entityMapper.insert(m);
        return no;
    }

    @SuppressWarnings("unused")
    private MchEntity reload(String entityNo) {
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() ->
                entityMapper.selectOne(Wrappers.<MchEntity>lambdaQuery()
                        .eq(MchEntity::getEntityNo, entityNo).last("LIMIT 1")));
    }
}
