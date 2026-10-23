package ai.neargo.shop.trade.order;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.trade.entity.OrdIndustryOrderSource;
import ai.neargo.shop.trade.mapper.TradeMappers.IndustryOrderSourceMapper;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.order.service.lifecycle.PaymentResolutionService;
import ai.neargo.shop.trade.order.service.lifecycle.impl.PaymentLifecycleServiceImpl;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PaymentCurrencyConfirmationTest {
    private final SettlePort payments = mock(SettlePort.class);
    private final PaymentResolutionService resolutions = mock(PaymentResolutionService.class);
    private final PaymentLifecycleServiceImpl service = new PaymentLifecycleServiceImpl(payments, resolutions);

    @Test void confirmsExactOrderCurrencyInsteadOfHardcodedCny() {
        var order = order("AED");
        when(payments.successPayment("O", "WECHAT", "T")).thenReturn(receipt("AED"));
        assertThat(service.requireConfirmed(order, "WECHAT", "T").tradeNo()).isEqualTo("T");
        verifyNoInteractions(resolutions);
    }
    @Test void sameNumberInDifferentCurrencyIsNotPaymentProof() {
        when(payments.successPayment("O", "WECHAT", "T")).thenReturn(receipt("CNY"));
        assertThatThrownBy(() -> service.requireConfirmed(order("AED"), "WECHAT", "T"))
                .isInstanceOf(BizException.class);
    }
    @Test void retailCnyStillConfirmsWithoutBehaviourChange() {
        when(payments.successPayment("O", "WECHAT", "T")).thenReturn(receipt("CNY"));
        assertThat(service.requireConfirmed(order("CNY"), "WECHAT", "T").channel()).isEqualTo("WECHAT");
    }
    @Test void industryReceiptUsesActualPayOwnerAndNeverLocalRetailLedger() {
        var owner = mock(OrdinaryPaymentPort.class);
        var sources = mock(IndustryOrderSourceMapper.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("owner", owner);
        var service = new PaymentLifecycleServiceImpl(payments, resolutions, sources,
                beans.getBeanProvider(OrdinaryPaymentPort.class));
        var source = new OrdIndustryOrderSource(); source.setStatus("BOUND"); source.setBuyerNo("U");
        source.setPaymentOutTradeNo("OUT");
        when(sources.selectOne(any())).thenReturn(source);
        when(owner.query(new OrdinaryPaymentPort.Reference("O", "U", "OUT"))).thenReturn(
                new OrdinaryPaymentPort.Lookup(true, new OrdinaryPaymentPort.Attempt("P", "OUT", "O", "U", "E",
                        "WECHAT", "T", 10500L, "AED", "SUCCESS", null)));
        var proof = service.requireConfirmed(order("AED"), "WECHAT", "T");
        clearInvocations(owner, sources);
        service.verifyConfirmed(order("AED"), proof);
        verifyNoInteractions(payments, owner, sources, resolutions);
    }
    @Test void lockTimeVerificationRejectsChangedOrderAndDoesNotRepeatRemoteQuery() {
        when(payments.successPayment("O", "WECHAT", "T")).thenReturn(receipt("AED"));
        var proof = service.requireConfirmed(order("AED"), "WECHAT", "T");
        clearInvocations(payments);
        var changed = order("AED"); changed.setPayAmount(10501L);
        assertThatThrownBy(() -> service.verifyConfirmed(changed, proof)).isInstanceOf(BizException.class);
        verifyNoInteractions(payments);
    }
    @Test void wrongOriginalAttemptCannotConfirmSameOrderAndAmount() {
        var owner = mock(OrdinaryPaymentPort.class);
        var sources = mock(IndustryOrderSourceMapper.class);
        var beans = new StaticListableBeanFactory(); beans.addBean("owner", owner);
        var service = new PaymentLifecycleServiceImpl(payments, resolutions, sources,
                beans.getBeanProvider(OrdinaryPaymentPort.class));
        var source = new OrdIndustryOrderSource(); source.setStatus("BOUND"); source.setBuyerNo("U");
        source.setPaymentOutTradeNo("OUT"); when(sources.selectOne(any())).thenReturn(source);
        when(owner.query(new OrdinaryPaymentPort.Reference("O", "U", "OUT"))).thenReturn(
                new OrdinaryPaymentPort.Lookup(true, new OrdinaryPaymentPort.Attempt("P", "OTHER", "O", "U", "E",
                        "WECHAT", "T", 10500L, "AED", "SUCCESS", null)));
        assertThatThrownBy(() -> service.requireConfirmed(order("AED"), "WECHAT", "T"))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(payments);
    }
    private static OrdOrder order(String currency) {
        var order = new OrdOrder(); order.setTenantNo("MAIN"); order.setOrderNo("O"); order.setUserNo("U");
        order.setStatus(OrdOrder.WAIT_PAY); order.setPayAmount(10500L); order.setCurrency(currency); return order;
    }
    private static SettlePort.PaymentReceipt receipt(String currency) {
        return new SettlePort.PaymentReceipt("P", "O", "U", "WECHAT", "T", 10500, currency,
                "SUCCESS", null);
    }
}
