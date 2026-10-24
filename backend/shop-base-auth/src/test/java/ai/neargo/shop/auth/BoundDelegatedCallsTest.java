package ai.neargo.shop.auth;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.concurrent.atomic.AtomicInteger;

/** Real contexts are reused to expose leaks across authorization and failure exits. */
class BoundDelegatedCallsTest {
    record Payload(String operationNo) {}

    record Result(long revision) {}

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static InternalCall<Payload> call(String realm, String kind) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        "TEST",
                        "READ",
                        new DelegatedIdentity("MAIN", realm, kind, "actor"),
                        "MERCHANT".equals(realm) ? "entity" : null,
                        "MERCHANT".equals(realm) ? "store" : null,
                        "operation"),
                new Payload("operation"));
    }

    @Test
    void tokenPrecedesDelegationAndEveryExitRestoresPreviousContext() {
        var helper = new BoundDelegatedCalls("secret");
        var previous = SecurityContextHolder.getContext();
        for (var identity :
                new String[][] {{"MERCHANT", "MCH"}, {"MERCHANT", "USR"}, {"CONSUMER", "USR"}}) {
            var request = call(identity[0], identity[1]);
            var mode = BoundDelegatedCalls.Mode.valueOf(identity[0]);
            var response =
                    helper.invoke(
                            "secret",
                            request,
                            "TEST",
                            "READ",
                            mode,
                            "operation",
                            () -> {
                                assertThat(DelegatedIdentityContext.current())
                                        .isEqualTo(request.binding().identity());
                                assertThat(
                                                SecurityContextHolder.getContext()
                                                        .getAuthentication()
                                                        .getDetails())
                                        .isNull();
                                return new Result(1);
                            });
            assertThat(response.getStatusCode().value()).isEqualTo(200);
            assertThat(response.getBody()).isInstanceOf(InternalReply.class);
            assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
            var failure =
                    helper.invoke(
                            "secret",
                            request,
                            "TEST",
                            "READ",
                            mode,
                            "operation",
                            () -> {
                                throw BizException.of(ErrorCode.FORBIDDEN);
                            });
            assertThat(failure.getStatusCode().value()).isEqualTo(403);
            assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
            var runtime =
                    helper.invoke(
                            "secret",
                            request,
                            "TEST",
                            "READ",
                            mode,
                            "operation",
                            () -> {
                                throw new IllegalStateException("private evidence");
                            });
            assertThat(runtime.getStatusCode().value()).isEqualTo(503);
            assertThat(runtime.getBody()).isNull();
            assertThat(SecurityContextHolder.getContext()).isSameAs(previous);
        }
    }

    @Test
    void blankTokenConstructionIsSafeAndInvocationRejectsBeforeDigest() {
        var helper = new BoundDelegatedCalls("");
        var calls = new AtomicInteger();
        var response =
                helper.invoke(
                        "",
                        null,
                        "TEST",
                        "READ",
                        BoundDelegatedCalls.Mode.MERCHANT,
                        null,
                        () -> new Result(calls.incrementAndGet()));
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(calls).hasValue(0);
        var configured = new BoundDelegatedCalls("secret");
        var wrong =
                configured.invoke(
                        "wrong",
                        null,
                        "TEST",
                        "READ",
                        BoundDelegatedCalls.Mode.MERCHANT,
                        null,
                        () -> new Result(calls.incrementAndGet()));
        assertThat(wrong.getStatusCode().value()).isEqualTo(401);
        assertThat(calls).hasValue(0);
    }
}
