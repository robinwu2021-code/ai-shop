package ai.neargo.shop.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;

/** Report-only HTTP boundary: typed failures never become successful, empty DTOs. */
public final class ReportServiceResponses {
    private ReportServiceResponses() { }
    public static <T> T domain(Supplier<T> operation) {
        try { return operation.get(); }
        catch (IllegalArgumentException failure) {
            if (controlled(failure)) throw BizException.of(ErrorCode.BAD_REQUEST);
            throw failure;
        } catch (IllegalStateException failure) {
            if (controlled(failure)) throw BizException.of(ErrorCode.CONFLICT);
            throw failure;
        }
    }
    public static ResponseEntity<Void> error(RuntimeException failure) {
        int status;
        if (failure instanceof BizException business) {
            status=switch(business.errorCode()) {
                case BAD_REQUEST -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT -> 409;
                case ENTITLEMENT_FEATURE_UNAVAILABLE, PAY_MODE_NOT_SUPPORTED, PRICING_OWNER_UNSUPPORTED -> 503;
                default -> 500;
            };
        } else if (failure instanceof IllegalArgumentException || failure instanceof HttpMessageNotReadableException) status=400;
        else if (failure instanceof IllegalStateException && controlled(failure)) status=409;
        else status=500;
        return ResponseEntity.status(status).build();
    }
    public static <T> T call(String owner,Supplier<T> operation) {
        try { return ServiceCalls.call(owner,operation); }
        catch (ServiceCallException failure) {
            ErrorCode code=switch(failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if(code!=null) throw BizException.of(code);
            throw failure;
        }
    }
    private static boolean controlled(RuntimeException failure) {
        return failure.getMessage()!=null && failure.getMessage().matches("REPORT_[A-Z0-9_]+");
    }
}
