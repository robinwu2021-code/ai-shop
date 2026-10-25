package ai.neargo.shop.printing.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.printing.entity.PrnAttempt;
import ai.neargo.shop.printing.entity.PrnDevice;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.entity.PrnRoute;
import ai.neargo.shop.printing.entity.PrnTemplate;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.DeviceMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.RouteMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.TemplateMapper;
import ai.neargo.shop.printing.policy.PrintDocumentPolicy;
import ai.neargo.shop.printing.service.PrintService;
import ai.neargo.shop.spi.printing.PrintJobChanged;
import ai.neargo.shop.spi.printing.PrintPort.Acknowledge;
import ai.neargo.shop.spi.printing.PrintPort.AttemptVO;
import ai.neargo.shop.spi.printing.PrintPort.Claim;
import ai.neargo.shop.spi.printing.PrintPort.Configuration;
import ai.neargo.shop.spi.printing.PrintPort.DeviceReq;
import ai.neargo.shop.spi.printing.PrintPort.DeviceVO;
import ai.neargo.shop.spi.printing.PrintPort.Document;
import ai.neargo.shop.spi.printing.PrintPort.Enqueue;
import ai.neargo.shop.spi.printing.PrintPort.JobVO;
import ai.neargo.shop.spi.printing.PrintPort.RouteReq;
import ai.neargo.shop.spi.printing.PrintPort.Scope;
import ai.neargo.shop.spi.printing.PrintPort.TemplateReq;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Database locks serialize the hardware boundary; timeouts retain uncertainty instead of re-sending
 * paper.
 */
public class PrintServiceImpl implements PrintService {
    private final DeviceMapper devices;
    private final TemplateMapper templates;
    private final RouteMapper routes;
    private final JobMapper jobs;
    private final AttemptMapper attempts;
    private final ObjectMapper json;
    private final Clock clock;
    private final OutboxPublisher events;
    private final TransactionTemplate tx;

