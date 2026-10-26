package ai.neargo.shop.pay.api.common;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.Messages;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 开放 API 与收银台的错误响应（安全规范 §5.2）：真 HTTP 状态 + {@code {code, message, requestId}}，三语文案；
 * 未知异常只回请求号，不回栈、不回 SQL、不回渠道原始报错。结果码记进请求属性，过滤器写请求记录时用。
 */
@RestControllerAdvice(basePackages = {"ai.neargo.shop.pay.api.open", "ai.neargo.shop.pay.api.checkout"})
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PortalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(PortalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<PortalResponse<Void>> onBiz(BizException e, HttpServletRequest req) {
        return error(req, e.errorCode(), Messages.get(e.errorCode().msgKey(), e.args()));
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            IllegalArgumentException.class})
    public ResponseEntity<PortalResponse<Void>> onBadRequest(Exception e, HttpServletRequest req) {
        return error(req, ErrorCode.BAD_REQUEST, Messages.get(ErrorCode.BAD_REQUEST.msgKey()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<PortalResponse<Void>> onUnknown(Exception e, HttpServletRequest req) {
        log.error("[portal] 未处理异常 requestId={}", PortalRequests.requestId(req), e);
        return error(req, ErrorCode.INTERNAL_ERROR, Messages.get(ErrorCode.INTERNAL_ERROR.msgKey()));
    }

    private static ResponseEntity<PortalResponse<Void>> error(HttpServletRequest req, ErrorCode code, String message) {
        req.setAttribute(PortalRequests.RESULT_CODE, code.name());
        return ResponseEntity.status(httpStatusOf(code))
                .body(new PortalResponse<>(code.code(), message, null, PortalRequests.requestId(req)));
    }

    /** 错误码 → HTTP 状态（按码名的约定：*_NOT_FOUND 404、*_CONFLICT 409、*FORBIDDEN 403 …） */
    static int httpStatusOf(ErrorCode code) {
        String n = code.name();
        if (code == ErrorCode.UNAUTHORIZED || code == ErrorCode.TOKEN_EXPIRED) {
            return 401;
        }
        if (n.endsWith("FORBIDDEN")) {
            return 403;
        }
        if (n.endsWith("NOT_FOUND")) {
            return 404;
        }
        if (n.endsWith("CONFLICT")) {
            return 409;
        }
        if (code == ErrorCode.TOO_MANY_REQUESTS) {
            return 429;
        }
        if (code == ErrorCode.INTERNAL_ERROR) {
            return 500;
        }
        if (code == ErrorCode.PAY_CHANNEL_UNAVAILABLE) {
            return 503;
        }
        return n.endsWith("_STATE") || n.endsWith("_LIMIT") || n.endsWith("_EXCEEDS") ? 422 : 400;
    }
}
