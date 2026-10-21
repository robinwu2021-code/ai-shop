package ai.neargo.shop.beauty.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceRecoveryInternalApi;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads the platform authority outside source/resource locks; failures never become absence or permission. */
public class RemoteResourceCommandFactsPort implements ResourceCommandFactsPort {
    private final ResourceRecoveryInternalApi api;
    public RemoteResourceCommandFactsPort(ResourceRecoveryInternalApi api) { this.api = api; }
    @Override
    public Lookup lookup(ResourceSourceAuthorityPort.Request query) {
        if (query == null) { throw new IllegalArgumentException("original request required"); }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("remote owner reads require no caller transaction");
        }
        try {
            var result = ServiceCalls.call(ServiceName.PLATFORM, () -> api.command(query));
            if (result == null) { throw new IllegalStateException("owner returned no explicit result"); }
            if (result.command() != null && !query.binding().equals(result.command().binding())) {
                throw new IllegalStateException("owner returned a different command binding");
            }
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
