package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeInternalApi;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;
import ai.neargo.shop.spi.trade.ExecutionStartContract;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;
import ai.neargo.shop.svc.ExecutionRuntimeBindings;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.validation.Validator;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Actual Native/Core consumed endpoints; no machine method installs a delegated user. */
@RestController
@ConditionalOnProperty(prefix = "shop.beauty", name = "enabled", havingValue = "true")
public final class InternalExecutionRuntimeController {
    private final ObjectProvider<ResourceOccupationPort> resources;
    private final ObjectProvider<ResourceExecutionEvidencePort> resourceEvidence;
    private final ObjectProvider<ResourceOccupationLifecyclePort> lifecycle;
    private final ObjectProvider<ServiceExecutionStartPort> starts;
    private final ObjectProvider<ServiceExecutionCompletionPort> completion;
    private final ObjectProvider<ServiceExecutionEvidencePort> evidence;
    private final ObjectProvider<ResourceQualificationPort> qualification;
    private final ObjectProvider<ResourceBookingExecutionEvidencePort> bookingEvidence;
    private final String token;
    private final BoundDelegatedCalls human;
    private final InternalBindings bindings;
    private final ExecutionRuntimeBindings originals;
    private final Validator validator;

    public InternalExecutionRuntimeController(
            ObjectProvider<ResourceOccupationPort> resources,
            ObjectProvider<ResourceExecutionEvidencePort> resourceEvidence,
            ObjectProvider<ResourceOccupationLifecyclePort> lifecycle,
            ObjectProvider<ServiceExecutionStartPort> starts,
            ObjectProvider<ServiceExecutionCompletionPort> completion,
            ObjectProvider<ServiceExecutionEvidencePort> evidence,
            ObjectProvider<ResourceQualificationPort> qualification,
            Validator validator,
            ObjectProvider<ResourceBookingExecutionEvidencePort> bookingEvidence,
            @Value("${shop.services.internal-token:}") String token) {
        this.resources = resources;
        this.resourceEvidence = resourceEvidence;
        this.lifecycle = lifecycle;
        this.starts = starts;
        this.completion = completion;
        this.evidence = evidence;
        this.qualification = qualification;
        this.validator = validator;
        this.bookingEvidence = bookingEvidence;
        this.token = token;
        this.human = new BoundDelegatedCalls(token);
        this.bindings = new InternalBindings(token);
        this.originals = new ExecutionRuntimeBindings(token);
    }

