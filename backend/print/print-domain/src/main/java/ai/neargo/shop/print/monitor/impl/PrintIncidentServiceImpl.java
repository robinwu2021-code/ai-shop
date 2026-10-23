package ai.neargo.shop.print.monitor.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.monitor.PrintIncidentService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrintIncidentServiceImpl implements PrintIncidentService {
    private final PrnRequestMapper requests;
    private final PrnJobMapper jobs;
    private final PrintJsonCodec json;
    private final PrnIncidentMapper incidents;
    private final OutboxEventBus events;
    private final ai.neargo.shop.print.audit.PrintActionAppender audit;

    public PrintIncidentServiceImpl(
            PrnIncidentMapper i,
            OutboxEventBus e,
            ai.neargo.shop.print.audit.PrintActionAppender a,
            PrnRequestMapper requests,
            PrnJobMapper jobs,
            PrintJsonCodec json) {
        this.requests = requests;
        this.jobs = jobs;
        this.json = json;
        incidents = i;
        events = e;
        audit = a;
    }

    @Transactional
    public String open(PrintIncidentCommand c) {
        audit.lock(c.scope());
        String key = PrintHashes.sha256(c.type() + ":" + c.targetType() + ":" + c.targetNo());
        var i =
                incidents.selectOne(
                        PrintSupport.<PrnIncident>scoped(c.scope())
                                .eq("incident_key", key)
                                .last("FOR UPDATE"));
        boolean notify = i == null || "RESOLVED".equals(i.getStatus());
        if (i == null) {
            i = init(new PrnIncident(), c.scope());
            i.setIncidentNo(no("IC"));
            i.setStoreNo(c.scope().storeNo());
            i.setIncidentType(c.type());
            i.setTargetType(c.targetType());
            i.setTargetNo(c.targetNo());
            i.setIncidentKey(key);
            i.setSeverity(c.severity());
            i.setOccurrenceCount(0);
            i.setFirstSeenAt(now());
        }
        i.setStatus("OPEN");
        i.setOccurrenceCount(i.getOccurrenceCount() + 1);
        i.setLastSeenAt(now());
        i.setDetails(c.details().canonicalJson());
        i.setRevision(i.getRevision() + 1);
        i.setUpdatedAt(now());
        if (i.getId() == null) incidents.insert(i);
        else incidents.updateById(i);
        if (notify)
            events.publish(
                    new PrintIncidentOpened(
                            c.scope(),
                            i.getIncidentNo(),
                            c.type(),
                            c.targetNo(),
                            notifications(c.scope(), c.targetType(), c.targetNo())));
        return i.getIncidentNo();
    }

    @Transactional
    public void resolveByEvidence(PrintScope s, String no, FrozenJson proof) {
        audit.lock(s);
        var i =
                incidents.selectOne(
                        PrintSupport.<PrnIncident>scoped(s)
                                .eq("incident_no", no)
                                .last("FOR UPDATE"));
        require(i != null, "print.notFound");
        if ("RESOLVED".equals(i.getStatus())) return;
        i.setStatus("RESOLVED");
        i.setResolvedAt(now());
        i.setResolutionCode("CONFIRMED_EVIDENCE");
        i.setDetails(proof.canonicalJson());
        i.setRevision(i.getRevision() + 1);
        i.setUpdatedAt(now());
        incidents.updateById(i);
        events.publish(
                new PrintIncidentResolved(
                        s, no, notifications(s, i.getTargetType(), i.getTargetNo())));
    }

    private FrozenJson notifications(PrintScope scope, String type, String target) {
        String request = target;
        if ("JOB".equals(type)) {
            var j = jobs.selectOne(PrintSupport.<PrnJob>scoped(scope).eq("job_no", target));
            if (j == null) return json.freeze(Map.of());
            request = j.getRequestNo();
        }
        var r =
                requests.selectOne(
                        PrintSupport.<PrnRequest>scoped(scope).eq("request_no", request));
        if (r == null) return json.freeze(Map.of());
        var p = json.read(r.getPolicySnapshot(), PrnScenePolicy.class);
        return json.freeze(p.getNotificationPolicy() == null ? "{}" : p.getNotificationPolicy());
    }
}
