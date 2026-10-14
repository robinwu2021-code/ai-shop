package ai.neargo.shop.scenario;

import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchPaymentMapper;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「能不能开票」判的是<b>谁是销售主体</b>，不是「这家商家自己开不开得出票」。
 *
 * <p>归集路径下平台是销售主体：合同相对方是平台、钱在平台账户，
 * <b>票由平台开给消费者</b>（ADR-017 §3.4 条件 2）——
 * 供应商有没有票是平台跟他之间的事（<b>进项</b>），与消费者这张<b>销项</b>票无关。
 *
 * <p><b>此前判在错的轴上</b>：只读 {@code mch_payment_merchant.invoice_capable}，
 * 于是无照自然人在归集下会显示「本商家无法开具发票」。那句话有两重错：
 * <ol>
 *   <li>事实错 —— 平台开得出</li>
 *   <li><b>它把销售方指给了商家</b> —— 而那正是 {@code seller-statement} 守卫
 *       在防的表述：写了它，归集资金模式就不成立（二清）</li>
 * </ol>
 *
 * <p>与积分能力、售后分流同一根轴：<b>责任跟着钱走</b>。
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("开票能力：判的是谁是销售主体，不是商家自己开不开得出票")
class InvoiceCapableAxisFlowTest {

    @Autowired
    private MerchantQueryPort merchantQueryPort;

    @Autowired
    private MchEntityMapper entityMapper;

    @Autowired
    private MchPaymentMapper paymentMapper;

    @Test
    @DisplayName("★★★ 归集 + 商家自己开不出票 → 仍然可开票（平台开的）")
    void aggregatedIsInvoiceCapableEvenIfMerchantIsNot() {
        String m = anEntity(MerchantQueryPort.FUNDS_AGGREGATED);
        payAccount(m, false);

        assertThat(merchantQueryPort.payCapabilityOf(m, null).invoiceCapable())
                .as("归集下平台是销售主体，票是平台开的 —— "
                        + "说「本商家无法开具发票」既是事实错，也把销售方指给了商家")
                .isTrue();
    }

    @Test
    @DisplayName("★★★ 直连 + 商家开不出票 → 确实开不出（他自己就是销售主体）")
    void directFollowsMerchantCapability() {
        String m = anEntity(MerchantQueryPort.FUNDS_DIRECT);
        payAccount(m, false);

        // 这条不能一起放行：钱在商家账户、合同相对方是他，
        // 平台没有替他开票的立场，硬说能开是骗消费者
        assertThat(merchantQueryPort.payCapabilityOf(m, null).invoiceCapable()).isFalse();
    }

    @Test
    @DisplayName("★★ 直连 + 商家开得出票 → 可开票")
    void directCapableMerchant() {
        String m = anEntity(MerchantQueryPort.FUNDS_DIRECT);
        payAccount(m, true);

        assertThat(merchantQueryPort.payCapabilityOf(m, null).invoiceCapable()).isTrue();
    }

    // ---------------------------------------------------------------- fixtures

    private String anEntity(String fundsMode) {
        String no = "IC" + System.nanoTime() % 100_000_000L;
        MchEntity m = new MchEntity();
        m.setEntityNo(no);
        m.setName("开票轴测试主体");
        // 无照自然人 —— 正是此前会被误判成「无法开票」的那一档
        m.setLegalForm("NATURAL_PERSON");
        m.setStatus("ACTIVE");
        m.setFundsMode(fundsMode);
        entityMapper.insert(m);
        return no;
    }

    private void payAccount(String merchantNo, boolean invoiceCapable) {
        MchPaymentMerchant pm = new MchPaymentMerchant();
        pm.setEntityNo(merchantNo);
        pm.setStoreNo(MchPaymentMerchant.ENTITY_LEVEL);
        pm.setPayChannel(MchPaymentMerchant.WECHAT);
        pm.setApplyStatus(MchPaymentMerchant.ACTIVE);
        pm.setPayMerchantNo("PM-" + merchantNo);
        pm.setPayMethods("[\"JSAPI\"]");
        pm.setInvoiceCapable(invoiceCapable);
        pm.setQuotaLimitMinor(0L);
        pm.setQuotaUsedMinor(0L);
        pm.setQuotaPeriod(String.valueOf(java.time.LocalDate.now().getYear()));
        paymentMapper.insert(pm);
    }
}
