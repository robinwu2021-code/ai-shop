package ai.neargo.shop.beauty.port;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeInternalApi;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineReply;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;
import ai.neargo.shop.spi.trade.ExecutionStartContract;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;
import ai.neargo.shop.svc.ExecutionRemoteErrors;
import ai.neargo.shop.svc.ExecutionRuntimeBindings;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;

import java.util.Optional;
import java.util.function.Function;

/**
 * Standalone uses actual Native/Core owners over finite routes, never a local status substitute.
 */
public final class RemoteExecutionRuntimePort
        implements ResourceOccupationPort,
                ResourceExecutionEvidencePort,
                ResourceOccupationLifecyclePort,
                ServiceExecutionStartPort,
                ServiceExecutionCompletionPort,
                ServiceExecutionEvidencePort,
                ResourceQualificationPort,
                ResourceBookingExecutionEvidencePort {
    private final ExecutionRuntimeInternalApi api;
    private final InternalBindings human;
    private final ExecutionRuntimeBindings machine;

    public RemoteExecutionRuntimePort(ExecutionRuntimeInternalApi api, String token) {
        this.api = api;
        this.human = new InternalBindings(token);
        this.machine = new ExecutionRuntimeBindings(token);
    }

    private <P, R> R human(
            ExecutionReference ref,
            P request,
            String action,
            Function<InternalCall<P>, InternalReply<R>> send) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        if (!"MERCHANT".equals(identity.realm())) throw BizException.of(ErrorCode.FORBIDDEN);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ExecutionRuntimeInternalApi.ENDPOINT,
                                action,
                                identity,
                                ref.scope().entityNo(),
                                ref.scope().storeNo(),
                                ref.executionNo()),
                        request);
        String digest = human.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> send.apply(call));
            human.require(call, reply);
            return reply.result();
        } catch (ServiceCallException failed) {
            throw ExecutionRemoteErrors.map(failed, digest);
        }
    }

    private <P, R> R machine(
            ExecutionReference ref,
            P request,
            Purpose purpose,
            Function<MachineCall<P>, MachineReply<R>> send) {
        DelegatedIdentityContext.outside();
        var call = new MachineCall<>(purpose, ref, request);
        String digest = machine.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> send.apply(call));
            machine.require(call, reply);
            return reply.result();
        } catch (ServiceCallException failed) {
            throw ExecutionRemoteErrors.map(failed, digest);
        }
    }

    @Override
    public ResourceOccupationContract.StartResult start(
            ResourceOccupationContract.StartRequest request) {
        var result = human(request.reference(), request, "RESOURCE_START", api::resourceStart);
        result.original().validate();
        require(request.lookup().equals(result.original().original()));
        return result;
    }

    @Override
    public ResourceOccupationContract.CancellationResult cancelBeforeEffect(
            ResourceOccupationContract.CancelOriginal request) {
        var result =
                machine(
                        request.original().reference(),
                        request,
                        Purpose.RESOURCE_CANCEL,
                        api::resourceCancel);
        result.original().validate();
        require(request.original().equals(result.original().original()));
        return result;
    }

    @Override
    public ResourceOccupationContract.OriginalResult queryOriginal(
            ResourceOccupationContract.OriginalLookup request) {
        var result =
                machine(
                        request.reference(),
                        request,
                        Purpose.RESOURCE_ORIGINAL,
                        api::resourceOriginal);
        result.validate();
        require(request.equals(result.original()));
        return result;
    }

    @Override
    public ResourceOccupationLifecycleContract.Result execute(
            ResourceOccupationLifecycleContract.Request request) {
        var result =
                human(
                        request.startOriginal().reference(),
                        request,
                        "LIFECYCLE_EXECUTE",
                        api::lifecycleExecute);
        result.validate();
        require(request.equals(result.request()));
        return result;
    }

    @Override
    public Optional<ResourceOccupationLifecycleContract.Result> queryOriginal(
            ResourceOccupationLifecycleContract.Request request) {
        var result =
                machine(
                                request.startOriginal().reference(),
                                request,
                                Purpose.LIFECYCLE_ORIGINAL,
                                api::lifecycleOriginal)
                        .result();
        if (result != null) {
            result.validate();
            require(request.equals(result.request()));
        }
        return Optional.ofNullable(result);
    }

    @Override
    public ExecutionStartContract.StartAcknowledgement markStarted(
            ExecutionStartContract.AcknowledgeResourceStart request) {
        var result = machine(request.reference(), request, Purpose.START_ACK, api::markStarted);
        result.validate();
        require(request.reference().equals(result.reference()));
        return result;
    }

    @Override
    public ExecutionStartContract.StartAcknowledgement queryStartOriginal(
            ExecutionEvidenceLookup request) {
        var result =
                machine(request.reference(), request, Purpose.START_ORIGINAL, api::startOriginal);
        result.validate();
        require(request.reference().equals(result.reference()));
        return result;
    }

    @Override
    public ExecutionCompletionContract.CompletionReceipt complete(
            ExecutionCompletionContract.CompleteExecution request) {
        var result = machine(request.reference(), request, Purpose.COMPLETE, api::complete);
        result.validate();
        require(request.reference().equals(result.reference()));
        return result;
    }

    @Override
    public ExecutionCompletionContract.CompletionReceipt queryOriginal(
            ExecutionEvidenceLookup request) {
        var result =
                machine(
                        request.reference(),
                        request,
                        Purpose.COMPLETION_ORIGINAL,
                        api::completionOriginal);
        result.validate();
        require(request.reference().equals(result.reference()));
        return result;
    }

    @Override
    public ExecutionOriginalEvidence queryOriginalEvidence(ExecutionEvidenceLookup request) {
        var result =
                machine(request.reference(), request, Purpose.CORE_EVIDENCE, api::coreEvidence);
        result.validate();
        require(request.reference().equals(result.reference()));
        return result;
    }

    @Override
    public CurrentActor authorizeCurrent(ExecutionEvidenceLookup request) {
        var result = human(request.reference(), request, "CORE_AUTHORIZE", api::coreAuthorize);
        result.validate(request.reference());
        return result;
    }

    @Override
    public Observation observe(Query request) {
        DelegatedIdentityContext.outside();
        var identity = DelegatedIdentityContext.current();
        if (!"MERCHANT".equals(identity.realm())) throw BizException.of(ErrorCode.FORBIDDEN);
        var call =
                new InternalCall<>(
                        new InternalRequestBinding(
                                1,
                                ExecutionRuntimeInternalApi.ENDPOINT,
                                "QUALIFICATION",
                                identity,
                                request.entityNo(),
                                request.storeNo(),
                                request.requestHash()),
                        request);
        String digest = human.digest(call);
        try {
            var reply = ServiceCalls.call(ServiceName.PLATFORM, () -> api.qualification(call));
            human.require(call, reply);
            var result = reply.result();
            require(ResourceQualificationPort.intact(result) && request.equals(result.query()));
            return result;
        } catch (ServiceCallException failed) {
            throw ExecutionRemoteErrors.map(failed, digest);
        }
    }

    private static void require(boolean valid) {
        if (!valid) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
    }

    @Override
    public ResourceOccupationLifecycleContract.Cancellation cancelBeforeEffect(
            ResourceOccupationLifecycleContract.Request request) {
        var result =
                human(
                        request.startOriginal().reference(),
                        request,
                        "LIFECYCLE_CANCEL",
                        api::lifecycleCancel);
        result.validate();
        require(request.equals(result.request()));
        return result;
    }

    @Override
    public Optional<ResourceOccupationLifecycleContract.Cancellation> queryCancellationOriginal(
            ResourceOccupationLifecycleContract.Request request) {
        var result =
                machine(
                                request.startOriginal().reference(),
                                request,
                                Purpose.LIFECYCLE_CANCELLATION_ORIGINAL,
                                api::lifecycleCancellationOriginal)
                        .result();
        if (result != null) {
            result.validate();
            require(request.equals(result.request()));
        }
        return Optional.ofNullable(result);
    }

    @Override
    public ResourceOccupationLifecycleContract.CurrentWorkflowProof validateCurrentStart(
            ServiceTaskStartAuthorityPort.StartAuthorityLookup authority,
            ResourceOccupationContract.OriginalLookup original) {
        var result =
                human(
                        original.reference(),
                        new ExecutionRuntimeInternalApi.StartCurrentRequest(authority, original),
                        "VALIDATE_CURRENT_START",
                        api::validateCurrentStart);
        result.validate();
        require(
                authority.equals(result.authority())
                        && original.equals(result.original())
                        && result.workflow() == null);
        return result;
    }

    @Override
    public ResourceOccupationLifecycleContract.CurrentWorkflowProof validateCurrentWorkflow(
            ResourceOccupationLifecycleContract.Request request) {
        var result =
                human(
                        request.startOriginal().reference(),
                        request,
                        "VALIDATE_CURRENT_WORKFLOW",
                        api::validateCurrentWorkflow);
        result.validate();
        require(
                request.authority().equals(result.workflow())
                        && request.startOriginal().equals(result.original())
                        && request.occupationNo().equals(result.occupationNo()));
        return result;
    }

    @Override
    public ResourceOccupationLifecycleContract.CurrentOccupation queryCurrent(
            ResourceOccupationContract.OriginalLookup original) {
        var result =
                machine(
                        original.reference(),
                        original,
                        Purpose.RESOURCE_CURRENT,
                        api::currentOccupation);
        result.validate();
        require(
                original.equals(result.original())
                        && original.reference()
                                .scope()
                                .equals(result.original().reference().scope())
                        && (original.actual() == null
                                || original.actual().equals(result.actual())));
        return result;
    }

    @Override
    public ResourceBookingExecutionEvidencePort.Evidence queryOriginal(
            ResourceBookingExecutionEvidencePort.Lookup lookup) {
        var result =
                machine(
                        lookup.reference(),
                        lookup,
                        Purpose.BOOKING_EXECUTION_ORIGINAL,
                        api::bookingExecutionOriginal);
        result.validate();
        require(lookup.equals(result.lookup()));
        return result;
    }
}
