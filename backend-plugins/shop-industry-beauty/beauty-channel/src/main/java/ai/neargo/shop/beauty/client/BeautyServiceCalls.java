package ai.neargo.shop.beauty.client;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** Business errors retain their meaning; transport UNKNOWN is never converted into a second write attempt. */
final class BeautyServiceCalls {
    private BeautyServiceCalls() { }
    static <T> T call(Supplier<T> request) {
        try { return ServiceCalls.call(ServiceName.BEAUTY, request); }
        catch (ServiceCallException failure) {
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
