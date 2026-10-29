package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import java.util.function.Function;

/** Typed fixed-purpose transport preserves the original without replaying an employee during recovery. */
public final class RemoteServiceExecutionPort implements ServiceExecutionPort {
    private final ServiceExecutionInternalApi api;
    private final InternalBindings bindings;
    public RemoteServiceExecutionPort(ServiceExecutionInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }
    @Override
    public Grant prepare(PrepareExecution request) {
        return human(request.binding(), request, "PREPARE", api::prepare);
    }
    @Override
    public Grant query(ExecutionLookup request) {
        return human(request.binding(), request, "QUERY", api::query);
    }

    @Override
    public Grant queryOriginal(ExecutionLookup request) {
        DelegatedIdentityContext.outside();
        var b = request.binding();
        var call = new ExecutionOriginalProtocol.Call<>(new ExecutionOriginalProtocol.Binding(1,
                ServiceExecutionInternalApi.ENDPOINT, "QUERY_ORIGINAL", b.scope(), b.executionNo(),
                ServiceExecutionContract.bindingHash(b)), request);
        String digest;
        try { digest = bindings.digest(call); }
        catch (RuntimeException malformed) { throw unavailable(); }
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> api.queryOriginal(call));
            bindings.require(call, reply);
            if (!b.equals(reply.result().binding())) throw unavailable();
            return reply.result();
        } catch (ServiceCallException failure) { throw ExecutionRemoteErrors.map(failure, digest); }
        catch (RuntimeException malformed) { throw unavailable(); }
    }

    private <T> Grant human(ExecutionBinding b, T request, String action,
            Function<InternalCall<T>, InternalReply<Grant>> send) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        var call = new InternalCall<>(new InternalRequestBinding(1, ServiceExecutionInternalApi.ENDPOINT, action,
                identity, b.scope().entityNo(), b.scope().storeNo(), b.executionNo()), request);
        String digest;
        try { digest = bindings.digest(call); }
        catch (RuntimeException malformed) { throw unavailable(); }
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> send.apply(call));
            bindings.require(call, reply);
            if (!identity.equals(DelegatedIdentityContext.current()) || !b.equals(reply.result().binding())) {
                throw unavailable();
            }
            return reply.result();
        } catch (ServiceCallException failure) { throw ExecutionRemoteErrors.map(failure, digest); }
        catch (RuntimeException malformed) { throw unavailable(); }
    }

    private static BizException unavailable() { return BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE); }
}
