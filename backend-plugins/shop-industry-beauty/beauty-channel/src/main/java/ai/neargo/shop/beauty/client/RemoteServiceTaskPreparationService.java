package ai.neargo.shop.beauty.client;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.service.ServiceTaskPreparationService;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi;
import ai.neargo.shop.beauty.spi.BeautyTaskPreparationInternalApi.PrepareTaskCall;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

/**
 * Finite standalone consumer checks the live actor again before accepting safe preparation
 * progress.
 */
public final class RemoteServiceTaskPreparationService implements ServiceTaskPreparationService {
    private final BeautyTaskPreparationInternalApi api;
    private final InternalBindings bindings;

    public RemoteServiceTaskPreparationService(
            BeautyTaskPreparationInternalApi api, InternalBindings bindings) {
        this.api = api;
        this.bindings = bindings;
    }

    @Override
    public ServiceTaskPreparationVO prepareTask(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            PrepareServiceTaskReq request,
            String key) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current(actor);
        if (!"MERCHANT".equals(identity.realm())) throw BizException.of(ErrorCode.FORBIDDEN);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                BeautyTaskPreparationInternalApi.ENDPOINT,
                                "PREPARE_TASK",
                                identity,
                                entity,
                                store,
                                task),
                        new PrepareTaskCall(entity, store, actor, workOrder, task, request, key));
        String digest = bindings.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.BEAUTY, () -> api.prepareTask(call));
            bindings.require(call, reply);
            var result = reply.result();
            if (!identity.equals(DelegatedIdentityContext.current(actor))
                    || !workOrder.equals(result.workOrderNo())
                    || !task.equals(result.taskNo())
                    || result.canStart()) {
                throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
            }
            return result;
        } catch (ServiceCallException failure) {
            throw ExecutionRemoteErrors.map(failure, digest);
        } catch (RuntimeException malformed) {
            throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
        }
    }
}
