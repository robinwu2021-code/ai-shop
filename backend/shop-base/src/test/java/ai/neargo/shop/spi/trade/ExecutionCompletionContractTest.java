package ai.neargo.shop.spi.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.resource.ResourceOccupationContract;
import ai.neargo.shop.spi.resource.ResourceOccupationContract.StartRequest;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Action;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Request;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Result;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecycleContract.Segment;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort.StartAuthorityLookup;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort.WorkflowAuthorityLookup;
import ai.neargo.shop.spi.trade.ExecutionCompletionContract.CompleteExecution;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionCompletionContractTest {
    private final String hash = "a".repeat(64);
    private final Instant start = Instant.parse("2026-11-10T02:00:00Z");
    private final Instant end = start.plusSeconds(1800);

    private ExecutionReference reference() {
        return new ExecutionReference("SERVICE_EXECUTION_REFERENCE_V1", new ExecutionScope("MAIN", "M1", "S1"),
                ExecutionReference.SourceKind.STORE_ORDER, "MANUAL_EXECUTION_V1", "E1", "G1", "U1", "T1", "WO1", 1,
                1, hash, hash, hash, hash, hash, "F1", 1, hash, "BTRC1", hash, "P1", hash, hash, null).signed();
    }

    private CompleteExecution request() {
        var ref = reference();
        var authority = new StartAuthorityLookup(ref, "START1", hash);
        var started = new StartRequest(ref, authority, "STARTKEY",
                ResourceOccupationContract.stableRequestHash(ref, authority, "STARTKEY")).lookup();
        var original = new ResourceOccupationContract.OriginalLookup(started.reference(), started.source(),
                started.requestKey(), started.requestHash(), new ResourceOccupationContract.ActualOriginal(
                        "RESOURCE_OWNER", "RESOURCE_COMMAND", "RESOURCE_OCCUPATION_START_V1", hash));
        var ended = new Request(new WorkflowAuthorityLookup(ref, "END1", hash), original, "OCC1", 2,
                Action.END, "END1", hash, null, List.of(), List.of()).signed();
        return ExecutionCompletionContract.request(ref, ended, "END1");
    }

    private Result ended(CompleteExecution request, Instant actualEnd) {
        return new Result(request.resourceEndOriginal(), "RCEND1", "R1", 3, "CLEANING", end, end,
                List.of(new Segment("SEG1", "DEMAND1", "ROOM1", null, 1, start, actualEnd, true, null)),
                null, null).signed();
    }

    @Test
    void realEndIsAcceptedWithManualCleaningStillPendingAndItsExactOriginalReplays() {
        var request = request();
        request.validate();
        var original = ended(request, end);
        ExecutionCompletionContract.requireCompletedEnd(request, original);
        ExecutionCompletionContract.requireCompletedEnd(request, original);
        assertThat(original.state()).isEqualTo("CLEANING");
        assertThat(original.segments().getFirst().cleaningCompletedAt()).isNull();
    }

    @Test
    void openOrFutureEndedSegmentCannotMintCompletedQuantity() {
        var request = request();
        assertThatThrownBy(() -> ExecutionCompletionContract.requireCompletedEnd(request, ended(request, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionCompletionContract.requireCompletedEnd(request, ended(request, end.plusSeconds(1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSignedDifferentOwnerEndDoesNotSatisfyTheRequestedOriginal() {
        var request = request();
        var original = request.resourceEndOriginal();
        var other = new Request(original.authority(), original.startOriginal(), "OTHER_OCC", original.expectedRevision(),
                Action.END, original.actionNo(), original.keyHash(), null, List.of(), List.of()).signed();
        var wrong = new Result(other, "RCEND2", "R2", 3, "CLEANING", end, end,
                ended(request, end).segments(), null, null).signed();
        assertThatThrownBy(() -> ExecutionCompletionContract.requireCompletedEnd(request, wrong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void completionCannotRequestTwoCountsOrSubstituteANewSourceAction() {
        var request = request();
        assertThatThrownBy(() -> new CompleteExecution(request.reference(), request.resourceEndOriginal(),
                request.actionNo(), request.keyHash(), 2).validate()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CompleteExecution(request.reference(), request.resourceEndOriginal(),
                "NEW_ACTION", request.keyHash(), 1).validate()).isInstanceOf(IllegalArgumentException.class);
    }
}
