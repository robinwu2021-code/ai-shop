package ai.neargo.shop.print.agent;

import ai.neargo.shop.print.agent.AgentModels.*;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;

import java.time.*;
import java.util.*;

public final class AgentModels {
    private AgentModels() {}

    public record LocalDispatch(
            String dispatchKey,
            String jobNo,
            String attemptNo,
            String printerNo,
            long sessionEpoch,
            long fenceEpoch,
            long connectionRevision,
            String leaseToken,
            Instant leaseUntil,
            Instant executeBefore,
            PrintLayout layout,
            String documentHash,
            FrozenJson connection,
            ObservedPrintStatus status,
            String systemJobId,
            long revision,
            Instant receivedAt,
            Instant submittingAt,
            Instant finishedAt) {}

    public record LocalEvent(
            String eventNo,
            String dispatchKey,
            String attemptNo,
            long sessionEpoch,
            long fenceEpoch,
            long sequence,
            ObservedPrintStatus status,
            PrintEvidenceLevel evidenceLevel,
            FrozenJson evidence,
            Instant occurredAt,
            Instant acknowledgedAt) {}

    public record QueueObservation(
            ObservedPrintStatus status,
            String systemJobId,
            boolean queueTerminated,
            boolean partialOutput,
            PrintEvidenceLevel evidenceLevel,
            FrozenJson evidence) {}
}
