package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutQuote;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionInternalApi.Scoped;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

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
                                        || request.subOrderNo().equals(result.subOrderNo())));
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
            if (!expected.test(reply.result())) {
                throw new IllegalArgumentException("manual result differs from original request");
            }
            return reply.result();
        } catch (RuntimeException failure) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }
}
