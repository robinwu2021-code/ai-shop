package ai.neargo.shop.portal;

import ai.neargo.shop.common.*;
import ai.neargo.shop.print.api.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(annotations = PrintEndpoint.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PrintExceptionHandler {
    @ExceptionHandler(PrintFailure.class)
    public ResponseEntity<ApiResult<PrintOperationError>> failure(PrintFailure e) {
        String key = e.key();
        ErrorCode code = ErrorCode.PRINT_BLOCKED;
        HttpStatus status = HttpStatus.UNPROCESSABLE_ENTITY;
        if (key.contains("Forbidden") || key.equals("print.forbidden")) {
            code = ErrorCode.FORBIDDEN;
            status = HttpStatus.FORBIDDEN;
        } else if (key.toLowerCase().contains("unauthorized")) {
            code = ErrorCode.UNAUTHORIZED;
            status = HttpStatus.UNAUTHORIZED;
        } else if (key.toLowerCase().contains("notfound")) {
            code = ErrorCode.NOT_FOUND;
            status = HttpStatus.NOT_FOUND;
        } else if (key.toLowerCase().contains("conflict") || key.equals("print.revisionConflict")) {
            code = ErrorCode.PRINT_CONFLICT;
            status = HttpStatus.CONFLICT;
        } else if (key.contains("NotConfigured") || key.contains("Unavailable")) {
            code = ErrorCode.PRINT_UNAVAILABLE;
            status = HttpStatus.SERVICE_UNAVAILABLE;
        }
        var error =
                new PrintOperationError(
                        key,
                        false,
                        java.util.UUID.randomUUID().toString(),
                        new FrozenJson("{}", PrintHashes.sha256("{}")));
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ApiResult<>(code.code(), Messages.get(code.msgKey()), error));
    }
}
