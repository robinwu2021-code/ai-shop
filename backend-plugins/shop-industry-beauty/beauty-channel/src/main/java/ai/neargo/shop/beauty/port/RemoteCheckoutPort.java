package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutInternalApi;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** The private server can prepare/read merchant facts but cannot reconstruct consumer authority. */
public final class RemoteCheckoutPort implements CheckoutPort {
    private final CheckoutInternalApi api;
    private final InternalBindings bindings;

    /** Real configured transport and the same internal binding key are mandatory. */
    public RemoteCheckoutPort(CheckoutInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        return invoke(
                scope,
                request.operation(),
                "CHECKOUT_PREPARE",
                "PREPARE",
                new CheckoutInternalApi.BoundPrepare(scope, request),
                api::prepareBound,
                result -> {
                    requireQuote(scope, request.operation(), result);
                    require(result.lines().size() == request.lines().size());
                    for (var selected : request.lines()) {
                        require(
                                result.lines().stream()
                                                .filter(
                                                        line ->
                                                                line.clientLineRef()
                                                                                .equals(
                                                                                        selected
                                                                                                .clientLineRef())
                                                                        && line.skuNo()
                                                                                .equals(
                                                                                        selected
                                                                                                .skuNo())
                                                                        && line.quantity()
                                                                                == selected
                                                                                        .quantity())
                                                .count()
                                        == 1);
                    }
                });
    }

    @Override
    public CheckoutQuote readQuote(CheckoutScope scope, OperationRef operation) {
        return invoke(
                scope,
                operation,
                "CHECKOUT_READ_QUOTE",
                "READ_QUOTE",
                new CheckoutInternalApi.BoundOperation(scope, operation),
                api::readQuoteBound,
                result -> requireQuote(scope, operation, result));
    }

    @Override
    public CheckoutResult findOperation(CheckoutScope scope, OperationRef operation) {
        return invoke(
                scope,
                operation,
                "CHECKOUT_FIND_OPERATION",
                "FIND_OPERATION",
                new CheckoutInternalApi.BoundOperation(scope, operation),
                api::findOperationBound,
                result -> {
                    require(
                            operation.equals(result.operation())
                                    && present(result.phase())
                                    && result.revision() > 0
                                    && result.observedAt() != null);
                    if ("CREATED".equals(result.phase())) {
                        require(
                                present(result.orderNo())
                                        && present(result.subOrderNo())
                                        && hash(result.sourceHash()));
                    }
                    if ("REJECTED".equals(result.phase())) {
                        require(present(result.reasonCode()));
                    }
                });
    }

    @Override
    public CheckoutSourceFacts source(CheckoutScope scope, SourceLookup request) {
        return invoke(
                scope,
                request.operation(),
                "CHECKOUT_SOURCE",
                "SOURCE",
                new CheckoutInternalApi.BoundSource(scope, request),
                api::sourceBound,
                result -> requireSource(scope, request, result));
    }

