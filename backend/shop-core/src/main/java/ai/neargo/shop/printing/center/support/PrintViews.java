package ai.neargo.shop.printing.center.support;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.center.persistence.entity.*;

public final class PrintViews {
    private PrintViews() {}

    public static PrintJobVO job(PrnJob j, ai.neargo.shop.print.api.PrintActor actor) {
        return job(j, actor, null);
    }

    public static PrintJobVO job(
            PrnJob j, ai.neargo.shop.print.api.PrintActor actor, PrnRequest request) {
        var actions = new java.util.HashSet<String>();
        String state = j.getStatus();
        if (java.util.Set.of("COMPLETED", "FAILED", "UNKNOWN").contains(state)
                && actor.dataScopes().contains("biz:print:reprint")) actions.add("REPRINT");
        if (state.equals("COMPLETED") && actor.dataScopes().contains("biz:print:extra"))
            actions.add("EXTRA");
        if (state.equals("RETRY_WAIT") && actor.dataScopes().contains("biz:print:resolve"))
            actions.add("RETRY");
        if (!java.util.Set.of("COMPLETED", "FAILED", "CANCELLED").contains(state)
                && actor.dataScopes().contains("biz:print:submit")) actions.add("CANCEL");
        if (java.util.Set.of("DISPATCHING", "ACCEPTED", "PRINTING", "UNKNOWN").contains(state)
                && actor.dataScopes().contains("biz:print:resolve")) {
            actions.add("QUERY");
            actions.add("CONFIRM");
        }
        if(j.getRequestNo()==null) actions.clear(); // Legacy actions remain on the existing PrintPort contract.
        return new PrintJobVO(
                j.getJobNo(),
                j.getRequestNo(),
                j.getBatchNo(),
                j.getStoreNo(),
                j.getScene(),
                j.getPrinterNo()==null?j.getDeviceNo():j.getPrinterNo(),
                j.getStatus(),
                j.getJobKind()==null?(j.getOriginalJobNo()==null?"INITIAL":"REPRINT"):j.getJobKind(),
                j.getRootJobNo()==null?j.getJobNo():j.getRootJobNo(),
                j.getParentJobNo(),
                j.getReplacementJobNo(),
                j.getResolutionStatus(),
                j.getRevision(),
                instant(j.getCreatedAt()),
                j.getDocumentKey(),
                j.getCopyIndex(),
                j.getRequired() == 1,
                j.getPriority(),
                j.getRetryCount(),
                j.getMaxRetryCount()==null?0:j.getMaxRetryCount(),
                j.getResultEvidence(),
                instant(j.getConfirmedAt()),
                instant(j.getCancelRequestedAt()),
                j.getLastErrorCode() == null
                        ? null
                        : new ai.neargo.shop.print.api.PrintOperationError(
                                j.getLastErrorCode(), false, null, null),
                actions,
                j.getLastErrorCode() == null
                        ? java.util.List.of()
                        : java.util.List.of(j.getLastErrorCode()),
                instant(j.getUpdatedAt()),
                java.time.Instant.now(),
                request == null ? null : request.getOrderNo(),
                request == null ? null : request.getSubOrderNo());
    }

    public static PrintRequestVO request(PrnRequest r) {
        return new PrintRequestVO(
                r.getRequestNo(),
                r.getStoreNo(),
                r.getOrderNo(),
                r.getSubOrderNo(),
                r.getScene(),
                r.getStatus(),
                r.getBlockCode(),
                r.getRevision(),
                instant(r.getCreatedAt()));
    }

    public static IncidentVO incident(PrnIncident i) {
        return new IncidentVO(
                i.getIncidentNo(),
                i.getStoreNo(),
                i.getIncidentType(),
                i.getTargetType(),
                i.getTargetNo(),
                i.getSeverity(),
                i.getStatus(),
                i.getOccurrenceCount(),
                instant(i.getFirstSeenAt()),
                instant(i.getLastSeenAt()),
                i.getRevision());
    }
}
