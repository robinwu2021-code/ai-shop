package ai.neargo.shop.portal.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol;
import ai.neargo.shop.spi.trade.ServiceExecutionContract;
import ai.neargo.shop.spi.trade.ServiceExecutionSourceInternalApi;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.Objects;
import java.util.function.Function;

/** Typed fixed-purpose transport preserves the original without replaying an employee during recovery. */
public final class RemoteServiceExecutionSourcePort implements ServiceExecutionSourcePort {
    private final ServiceExecutionSourceInternalApi api;
    private final InternalBindings bindings;
    public RemoteServiceExecutionSourcePort(ServiceExecutionSourceInternalApi api, InternalBindings bindings) {
        this.api = Objects.requireNonNull(api);
        this.bindings = Objects.requireNonNull(bindings);
    }
    @Override
    public StartIntent freeze(FreezeStart request) {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }
    @Override
    public StartIntent inspect(StartIntentLookup request) {
        return human(request.binding(), request, "INSPECT", api::inspect);
    }

    @Override
    public StartIntent inspectOriginal(StartIntentLookup request) {
        DelegatedIdentityContext.outside();
        var b = request.binding();
        var call = new ExecutionOriginalProtocol.Call<>(new ExecutionOriginalProtocol.Binding(1,
                ServiceExecutionSourceInternalApi.ENDPOINT, "INSPECT_ORIGINAL", b.scope(), b.executionNo(),
                ServiceExecutionContract.bindingHash(b)), request);
        String digest;
        try { digest = bindings.digest(call); }
        catch (RuntimeException malformed) { throw unavailable(); }
        try {
            var reply = ServiceCalls.call(ServiceName.BEAUTY, () -> api.inspectOriginal(call));
            bindings.require(call, reply);
            if (!b.equals(reply.result().binding())) throw unavailable();
            return reply.result();
        } catch (ServiceCallException failure) { throw ExecutionRemoteErrors.map(failure, digest); }
        catch (RuntimeException malformed) { throw unavailable(); }
    }

    private <T> StartIntent human(ExecutionBinding b, T request, String action,
            Function<InternalCall<T>, InternalReply<StartIntent>> send) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        var call = new InternalCall<>(new InternalRequestBinding(1, ServiceExecutionSourceInternalApi.ENDPOINT, action,
                identity, b.scope().entityNo(), b.scope().storeNo(), b.executionNo()), request);
        String digest;
        try { digest = bindings.digest(call); }
        catch (RuntimeException malformed) { throw unavailable(); }
        try {
            var reply = ServiceCalls.call(ServiceName.BEAUTY, () -> send.apply(call));
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
