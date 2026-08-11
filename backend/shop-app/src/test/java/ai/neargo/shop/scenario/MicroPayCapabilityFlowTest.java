package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.merchant.service.AdmissionService;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 小微收款能力：支付方式 / 开票 / 额度（落地清单 P2-3 ~ P2-6）。
 *
 * <p>这三件事的紧迫性完全是 F-6 与准入矩阵造成的：在那之前小微进不来，
 * 三个坑是死的；现在它们活了。共同后果都是<b>付款那一刻才炸</b>——
 * 小微没有 H5/App 支付方式（混合购物车整单付不了）、小微不能开票
 * （买完才发现补救不了）、额度用尽（通道直接拒收）。
 * 每一条单独看都像偶发故障，放在一起看才是同一件事。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("小微收款能力：付款前就该知道的三件事")
class MicroPayCapabilityFlowTest {

    private static final String MERCHANT = "MPQ0001";

    @Autowired
    private MerchantQueryPort merchantQueryPort;

    @Autowired
    private MerchantAdminPort merchantAdminPort;

    @Autowired
    private AdmissionService admissionService;

    @Autowired
    private MchPaymentMapper paymentMapper;

    @Test
    @DisplayName("★ 没有收款记录时全放行 —— 进件没走完不等于他的货谁都买不了")
    void noPaymentRecordPassesThrough() {
        var cap = merchantQueryPort.payCapabilityOf("MPQ-NOBODY", null);

        assertThat(cap.invoiceCapable()).as("钱是欠着的，不是不能成交").isTrue();
        assertThat(cap.quotaExhausted()).isFalse();
        assertThat(cap.wouldExceed(999_999_999L)).isFalse();
    }

    @Test
    @DisplayName("★★ 额度未设置（0）时恒不拦 —— 没核对过的阈值不能拿来拦单")
    void unsetQuotaNeverBlocks() {
        payAccount(MERCHANT, 0L, 0L, "[\"JSAPI\"]", false);

        var cap = merchantQueryPort.payCapabilityOf(MERCHANT, null);

        assertThat(cap.quotaLimitMinor()).isZero();
        assertThat(cap.quotaExhausted())
                .as("0 是「未设置」不是「额度为零」—— 读成后者会把所有商家一次拦死")
                .isFalse();
        assertThat(cap.wouldExceed(1_000_000L)).isFalse();
    }

    @Test
    @DisplayName("★★ 额度卡在边缘的那一单也要拦 —— 放过去仍然会在通道侧失败")
    void wouldExceedBlocksTheEdgeOrder() {
        payAccount(MERCHANT, 100_000L, 90_000L, "[\"JSAPI\"]", false);

        var cap = merchantQueryPort.payCapabilityOf(MERCHANT, null);

        assertThat(cap.quotaExhausted()).as("还没用尽").isFalse();
        assertThat(cap.wouldExceed(5_000L)).as("加上还在额度内").isFalse();
        assertThat(cap.wouldExceed(20_000L)).as("加上就超了 —— 这一单过不去").isTrue();
    }

    @Test
    @DisplayName("★ 不可开票要读得出来 —— 这件事必须在付款前告诉用户")
    void invoiceCapabilityIsReadable() {
        payAccount(MERCHANT, 0L, 0L, "[\"JSAPI\"]", true);

        assertThat(merchantQueryPort.payCapabilityOf(MERCHANT, null).invoiceCapable())
                .as("字段与 DDL 一直都在，缺的只是读它的人")
                .isFalse();
    }

    @Test
    @DisplayName("★ 支付方式坏 JSON 按「什么都不支持」处理，不按「全都支持」放过去")
    void brokenPayMethodsJsonFailsClosed() {
        payAccount(MERCHANT, 0L, 0L, "{不是数组", false);

        assertThat(merchantQueryPort.payCapabilityOf(MERCHANT, null).payMethods())
                .as("一行坏数据不该变成「这家什么都能付」")
                .isEmpty();
    }

    @Test
    @DisplayName("★★ 累加用量：跨周期自动清零，且周期由服务端按当前时间算")
    void accrualRollsOverByPeriod() {
        payAccount(MERCHANT, 1_000_000L, 0L, "[\"JSAPI\"]", false);
        // 造一个上一个周期的用量
        MchPaymentMerchant pm = load(MERCHANT);
        pm.setQuotaPeriod("1999");
        pm.setQuotaUsedMinor(999_999L);
        paymentMapper.updateById(pm);

        merchantAdminPort.accruePayQuota(MERCHANT, null, 10_000L);

        var cap = merchantQueryPort.payCapabilityOf(MERCHANT, null);
        assertThat(cap.quotaUsedMinor())
                .as("周期翻篇要清零重算 —— 否则去年的钱会一直压着今年的额度")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("★ 运营只能设上限，不能改已用量 —— 能改就等于能把账做平")
    void opsCanOnlySetTheLimit() {
        payAccount(MERCHANT, 0L, 0L, "[\"JSAPI\"]", false);
        merchantAdminPort.accruePayQuota(MERCHANT, null, 30_000L);

        admissionService.setPayQuotaLimit(MERCHANT, null, 500_000L, "OPS");

        var cap = merchantQueryPort.payCapabilityOf(MERCHANT, null);
        assertThat(cap.quotaLimitMinor()).isEqualTo(500_000L);
        assertThat(cap.quotaUsedMinor()).as("用量是支付累加出来的事实").isEqualTo(30_000L);
    }

    // ---------------------------------------------------------------- helpers

    /** 主体级收款记录（{@code storeNo} 用空串，不是 null —— 唯一索引不约束 NULL）。 */
    private void payAccount(String merchantNo, long limit, long used,
                            String payMethodsJson, boolean noInvoice) {
        MchPaymentMerchant existing = load(merchantNo);
        MchPaymentMerchant pm = existing == null ? new MchPaymentMerchant() : existing;
        pm.setEntityNo(merchantNo);
        pm.setStoreNo(MchPaymentMerchant.ENTITY_LEVEL);
        pm.setPayChannel(MchPaymentMerchant.WECHAT);
        pm.setApplyStatus(MchPaymentMerchant.ACTIVE);
        pm.setPayMerchantNo("PM-" + merchantNo);
        pm.setPayMethods(payMethodsJson);
        pm.setInvoiceCapable(!noInvoice);
        pm.setQuotaLimitMinor(limit);
        pm.setQuotaUsedMinor(used);
        pm.setQuotaPeriod(String.valueOf(java.time.LocalDate.now().getYear()));
        if (existing == null) {
            paymentMapper.insert(pm);
        } else {
            paymentMapper.updateById(pm);
        }
    }

    private MchPaymentMerchant load(String merchantNo) {
        return paymentMapper.selectOne(Wrappers.<MchPaymentMerchant>lambdaQuery()
                .eq(MchPaymentMerchant::getEntityNo, merchantNo)
                .eq(MchPaymentMerchant::getStoreNo, MchPaymentMerchant.ENTITY_LEVEL)
                .last("LIMIT 1"));
    }
}
