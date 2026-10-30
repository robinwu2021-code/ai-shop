package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/**
 * Bound original manual facts never fall back to financial success or empty permissive evidence.
 */
public final class RemoteManualServiceAdmissionPort implements ManualServiceAdmissionPort {
    private final ManualServiceAdmissionInternalApi api;
    private final InternalBindings bindings;

    public RemoteManualServiceAdmissionPort(
            ManualServiceAdmissionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }

    @Override
    public CounterCheckoutQuote prepareCounter(CheckoutScope scope, PrepareCounterReq request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "COUNTER_PREPARE",
                api::prepareCounter,
                result ->
                        counterQuote(scope, request.operation(), result)
                                && ("GUEST".equals(request.customer().kind())
                                        ? "GUEST".equals(result.party().kind())
                                                && request.customer()
                                                        .guestNo()
                                                        .equals(result.party().guestNo())
                                        : "USER".equals(result.party().kind())
                                                && result.party().identityRevision() == null)
                                && request.lines().size() == result.quote().lines().size()
                                && IntStream.range(0, request.lines().size())
                                        .allMatch(
                                                i -> {
                                                    var line = request.lines().get(i);
                                                    var actual = result.quote().lines().get(i);
                                                    return line.clientLineRef()
                                                                    .equals(actual.clientLineRef())
                                                            && line.skuNo().equals(actual.skuNo())
                                                            && line.quantity() == actual.quantity();
                                                }));
    }

    @Override
    public CounterCheckoutQuote readCounterQuote(CheckoutScope scope, OperationRef operation) {
        return call(
                scope,
                operation,
                operation.operationNo(),
                "COUNTER_READ",
                api::readCounterQuote,
                result -> counterQuote(scope, operation, result));
    }

    @Override
    public CounterConfirmation acceptCounter(CheckoutScope scope, AcceptCounterReq request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "COUNTER_ACCEPT",
                api::acceptCounter,
                result ->
                        request.operation().equals(result.operation())
                                && request.quoteNo().equals(result.quoteNo())
                                && request.quoteRevision() == result.quoteRevision()
                                && request.quoteHash().equals(result.quoteHash())
                                && Objects.equals(
                                        request.identityRevision(),
                                        result.party().identityRevision())
                                && request.acceptedAt().equals(result.acceptedAt())
                                && request.evidenceKind().equals(result.purchaseAuthority()));
    }

    @Override
    public CheckoutResult placeCounter(CheckoutScope scope, PlaceCounterReq request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "COUNTER_PLACE",
                api::placeCounter,
                result ->
                        request.operation().equals(result.operation())
                                && ("CREATED".equals(result.phase())
                                        ? result.orderNo() != null
                                                && !result.orderNo().isBlank()
                                                && result.subOrderNo() != null
                                                && !result.subOrderNo().isBlank()
                                        : result.orderNo() == null
                                                && result.subOrderNo() == null
                                                && List.of(
                                                                "PLACING",
                                                                "REQUOTE_REQUIRED",
                                                                "REJECTED")
                                                        .contains(result.phase())));
    }

    private static boolean counterQuote(
            CheckoutScope scope, OperationRef operation, CounterCheckoutQuote result) {
        return operation.equals(result.quote().operation())
                && scope.entityNo().equals(result.quote().entityNo())
                && scope.storeNo().equals(result.quote().storeNo());
    }

    @Override
    public CheckoutQuote prepare(CheckoutScope scope, PrepareCheckoutReq request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "PREPARE",
                api::prepare,
                result ->
                        request.operation().equals(result.operation())
                                && scope.entityNo().equals(result.entityNo())
                                && scope.storeNo().equals(result.storeNo()));
    }

    @Override
    public ManualAdmissionView confirm(CheckoutScope scope, ConfirmManualReceipt request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "CONFIRM",
                api::confirm,
                result ->
                        request.operation().equals(result.operation())
                                && !"AWAITING_RECEIPT".equals(result.admissionState())
                                && (result.method() == request.method()
                                        && Objects.equals(
                                                result.declaredMinor(), request.declaredMinor())
                                        && request.currency().equals(result.currency())
                                        && request.receivedAt().equals(result.receivedAt())));
    }

    @Override
    public ManualAdmissionView query(CheckoutScope scope, ManualLookup request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "QUERY",
                api::query,
                result ->
                        request.operation().equals(result.operation())
                                && (request.declarationNo() == null
                                        || request.declarationNo()
                                                .equals(result.manualDeclarationNo())));
    }

    @Override
    public ManualSourceFacts source(CheckoutScope scope, SourceLookup request) {
        return call(
                scope,
                request,
                request.operation().operationNo(),
                "SOURCE",
                api::source,
                result ->
                        request.operation().equals(result.operation())
                                && scope.entityNo().equals(result.entityNo())
                                && scope.storeNo().equals(result.storeNo())
                                && (request.subOrderNo() == null
                                        || request.subOrderNo().equals(result.subOrderNo()))
                                && sourceDisplay(result));
    }

    private static boolean sourceDisplay(ManualSourceFacts facts) {
        var receipt = facts.declaration();
        if (receipt == null) return true; // Old provider shape is explicitly incomplete downstream.
        if (!facts.operation().equals(receipt.operation())
                || !Objects.equals(facts.orderNo(), receipt.orderNo())
                || !Objects.equals(facts.subOrderNo(), receipt.subOrderNo())
                || facts.orderRevision() != receipt.orderRevision()
                || facts.admissionRevision() != receipt.admissionRevision()
                || !Objects.equals(facts.currency(), receipt.currency())) {
            return false;
        }
        if (facts.orderNo() == null) {
            // The owner has no payable amount before order creation, nor a receipt declaration.
            // Record constructors already require paired order refs and empty awaiting
            // fields/lines.
            return facts.dueMinor() == null
                    && facts.manualObservationRevision() == 0
                    && facts.manualObservationHash() == null
                    && facts.orderRevision() == 0
                    && facts.admissionRevision() == 0
                    && "AWAITING_RECEIPT".equals(facts.admissionState())
                    && "AWAITING_RECEIPT".equals(receipt.admissionState())
                    && receipt.reasonCode() == null;
        }
        return facts.dueMinor() != null
                && (receipt.declaredMinor() == null
                        || facts.dueMinor().equals(receipt.declaredMinor()));
    }

    private <T, R> R call(
            CheckoutScope scope,
            T payload,
            String operation,
            String action,
            Function<InternalCall<Scoped<T>>, InternalReply<R>> send,
            Predicate<R> expected) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        if (!identity.equals(
                new DelegatedIdentity(
                        scope.tenantNo(), "MERCHANT", identity.subjectKind(), scope.actorNo()))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var request =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ManualServiceAdmissionInternalApi.ENDPOINT,
                                action,
                                identity,
                                scope.entityNo(),
                                scope.storeNo(),
                                operation),
                        new Scoped<>(scope, payload));
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> send.apply(request));
            bindings.require(request, reply);
            if (!identity.equals(DelegatedIdentityContext.current())
                    || !expected.test(reply.result())) {
                throw new IllegalArgumentException("manual result differs from original request");
            }
            return reply.result();
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }
}
