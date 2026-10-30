package ai.neargo.shop.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Only an exact request-bound, status-consistent execution error can become a business result. */
public final class ExecutionRemoteErrors {
    private ExecutionRemoteErrors() {}

    /** Unbound, unknown and inconsistent failures remain unavailable; none authorize retry effects. */
    public static BizException map(ServiceCallException failure, String digest) {
        ErrorCode code = null;
        if (failure.outcome() == CallOutcome.REMOTE_ERROR && digest != null && failure.requestDigest() != null
                && MessageDigest.isEqual(digest.getBytes(StandardCharsets.UTF_8),
                        failure.requestDigest().getBytes(StandardCharsets.UTF_8))) {
            code = switch (failure.remoteErrorCode() == null ? "" : failure.remoteErrorCode()) {
                case "UNAUTHORIZED" -> ErrorCode.UNAUTHORIZED;
                case "TOKEN_EXPIRED" -> ErrorCode.TOKEN_EXPIRED;
                case "BAD_REQUEST" -> ErrorCode.BAD_REQUEST;
                case "FORBIDDEN" -> ErrorCode.FORBIDDEN;
                case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
                case "CONFLICT" -> ErrorCode.CONFLICT;
                case "CHECKOUT_UNSUPPORTED_SELECTION" -> ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION;
                default -> null;
            };
        }
        int status = code == null ? 0 : switch (code) {
            case UNAUTHORIZED, TOKEN_EXPIRED -> 401;
            case BAD_REQUEST -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case CHECKOUT_UNSUPPORTED_SELECTION -> 422;
            default -> 0;
        };
        return BizException.of(code != null && status == failure.statusCode()
                ? code : ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }
}
