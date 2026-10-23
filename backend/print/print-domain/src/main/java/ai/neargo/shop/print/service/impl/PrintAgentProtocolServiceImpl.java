package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintProtocolDtos.*;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintAgentProtocolServiceImpl implements PrintAgentProtocolService {
    private final PrnAgentMapper agents;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrnAttemptMapper attempts;
    private final PrnJobMapper jobs;
    private final PrnOperationMapper operations;
    private final PrintOperationService ops;
    private final PrintReceiptService receipts;
    private final PrinterLeaseService leases;
    private final PrintAgentSecrets secrets;
    private final PrintJsonCodec json;

    public PrintAgentProtocolServiceImpl(
            PrnAgentMapper a,
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper rt,
            PrnAttemptMapper at,
            PrnJobMapper j,
            PrnOperationMapper operations,
            PrintOperationService o,
            PrintReceiptService r,
            PrinterLeaseService l,
            PrintAgentSecrets s,
            PrintJsonCodec c) {
        agents = a;
        printers = p;
        runtimes = rt;
        attempts = at;
        jobs = j;
        this.operations = operations;
        ops = o;
        receipts = r;
        leases = l;
        secrets = s;
        json = c;
    }

    private PrintScope scope(PrintAgentIdentity i) {
        return new PrintScope(i.tenantNo(), i.entityNo(), i.storeNo());
    }

    private PrintActor actor(PrintAgentIdentity i, String key) {
        return new PrintActor(
                i.tenantNo(), i.entityNo(), i.storeNo(), "AGENT", i.agentNo(), key, Set.of());
    }

    private PrnAgent current(PrintAgentIdentity i, Long epoch, boolean execution) {
        var a =
                agents.selectOne(
                        PrintSupport.<PrnAgent>scoped(scope(i)).eq("agent_no", i.agentNo()));
        require(
                a != null
                        && "ACTIVE".equals(a.getStatus())
                        && a.getCredentialVersion() == i.credentialVersion(),
                "print.agentUnauthorized");
        if (execution)
            require(epoch != null && epoch.equals(a.getSessionEpoch()), "print.agentSessionLost");
        return a;
    }

    private PrnAttempt attempt(String dispatch, PrintAgentIdentity i) {
        var at =
                attempts.selectOne(
                        PrintSupport.<PrnAttempt>owned(scope(i))
                                .eq("dispatch_key", dispatch)
                                .eq("agent_no", i.agentNo()));
        require(at != null, "print.dispatchNotFound");
        require(
                jobs.selectCount(PrintSupport.<PrnJob>scoped(scope(i)).eq("job_no", at.getJobNo()))
                        == 1,
                "print.forbidden");
        return at;
    }

    private PrnPrinterRuntime runtime(PrnAttempt at, PrintAgentIdentity i) {
        var rt =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope(i))
                                .eq("printer_no", at.getPrinterNo())
                                .last("FOR UPDATE"));
        require(
                rt != null
                        && at.getAttemptNo().equals(rt.getActiveAttemptNo())
                        && rt.getFenceEpoch().equals(at.getFenceEpoch()),
                "print.leaseLost");
        return rt;
    }

    private String token(PrnAttempt at, PrnPrinterRuntime rt) {
        return secrets.lease(
                at.getDispatchKey(),
                at.getAgentSessionEpoch(),
                at.getFenceEpoch(),
                instant(rt.getLeaseUntil()));
    }

    public DeviceEnrollVO enroll(DeviceEnrollReq r) {
        var g =
                agents.selectOne(
                        new QueryWrapper<PrnAgent>().eq("agent_no", r.agentNo()).last("LIMIT 1"));
        require(g != null, "print.agentUnauthorized");
        var identity =
                new PrintAgentIdentity(
                        g.getTenantNo(),
                        g.getEntityNo(),
                        g.getStoreNo(),
                        g.getAgentNo(),
                        g.getCredentialVersion(),
                        g.getSessionEpoch());
        var operation =
                ops.execute(
                        actor(identity, r.requestId()),
                        "AGENT_ENROLL",
                        json.freeze(r).sha256(),
                        () -> {
                            var locked =
                                    agents.selectOne(
                                            PrintSupport.<PrnAgent>scoped(scope(identity))
                                                    .eq("agent_no", r.agentNo())
                                                    .last("FOR UPDATE"));
                            require(
                                    "PAIRING".equals(locked.getStatus())
                                            && locked.getEnrollmentExpiresAt() != null
                                            && locked.getEnrollmentExpiresAt().isAfter(now())
                                            && secrets.matches(
                                                    r.enrollmentCode(), locked.getEnrollmentHash())
                                            && "WINDOWS".equals(r.platform()),
                                    "print.agentUnauthorized");
                            int version = locked.getCredentialVersion() + 1;
                            String token =
                                    locked.getAgentNo() + "." + version + "." + secrets.random();
                            String sealed = secrets.seal(token, r.clientPublicKey());
                            locked.setCredentialVersion(version);
                            locked.setCredentialHash(PrintHashes.sha256(token));
                            locked.setStatus("ACTIVE");
                            locked.setEnrollmentHash(null);
                            locked.setSoftwareVersion(r.softwareVersion());
                            locked.setLastSeenAt(now());
                            locked.setRevision(locked.getRevision() + 1);
                            locked.setUpdatedAt(now());
                            agents.updateById(locked);
                            return succeeded(
                                    "AGENT_ENROLL",
                                    "AGENT",
                                    r.agentNo(),
                                    json.freeze(new DeviceEnrollVO(r.agentNo(), sealed, version)));
                        });
        return json.read(operation.result().canonicalJson(), DeviceEnrollVO.class);
    }

    public DeviceOpenSessionVO openSession(DeviceOpenSessionReq r, PrintAgentIdentity i) {
        current(i, null, false);
        var operation =
                ops.execute(
                        actor(i, r.requestId()),
                        "AGENT_OPEN_SESSION",
                        json.freeze(r).sha256(),
                        () -> {
                            var a = current(i, null, false);
                            a.setSessionEpoch(a.getSessionEpoch() + 1);
                            a.setSoftwareVersion(r.softwareVersion());
                            a.setLastSeenAt(now());
                            a.setRevision(a.getRevision() + 1);
                            a.setUpdatedAt(now());
                            agents.updateById(a);
                            return succeeded(
                                    "AGENT_OPEN_SESSION",
                                    "AGENT",
                                    i.agentNo(),
                                    json.freeze(
                                            new DeviceOpenSessionVO(
                                                    a.getSessionEpoch(), 15, Instant.now())));
                        });
        return json.read(operation.result().canonicalJson(), DeviceOpenSessionVO.class);
    }

    public DeviceHeartbeatVO heartbeat(DeviceHeartbeatReq r, PrintAgentIdentity i) {
        current(i, r.sessionEpoch(), true);
        require(
                r.printers().size() <= 100 && r.discoveredPrinters().size() <= 100,
                "print.protocolLimit");
        var operation =
                ops.execute(
                        actor(i, r.requestId()),
                        "AGENT_HEARTBEAT",
                        json.freeze(r).sha256(),
                        () -> {
                            var g = current(i, r.sessionEpoch(), true);
                            for (var observation : r.printers()) {
                                var p =
                                        printers.selectOne(
                                                PrintSupport.<PrnPrinter>scoped(scope(i))
                                                        .eq("printer_no", observation.printerNo())
                                                        .eq("agent_no", i.agentNo())
                                                        .eq(
                                                                "connection_revision",
                                                                observation.connectionRevision()));
                                if (p == null) continue;
                                p.setHealthStatus(observation.health().name());
                                p.setHealthObservedAt(
                                        LocalDateTime.ofInstant(
                                                observation.observedAt(), ZoneOffset.UTC));
                                p.setRevision(p.getRevision() + 1);
                                p.setUpdatedAt(now());
                                printers.updateById(p);
                            }
                            var registered = new TreeMap<String, Object>();
                            for (var d : r.discoveredPrinters()) {
                                require(
                                        d.physicalRegistrationRef() != null
                                                && d.physicalRegistrationRef()
                                                        .matches("[A-Za-z0-9_-]{8,128}")
                                                && d.queueName() != null
                                                && !d.queueName().isBlank()
                                                && d.queueName().length() <= 128,
                                        "print.invalidDiscovery");
                                registered.put(
                                        d.physicalRegistrationRef(),
                                        Map.of("queueName", d.queueName()));
                            }
                            g.setCapabilities(json.write(Map.of("registeredDevices", registered)));
                            g.setLastSeenAt(now());
                            g.setRevision(g.getRevision() + 1);
                            g.setUpdatedAt(now());
                            agents.updateById(g);
                            return succeeded(
                                    "AGENT_HEARTBEAT",
                                    "AGENT",
                                    i.agentNo(),
                                    json.freeze(Map.of("accepted", true)));
                        });
        var commands = new ArrayList<DeviceCommand>();
        for (var o :
                operations.selectList(
                        PrintSupport.<PrnOperation>scoped(scope(i))
                                .eq("status", "PENDING")
                                .in("operation_type", List.of("PROBE_PRINTER", "CANCEL_JOB"))
                                .orderByAsc("id")
                                .last("LIMIT 100"))) {
            var data = json.tree(o.getCommandPayload());
            var p =
                    printers.selectOne(
                            PrintSupport.<PrnPrinter>scoped(scope(i))
                                    .eq("printer_no", data.path("printerNo").asText())
                                    .eq("agent_no", i.agentNo()));
            if (p != null)
                commands.add(
                        new DeviceCommand(
                                o.getOperationNo(),
                                p.getPrinterNo(),
                                data.path("commandType").asText(),
                                data.path("dispatchKey").asText(null),
                                data.path("attemptNo").asText(null),
                                data.path("connectionRevision").asLong(),
                                data.has("fenceEpoch") ? data.path("fenceEpoch").asLong() : null,
                                data.has("sessionEpoch") && !data.path("sessionEpoch").isNull()
                                        ? data.path("sessionEpoch").asLong()
                                        : r.sessionEpoch(),
                                data.has("expiresAt")
                                        ? Instant.parse(data.path("expiresAt").asText())
                                        : instant(o.getCreatedAt().plusMinutes(1)),
                                json.freeze(p.getConnectionConfig())));
        }
        return new DeviceHeartbeatVO(true, Instant.now(), false, commands);
    }

    public DeviceClaimVO claim(DeviceClaimReq r, PrintAgentIdentity i) {
        current(i, r.sessionEpoch(), true);
        require(
                r.maxJobs() == 1
                        && r.printerNos().size() <= 100
                        && r.waitSeconds() >= 0
                        && r.waitSeconds() <= 25,
                "print.protocolLimit");
        var operation =
                ops.execute(
                        actor(i, r.requestId()),
                        "AGENT_CLAIM",
                        json.freeze(r).sha256(),
                        () -> {
                            var keys = new ArrayList<String>();
                            for (String no : new TreeSet<>(r.printerNos())) {
                                var p =
                                        printers.selectOne(
                                                PrintSupport.<PrnPrinter>scoped(scope(i))
                                                        .eq("printer_no", no)
                                                        .eq("agent_no", i.agentNo())
                                                        .eq("connection_type", "LOCAL"));
                                if (p == null) continue;
                                var prepared = leases.prepare(scope(i), no, i.agentNo());
                                if (prepared == null) continue;
                                var at = attempt(prepared.dispatch().dispatchKey(), i);
                                at.setAgentSessionEpoch(r.sessionEpoch());
                                at.setRevision(at.getRevision() + 1);
                                at.setUpdatedAt(now());
                                attempts.updateById(at);
                                var rt = runtime(at, i);
                                rt.setLeaseTokenHash(PrintHashes.sha256(token(at, rt)));
                                rt.setRevision(rt.getRevision() + 1);
                                rt.setUpdatedAt(now());
                                runtimes.updateById(rt);
                                keys.add(at.getDispatchKey());
                                break;
                            }
                            return succeeded(
                                    "AGENT_CLAIM",
                                    "AGENT",
                                    i.agentNo(),
                                    json.freeze(Map.of("claimNo", no("CL"), "dispatchKeys", keys)));
                        });
        var data = json.tree(operation.result());
        var out = new ArrayList<ClaimDispatch>();
        for (var key : data.path("dispatchKeys")) out.add(dispatch(attempt(key.asText(), i), i));
        return new DeviceClaimVO(data.path("claimNo").asText(), out);
    }

    private ClaimDispatch dispatch(PrnAttempt at, PrintAgentIdentity i) {
        var job = jobs.selectOne(PrintSupport.<PrnJob>scoped(scope(i)).eq("job_no", at.getJobNo()));
        var rt =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope(i))
                                .eq("printer_no", at.getPrinterNo()));
        boolean current =
                rt != null
                        && at.getAttemptNo().equals(rt.getActiveAttemptNo())
                        && Objects.equals(at.getAgentSessionEpoch(), i.sessionEpoch());
        var until =
                current && rt.getLeaseUntil() != null ? instant(rt.getLeaseUntil()) : Instant.EPOCH;
        return new ClaimDispatch(
                at.getDispatchKey(),
                at.getJobNo(),
                at.getAttemptNo(),
                at.getPrinterNo(),
                at.getAgentSessionEpoch() == null ? 0 : at.getAgentSessionEpoch(),
                at.getFenceEpoch(),
                at.getConnectionRevision(),
                current ? token(at, rt) : "",
                until,
                until,
                json.read(job.getLayoutSnapshot(), PrintLayout.class),
                job.getDocumentHash(),
                json.freeze(at.getConnectionSnapshot()),
                job.getCancelRequestedAt() != null);
    }

    private void validGrant(
            PrnAttempt at, PrnPrinterRuntime rt, PrintAgentIdentity i, long fence, String token) {
        require(
                at.getAgentSessionEpoch() != null
                        && at.getAgentSessionEpoch() == i.sessionEpoch()
                        && at.getFenceEpoch() == fence
                        && rt.getLeaseUntil() != null
                        && rt.getLeaseUntil().isAfter(now())
                        && secrets.matches(token, rt.getLeaseTokenHash()),
                "print.leaseLost");
    }

    public DeviceAcceptDispatchVO acceptDispatch(
            String key, DeviceAcceptDispatchReq r, PrintAgentIdentity i) {
        current(i, r.sessionEpoch(), true);
        var operation =
                ops.execute(
                        actor(i, r.requestId()),
                        "AGENT_ACCEPT",
                        json.freeze(Map.of("key", key, "request", r)).sha256(),
                        () -> {
                            var at = attempt(key, i);
                            var rt = runtime(at, i);
                            validGrant(at, rt, i, r.fenceEpoch(), r.leaseToken());
                            var j =
                                    jobs.selectOne(
                                            PrintSupport.<PrnJob>scoped(scope(i))
                                                    .eq("job_no", at.getJobNo()));
                            require(
                                    j.getDocumentHash().equals(r.documentHash())
                                            && j.getCancelRequestedAt() == null,
                                    "print.documentMismatch");
                            at.setStatus("ACCEPTED");
                            at.setAcceptedAt(now());
                            at.setRevision(at.getRevision() + 1);
                            at.setUpdatedAt(now());
                            attempts.updateById(at);
                            j.setStatus("ACCEPTED");
                            j.setRevision(j.getRevision() + 1);
                            j.setUpdatedAt(now());
                            jobs.updateById(j);
                            return succeeded(
                                    "AGENT_ACCEPT",
                                    "ATTEMPT",
                                    at.getAttemptNo(),
                                    json.freeze(Map.of("accepted", true)));
                        });
        var d = dispatch(attempt(key, i), i);
        return new DeviceAcceptDispatchVO(
                d.leaseUntil().isAfter(Instant.now()), d.leaseUntil(), d.leaseToken());
    }

    public DeviceRenewLeaseVO renewLease(String key, DeviceRenewLeaseReq r, PrintAgentIdentity i) {
        current(i, r.sessionEpoch(), true);
        ops.execute(
                actor(i, r.requestId()),
                "AGENT_RENEW",
                json.freeze(Map.of("key", key, "request", r)).sha256(),
                () -> {
                    var at = attempt(key, i);
                    var rt = runtime(at, i);
                    validGrant(at, rt, i, r.fenceEpoch(), r.leaseToken());
                    rt.setLeaseUntil(now().plusSeconds(60));
                    rt.setLeaseTokenHash(PrintHashes.sha256(token(at, rt)));
                    rt.setRevision(rt.getRevision() + 1);
                    rt.setUpdatedAt(now());
                    runtimes.updateById(rt);
                    return succeeded(
                            "AGENT_RENEW",
                            "ATTEMPT",
                            at.getAttemptNo(),
                            json.freeze(Map.of("renewed", true)));
                });
        var d = dispatch(attempt(key, i), i);
        return new DeviceRenewLeaseVO(d.leaseUntil(), d.leaseToken());
    }

    public DeviceReportEventsVO reportEvents(DeviceReportEventsReq r, PrintAgentIdentity i) {
        current(i, null, false);
        require(r.events().size() <= 100, "print.protocolLimit");
        var acknowledgements = new ArrayList<EventAcknowledgement>();
        for (var event : r.events()) {
            try {
                var at = attempt(event.dispatchKey(), i);
                require(
                        at.getAttemptNo().equals(event.attemptNo())
                                && at.getFenceEpoch() == event.fenceEpoch()
                                && at.getAgentSessionEpoch() != null
                                && at.getAgentSessionEpoch() == r.sessionEpoch(),
                        "print.attemptMismatch");
                require(
                        event.evidenceLevel() == PrintEvidenceLevel.NONE,
                        "print.agentEvidenceUnsupported");
                json.verify(event.details());
                var ack =
                        receipts.ingest(
                                new VerifiedPrintReceipt(
                                        scope(i),
                                        "AGENT:" + i.agentNo(),
                                        event.eventNo(),
                                        at.getAttemptNo(),
                                        event.status(),
                                        event.evidenceLevel(),
                                        event.sequence(),
                                        event.occurredAt(),
                                        event.details()));
                acknowledgements.add(new EventAcknowledgement(event.eventNo(), ack.status()));
            } catch (PrintFailure f) {
                acknowledgements.add(
                        new EventAcknowledgement(
                                event.eventNo(),
                                f.key().equals("print.receiptConflict") ? "CONFLICT" : "REJECTED"));
            }
        }
        return new DeviceReportEventsVO(acknowledgements);
    }

    public DeviceGetDispatchVO getDispatch(String key, PrintAgentIdentity i) {
        current(i, null, false);
        var at = attempt(key, i);
        var d = dispatch(at, i);
        return new DeviceGetDispatchVO(
                d,
                at.getStatus(),
                d.sessionEpoch() == i.sessionEpoch()
                        && d.leaseUntil().isAfter(Instant.now())
                        && !d.cancelRequested(),
                d.cancelRequested());
    }

    public DeviceRecoverVO recover(DeviceRecoverReq r, PrintAgentIdentity i) {
        current(i, r.sessionEpoch(), true);
        require(r.unresolvedDispatches().size() <= 100, "print.protocolLimit");
        var decisions = new ArrayList<RecoveryDecision>();
        for (var item : r.unresolvedDispatches()) {
            var at = attempt(item.dispatchKey(), i);
            require(at.getAttemptNo().equals(item.attemptNo()), "print.attemptMismatch");
            var d = dispatch(at, i);
            String action =
                    d.cancelRequested()
                            ? "STOP_AND_REPORT"
                            : Set.of(ObservedPrintStatus.RECEIVED, ObservedPrintStatus.ACCEPTED)
                                                    .contains(item.localStatus())
                                            && d.sessionEpoch() == r.sessionEpoch()
                                            && d.leaseUntil().isAfter(Instant.now())
                                    ? "CONTINUE"
                                    : "QUERY_ONLY";
            decisions.add(new RecoveryDecision(item.dispatchKey(), action));
        }
        return new DeviceRecoverVO(decisions);
    }

    public DeviceReportCommandResultVO reportCommandResult(
            String command, DeviceReportCommandResultReq r, PrintAgentIdentity i) {
        current(i, null, false);
        require(
                Set.of("APPLIED", "FAILED", "UNKNOWN", "STALE_TARGET").contains(r.status()),
                "print.invalidCommandResult");
        var result =
                ops.execute(
                        actor(i, r.requestId()),
                        "AGENT_COMMAND_RESULT",
                        json.freeze(Map.of("command", command, "request", r)).sha256(),
                        () -> {
                            var o =
                                    operations.selectOne(
                                            PrintSupport.<PrnOperation>scoped(scope(i))
                                                    .eq("operation_no", command)
                                                    .last("FOR UPDATE"));
                            require(o != null, "print.commandNotFound");
                            var expected = json.tree(o.getCommandPayload());
                            var bound =
                                    printers.selectOne(
                                            PrintSupport.<PrnPrinter>scoped(scope(i))
                                                    .eq("printer_no", r.printerNo())
                                                    .eq("agent_no", i.agentNo()));
                            require(bound != null, "print.forbidden");
                            require(
                                    expected.path("printerNo").asText().equals(r.printerNo())
                                            && expected.path("commandType")
                                                    .asText()
                                                    .equals(r.commandType())
                                            && expected.path("connectionRevision").asLong()
                                                    == r.connectionRevision(),
                                    "print.commandTargetMismatch");
                            if ("PROBE".equals(r.commandType()) && "APPLIED".equals(r.status())) {
                                var probeResult = json.tree(r.result());
                                require(
                                        Set.of("ONLINE", "OFFLINE", "PAPER_OUT", "ERROR", "UNKNOWN")
                                                .contains(probeResult.path("health").asText()),
                                        "print.invalidProbeResult");
                                printers.update(
                                        null,
                                        PrintSupport.<PrnPrinter>ownedUpdate(scope(i))
                                                .eq("printer_no", r.printerNo())
                                                .eq("connection_revision", r.connectionRevision())
                                                .set(
                                                        "health_status",
                                                        probeResult.path("health").asText())
                                                .set("health_observed_at", now())
                                                .set("updated_at", now())
                                                .setSql("revision = revision + 1"));
                            }
                            if (!"PROBE".equals(r.commandType())) {
                                var at = attempt(r.dispatchKey(), i);
                                require(
                                        at.getAttemptNo().equals(r.attemptNo())
                                                && Objects.equals(
                                                        at.getFenceEpoch(), r.fenceEpoch())
                                                && at.getAgentSessionEpoch() == r.sessionEpoch()
                                                && expected.path("dispatchKey")
                                                        .asText()
                                                        .equals(r.dispatchKey())
                                                && expected.path("attemptNo")
                                                        .asText()
                                                        .equals(r.attemptNo())
                                                && expected.path("fenceEpoch").asLong()
                                                        == r.fenceEpoch(),
                                        "print.commandTargetMismatch");
                                receipts.ingest(
                                        new VerifiedPrintReceipt(
                                                scope(i),
                                                "AGENT_COMMAND:" + i.agentNo(),
                                                r.requestId(),
                                                at.getAttemptNo(),
                                                r.status().equals("APPLIED")
                                                        ? ObservedPrintStatus.CANCELLED
                                                        : ObservedPrintStatus.UNKNOWN,
                                                PrintEvidenceLevel.NONE,
                                                null,
                                                Instant.now(),
                                                r.result()));
                            }
                            o.setStatus(
                                    r.status().equals("APPLIED")
                                            ? (r.commandType().equals("PROBE")
                                                    ? "SUCCEEDED"
                                                    : "PENDING")
                                            : r.status().equals("UNKNOWN") ? "UNKNOWN" : "FAILED");
                            o.setFinishedAt(now());
                            o.setResultRef(
                                    json.write(
                                            new PrintOperation(
                                                    o.getOperationNo(),
                                                    o.getOperationType(),
                                                    PrintOperationStatus.valueOf(o.getStatus()),
                                                    o.getTargetType(),
                                                    o.getTargetNo(),
                                                    r.result(),
                                                    null,
                                                    instant(o.getCreatedAt()),
                                                    "PENDING".equals(o.getStatus())
                                                            ? null
                                                            : Instant.now())));
                            o.setRevision(o.getRevision() + 1);
                            o.setUpdatedAt(now());
                            operations.updateById(o);
                            return succeeded(
                                    "AGENT_COMMAND_RESULT",
                                    "OPERATION",
                                    command,
                                    json.freeze(
                                            new DeviceReportCommandResultVO(command, r.status())));
                        });
        return json.read(result.result().canonicalJson(), DeviceReportCommandResultVO.class);
    }
}