    private <P> ResponseEntity<?> human(
            String given,
            InternalCall<P> call,
            String action,
            Supplier<ExecutionReference> reference,
            Supplier<?> body) {
        var gate = InternalServiceResponses.invoke(token, given, () -> Boolean.TRUE);
        if (!gate.getStatusCode().is2xxSuccessful()) return gate;
        var ref = reference.get();
        return human.invoke(
                given,
                call,
                ExecutionRuntimeInternalApi.ENDPOINT,
                action,
                BoundDelegatedCalls.Mode.MERCHANT,
                ref.executionNo(),
                () -> {
                    bindings.requireCoordinates(
                            call,
                            ref.scope().tenantNo(),
                            ref.scope().entityNo(),
                            ref.scope().storeNo(),
                            call.binding().identity().actorNo());
                    if (!validator.validate(call.request()).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    return body.get();
                });
    }

    private <P> ResponseEntity<?> machine(
            String given,
            MachineCall<P> call,
            Purpose purpose,
            Supplier<ExecutionReference> reference,
            Supplier<?> body) {
        return InternalServiceResponses.invokeBound(
                token,
                given,
                () -> originals.digest(call),
                () -> {
                    originals.requireRoute(call, purpose, reference.get());
                    if (!validator.validate(call.request()).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    return originals.reply(call, body.get());
                });
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/resource-start")
    public ResponseEntity<?> resourceStart(
            @RequestBody InternalCall<ResourceOccupationContract.StartRequest> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "RESOURCE_START",
                () -> call.request().reference(),
                () -> resources.getObject().start(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/lifecycle-execute")
    public ResponseEntity<?> lifecycleExecute(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "LIFECYCLE_EXECUTE",
                () -> call.request().startOriginal().reference(),
                () -> lifecycle.getObject().execute(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/core-authorize")
    public ResponseEntity<?> coreAuthorize(
            @RequestBody InternalCall<ExecutionEvidenceLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "CORE_AUTHORIZE",
                () -> call.request().reference(),
                () -> evidence.getObject().authorizeCurrent(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/resource-cancel")
    public ResponseEntity<?> resourceCancel(
            @RequestBody MachineCall<ResourceOccupationContract.CancelOriginal> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.RESOURCE_CANCEL,
                () -> call.request().original().reference(),
                () -> resources.getObject().cancelBeforeEffect(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/resource-original")
    public ResponseEntity<?> resourceOriginal(
            @RequestBody MachineCall<ResourceOccupationContract.OriginalLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.RESOURCE_ORIGINAL,
                () -> call.request().reference(),
                () -> resourceEvidence.getObject().queryOriginal(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/lifecycle-original")
    public ResponseEntity<?> lifecycleOriginal(
            @RequestBody MachineCall<ResourceOccupationLifecycleContract.Request> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.LIFECYCLE_ORIGINAL,
                () -> call.request().startOriginal().reference(),
                () ->
                        new ExecutionRuntimeInternalApi.LifecycleObservation(
                                lifecycle.getObject().queryOriginal(call.request()).orElse(null)));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/start-ack")
    public ResponseEntity<?> markStarted(
            @RequestBody MachineCall<ExecutionStartContract.AcknowledgeResourceStart> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.START_ACK,
                () -> call.request().reference(),
                () -> starts.getObject().markStarted(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/start-original")
    public ResponseEntity<?> startOriginal(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.START_ORIGINAL,
                () -> call.request().reference(),
                () -> starts.getObject().queryStartOriginal(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/complete")
    public ResponseEntity<?> complete(
            @RequestBody MachineCall<ExecutionCompletionContract.CompleteExecution> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.COMPLETE,
                () -> call.request().reference(),
                () -> completion.getObject().complete(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/completion-original")
    public ResponseEntity<?> completionOriginal(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.COMPLETION_ORIGINAL,
                () -> call.request().reference(),
                () -> completion.getObject().queryOriginal(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/core-evidence")
    public ResponseEntity<?> coreEvidence(
            @RequestBody MachineCall<ExecutionEvidenceLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.CORE_EVIDENCE,
                () -> call.request().reference(),
                () -> evidence.getObject().queryOriginalEvidence(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/qualification")
    public ResponseEntity<?> qualification(
            @RequestBody InternalCall<ResourceQualificationPort.Query> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        var gate = InternalServiceResponses.invoke(token, given, () -> Boolean.TRUE);
        if (!gate.getStatusCode().is2xxSuccessful()) return gate;
        var query = call.request();
        return human.invoke(
                given,
                call,
                ExecutionRuntimeInternalApi.ENDPOINT,
                "QUALIFICATION",
                BoundDelegatedCalls.Mode.MERCHANT,
                query.requestHash(),
                () -> {
                    bindings.requireCoordinates(
                            call,
                            query.tenantNo(),
                            query.entityNo(),
                            query.storeNo(),
                            call.binding().identity().actorNo());
                    if (!validator.validate(query).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    return qualification.getObject().observe(query);
                });
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/lifecycle-cancel")
    public ResponseEntity<?> lifecycleCancel(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "LIFECYCLE_CANCEL",
                () -> call.request().startOriginal().reference(),
                () -> lifecycle.getObject().cancelBeforeEffect(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/lifecycle-cancellation-original")
    public ResponseEntity<?> lifecycleCancellationOriginal(
            @RequestBody MachineCall<ResourceOccupationLifecycleContract.Request> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.LIFECYCLE_CANCELLATION_ORIGINAL,
                () -> call.request().startOriginal().reference(),
                () ->
                        new ExecutionRuntimeInternalApi.LifecycleCancellationObservation(
                                lifecycle
                                        .getObject()
                                        .queryCancellationOriginal(call.request())
                                        .orElse(null)));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/validate-current-start")
    public ResponseEntity<?> validateCurrentStart(
            @RequestBody InternalCall<ExecutionRuntimeInternalApi.StartCurrentRequest> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "VALIDATE_CURRENT_START",
                () -> call.request().original().reference(),
                () ->
                        lifecycle
                                .getObject()
                                .validateCurrentStart(
                                        call.request().authority(), call.request().original()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/validate-current-workflow")
    public ResponseEntity<?> validateCurrentWorkflow(
            @RequestBody InternalCall<ResourceOccupationLifecycleContract.Request> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "VALIDATE_CURRENT_WORKFLOW",
                () -> call.request().startOriginal().reference(),
                () -> lifecycle.getObject().validateCurrentWorkflow(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/resource-current")
    public ResponseEntity<?> currentOccupation(
            @RequestBody MachineCall<ResourceOccupationContract.OriginalLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.RESOURCE_CURRENT,
                () -> call.request().reference(),
                () -> lifecycle.getObject().queryCurrent(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/booking-execution-original")
    public ResponseEntity<?> bookingExecutionOriginal(
            @RequestBody MachineCall<ResourceBookingExecutionEvidencePort.Lookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return machine(
                given,
                call,
                Purpose.BOOKING_EXECUTION_ORIGINAL,
                () -> call.request().reference(),
                () -> bookingEvidence.getObject().queryOriginal(call.request()));
    }
}
