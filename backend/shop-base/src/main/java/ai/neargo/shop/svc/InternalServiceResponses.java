package ai.neargo.shop.svc;

import ai.neargo.shop.common.BizException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;

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
                case CONFLICT -> 409;
                case ENTITLEMENT_FEATURE_UNAVAILABLE -> 503;
                default -> 500;
            };
            return ResponseEntity.status(status).build();
        } catch (IllegalArgumentException failure) { return ResponseEntity.badRequest().build(); }
    }
}
