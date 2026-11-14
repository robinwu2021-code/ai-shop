package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.ApiResult;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.GlobalExceptionHandler.UnauthorizedException;
import ai.neargo.shop.portal.biz.BizPaymentEventsController;
import ai.neargo.shop.portal.internal.InternalPaymentCollectionController;

import jakarta.validation.ConstraintViolationException;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import tools.jackson.core.JacksonException;

/** These two wire protocols require HTTP errors before the legacy business API advice. */
@RestControllerAdvice(assignableTypes = {
    InternalPaymentCollectionController.class, BizPaymentEventsController.class
})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PaymentCollectionExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler({ConstraintViolationException.class, JacksonException.class})
    public ResponseEntity<Object> invalid(Exception failure, WebRequest request) {
        return createResponseEntity(null, new HttpHeaders(), HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> forbidden(WebRequest request) {
        return createResponseEntity(null, new HttpHeaders(),
                SecurityUtils.currentUser().isEmpty() ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN, request);
    }

    @ExceptionHandler({UnauthorizedException.class, AuthenticationException.class})
    public ResponseEntity<Object> unauthorized(WebRequest request) {
        return createResponseEntity(null, new HttpHeaders(), HttpStatus.UNAUTHORIZED, request);
    }

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Object> business(BizException failure, WebRequest request) {
        var status = switch (failure.errorCode()) {
            case UNAUTHORIZED, TOKEN_EXPIRED -> HttpStatus.UNAUTHORIZED;
            case FORBIDDEN, BIZ_ROLE_FORBIDDEN -> HttpStatus.FORBIDDEN;
            default -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return createResponseEntity(null, new HttpHeaders(), status, request);
    }

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return new ResponseEntity<>(
                ApiResult.error(status.value(), "payment protocol request rejected"), headers, status);
    }
}
