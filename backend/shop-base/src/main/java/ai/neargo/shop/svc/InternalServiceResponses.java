package ai.neargo.shop.svc;

import ai.neargo.shop.common.BizException;

import org.springframework.http.ResponseEntity;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/** Shared service-token and status semantics. Never copy private owner request/response bodies into error output. */
public final class InternalServiceResponses {
    private InternalServiceResponses() { }
    public static ResponseEntity<?> invoke(String expected, String given, Supplier<?> operation) {
        if (expected == null || expected.isBlank() || given == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(401).build();
        }
        try {
            Object result = operation.get();
            return result == null ? ResponseEntity.status(503).build() : ResponseEntity.ok(result);
        }
        catch (BizException failure) {
            int status = switch (failure.errorCode()) {
                case BAD_REQUEST -> 400;
                case FORBIDDEN -> 403;
                case NOT_FOUND -> 404;
                case CONFLICT,
                                CHECKOUT_QUOTE_STALE,
                                CHECKOUT_CONFIRMATION_REQUIRED,
                                MANAGED_SERVICE_COMPLETION_BLOCKED,
                                CHECKOUT_OPERATION_REJECTED ->
                                409;
                        case CHECKOUT_UNSUPPORTED_SELECTION -> 422;
                        case CHECKOUT_DEPENDENCY_UNAVAILABLE -> 503;
                case ENTITLEMENT_FEATURE_UNAVAILABLE, MEMBER_GUEST_UNAVAILABLE -> 503;
                case MEMBER_CODE_INVALID -> 400;
                case BEAUTY_SEARCH_INCOMPLETE -> 422;
                default -> 500;
            };
            return ResponseEntity.status(status).build();
        } catch (IllegalArgumentException failure) { return ResponseEntity.badRequest().build(); }
    }
}
