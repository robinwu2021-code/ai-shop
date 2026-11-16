package ai.neargo.shop.payclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/**
 * Preserve expected public errors without exposing internal bodies or creating a second retry
 * executor.
 */
public final class EntityProfileRemoteCalls {
  private EntityProfileRemoteCalls() {}

  public static <T> T call(Supplier<T> action) {
    try {
      return ServiceCalls.call(ServiceName.PAY, action);
    } catch (ServiceCallException failure) {
      throw BizException.of(
          switch (failure.statusCode()) {
            case 400 -> ErrorCode.BAD_REQUEST;
            case 403 -> ErrorCode.FORBIDDEN;
            case 404 -> ErrorCode.NOT_FOUND;
            case 409 -> ErrorCode.CONFLICT;
            default -> ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE;
          });
    }
  }
}
