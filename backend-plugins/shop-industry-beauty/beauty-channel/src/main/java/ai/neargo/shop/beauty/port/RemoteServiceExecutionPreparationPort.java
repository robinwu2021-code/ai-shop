package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetainExecutionStock;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionLookup;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.svc.ExecutionPreparationOriginalBindings;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.function.Function;

/**
 * Four real typed routes support standalone preparation without implementing aggregate owner
 * authority.
 */
public final class RemoteServiceExecutionPreparationPort
        implements ServiceExecutionPreparationPort {
    private final ServiceExecutionPreparationInternalApi api;
    private final InternalBindings human;
    private final ExecutionPreparationOriginalBindings originals;

    public RemoteServiceExecutionPreparationPort(
            ServiceExecutionPreparationInternalApi api, String token) {
        this.api = api;
        this.human = new InternalBindings(token);
        this.originals = new ExecutionPreparationOriginalBindings(token);
    }

    @Override
    public ExecutionReference referenceOriginal(ExecutionLookup request) {
        var b = request.binding();
        var result =
                human(
                        b.scope(),
                        b.executionNo(),
                        request,
                        "REFERENCE_ORIGINAL",
                        api::referenceOriginal);
        result.validate();
        if (!b.scope().equals(result.scope()) || !b.executionNo().equals(result.executionNo()))
            throw unavailable();
        return result;
    }

    @Override
    public ExecutionStockRetentionPreparationObservation retainStock(RetainExecutionStock request) {
        var ref = request.reference();
        var result =
                human(ref.scope(), ref.executionNo(), request, "RETAIN_STOCK", api::retainStock);
        requireStock(request.lookup(), result);
        return result;
    }

    @Override
    public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request) {
        DelegatedIdentityContext.outside();
        var ref = request.reference();
        var call =
                new EvidenceCall(
                        new EvidenceBinding(
                                1,
                                "QUERY_ORIGINAL_EVIDENCE",
                                ref.scope(),
                                ref.executionNo(),
                                ref.referenceHash()),
                        request);
        String digest = originals.digest(call);
        try {
            var reply =
                    ServiceCalls.call(ServiceName.PLATFORM, () -> api.queryOriginalEvidence(call));
            originals.require(call, reply);
            return reply.result();
        } catch (ServiceCallException failure) {
            throw ExecutionRemoteErrors.map(failure, digest);
        } catch (RuntimeException malformed) {
            throw unavailable();
        }
    }

    @Override
    public ExecutionStockRetentionPreparationObservation queryStockOriginal(
            RetentionLookup request) {
        DelegatedIdentityContext.outside();
        var ref = request.reference();
        var call =
                new StockCall(
                        new StockBinding(
                                1,
                                "QUERY_STOCK_ORIGINAL",
                                ref.scope(),
                                ref.executionNo(),
                                ref.referenceHash(),
                                request.retentionNo(),
                                request.requestHash()),
                        request);
        String digest = originals.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> api.queryStockOriginal(call));
            originals.require(call, reply);
            requireStock(request, reply.result());
            return reply.result();
        } catch (ServiceCallException failure) {
            throw ExecutionRemoteErrors.map(failure, digest);
        } catch (RuntimeException malformed) {
            throw unavailable();
        }
    }

    private <T, R> R human(
            ExecutionScope scope,
            String execution,
            T request,
            String action,
            Function<InternalCall<T>, InternalReply<R>> send) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ServiceExecutionPreparationInternalApi.ENDPOINT,
                                action,
                                identity,
                                scope.entityNo(),
                                scope.storeNo(),
                                execution),
                        request);
        String digest = human.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> send.apply(call));
            human.require(call, reply);
            if (!identity.equals(DelegatedIdentityContext.current())) throw unavailable();
            return reply.result();
        } catch (ServiceCallException failure) {
            throw ExecutionRemoteErrors.map(failure, digest);
        } catch (RuntimeException malformed) {
            throw unavailable();
        }
    }

    private static void requireStock(
            RetentionLookup lookup, ExecutionStockRetentionPreparationObservation result) {
        if (result == null) throw unavailable();
        result.validate(lookup);
    }

    private static BizException unavailable() {
        return BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }
}
