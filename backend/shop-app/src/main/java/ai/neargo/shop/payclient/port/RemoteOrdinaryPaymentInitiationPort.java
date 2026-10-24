package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayOrdinaryPaymentRequests.Initiate;
import ai.neargo.shop.payclient.OrdinaryPaymentPayerResolver;
import ai.neargo.shop.spi.settle.OrdinaryPaymentInitiationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** One send to PAY with authenticated core-owned identity; transport uncertainty is never retried here. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOrdinaryPaymentInitiationPort implements OrdinaryPaymentInitiationPort {
    private final PayInternalApi pay;
    private final OrdinaryPaymentPayerResolver payer;

    public RemoteOrdinaryPaymentInitiationPort(PayInternalApi pay, OrdinaryPaymentPayerResolver payer) {
        this.pay = pay;
        this.payer = payer;
    }

    @Override
    public SettlePort.PayInitResult initiate(SettlePort.PaymentOpen request) {
        var command = new Initiate(request, payer.resolve(request));
        var result = ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryPaymentInitiate(command));
        if (result == null || result.outTradeNo() == null || result.outTradeNo().isBlank()
                || !Objects.equals(result.payChannel(), request.payChannel())) {
            throw new IllegalStateException("PAY_REMOTE_INIT_RECEIPT_CONFLICT");
        }
        return result;
    }
}
