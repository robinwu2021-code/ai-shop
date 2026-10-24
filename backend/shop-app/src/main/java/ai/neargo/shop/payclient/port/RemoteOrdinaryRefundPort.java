package ai.neargo.shop.payclient.port;

import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.spi.settle.OrdinaryRefundPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Trusted financial transport; uncertain writes are not retried and recovery uses the frozen request. */
@Component
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteOrdinaryRefundPort implements OrdinaryRefundPort {
    private final PayInternalApi pay;

    public RemoteOrdinaryRefundPort(PayInternalApi pay) {
        this.pay = pay;
    }

    @Override
    public Receipt request(Execution execution) {
        return verify(execution, ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryRefundRequest(execution)));
    }

    @Override
    public Receipt query(Execution execution) {
        return verify(execution, ServiceCalls.call(ServiceName.PAY, () -> pay.ordinaryRefundQuery(execution)));
    }

    private static Receipt verify(Execution request, Receipt result) {
        if (request == null || request.refund() == null || result == null || result.outcome() == null
                || result.outcome().status() == null || !Objects.equals(request, result.execution())
                || !Objects.equals(request.refund().executionNo(), result.outcome().executionNo())) {
            throw new IllegalStateException("PAY_REMOTE_REFUND_RECEIPT_CONFLICT");
        }
        return result;
    }
}
