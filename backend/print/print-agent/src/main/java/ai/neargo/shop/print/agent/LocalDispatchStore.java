package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public interface LocalDispatchStore {
    boolean cancelBeforeSubmittingAndAppendEvent(
            String dispatchKey, long expectedRevision, LocalEvent event);

    long nextSequence(String dispatchKey);

    void receiveAndCommit(ClaimDispatch dispatch);

    Optional<LocalDispatch> find(String dispatchKey);

    void applyLeaseGrant(
            String dispatchKey, long expectedRevision, String leaseToken, Instant leaseUntil);

    void markSubmittingAndAppendEvent(String dispatchKey, long expectedRevision, LocalEvent event);

    void recordObservationAndAppendEvent(
            String dispatchKey, QueueObservation observation, LocalEvent event);

    List<LocalDispatch> unresolved(int limit);

    List<LocalEvent> pendingEvents(int limit);

    void markEventsAcknowledged(Set<String> eventNos);
}
