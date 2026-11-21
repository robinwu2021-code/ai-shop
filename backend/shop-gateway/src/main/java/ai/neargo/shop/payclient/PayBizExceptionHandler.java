package ai.neargo.shop.payclient;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.ErrorCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** The bridge uses real HTTP statuses, unlike the legacy business API. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PayBizExceptionHandler {
  @ExceptionHandler(PayBizException.class)
  public ResponseEntity<ApiResult<Void>> failure(PayBizException error) {
    var status = error.status();
    var code =
        switch (status) {
          case UNAUTHORIZED -> ErrorCode.UNAUTHORIZED;
          case FORBIDDEN -> ErrorCode.FORBIDDEN;
          case BAD_REQUEST, UNSUPPORTED_MEDIA_TYPE, PAYLOAD_TOO_LARGE -> ErrorCode.BAD_REQUEST;
          default -> ErrorCode.RESOURCE_DEPENDENCY_UNAVAILABLE;
        };
    return ResponseEntity.status(status)
        .header("Cache-Control", "no-store")
        .body(ApiResult.error(code.code(), status.getReasonPhrase()));
  }
}