    @Override
    public BuyerConfirmation confirm(ConfirmCheckoutReq request) {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    @Override
    public CheckoutResult place(PlaceCheckoutReq request) {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    @Override
    public PaymentAttemptView initiatePayment(InitiatePaymentReq request) {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }

    @Override
    public PaymentAttemptView findPayment(CheckoutScope scope, PaymentLookup request) {
        throw BizException.of(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
    }

    private <P, R> R invoke(
            CheckoutScope scope,
            OperationRef operation,
            String endpoint,
            String action,
            P payload,
            Function<InternalCall<P>, InternalReply<R>> invoke,
            Consumer<R> validate) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current(scope.actorNo());
        if (!"MERCHANT".equals(identity.realm()) || !scope.tenantNo().equals(identity.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                endpoint,
                                action,
                                identity,
                                scope.entityNo(),
                                scope.storeNo(),
                                operation.operationNo()),
                        payload);
        final String digest;
        try {
            digest = bindings.digest(call);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> invoke.apply(call));
            bindings.require(call, reply);
            validate.accept(reply.result());
            return reply.result();
        } catch (ServiceCallException failure) {
            ErrorCode code = business(failure, digest);
            throw BizException.of(code == null ? ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE : code);
        } catch (RuntimeException failure) {
            throw unavailable();
        }
    }

    private static void requireQuote(
            CheckoutScope scope, OperationRef operation, CheckoutQuote quote) {
        require(
                operation.equals(quote.operation())
                        && scope.entityNo().equals(quote.entityNo())
                        && scope.storeNo().equals(quote.storeNo())
                        && present(quote.buyerNo())
                        && present(quote.quoteNo())
                        && quote.quoteRevision() > 0
                        && quote.operationRevision() > 0
                        && hash(quote.quoteHash())
                        && quote.expiresAt() != null
                        && present(quote.currency())
                        && present(quote.phase())
                        && present(quote.payMode())
                        && present(quote.fulfillment())
                        && present(quote.fulfillmentAuthority())
                        && !quote.lines().isEmpty()
                        && quote.goodsMinor() >= 0
                        && quote.discountMinor() >= 0
                        && quote.dueMinor() >= 0);
        var lines = new HashSet<String>();
        for (var line : quote.lines()) {
            require(
                    present(line.clientLineRef())
                            && lines.add(line.clientLineRef())
                            && present(line.skuNo())
                            && present(line.goodsNo())
                            && line.quantity() > 0
                            && "COUNT".equals(line.uom())
                            && hash(line.productVersionHash())
                            && line.unitPriceMinor() >= 0
                            && line.grossMinor() >= 0
                            && line.discountMinor() >= 0
                            && line.dueMinor() >= 0);
        }
    }

    private static void requireSource(
            CheckoutScope scope, SourceLookup request, CheckoutSourceFacts source) {
        require(
                request.operation().equals(source.operation())
                        && scope.entityNo().equals(source.entityNo())
                        && scope.storeNo().equals(source.storeNo())
                        && present(source.buyerNo())
                        && present(source.orderNo())
                        && present(source.subOrderNo())
                        && (request.subOrderNo() == null
                                || request.subOrderNo().equals(source.subOrderNo()))
                        && source.observedAt() != null
                        && present(source.currency())
                        && present(source.financialState())
                        && present(source.executionState())
                        && source.sourceRevision() >= 0
                        && source.dueMinor() >= 0);
        require(
                source.sourceRevision() == 0
                        ? source.sourceHash() == null
                        : hash(source.sourceHash()));
        if ("UNKNOWN".equals(source.financialState())) {
            require(
                    source.lines().isEmpty()
                            && source.payments().isEmpty()
                            && source.receivedMinor() == null
                            && source.outstandingMinor() == null
                            && "BLOCKED".equals(source.executionState()));
            return;
        }
        require(
                source.sourceRevision() > 0
                        && source.receivedMinor() != null
                        && source.outstandingMinor() != null
                        && source.receivedMinor() >= 0
                        && source.outstandingMinor() >= 0
                        && !source.lines().isEmpty());
        var lines = new HashSet<String>();
        for (var line : source.lines()) {
            require(
                    present(line.sourceLineNo())
                            && lines.add(line.sourceLineNo())
                            && present(line.clientLineRef())
                            && present(line.goodsNo())
                            && present(line.skuNo())
                            && "COUNT".equals(line.uom())
                            && line.orderedQuantity() > 0
                            && line.refundableQuantity() >= 0
                            && line.refundableQuantity() <= line.orderedQuantity()
                            && line.dueMinor() >= 0);
        }
    }

    private static ErrorCode business(ServiceCallException failure, String digest) {
        if (failure.remoteErrorCode() == null
                || failure.requestDigest() == null
                || !MessageDigest.isEqual(
                        digest.getBytes(StandardCharsets.UTF_8),
                        failure.requestDigest().getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        ErrorCode code =
                switch (failure.remoteErrorCode()) {
                    case "UNAUTHORIZED" -> ErrorCode.UNAUTHORIZED;
                    case "TOKEN_EXPIRED" -> ErrorCode.TOKEN_EXPIRED;
                    case "BAD_REQUEST" -> ErrorCode.BAD_REQUEST;
                    case "MEMBER_CODE_INVALID" -> ErrorCode.MEMBER_CODE_INVALID;
                    case "FORBIDDEN" -> ErrorCode.FORBIDDEN;
                    case "NOT_FOUND" -> ErrorCode.NOT_FOUND;
                    case "CONFLICT" -> ErrorCode.CONFLICT;
                    case "CHECKOUT_QUOTE_STALE" -> ErrorCode.CHECKOUT_QUOTE_STALE;
                    case "CHECKOUT_CONFIRMATION_REQUIRED" ->
                            ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED;
                    case "CHECKOUT_OPERATION_REJECTED" -> ErrorCode.CHECKOUT_OPERATION_REJECTED;
                    case "MANAGED_SERVICE_COMPLETION_BLOCKED" ->
                            ErrorCode.MANAGED_SERVICE_COMPLETION_BLOCKED;
                    case "CHECKOUT_UNSUPPORTED_SELECTION" ->
                            ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION;
                    case "CHECKOUT_DEPENDENCY_UNAVAILABLE" ->
                            ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE;
                    case "MEMBER_GUEST_UNAVAILABLE" -> ErrorCode.MEMBER_GUEST_UNAVAILABLE;
                    default -> null;
                };
        if (code == null) {
            return null;
        }
        int status =
                switch (code) {
                    case UNAUTHORIZED, TOKEN_EXPIRED -> 401;
                    case BAD_REQUEST, MEMBER_CODE_INVALID -> 400;
                    case FORBIDDEN -> 403;
                    case NOT_FOUND -> 404;
                    case CONFLICT,
                            CHECKOUT_QUOTE_STALE,
                            CHECKOUT_CONFIRMATION_REQUIRED,
                            CHECKOUT_OPERATION_REJECTED,
                            MANAGED_SERVICE_COMPLETION_BLOCKED ->
                            409;
                    case CHECKOUT_UNSUPPORTED_SELECTION -> 422;
                    default -> 503;
                };
        return failure.statusCode() == status ? code : null;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean hash(String value) {
        return value != null && value.matches("[a-f0-9]{64}");
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new IllegalArgumentException("invalid checkout reply");
        }
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
    }
}
