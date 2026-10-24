package ai.neargo.shop.svc;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Supplier;

/** Shared service-token and status semantics. Never copy private owner request/response bodies into error output. */
public final class InternalServiceResponses {
    private InternalServiceResponses() { }
    public static ResponseEntity<?> invoke(String expected, String given, Supplier<?> operation) {
        if (!accepts(expected, given)) {
            return ResponseEntity.status(401).build();
        }
        try {
            Object result = operation.get();
            return result == null ? ResponseEntity.status(503).build() : ResponseEntity.ok(result);
        }
        catch (BizException failure) {
            int status = status(failure.errorCode(), false);
            return ResponseEntity.status(status).build();
        } catch (IllegalArgumentException failure) { return ResponseEntity.badRequest().build(); }
    }

    /** Bound failures retain safe association metadata without exposing owner bodies. */
    public static ResponseEntity<?> invokeBound(
            String expected, String given, Supplier<String> requestDigest, Supplier<?> operation) {
        if (!accepts(expected, given)) {
            return ResponseEntity.status(401).build();
        }
        String digest;
        try {
            digest = requestDigest.get();
            if (digest == null || !digest.matches("[0-9a-f]{64}")) {
                return ResponseEntity.badRequest().build();
            }
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).build();
        }
        try {
            Object result = operation.get();
            return result == null ? ResponseEntity.status(503).build() : ResponseEntity.ok(result);
        } catch (BizException failure) {
            return ResponseEntity.status(status(failure.errorCode(), true))
                    .header("X-Internal-Error-Code", failure.errorCode().name())
                    .header("X-Internal-Request-Digest", digest)
                    .build();
        } catch (IllegalArgumentException failure) {
            return ResponseEntity.badRequest().build();
        } catch (RuntimeException failure) {
            return ResponseEntity.status(503).build();
        }
    }

    private static boolean accepts(String expected, String given) {
        return expected != null
                && !expected.isBlank()
                && given != null
                && MessageDigest.isEqual(
                        expected.getBytes(StandardCharsets.UTF_8),
                        given.getBytes(StandardCharsets.UTF_8));
    }

    private static int status(ErrorCode code, boolean bound) {
        return switch (code) {
            case BAD_REQUEST, MEMBER_CODE_INVALID -> 400;
            case FORBIDDEN -> 403;
            case NOT_FOUND -> 404;
            case CONFLICT,
                    CHECKOUT_QUOTE_STALE,
                    CHECKOUT_CONFIRMATION_REQUIRED,
                    MANAGED_SERVICE_COMPLETION_BLOCKED,
                    CHECKOUT_OPERATION_REJECTED ->
                    409;
            case CHECKOUT_UNSUPPORTED_SELECTION, BEAUTY_SEARCH_INCOMPLETE -> 422;
            case CHECKOUT_DEPENDENCY_UNAVAILABLE,
                    ENTITLEMENT_FEATURE_UNAVAILABLE,
                    MEMBER_GUEST_UNAVAILABLE ->
                    503;
            case UNAUTHORIZED, TOKEN_EXPIRED -> bound ? 401 : 500;
            case BEAUTY_DEPENDENCY_UNAVAILABLE -> bound ? 503 : 500;
            case BEAUTY_ARRIVAL_REQUIRES_RESCHEDULE, BEAUTY_SOURCE_CUSTOMER_REQUIRED ->
                    bound ? 409 : 500;
            default -> 500;
        };
    }
}
