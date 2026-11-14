package ai.neargo.shop.beauty.server.api;

import ai.neargo.shop.auth.BoundDelegatedCalls;
import ai.neargo.shop.beauty.config.ConditionalOnBeautyEnabled;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ExecutionRuntimeInternalApi;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.MachineCall;
import ai.neargo.shop.spi.trade.ExecutionRuntimeProtocol.Purpose;
import ai.neargo.shop.svc.ExecutionRuntimeBindings;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.InternalServiceResponses;

import jakarta.validation.Validator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Supplier;

/** Reverse current source authority is distinct from immutable machine source-original reads. */
@RestController
@ConditionalOnBeautyEnabled
@Profile("api")
public class InternalTaskRuntimeAuthorityController {
    private final ServiceTaskStartAuthorityPort starts;
    private final ServiceTaskWorkflowAuthorityPort workflow;
    private final BoundDelegatedCalls human;
    private final InternalBindings bindings;
    private final ExecutionRuntimeBindings originals;
    private final Validator validator;
    private final String token;

    public InternalTaskRuntimeAuthorityController(
            ServiceTaskStartAuthorityPort starts,
            ServiceTaskWorkflowAuthorityPort workflow,
            Validator validator,
            @Value("${shop.services.internal-token:}") String token) {
        this.starts = starts;
        this.workflow = workflow;
        this.validator = validator;
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

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/start-authority")
    public ResponseEntity<?> startAuthority(
            @RequestBody InternalCall<ServiceTaskStartAuthorityPort.StartAuthorityLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "START_AUTHORITY",
                () -> call.request().reference(),
                () -> starts.authorizeCurrent(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/workflow-authority")
    public ResponseEntity<?> workflowAuthority(
            @RequestBody
                    InternalCall<ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return human(
                given,
                call,
                "WORKFLOW_AUTHORITY",
                () -> call.request().reference(),
                () -> workflow.authorizeCurrent(call.request()));
    }

    @PostMapping(ExecutionRuntimeInternalApi.PATH + "/start-authority-original")
    public ResponseEntity<?> startAuthorityOriginal(
            @RequestBody MachineCall<ServiceTaskStartAuthorityPort.StartAuthorityLookup> call,
            @RequestHeader(value = InternalHttp.TOKEN_HEADER, required = false) String given) {
        return InternalServiceResponses.invokeBound(
                token,
                given,
                () -> originals.digest(call),
                () -> {
                    originals.requireRoute(
                            call, Purpose.START_AUTHORITY_ORIGINAL, call.request().reference());
                    if (!validator.validate(call.request()).isEmpty())
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    return originals.reply(call, starts.queryOriginal(call.request()));
                });
    }
}
