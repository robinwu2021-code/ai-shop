package ai.neargo.shop.beauty.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/** 调宿主（PLATFORM）的内部接口：400/403/404/409 还原成同义的业务错误，其余照原样抛出。商品类远程端口共用这一处。 */
final class PlatformCalls {
    private PlatformCalls() { }

    static <T> T call(Supplier<T> operation) {
        try {
            return ServiceCalls.call(ServiceName.PLATFORM, operation);
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
