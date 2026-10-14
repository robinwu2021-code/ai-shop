package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.MerchantGovernService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 门店经营模式：补上一个「下游已在依赖、上游无人能写」的缺口。
 *
 * <p>{@code mch_store.business_mode} 早已存在，{@code SettleServiceImpl} 每单都读它
 * 决定走哪条结算状态机与开票状态——但在此之前<b>全仓库没有任何一处能写它</b>，
 * 换一家店的经营模式只能手改数据库。这比「没有这个功能」更危险，
 * 因为下游已经在依赖它了。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("门店经营模式：下游一直在读，现在终于有人能写")
class StoreBusinessModeFlowTest {

    @Autowired
    private MerchantGovernService governService;

    @Autowired
    private MchStoreMapper storeMapper;

    @Autowired
    private MchPaymentMapper paymentMapper;

    @Test
    @DisplayName("★ 切自营：不要求收款号 —— 自营的钱本来就先进平台")
    void selfOperatedNeedsNoPayAccount() {
        String store = aStore("MBM1", null);

        var vo = governService.setBusinessMode(store, MchStore.SELF_OPERATED, "OPS");

        assertThat(vo.businessMode()).isEqualTo(MchStore.SELF_OPERATED);
        assertThat(reload(store).getBusinessMode()).isEqualTo(MchStore.SELF_OPERATED);
    }

    @Test
    @DisplayName("★★ 切第三方但没有收款号 → 拦下（70012），而不是静默欠款")
    void thirdPartyWithoutPayAccountIsBlocked() {
        String store = aStore("MBM2", null);

        /*
         * 不校验的后果不是报错，而是**静默欠款**：订单照常成交、账单照常生成，
         * 只是 payMerchantNo 为空，钱卡在平台侧下不去。等发现时已经积了一批单。
         */
        assertThatThrownBy(() -> governService.setBusinessMode(store, MchStore.THIRD_PARTY, "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.PAY_MERCHANT_REQUIRED.name());

        assertThat(reload(store).getBusinessMode())
                .as("拦下就不能落库 —— 半途改了模式比没改更难查")
                .isNotEqualTo(MchStore.THIRD_PARTY);
    }

    @Test
    @DisplayName("★ 有主体默认收款号（合并结算）的门店，可以切第三方")
    void mergedSettlementStoreCanGoThirdParty() {
        String merchantNo = "MBM3";
        String store = aStore(merchantNo, null);
        // 主体级默认号 = **空串**而非 null（唯一索引不约束 NULL）；
        // 这是「合并结算」的正常形态而非缺失
        payAccount(merchantNo, MchPaymentMerchant.ENTITY_LEVEL, "PM_DEFAULT_" + merchantNo);

        var vo = governService.setBusinessMode(store, MchStore.THIRD_PARTY, "OPS");

        assertThat(vo.payMerchantNo())
                .as("只查店号会把所有合并结算的门店误判成「没有收款账户」")
                .isEqualTo("PM_DEFAULT_" + merchantNo);
    }

    @Test
    @DisplayName("★ 本店专属收款号优先于主体默认号")
    void ownAccountWinsOverDefault() {
        String merchantNo = "MBM4";
        String store = aStore(merchantNo, null);
        payAccount(merchantNo, MchPaymentMerchant.ENTITY_LEVEL, "PM_DEFAULT_" + merchantNo);
        payAccount(merchantNo, store, "PM_OWN_" + merchantNo);

        var vo = governService.setBusinessMode(store, MchStore.THIRD_PARTY, "OPS");

        assertThat(vo.payMerchantNo()).isEqualTo("PM_OWN_" + merchantNo);
    }

    @Test
    @DisplayName("★ 非法模式值直接拒 —— 枚举只有两个值，第三个只可能是笔误")
    void unknownModeRejected() {
        String store = aStore("MBM5", null);

        assertThatThrownBy(() -> governService.setBusinessMode(store, "CONSIGNMENT", "OPS"))
                .isInstanceOf(BizException.class)
                .hasMessage(ErrorCode.BAD_REQUEST.name());
    }

    @Test
    @DisplayName("★ 一览能看出哪些店是自营，以及各自的收款号")
    void listShowsModePerStore() {
        String merchantNo = "MBM6";
        String a = aStore(merchantNo, null);
        String b = aStore(merchantNo, null);
        governService.setBusinessMode(a, MchStore.SELF_OPERATED, "OPS");

        var modes = governService.storeModes(merchantNo);

        assertThat(modes).hasSize(2);
        assertThat(modes).anySatisfy(m -> {
            assertThat(m.storeNo()).isEqualTo(a);
            assertThat(m.businessMode()).isEqualTo(MchStore.SELF_OPERATED);
        });
        assertThat(b).isNotEqualTo(a);
    }

    private MchStore reload(String storeNo) {
        return storeMapper.selectOne(Wrappers.<MchStore>lambdaQuery()
                .eq(MchStore::getStoreNo, storeNo).last("LIMIT 1"));
    }

    private String aStore(String merchantNo, String mode) {
        String no = "STBM" + System.nanoTime() % 100_000_000;
        MchStore s = new MchStore();
        s.setStoreNo(no);
        s.setEntityNo(merchantNo);
        s.setName("模式测试店");
        s.setStatus(MchStore.ACTIVE);
        s.setBusinessMode(mode);
        storeMapper.insert(s);
        return no;
    }

    private void payAccount(String merchantNo, String storeNo, String payMerchantNo) {
        MchPaymentMerchant p = new MchPaymentMerchant();
        p.setEntityNo(merchantNo);
        p.setStoreNo(storeNo);
        p.setPayMerchantNo(payMerchantNo);
        p.setPayChannel(MchPaymentMerchant.WECHAT);
        p.setApplyStatus(MchPaymentMerchant.ACTIVE);
        paymentMapper.insert(p);
    }
}
