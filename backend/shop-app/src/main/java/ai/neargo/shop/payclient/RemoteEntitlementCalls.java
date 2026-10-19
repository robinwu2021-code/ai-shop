package ai.neargo.shop.payclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Status-only failure mapping avoids copying internal request/response bodies into logs. No automatic retry. */
public final class RemoteEntitlementCalls {
    private RemoteEntitlementCalls() { }
    public static <T> T call(Supplier<T> operation) {
        try {
            return ServiceCalls.call(ServiceName.PAY, operation);
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
