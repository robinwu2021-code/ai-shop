package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.beauty.dto.StartServiceTaskReq;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.server.api.InternalTaskWorkflowController;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.beauty.spi.BeautyTaskWorkflowInternalApi;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import jakarta.validation.Validation;

import org.junit.jupiter.api.Test;

import java.util.List;

class TaskWorkflowInternalTest {
    private InternalCall<BeautyTaskWorkflowInternalApi.Start> call(long revision, String target) {
        return new InternalCall<>(
                new InternalRequestBinding(
                        1,
                        BeautyTaskWorkflowInternalApi.ENDPOINT,
                        "START",
                        new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"),
                        "E",
                        "S",
                        target),
                new BeautyTaskWorkflowInternalApi.Start(
                        "W",
                        "T",
                        new StartServiceTaskReq(
                                1,
                                revision,
                                1,
                                List.of(new StartServiceTaskReq.Slot("ROLE", 0, 1))),
                        "key"));
    }

    @Test
    void tokenRouteAndFrameworkValidationPrecedeActualOwnerAndRetainExactParent() {
        var service = mock(ServiceTaskWorkflowService.class);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var controller =
                    new InternalTaskWorkflowController(service, validators.getValidator(), "wire");
            assertThat(controller.start(call(-1, "T"), "wrong").getStatusCode().value())
                    .isEqualTo(401);
            assertThat(controller.start(call(-1, "T"), "wire").getStatusCode().value())
                    .isEqualTo(400);
            assertThat(controller.start(call(0, "OTHER"), "wire").getStatusCode().value())
                    .isEqualTo(400);
            verifyNoInteractions(service);
            var valid = call(0, "T");
            when(service.start("E", "S", "A", "W", "T", valid.request().request(), "key"))
                    .thenReturn(
                            new TaskWorkflowVO(
                                    "OP",
                                    "W",
                                    "T",
                                    "PENDING",
                                    0,
                                    null,
                                    0,
                                    null,
                                    false,
                                    "NOT_REQUIRED",
                                    "RESOURCE_EXECUTION_UNAVAILABLE",
                                    List.of()));
            assertThat(controller.start(valid, "wire").getStatusCode().value()).isEqualTo(200);
            verify(service).start("E", "S", "A", "W", "T", valid.request().request(), "key");
        }
    }

    @Test
    void cancellationTokenAndTargetCannotReachOrRebindAnotherOriginal() {
        var service = mock(ServiceTaskWorkflowService.class);
        try (var validators = Validation.buildDefaultValidatorFactory()) {
            var controller =
                    new InternalTaskWorkflowController(service, validators.getValidator(), "wire");
            var request = new BeautyTaskWorkflowInternalApi.Cancel("W", "T", "ORIGINAL");
            var binding =
                    new InternalRequestBinding(
                            1,
                            BeautyTaskWorkflowInternalApi.ENDPOINT,
                            "CANCEL_PENDING",
                            new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "COORDINATOR"),
                            "E",
                            "S",
                            "T");
            var valid = new InternalCall<>(binding, request);
            assertThat(controller.cancelPending(valid, "wrong").getStatusCode().value())
                    .isEqualTo(401);
            var bad =
                    new InternalCall<>(
                            new InternalRequestBinding(
                                    1,
                                    BeautyTaskWorkflowInternalApi.ENDPOINT,
                                    "CANCEL_PENDING",
                                    binding.identity(),
                                    "E",
                                    "S",
                                    "FOREIGN"),
                            request);
            assertThat(controller.cancelPending(bad, "wire").getStatusCode().value())
                    .isEqualTo(400);
            verifyNoInteractions(service);
            when(service.cancelPending("E", "S", "COORDINATOR", "W", "T", "ORIGINAL"))
                    .thenReturn(
                            new TaskWorkflowVO(
                                    "ORIGINAL",
                                    "W",
                                    "T",
                                    "PAUSED",
                                    5,
                                    "O",
                                    2,
                                    "PAUSED",
                                    false,
                                    "NOT_REQUIRED",
                                    "CANCELLED_BEFORE_EFFECT",
                                    List.of()));
            assertThat(controller.cancelPending(valid, "wire").getStatusCode().value())
                    .isEqualTo(200);
            verify(service).cancelPending("E", "S", "COORDINATOR", "W", "T", "ORIGINAL");
        }
    }
}
