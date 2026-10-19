package ai.neargo.shop.pay.client;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** No retries or body logging. Missing owner responses cannot become empty success or negative payment facts. */
public final class EntitlementRemoteCalls {
    private EntitlementRemoteCalls() { }
    public static <T> T call(String service, Supplier<T> operation) {
        try {
            T value = ServiceCalls.call(service, operation);
            if (value == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return value;
        } catch (ServiceCallException failure) {
            throw BizException.of(switch (failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                case 503 -> ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE;
                default -> ErrorCode.INTERNAL_ERROR;
            });
        }
    }
}