    public PrintServiceImpl(
            DeviceMapper devices,
            TemplateMapper templates,
            RouteMapper routes,
            JobMapper jobs,
            AttemptMapper attempts,
            ObjectMapper json,
            Clock clock,
            OutboxPublisher events,
            PlatformTransactionManager manager) {
        this.devices = devices;
        this.templates = templates;
        this.routes = routes;
        this.jobs = jobs;
        this.attempts = attempts;
        this.json = json;
        this.clock = clock;
        this.events = events;
        this.tx = new TransactionTemplate(manager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Configuration configuration(Scope scope) {
        return sql(
                scope,
                () ->
                        new Configuration(
                                devices.selectList(q(scope)).stream().map(this::deviceVO).toList(),
                                templates.selectList(q(scope)).stream()
                                        .map(this::templateVO)
                                        .toList(),
                                routes.selectList(q(scope)).stream().map(this::routeVO).toList()));
    }

    @Override
    public DeviceVO bindDevice(Scope scope, DeviceReq req) {
        if (req == null) {
            bad();
        }
        PrintDocumentPolicy.key(req.deviceNo());
        PrintDocumentPolicy.key(req.bridgeActorNo());
        PrintDocumentPolicy.text(req.vendor(), 32);
        PrintDocumentPolicy.text(req.credentialRef(), 64);
        if (!Set.of("CLOUD", "LAN", "USB")
                .contains(req.connectionType() == null ? "" : req.connectionType())) {
            bad();
        }
        try {
            var capabilities = json.readTree(req.capabilities());
            if (capabilities == null || !capabilities.isObject()) {
                bad();
            }
        } catch (RuntimeException failure) {
            bad();
        }
        if (req.capabilities() == null || req.capabilities().length() > 4000) {
            bad();
        }
        return sql(
                scope,
                () -> {
                    var row =
                            devices.selectOne(
                                    PrintServiceImpl.<PrnDevice>q(scope)
                                            .eq("device_no", req.deviceNo())
                                            .last("FOR UPDATE"));
                    if (row == null) {
                        row = new PrnDevice();
                        init(row, scope);
                        row.setEntityNo(scope.entityNo());
                        row.setStoreNo(scope.storeNo());
                        row.setDeviceNo(req.deviceNo());
                        row.setOnline(0);
                        row.setAuthorizationVersion(0L);
                    } else if (req.active()
                            && jobs.selectCount(
                                            PrintServiceImpl.<PrnJob>q(scope)
                                                    .eq("device_no", row.getDeviceNo())
                                                    .eq("status", "SENT"))
                                    > 0) {
                        conflict();
                    }
                    if (!req.active()) {
                        uncertainOutstanding(scope, req.deviceNo());
                    }
                    boolean authorizationChanged =
                            row.getId() == null
                                    || !Objects.equals(row.getBridgeActorNo(), req.bridgeActorNo())
                                    || !Objects.equals(row.getCredentialRef(), req.credentialRef())
                                    || !Objects.equals(
                                            row.getConnectionType(), req.connectionType())
                                    || !Objects.equals(row.getVendor(), req.vendor())
                                    || !Objects.equals(
                                            row.getStatus(), req.active() ? "ACTIVE" : "DISABLED");
                    if (authorizationChanged) {
                        row.setAuthorizationVersion(
                                Math.addExact(
                                        row.getAuthorizationVersion() == null
                                                ? 0
                                                : row.getAuthorizationVersion(),
                                        1));
                    }
                    row.setRevokeReason(null);
                    row.setRevokedBy(null);
                    row.setRevokeKey(null);
                    row.setVendor(req.vendor());
                    row.setConnectionType(req.connectionType());
                    row.setCredentialRef(req.credentialRef());
                    row.setBridgeActorNo(req.bridgeActorNo());
                    row.setCapabilities(req.capabilities());
                    row.setStatus(req.active() ? "ACTIVE" : "DISABLED");
                    if (row.getId() == null) {
                        devices.insert(row);
                    } else {
                        checked(devices.updateById(row));
                    }
                    return deviceVO(row);
                });
    }

    private record RevokeAudit(String key, String actor, String reason, DeviceVO response) {}

    @Override
    public DeviceVO revokeDevice(Scope scope, String no, String reason, String key) {
        PrintDocumentPolicy.key(no);
        PrintDocumentPolicy.text(reason, 255);
        PrintDocumentPolicy.text(key, 128);
        return sql(
                scope,
                () -> {
                    var device = device(scope, no, true);
                    var history = new ArrayList<RevokeAudit>();
                    if (device.getRevocationHistory() != null) {
                        history.addAll(
                                Arrays.asList(
                                        json.readValue(
                                                device.getRevocationHistory(),
                                                RevokeAudit[].class)));
                    }
                    for (var original : history) {
                        if (key.equals(original.key())) {
                            if (!reason.equals(original.reason())
                                    || !scope.actorNo().equals(original.actor())) {
                                conflict();
                            }
                            return original.response();
                        }
                    }
                    uncertainOutstanding(scope, no);
                    device.setStatus("DISABLED");
                    device.setOnline(0);
                    device.setAuthorizationVersion(
                            Math.addExact(
                                    device.getAuthorizationVersion() == null
                                            ? 0
                                            : device.getAuthorizationVersion(),
                                    1));
                    device.setRevokeReason(reason);
                    device.setRevokedBy(scope.actorNo());
                    device.setRevokeKey(key);
                    history.add(new RevokeAudit(key, scope.actorNo(), reason, deviceVO(device)));
                    device.setRevocationHistory(json.writeValueAsString(history));
                    checked(devices.updateById(device));
                    return deviceVO(device);
                });
    }

    private void uncertainOutstanding(Scope scope, String no) {
        for (var row :
                jobs.selectList(
                        PrintServiceImpl.<PrnJob>q(scope)
                                .eq("device_no", no)
                                .eq("status", "SENT")
                                .last("FOR UPDATE"))) {
            row.setStatus("UNKNOWN");
            checked(jobs.updateById(row));
            var attempt =
                    attempts.selectOne(
                            PrintServiceImpl.<PrnAttempt>q(scope)
                                    .eq("attempt_no", row.getCurrentAttemptNo()));
            if (attempt != null) {
                attempt.setStatus("UNKNOWN");
                attempt.setResponseCode("DEVICE_REVOKED");
                attempt.setFinishedAt(now());
                checked(attempts.updateById(attempt));
            }
            publish(row);
        }
    }

    @Override
    public TemplateReq publishTemplate(Scope scope, TemplateReq req) {
        if (req == null || req.revision() < 1) {
            bad();
        }
        PrintDocumentPolicy.key(req.templateNo());
        PrintDocumentPolicy.key(req.templateCode());
        PrintDocumentPolicy.scene(req.scene());
        PrintDocumentPolicy.template(req.content());
        return sql(
                scope,
                () -> {
                    var row =
                            templates.selectOne(
                                    PrintServiceImpl.<PrnTemplate>q(scope)
                                            .eq("template_no", req.templateNo()));
                    if (row != null) {
                        if (!templateVO(row).equals(req)) {
                            conflict();
                        }
                        return templateVO(row);
                    }
                    row = new PrnTemplate();
                    init(row, scope);
                    row.setEntityNo(scope.entityNo());
                    row.setStoreNo(scope.storeNo());
                    row.setTemplateNo(req.templateNo());
                    row.setTemplateCode(req.templateCode());
                    row.setRevision(req.revision());
                    row.setScene(req.scene());
                    row.setContent(req.content());
                    row.setStatus("PUBLISHED");
                    templates.insert(row);
                    return templateVO(row);
                });
    }

    @Override
    public RouteReq putRoute(Scope scope, RouteReq req) {
        if (req == null || req.copies() < 1 || req.copies() > 10) {
            bad();
        }
        PrintDocumentPolicy.key(req.routeNo());
        PrintDocumentPolicy.scene(req.scene());
        PrintDocumentPolicy.key(req.deviceNo());
        PrintDocumentPolicy.key(req.templateNo());
        if (req.stationNo() != null) {
            PrintDocumentPolicy.key(req.stationNo());
        }
        return sql(
                scope,
                () -> {
                    device(scope, req.deviceNo(), true);
                    var template = template(scope, req.templateNo());
                    if (!template.getScene().equals(req.scene())) {
                        bad();
                    }
                    var row =
                            routes.selectOne(
                                    PrintServiceImpl.<PrnRoute>q(scope)
                                            .eq("route_no", req.routeNo())
                                            .last("FOR UPDATE"));
                    if (row == null) {
                        row = new PrnRoute();
                        init(row, scope);
                        row.setEntityNo(scope.entityNo());
                        row.setStoreNo(scope.storeNo());
                        row.setRouteNo(req.routeNo());
                    }
                    row.setScene(req.scene());
                    row.setStationNo(req.stationNo() == null ? "" : req.stationNo());
                    row.setDeviceNo(req.deviceNo());
                    row.setTemplateNo(req.templateNo());
                    row.setCopies(req.copies());
                    row.setMatchRule("{}");
                    if (row.getId() == null) {
                        routes.insert(row);
                    } else {
                        checked(routes.updateById(row));
                    }
                    return routeVO(row);
                });
    }

    @Override
    public List<JobVO> enqueue(Scope scope, Enqueue req) {
        try {
            return enqueueOnce(scope, req);
        } catch (DuplicateKeyException concurrentEvent) {
            return sql(
                    scope,
                    () -> {
                        String hash =
                                PricingSnapshotHash.of(
                                        List.of(req.sourceEventNo(), req.partNo(), req.document()));
                        var replay =
                                jobs.selectList(
                                        PrintServiceImpl.<PrnJob>q(scope)
                                                .eq("source_event_no", req.sourceEventNo())
                                                .eq("scene", req.document().scene())
                                                .eq("part_no", req.partNo())
                                                .isNull("original_job_no")
                                                .orderByAsc("device_no", "copy_index"));
                        if (replay.isEmpty()
                                || replay.stream()
                                        .anyMatch(row -> !hash.equals(row.getRequestHash()))) {
                            conflict();
                        }
                        return replay.stream().map(row -> view(scope, row)).toList();
                    });
        }
    }

    private List<JobVO> enqueueOnce(Scope scope, Enqueue req) {
        if (req == null) {
            bad();
        }
        PrintDocumentPolicy.text(req.sourceEventNo(), 64);
        PrintDocumentPolicy.key(req.partNo());
        if (req.stationNo() != null) {
            PrintDocumentPolicy.key(req.stationNo());
        }
        PrintDocumentPolicy.document(req.document());
        return sql(
                scope,
                () -> {
                    String hash =
                            PricingSnapshotHash.of(
                                    List.of(req.sourceEventNo(), req.partNo(), req.document()));
                    var replay =
                            jobs.selectList(
                                    PrintServiceImpl.<PrnJob>q(scope)
                                            .eq("source_event_no", req.sourceEventNo())
                                            .eq("scene", req.document().scene())
                                            .eq("part_no", req.partNo())
                                            .isNull("original_job_no")
                                            .orderByAsc("device_no", "copy_index"));
                    if (!replay.isEmpty()) {
                        if (replay.stream().anyMatch(row -> !hash.equals(row.getRequestHash()))) {
                            conflict();
                        }
                        return replay.stream().map(row -> view(scope, row)).toList();
                    }
                    String eventRoot =
                            PricingSnapshotHash.of(
                                    List.of(
                                            "PRINT_EVENT_ROOT_V1",
                                            scope.entityNo(),
                                            scope.storeNo(),
                                            req.sourceEventNo(),
                                            req.document().scene(),
                                            req.partNo()));
                    var matched =
                            routes.selectList(
                                    PrintServiceImpl.<PrnRoute>q(scope)
                                            .eq("scene", req.document().scene())
                                            .eq(
                                                    "station_no",
                                                    req.stationNo() == null ? "" : req.stationNo())
                                            .orderByAsc("device_no"));
                    if (matched.isEmpty()) {
                        conflict();
                    }
                    var result = new ArrayList<JobVO>();
                    for (var route : matched) {
                        var target = device(scope, route.getDeviceNo(), true);
                        if (!"ACTIVE".equals(target.getStatus())) {
                            conflict();
                        }
                        var template = template(scope, route.getTemplateNo());
                        for (int copy = 1; copy <= route.getCopies(); copy++) {
                            String dedup =
                                    PricingSnapshotHash.of(
                                            List.of(
                                                    scope.entityNo(),
                                                    scope.storeNo(),
                                                    req.sourceEventNo(),
                                                    req.document().scene(),
                                                    req.partNo(),
                                                    route.getDeviceNo(),
                                                    copy));
                            var row =
                                    jobs.selectOne(
                                            PrintServiceImpl.<PrnJob>q(scope)
                                                    .eq("dedup_key", dedup));
                            if (row != null) {
                                if (!hash.equals(row.getRequestHash())) {
                                    conflict();
                                }
                            } else {
                                row = new PrnJob();
                                init(row, scope);
                                row.setEntityNo(scope.entityNo());
                                row.setStoreNo(scope.storeNo());
                                row.setJobNo(BizKey.next("PJ"));
                                row.setSourceEventNo(req.sourceEventNo());
                                row.setScene(req.document().scene());
                                row.setDeviceNo(route.getDeviceNo());
                                row.setPartNo(req.partNo());
                                row.setCopyIndex(copy);
                                row.setTemplateNo(template.getTemplateNo());
                                row.setDocumentSnapshot(json.writeValueAsString(req.document()));
                                row.setRenderedContent(
                                        PrintDocumentPolicy.render(
                                                req.document(), template.getContent(), null, null));
                                row.setDedupKey(dedup);
                                row.setRequestHash(hash);
                                row.setRequestedBy(scope.actorNo());
                                // The first job reserves the whole logical event, even if routing
                                // changes concurrently.
                                if (result.isEmpty()) {
                                    row.setEventRootKey(eventRoot);
                                }
                                row.setStatus("PENDING");
                                row.setAttemptCount(0);
                                jobs.insert(row);
                                publish(row);
                            }
                            result.add(view(scope, row));
                        }
                    }
                    return List.copyOf(result);
                });
    }

    @Override
    public List<JobVO> list(Scope scope, String after, int limit) {
        if (limit < 1 || limit > 100) {
            bad();
        }
        if (after != null) {
            PrintDocumentPolicy.key(after);
        }
        return sql(
                scope,
                () ->
                        jobs
                                .selectList(
                                        PrintServiceImpl.<PrnJob>q(scope)
                                                .gt(after != null, "job_no", after)
                                                .orderByAsc("job_no")
                                                .last("LIMIT " + limit))
                                .stream()
                                .map(row -> view(scope, row))
                                .toList());
    }

    @Override
    public JobVO get(Scope scope, String no) {
        PrintDocumentPolicy.key(no);
        return sql(scope, () -> view(scope, job(scope, no, false)));
    }

    @Override
    public JobVO retry(Scope scope, String no) {
        PrintDocumentPolicy.key(no);
        return sql(
                scope,
                () -> {
                    var row = job(scope, no, true);
                    if (!"FAILED".equals(row.getStatus())) {
                        conflict();
                    }
                    // FAILED means an explicit NOT_PRINTED device receipt. UNKNOWN requires an
                    // audited reprint.
                    row.setStatus("PENDING");
                    checked(jobs.updateById(row));
                    publish(row);
                    return view(scope, row);
                });
    }

    @Override
    public JobVO reprint(Scope scope, String no, String reason, String key) {
        PrintDocumentPolicy.key(no);
        PrintDocumentPolicy.text(reason, 256);
        PrintDocumentPolicy.text(key, 128);
        return sql(
                scope,
                () -> {
                    var original = job(scope, no, true);
                    String dedup =
                            PricingSnapshotHash.of(
                                    List.of(scope.entityNo(), scope.storeNo(), "REPRINT", no, key));
                    String hash = PricingSnapshotHash.of(List.of(no, reason, scope.actorNo()));
                    var existing =
                            jobs.selectOne(
                                    PrintServiceImpl.<PrnJob>q(scope).eq("dedup_key", dedup));
                    if (existing != null) {
                        if (!hash.equals(existing.getRequestHash())) {
                            conflict();
                        }
                        return view(scope, existing);
                    }
                    var row = new PrnJob();
                    init(row, scope);
                    row.setEntityNo(scope.entityNo());
                    row.setStoreNo(scope.storeNo());
                    row.setJobNo(BizKey.next("PJ"));
                    row.setSourceEventNo(original.getSourceEventNo());
                    row.setScene(original.getScene());
                    row.setDeviceNo(original.getDeviceNo());
                    row.setPartNo(original.getPartNo());
                    row.setCopyIndex(original.getCopyIndex());
                    row.setTemplateNo(original.getTemplateNo());
                    row.setDocumentSnapshot(original.getDocumentSnapshot());
                    row.setRenderedContent(
                            "REPRINT / 补打 "
                                    + no
                                    + " / "
                                    + reason
                                    + "\n"
                                    + original.getRenderedContent());
                    row.setOriginalJobNo(no);
                    row.setReprintReason(reason);
                    row.setRequestedBy(scope.actorNo());
                    row.setDedupKey(dedup);
                    row.setRequestHash(hash);
                    row.setStatus("PENDING");
                    row.setAttemptCount(0);
                    jobs.insert(row);
                    publish(row);
                    return view(scope, row);
                });
    }

    @Override
    public DeviceVO heartbeat(Scope scope, String no, boolean online) {
        PrintDocumentPolicy.key(no);
        return sql(
                scope,
                () -> {
                    var device = device(scope, no, true);
                    authorizeBridge(scope, device);
                    device.setLastSeenAt(now());
                    device.setOnline(online ? 1 : 0);
                    checked(devices.updateById(device));
                    expire(scope, no);
                    return deviceVO(device);
                });
    }

    @Override
    public Claim claim(Scope scope, String no) {
        PrintDocumentPolicy.key(no);
        return sql(
                scope,
                () -> {
                    var device = device(scope, no, true);
                    authorizeBridge(scope, device);
                    expire(scope, no);
                    if (!online(device)) {
                        conflict();
                    }
                    if (jobs.selectCount(
                                    PrintServiceImpl.<PrnJob>q(scope)
                                            .eq("device_no", no)
                                            .eq("status", "SENT"))
                            > 0) {
                        return null;
                    }
                    var row =
                            jobs.selectOne(
                                    PrintServiceImpl.<PrnJob>q(scope)
                                            .eq("device_no", no)
                                            .eq("status", "PENDING")
                                            .orderByAsc("id")
                                            .last("LIMIT 1 FOR UPDATE"));
                    if (row == null) {
                        return null;
                    }
                    var attempt = new PrnAttempt();
                    init(attempt, scope);
                    attempt.setEntityNo(scope.entityNo());
                    attempt.setStoreNo(scope.storeNo());
                    attempt.setAttemptNo(BizKey.next("PA"));
                    attempt.setJobNo(row.getJobNo());
                    attempt.setSequenceNo(row.getAttemptCount() + 1);
                    attempt.setStatus("SENT");
                    attempt.setStartedAt(now());
                    attempt.setLeaseToken(UUID.randomUUID().toString());
                    attempt.setBridgeActorNo(scope.actorNo());
                    attempts.insert(attempt);
                    row.setStatus("SENT");
                    row.setCurrentAttemptNo(attempt.getAttemptNo());
                    row.setAttemptCount(attempt.getSequenceNo());
                    row.setLeaseToken(attempt.getLeaseToken());
                    row.setLeaseUntil(now().plusSeconds(60));
                    checked(jobs.updateById(row));
                    publish(row);
                    return new Claim(
                            row.getJobNo(),
                            no,
                            attempt.getAttemptNo(),
                            attempt.getLeaseToken(),
                            row.getLeaseUntil(),
                            row.getRenderedContent());
                });
    }

    @Override
    public JobVO acknowledge(Scope scope, String no, Acknowledge req) {
        PrintDocumentPolicy.key(no);
        if (req == null) {
            bad();
        }
        PrintDocumentPolicy.key(req.attemptNo());
        PrintDocumentPolicy.text(req.leaseToken(), 64);
        if (!Set.of("SUCCESS", "FAILED", "UNKNOWN")
                .contains(req.status() == null ? "" : req.status())) {
            bad();
        }
        if (req.channelJobNo() != null) {
            PrintDocumentPolicy.text(req.channelJobNo(), 64);
        }
        if (req.responseCode() != null && !req.responseCode().matches("[A-Z0-9_]{1,64}")) {
            bad();
        }
        return sql(
                scope,
                () -> {
                    var device = device(scope, no, true);
                    authorizeBridge(scope, device);
                    var attempt =
                            attempts.selectOne(
                                    PrintServiceImpl.<PrnAttempt>q(scope)
                                            .eq("attempt_no", req.attemptNo()));
                    if (attempt == null) {
                        missing();
                    }
                    var row = job(scope, attempt.getJobNo(), true);
                    if (!no.equals(row.getDeviceNo())
                            || !scope.actorNo().equals(attempt.getBridgeActorNo())
                            || !req.leaseToken().equals(attempt.getLeaseToken())
                            || !req.attemptNo().equals(row.getCurrentAttemptNo())
                            || !req.leaseToken().equals(row.getLeaseToken())) {
                        forbidden();
                    }
                    String status =
                            "FAILED".equals(req.status())
                                            && !"NOT_PRINTED".equals(req.responseCode())
                                    ? "UNKNOWN"
                                    : req.status();
                    if (Set.of("SUCCESS", "FAILED").contains(attempt.getStatus())) {
                        if (!attempt.getStatus().equals(status)
                                || !Objects.equals(req.channelJobNo(), attempt.getChannelJobNo())
                                || !Objects.equals(req.responseCode(), attempt.getResponseCode())) {
                            conflict();
                        }
                        return view(scope, row);
                    }
                    attempt.setStatus(status);
                    attempt.setChannelJobNo(req.channelJobNo());
                    attempt.setResponseCode(req.responseCode());
                    attempt.setFinishedAt(now());
                    checked(attempts.updateById(attempt));
                    row.setStatus(status);
                    checked(jobs.updateById(row));
                    publish(row);
                    return view(scope, row);
                });
    }

    private void expire(Scope scope, String no) {
        for (var row :
                jobs.selectList(
                        PrintServiceImpl.<PrnJob>q(scope)
                                .eq("device_no", no)
                                .eq("status", "SENT")
                                .le("lease_until", now())
                                .last("FOR UPDATE"))) {
            row.setStatus("UNKNOWN");
            checked(jobs.updateById(row));
            var attempt =
                    attempts.selectOne(
                            PrintServiceImpl.<PrnAttempt>q(scope)
                                    .eq("attempt_no", row.getCurrentAttemptNo()));
            if (attempt != null) {
                attempt.setStatus("UNKNOWN");
                attempt.setResponseCode("RECEIPT_TIMEOUT");
                attempt.setFinishedAt(now());
                checked(attempts.updateById(attempt));
            }
            publish(row);
        }
    }

    private PrnDevice device(Scope scope, String no, boolean lock) {
        var query = PrintServiceImpl.<PrnDevice>q(scope).eq("device_no", no);
        if (lock) {
            query.last("FOR UPDATE");
        }
        var row = devices.selectOne(query);
        if (row == null) {
            missing();
        }
        return row;
    }

    private PrnTemplate template(Scope scope, String no) {
        var row =
                templates.selectOne(
                        PrintServiceImpl.<PrnTemplate>q(scope)
                                .eq("template_no", no)
                                .eq("status", "PUBLISHED"));
        if (row == null) {
            missing();
        }
        return row;
    }

    private PrnJob job(Scope scope, String no, boolean lock) {
        var query = PrintServiceImpl.<PrnJob>q(scope).eq("job_no", no);
        if (lock) {
            query.last("FOR UPDATE");
        }
        var row = jobs.selectOne(query);
        if (row == null) {
            missing();
        }
        return row;
    }

    private void authorizeBridge(Scope scope, PrnDevice device) {
        if (!scope.actorNo().equals(device.getBridgeActorNo())
                || !"ACTIVE".equals(device.getStatus())) {
            forbidden();
        }
    }

    private JobVO view(Scope scope, PrnJob row) {
        // A read reports a timed-out lease as UNKNOWN even if the bridge has not polled again yet.
        String status =
                "SENT".equals(row.getStatus()) && !row.getLeaseUntil().isAfter(now())
                        ? "UNKNOWN"
                        : row.getStatus();
        var history =
                attempts
                        .selectList(
                                PrintServiceImpl.<PrnAttempt>q(scope)
                                        .eq("job_no", row.getJobNo())
                                        .orderByAsc("sequence_no"))
                        .stream()
                        .map(
                                a ->
                                        new AttemptVO(
                                                a.getAttemptNo(),
                                                a.getSequenceNo(),
                                                "SENT".equals(a.getStatus())
                                                                && "UNKNOWN".equals(status)
                                                        ? "UNKNOWN"
                                                        : a.getStatus(),
                                                a.getResponseCode(),
                                                a.getStartedAt(),
                                                a.getFinishedAt()))
                        .toList();
        return new JobVO(
                row.getJobNo(),
                row.getStoreNo(),
                row.getSourceEventNo(),
                row.getScene(),
                row.getDeviceNo(),
                row.getPartNo(),
                row.getCopyIndex(),
                row.getTemplateNo(),
                status,
                row.getOriginalJobNo(),
                row.getReprintReason(),
                row.getRequestedBy(),
                json.readValue(row.getDocumentSnapshot(), Document.class),
                row.getRenderedContent(),
                history);
    }

    private DeviceVO deviceVO(PrnDevice row) {
        return new DeviceVO(
                row.getDeviceNo(),
                row.getVendor(),
                row.getConnectionType(),
                row.getBridgeActorNo(),
                row.getStatus(),
                row.getCapabilities(),
                row.getLastSeenAt(),
                online(row),
                row.getAuthorizationVersion() == null ? 0 : row.getAuthorizationVersion());
    }

    private boolean online(PrnDevice row) {
        return "ACTIVE".equals(row.getStatus())
                && Integer.valueOf(1).equals(row.getOnline())
                && row.getLastSeenAt() != null
                && row.getLastSeenAt().isAfter(now().minusSeconds(90));
    }

    private TemplateReq templateVO(PrnTemplate row) {
        return new TemplateReq(
                row.getTemplateNo(),
                row.getTemplateCode(),
                row.getRevision(),
                row.getScene(),
                row.getContent());
    }

    private RouteReq routeVO(PrnRoute row) {
        return new RouteReq(
                row.getRouteNo(),
                row.getScene(),
                row.getStationNo().isEmpty() ? null : row.getStationNo(),
                row.getDeviceNo(),
                row.getTemplateNo(),
                row.getCopies());
    }

    private static <T> QueryWrapper<T> q(Scope scope) {
        return Wrappers.<T>query()
                .eq("tenant_no", "MAIN")
                .eq("entity_no", scope.entityNo())
                .eq("store_no", scope.storeNo());
    }

    private void init(BaseEntity row, Scope scope) {
        row.setTenantNo("MAIN");
        row.setCreatedAt(now());
        row.setCreatedBy(scope.actorNo());
        row.setUpdatedAt(now());
        row.setUpdatedBy(scope.actorNo());
        row.setVersion(0L);
        row.setDeleted(0);
    }

    private void publish(PrnJob row) {
        events.publish(
                new PrintJobChanged(
                        row.getEntityNo(),
                        row.getStoreNo(),
                        row.getJobNo(),
                        row.getStatus(),
                        row.getDeviceNo(),
                        row.getAttemptCount()),
                new PublishOptions("PRINT:" + row.getJobNo(), 1));
    }

    private <T> T sql(Scope scope, Supplier<T> action) {
        PrintDocumentPolicy.scope(scope);
        return DataScopeContext.executeWithoutScope(() -> tx.execute(status -> action.get()));
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private static void checked(int rows) {
        if (rows != 1) {
            conflict();
        }
    }

    private static void bad() {
        throw BizException.of(ErrorCode.BAD_REQUEST);
    }

    private static void missing() {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }

    private static void conflict() {
        throw BizException.of(ErrorCode.CONFLICT);
    }

    private static void forbidden() {
        throw BizException.of(ErrorCode.FORBIDDEN);
    }
}
