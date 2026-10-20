package ai.neargo.shop.paybridge.port;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.paybridge.CardSaleCoordinator;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.PaidCardPaymentGatePort;
import org.springframework.stereotype.Component;
@Component
public class PaidCardPaymentGatePortImpl implements PaidCardPaymentGatePort {
    private final CardSaleCoordinator coordinator;
    public PaidCardPaymentGatePortImpl(CardSaleCoordinator coordinator) { this.coordinator=coordinator; }
    @Override public void requireReady(String tenant,String orderNo) {
        var progress=coordinator.recover(new EntitlementContext(tenant,"SYSTEM",orderNo,"0".repeat(64)),orderNo);
        if(progress.total()>0 && !progress.readyForPayment()) throw BizException.of(ErrorCode.CONFLICT);
    }
}
