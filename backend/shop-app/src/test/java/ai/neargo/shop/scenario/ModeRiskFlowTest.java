package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 无照主体 × 自营门店的风险清单。
 *
 * <p><b>这个组合是默认会发生的，不是谁配错了</b>：{@code mch_store.business_mode}
 * 默认值就是自营，且全仓没有任何一处校验「无照不得自营」。
 * 而它的后果是税务的 —— 自营下平台是销售主体，列支成本要进项票，
 * 无照主体开不出票，这笔支出<b>不得税前扣除</b>。
 *
 * <p>本轮刻意<b>只做看得见，不做拦截</b>：硬拦会同时打断存量商户与
 * 农产品供应商（农户正是「无照 + 自营采购」这条合规路径）。
 * 所以这里断言的是「查得出来、排得对」，<b>不是</b>「被拒绝」。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("无照 × 自营：先看见，不拦截")
class ModeRiskFlowTest {

    @Autowired
    private MerchantGovernService governService;

    @Autowired
    private MchEntityMapper entityMapper;

    @Autowired
    private MchStoreMapper storeMapper;

    @Test
    @DisplayName("★★ 无照主体的自营门店进清单，有照的不进")
    void onlyUnlicensedSelfOperatedListed() {
        String micro = anEntity("NATURAL_PERSON");
        String company = anEntity("ENTERPRISE");
        String microStore = aStore(micro, MchStore.SELF_OPERATED);
        aStore(company, MchStore.SELF_OPERATED);

        var risk = governService.modeRiskStores();

        assertThat(risk).anySatisfy(r -> assertThat(r.storeNo()).isEqualTo(microStore));
        // 有照主体即便自营也不是风险 —— 它开得出进项票
        assertThat(risk).noneSatisfy(r -> assertThat(r.merchantNo()).isEqualTo(company));
    }

    @Test
    @DisplayName("★★ 第三方门店不进清单 —— 钱不过平台，无票不构成平台风险")
    void thirdPartyNotListed() {
        String micro = anEntity("NATURAL_PERSON");
        String store = aStore(micro, MchStore.THIRD_PARTY);

        var risk = governService.modeRiskStores();

        assertThat(risk).noneSatisfy(r -> assertThat(r.storeNo()).isEqualTo(store));
    }

    @Test
    @DisplayName("★ 还没成交的也要列出来 —— 那是即将发生的敞口，最该在成交前处理")
    void zeroExposureStillListed() {
        String micro = anEntity("NATURAL_PERSON");
        String store = aStore(micro, MchStore.SELF_OPERATED);

        var risk = governService.modeRiskStores();

        assertThat(risk).anySatisfy(r -> {
            assertThat(r.storeNo()).isEqualTo(store);
            // 缺省成 0 而不是跳过：「有这家店但还没成交」也是一行
            assertThat(r.settledBills()).isZero();
            assertThat(r.settledMinor()).isZero();
        });
    }

    @Test
    @DisplayName("★ 按敞口倒序 —— 这份清单的用途就是决定先处理谁")
    void sortedByExposureDesc() {
        anEntityWithSelfOperatedStore();

        var risk = governService.modeRiskStores();

        assertThat(risk).isSortedAccordingTo(
                (a, b) -> Long.compare(b.settledMinor(), a.settledMinor()));
    }

    private void anEntityWithSelfOperatedStore() {
        aStore(anEntity("NATURAL_PERSON"), MchStore.SELF_OPERATED);
    }

    private String anEntity(String legalForm) {
        String no = "MRK" + System.nanoTime() % 100_000_000;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("风险清单测试主体");
        m.setLegalForm(legalForm);
        m.setStatus("ACTIVE");
        entityMapper.insert(m);
        return no;
    }

    private String aStore(String entityNo, String mode) {
        String no = "STMRK" + System.nanoTime() % 100_000_000;
        MchStore s = new MchStore();
        s.setStoreNo(no);
        s.setEntityNo(entityNo);
        s.setName("风险清单测试店");
        s.setStatus(MchStore.ACTIVE);
        s.setBusinessMode(mode);
        storeMapper.insert(s);
        return no;
    }
}
