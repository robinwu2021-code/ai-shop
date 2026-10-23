package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public final class AgentRecoveryCoordinator {
    private final LocalDispatchStore store;
    private final DesktopPrintExecutor executor;
    private final AgentJson json = new AgentJson();

    public AgentRecoveryCoordinator(LocalDispatchStore s, DesktopPrintExecutor e) {
        store = s;
        executor = e;
    }

    public void recoverOnStartup(PrintWorkerBudget budget) {
        for (var d : store.unresolved(budget.maxItems())) {
            QueueObservation observed =
                    d.submittingAt() == null
                            ? new QueueObservation(
                                    ObservedPrintStatus.NOT_ACCEPTED,
                                    null,
                                    true,
                                    false,
                                    PrintEvidenceLevel.NONE,
                                    json.freeze(
                                            Map.of(
                                                    "queueTerminated",
                                                    true,
                                                    "partialOutput",
                                                    false,
                                                    "reason",
                                                    "RESTART_BEFORE_SUBMIT")))
                            : executor.query(d);
            var event =
                    new LocalEvent(
                            "AE" + UUID.randomUUID().toString().replace("-", ""),
                            d.dispatchKey(),
                            d.attemptNo(),
                            d.sessionEpoch(),
                            d.fenceEpoch(),
                            store.nextSequence(d.dispatchKey()),
                            observed.status(),
                            observed.evidenceLevel(),
                            observed.evidence(),
                            Instant.now(),
                            null);
            store.recordObservationAndAppendEvent(d.dispatchKey(), observed, event);
        }
    }
}
