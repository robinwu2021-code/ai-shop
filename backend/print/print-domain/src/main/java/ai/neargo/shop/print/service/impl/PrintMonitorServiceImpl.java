package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintMonitorServiceImpl implements PrintMonitorService {
    private final PrnPrinterMapper printers;
    private final PrnJobMapper jobs;
    private final PrnRequestMapper requests;
    private final PrnIncidentMapper incidents;
    private final PrnActionLogMapper logs;
    private final PrnStoreStreamMapper streams;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;

    public PrintMonitorServiceImpl(
            PrnPrinterMapper p,
            PrnJobMapper j,
            PrnRequestMapper r,
            PrnIncidentMapper i,
            PrnActionLogMapper l,
            PrnStoreStreamMapper s,
            PrintOperationService o,
            PrintJsonCodec c) {
        printers = p;
        jobs = j;
        requests = r;
        incidents = i;
        logs = l;
        streams = s;
        ops = o;
        json = c;
    }

    public GetHealthVO getHealth(GetHealthQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        return new GetHealthVO(
                printers.selectCount(
                        PrintSupport.<PrnPrinter>scoped(scope(a))
                                .eq("status", "ACTIVE")
                                .eq("health_status", "ONLINE")),
                jobs.selectCount(
                        PrintSupport.<PrnJob>scoped(scope(a))
                                .in(
                                        "status",
                                        List.of(
                                                "PENDING",
                                                "DISPATCHING",
                                                "ACCEPTED",
                                                "PRINTING",
                                                "RETRY_WAIT"))),
                jobs.selectCount(PrintSupport.<PrnJob>scoped(scope(a)).eq("status", "UNKNOWN")),
                requests.selectCount(
                        PrintSupport.<PrnRequest>scoped(scope(a)).eq("status", "BLOCKED")),
                Instant.now());
    }

    public PageData<IncidentVO> listIncidents(ListIncidentsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                incidents.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnIncident>scoped(scope(a))
                                .eq(q.status() != null, "status", q.status())
                                .eq(q.severity() != null, "severity", q.severity())
                                .eq(q.targetNo() != null, "target_no", q.targetNo())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream().map(PrintViews::incident).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PrintOperation acknowledgeIncident(String no, AcknowledgeIncidentReq r, PrintActor a) {
        return ops.execute(
                a,
                "ACKNOWLEDGE_PRINT_INCIDENT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var i =
                            incidents.selectOne(
                                    PrintSupport.<PrnIncident>scoped(scope(a))
                                            .eq("incident_no", no)
                                            .last("FOR UPDATE"));
                    revision(i, r.expectedRevision());
                    require(!"RESOLVED".equals(i.getStatus()), "print.incidentResolved");
                    i.setStatus("ACKNOWLEDGED");
                    i.setAcknowledgedBy(a.actorNo());
                    i.setAcknowledgedAt(now());
                    i.setRevision(i.getRevision() + 1);
                    i.setUpdatedAt(now());
                    incidents.updateById(i);
                    return succeeded(
                            "ACKNOWLEDGE_PRINT_INCIDENT",
                            "INCIDENT",
                            no,
                            json.freeze(Map.of("incidentNo", no)));
                });
    }

    private ActionLogVO log(PrnActionLog l) {
        return new ActionLogVO(
                l.getStoreSequence(),
                l.getTargetType(),
                l.getTargetNo(),
                l.getAction(),
                l.getActorNo(),
                instant(l.getCreatedAt()));
    }

    public PageData<ActionLogVO> listActionLogs(ListActionLogsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                logs.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnActionLog>scoped(scope(a))
                                .eq(q.targetType() != null, "target_type", q.targetType())
                                .eq(q.targetNo() != null, "target_no", q.targetNo())
                                .orderByDesc("store_sequence"));
        return PageData.of(
                p.getRecords().stream().map(this::log).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public ChangePageVO listChanges(ListChangesQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        int limit = q.limit() == null ? 100 : q.limit();
        require(limit >= 1 && limit <= 100, "print.queryLimit");
        long after = 0;
        try {
            if (q.afterCursor() != null) after = Long.parseLong(q.afterCursor());
        } catch (Exception e) {
            throw new PrintFailure("print.invalidCursor");
        }
        require(after >= 0, "print.invalidCursor");
        var stream = streams.selectOne(PrintSupport.<PrnStoreStream>scoped(scope(a)));
        if (stream == null) return new ChangePageVO(List.of(), 0, false);
        if (after < stream.getRetainedFromSequence())
            return new ChangePageVO(List.of(), stream.getLastSequence(), true);
        var rows =
                logs.selectList(
                        PrintSupport.<PrnActionLog>scoped(scope(a))
                                .gt("store_sequence", after)
                                .le("store_sequence", stream.getLastSequence())
                                .orderByAsc("store_sequence")
                                .last("LIMIT " + limit));
        return new ChangePageVO(
                rows.stream().map(this::log).toList(),
                rows.isEmpty() ? after : rows.getLast().getStoreSequence(),
                false);
    }
}
