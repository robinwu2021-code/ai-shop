package ai.neargo.shop.svc;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** A peer's error label is evidence only when its request digest and real status agree. */
class ExecutionRemoteErrorsTest {
    private static final String DIGEST = "a".repeat(64);

    @Test
    void matchingBusinessErrorRetainsItsMeaning() {
        assertEquals(ErrorCode.FORBIDDEN, ExecutionRemoteErrors.map(failure(403, "FORBIDDEN", DIGEST), DIGEST)
                .errorCode());
        assertEquals(ErrorCode.NOT_FOUND, ExecutionRemoteErrors.map(failure(404, "NOT_FOUND", DIGEST), DIGEST)
                .errorCode());
    }

    @Test
    void unsupportedPlanKeepsItsExactBoundBusinessError() {
        assertEquals(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION,
                ExecutionRemoteErrors.map(failure(422, "CHECKOUT_UNSUPPORTED_SELECTION", DIGEST), DIGEST).errorCode());
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                ExecutionRemoteErrors.map(failure(409, "CHECKOUT_UNSUPPORTED_SELECTION", DIGEST), DIGEST).errorCode());
    }

    @Test
    void copiedDigestOrMismatchedStatusDoesNotBecomeAnAuthoritativeRejection() {
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                ExecutionRemoteErrors.map(failure(403, "FORBIDDEN", "b".repeat(64)), DIGEST).errorCode());
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                ExecutionRemoteErrors.map(failure(503, "FORBIDDEN", DIGEST), DIGEST).errorCode());
        assertEquals(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE,
                ExecutionRemoteErrors.map(failure(409, "UNRECOGNIZED", DIGEST), DIGEST).errorCode());
    }

    private static ServiceCallException failure(int status, String code, String digest) {
        return new ServiceCallException(CallOutcome.REMOTE_ERROR, ServiceName.PLATFORM, status,
                "test-only transport status", null, code, digest);
    }
}
