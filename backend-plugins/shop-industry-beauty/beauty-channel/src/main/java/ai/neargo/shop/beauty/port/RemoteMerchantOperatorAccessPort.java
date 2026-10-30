package ai.neargo.shop.beauty.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads the platform authority outside source/resource locks; failures never become absence or permission. */
public class RemoteMerchantOperatorAccessPort implements MerchantOperatorAccessPort {
    private final ResourceRecoveryInternalApi api;
    public RemoteMerchantOperatorAccessPort(ResourceRecoveryInternalApi api) { this.api = api; }
    @Override
    public boolean allowed(Query query) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("remote owner reads require no caller transaction");
        }
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.operatorAccess(query));
            if (result == null) { throw new IllegalStateException("owner returned no explicit result"); }
            return result;
        } catch (ServiceCallException failure) {
            ErrorCode code = switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if (code != null) { throw BizException.of(code); }
            throw failure;
        }
    }
}
