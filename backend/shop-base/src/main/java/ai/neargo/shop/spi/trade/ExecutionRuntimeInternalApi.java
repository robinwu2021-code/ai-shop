package ai.neargo.shop.spi.trade;

import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineReply;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Finite consumed runtime routes; current HUMAN gates and MACHINE originals never share an
 * envelope.
 */
public interface ExecutionRuntimeInternalApi {
    String ENDPOINT = "EXECUTION_RUNTIME";
    String PATH = "/internal/service-execution/runtime";

    @PostExchange(PATH + "/qualification")
    InternalReply<ResourceQualificationPort.Observation> qualification(
            @RequestBody InternalCall<ResourceQualificationPort.Query> call);

    record LifecycleObservation(ResourceOccupationLifecycleContract.Result result) {}

    @PostExchange(PATH + "/resource-start")
    InternalReply<ResourceOccupationContract.StartResult> resourceStart(
            @RequestBody InternalCall<ResourceOccupationContract.StartRequest> call);

    @PostExchange(PATH + "/lifecycle-execute")
    InternalReply<ResourceOccupationLifecycleContract.Result> lifecycleExecute(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call);

    @PostExchange(PATH + "/start-authority")
    InternalReply<ServiceTaskStartAuthorityPort.StartAuthority> startAuthority(
            @RequestBody InternalCall<ServiceTaskStartAuthorityPort.StartAuthorityLookup> call);

    @PostExchange(PATH + "/workflow-authority")
    InternalReply<ServiceTaskWorkflowAuthorityPort.WorkflowAuthority> workflowAuthority(
            @RequestBody
                    InternalCall<ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup> call);

    @PostExchange(PATH + "/core-authorize")
    InternalReply<ServiceExecutionEvidencePort.CurrentActor> coreAuthorize(
            @RequestBody InternalCall<ExecutionEvidenceLookup> call);

    @PostExchange(PATH + "/resource-cancel")
    MachineReply<ResourceOccupationContract.CancellationResult> resourceCancel(
            @RequestBody MachineCall<ResourceOccupationContract.CancelOriginal> call);

    @PostExchange(PATH + "/resource-original")
    MachineReply<ResourceOccupationContract.OriginalResult> resourceOriginal(
            @RequestBody MachineCall<ResourceOccupationContract.OriginalLookup> call);

    @PostExchange(PATH + "/lifecycle-original")
    MachineReply<LifecycleObservation> lifecycleOriginal(
            @RequestBody MachineCall<ResourceOccupationLifecycleContract.Request> call);

    @PostExchange(PATH + "/start-ack")
    MachineReply<ExecutionStartContract.StartAcknowledgement> markStarted(
            @RequestBody MachineCall<ExecutionStartContract.AcknowledgeResourceStart> call);

    @PostExchange(PATH + "/start-original")
    MachineReply<ExecutionStartContract.StartAcknowledgement> startOriginal(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call);

    @PostExchange(PATH + "/complete")
    MachineReply<ExecutionCompletionContract.CompletionReceipt> complete(
            @RequestBody MachineCall<ExecutionCompletionContract.CompleteExecution> call);

    @PostExchange(PATH + "/completion-original")
    MachineReply<ExecutionCompletionContract.CompletionReceipt> completionOriginal(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call);

    @PostExchange(PATH + "/start-authority-original")
    MachineReply<ServiceTaskStartAuthorityPort.StartAuthorityOriginal> startAuthorityOriginal(
            @RequestBody MachineCall<ServiceTaskStartAuthorityPort.StartAuthorityLookup> call);

    @PostExchange(PATH + "/core-evidence")
    MachineReply<ExecutionOriginalEvidence> coreEvidence(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call);

    record LifecycleCancellationObservation(
            ResourceOccupationLifecycleContract.Cancellation result) {}

    record StartCurrentRequest(
            @NotNull @Valid ServiceTaskStartAuthorityPort.StartAuthorityLookup authority,
            @NotNull @Valid ResourceOccupationContract.OriginalLookup original) {}

    @PostExchange(PATH + "/lifecycle-cancel")
    InternalReply<ResourceOccupationLifecycleContract.Cancellation> lifecycleCancel(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call);

    @PostExchange(PATH + "/lifecycle-cancellation-original")
    MachineReply<LifecycleCancellationObservation> lifecycleCancellationOriginal(
            @RequestBody MachineCall<ResourceOccupationLifecycleContract.Request> call);

    @PostExchange(PATH + "/validate-current-start")
    InternalReply<ResourceOccupationLifecycleContract.CurrentWorkflowProof> validateCurrentStart(
            @RequestBody InternalCall<StartCurrentRequest> call);

    @PostExchange(PATH + "/validate-current-workflow")
    InternalReply<ResourceOccupationLifecycleContract.CurrentWorkflowProof> validateCurrentWorkflow(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call);

    @PostExchange(PATH + "/resource-current")
    MachineReply<ResourceOccupationLifecycleContract.CurrentOccupation> currentOccupation(
            @RequestBody MachineCall<ResourceOccupationContract.OriginalLookup> call);

    @PostExchange(PATH + "/booking-execution-original")
    MachineReply<ResourceBookingExecutionEvidencePort.Evidence> bookingExecutionOriginal(
            @RequestBody MachineCall<ResourceBookingExecutionEvidencePort.Lookup> call);
}
