package ai.neargo.shop.beauty.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.CheckoutInternalApi;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.svc.client.CallOutcome;
import ai.neargo.svc.client.ServiceCallException;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Adapter assertions are separate from real HOST TCP tests and never claim native owner acceptance.
 */
class RemoteCheckoutPortTest {
    private static final CheckoutScope SCOPE = new CheckoutScope("MAIN", "E", "S", "actor");
    private static final OperationRef OP = new OperationRef("BEAUTY", "CO");
    private static final Instant PAST = Instant.parse("2020-01-01T00:00:00Z");
    private final InternalBindings bindings = new InternalBindings("secret");

    private static CheckoutPort.PrepareCheckoutReq prepare() {
        return new CheckoutPort.PrepareCheckoutReq(
                OP,
                "key",
                "cr1_private",
                1,
                "a".repeat(64),
                List.of(new CheckoutPort.CheckoutLineReq("L", "SKU", 1)),
                "ONLINE",
                null,
                Map.of(),
                null);
    }

    private static CheckoutPort.CheckoutQuote quote(String store, Instant expiresAt, int quantity) {
        return new CheckoutPort.CheckoutQuote(
                OP,
                "Q",
                1,
                "b".repeat(64),
                expiresAt,
                "BUYER",
                "E",
                store,
                "CNY",
                List.of(
                        new CheckoutPort.QuoteLine(
                                "L",
                                "SKU",
                                "G",
                                quantity,
                                "COUNT",
                                100,
                                100,
                                0,
                                100,
                                "c".repeat(64))),
                100,
                0,
                100,
                "ONLINE",
                "STORE_VERIFY",
                "SERVICE_EXECUTION",
                List.of(),
                List.of(),
                "CREATED",
                4);
    }

    private static CheckoutPort.CheckoutSourceFacts unknown() {
        return new CheckoutPort.CheckoutSourceFacts(
                OP,
                "ORDER",
                "SO",
                "BUYER",
                "E",
                "S",
                3,
                "d".repeat(64),
                null,
                "CNY",
                100,
                null,
                null,
                "UNKNOWN",
                List.of(),
                List.of(),
                "BLOCKED",
                PAST);
    }

    @Test
    void fourBoundCallsPreserveHistoricalAndNullableOwnerFacts() {
        var api =
                mock(
                        CheckoutInternalApi.class,
                        invocation -> {
                            InternalCall<?> call = invocation.getArgument(0);
                            Object result =
                                    switch (invocation.getMethod().getName()) {
                                        case "prepareBound", "readQuoteBound" ->
                                                quote("S", PAST, 1);
                                        case "findOperationBound" ->
                                                new CheckoutPort.CheckoutResult(
                                                        OP,
                                                        "REJECTED",
                                                        3,
                                                        null,
                                                        null,
                                                        null,
                                                        "CHECKOUT_QUOTE_STALE",
                                                        PAST);
                                        case "sourceBound" -> unknown();
                                        default ->
                                                throw new AssertionError(
                                                        "legacy or consumer route reached");
                                    };
                            return bindings.reply(call, result);
                        });
        var remote = new RemoteCheckoutPort(api, bindings);
        for (String kind : List.of("MCH", "USR")) {
            DelegatedIdentityContext.call(
                    new DelegatedIdentity("MAIN", "MERCHANT", kind, "actor"),
                    () -> {
                        assertThatCode(
                                        () -> {
                                            assertThat(remote.prepare(SCOPE, prepare()).expiresAt())
                                                    .isEqualTo(PAST);
                                            assertThat(remote.readQuote(SCOPE, OP).phase())
                                                    .isEqualTo("CREATED");
                                            assertThat(remote.findOperation(SCOPE, OP).phase())
                                                    .isEqualTo("REJECTED");
                                            var source =
                                                    remote.source(
                                                            SCOPE,
                                                            new CheckoutPort.SourceLookup(
                                                                    OP, "SO"));
                                            assertThat(source.receivedMinor()).isNull();
                                            assertThat(source.outstandingMinor()).isNull();
                                            assertThat(source.sourceRevision()).isEqualTo(3);
                                            assertThat(source.lines()).isEmpty();
                                        })
                                .doesNotThrowAnyException();
                        return null;
                    });
        }
    }

    @Test
    void consumerWritesUnsupportedPaymentAndCallerTransactionsMakeNoRpc() {
        var api = mock(CheckoutInternalApi.class);
        var remote = new RemoteCheckoutPort(api, bindings);
        assertError(() -> remote.confirm(null), ErrorCode.FORBIDDEN);
        assertError(() -> remote.place(null), ErrorCode.FORBIDDEN);
        assertError(() -> remote.initiatePayment(null), ErrorCode.FORBIDDEN);
        assertError(
                () -> remote.findPayment(SCOPE, null), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "CONSUMER", "USR", "actor"),
                () -> {
                    assertError(() -> remote.readQuote(SCOPE, OP), ErrorCode.FORBIDDEN);
                    return null;
                });
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                () -> {
                    assertError(
                            () ->
                                    remote.readQuote(
                                            new CheckoutScope("MAIN", "E", "S", "other"), OP),
                            ErrorCode.FORBIDDEN);
                    TransactionSynchronizationManager.setActualTransactionActive(true);
                    try {
                        assertThatThrownBy(() -> remote.readQuote(SCOPE, OP))
                                .isInstanceOf(IllegalStateException.class);
                    } finally {
                        TransactionSynchronizationManager.setActualTransactionActive(false);
                    }
                    return null;
                });
        verifyNoInteractions(api);
    }

    @Test
    void onlyBoundBusinessAbsencePropagatesAndInvalidRepliesRemainUnavailable() {
        var mode = new AtomicReference<>("bound");
        var api =
                mock(
                        CheckoutInternalApi.class,
                        invocation -> {
                            InternalCall<?> call = invocation.getArgument(0);
                            return switch (mode.get()) {
                                case "null" -> null;
                                case "digest" ->
                                        new InternalReply<>(
                                                call.binding(),
                                                "0".repeat(64),
                                                quote("S", PAST, 1));
                                case "store" -> bindings.reply(call, quote("other", PAST, 1));
                                case "expiry" -> bindings.reply(call, quote("S", null, 1));
                                case "quantity" -> bindings.reply(call, quote("S", PAST, 2));
                                default ->
                                        throw new ServiceCallException(
                                                CallOutcome.REMOTE_ERROR,
                                                "PLATFORM",
                                                mode.get().equals("status") ? 503 : 404,
                                                "safe",
                                                null,
                                                mode.get().equals("unbound") ? null : "NOT_FOUND",
                                                mode.get().equals("badErrorDigest")
                                                        ? "0".repeat(64)
                                                        : bindings.digest(call));
                            };
                        });
        var remote = new RemoteCheckoutPort(api, bindings);
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                () -> {
                    assertError(() -> remote.findOperation(SCOPE, OP), ErrorCode.NOT_FOUND);
                    for (String value :
                            List.of(
                                    "unbound",
                                    "badErrorDigest",
                                    "status",
                                    "null",
                                    "digest",
                                    "store",
                                    "expiry",
                                    "quantity")) {
                        mode.set(value);
                        assertError(
                                () -> remote.prepare(SCOPE, prepare()),
                                ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
                    }
                    return null;
                });
    }

    private static void assertError(Runnable operation, ErrorCode code) {
        assertThatThrownBy(operation::run)
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> assertThat(failure.errorCode()).isEqualTo(code));
    }
}
