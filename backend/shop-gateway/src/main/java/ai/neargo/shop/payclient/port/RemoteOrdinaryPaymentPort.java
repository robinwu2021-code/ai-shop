package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.settle.OrdinaryPaymentPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Standalone ordinary ledger access; writes are sent once and transport uncertainty is propagated. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOrdinaryPaymentPort implements OrdinaryPaymentPort {
    private final PayInternalApi pay;

    public RemoteOrdinaryPaymentPort(PayInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public Attempt open(SettlePort.PaymentOpen request) {
        Attempt result = ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryPaymentOpen(request));
        verify(result, request.orderNo(), request.userNo());
        if (!Objects.equals(request.payChannel(), result.payChannel())
                || !Objects.equals(request.currency(), result.currency())
                || !Objects.equals(request.entityNo(), result.entityNo())
                || !Objects.equals(request.amountMinor(), result.amountMinor())) {
            throw new IllegalStateException("PAY_REMOTE_RECEIPT_CONFLICT");
        }
        return result;
    }

    @Override
    public Lookup query(Reference reference) {
        Lookup result = ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryPaymentQuery(reference));
        verifyLookup(result, reference.orderNo(), reference.userNo());
        if (result.found() && !Objects.equals(reference.outTradeNo(), result.payment().outTradeNo())) {
            throw new IllegalStateException("PAY_REMOTE_REFERENCE_CONFLICT");
        }
        return result;
    }

    @Override
    public Lookup refresh(Reference reference) {
        Lookup result = ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryPaymentRefresh(reference));
        verifyLookup(result, reference.orderNo(), reference.userNo());
        if (result.found() && !Objects.equals(reference.outTradeNo(), result.payment().outTradeNo()))
            throw new IllegalStateException("PAY_REMOTE_REFERENCE_CONFLICT");
        return result;
    }

    @Override
    public Lookup success(OrderReference reference) {
        Lookup result = ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryPaymentSuccess(reference));
        verifyLookup(result, reference.orderNo(), reference.userNo());
        if (result.found() && !"SUCCESS".equals(result.payment().status())) {
            throw new IllegalStateException("PAY_REMOTE_SUCCESS_INVALID");
        }
        return result;
    }

    @Override
    public ChannelEvidence queryChannel(Reference reference) {
        ChannelEvidence result = ServiceCalls.call(ServiceName.PAY,
                () -> pay.ordinaryPaymentChannelQuery(reference));
        if (result == null || result.status() == null
                || !Objects.equals(result.outTradeNo(), reference.outTradeNo())) {
            throw new IllegalStateException("PAY_REMOTE_CHANNEL_REFERENCE_CONFLICT");
        }
        return result;
    }

    private static void verifyLookup(Lookup result, String orderNo, String userNo) {
        if (result == null || result.found() != (result.payment() != null)) {
            throw new IllegalStateException("PAY_REMOTE_RECEIPT_MISSING");
        }
        if (result.found()) {
            verify(result.payment(), orderNo, userNo);
        }
    }

    private static void verify(Attempt result, String orderNo, String userNo) {
        if (result == null || !Objects.equals(result.orderNo(), orderNo)
                || !Objects.equals(result.userNo(), userNo)
                || result.outTradeNo() == null || result.outTradeNo().isBlank()) {
            throw new IllegalStateException("PAY_REMOTE_RECEIPT_CONFLICT");
        }
    }
}
