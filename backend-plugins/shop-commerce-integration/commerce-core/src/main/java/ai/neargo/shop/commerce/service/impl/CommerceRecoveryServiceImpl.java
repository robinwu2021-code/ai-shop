package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.CatalogContracts.PreviewCatalogReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.RecoveryVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SourceReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncPolicyVO;
import ai.neargo.shop.commerce.entity.CimAuthorizationRevision;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimCatalogSnapshot;
import ai.neargo.shop.commerce.entity.CimOperation;
import ai.neargo.shop.commerce.entity.CimOperationAttempt;
import ai.neargo.shop.commerce.entity.CimSyncPolicy;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.entity.CimSyncRunItem;
import ai.neargo.shop.commerce.policy.ChannelIdentity;
import ai.neargo.shop.commerce.service.CommerceRecoveryService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway.Outcome;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway.Publication;
import ai.neargo.shop.spi.commerce.ChannelCatalogGateway.Result;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 同一原动作仅发送一次；恢复只查询。租约到期不是远端写入终止证据。 */
public final class CommerceRecoveryServiceImpl implements CommerceRecoveryService {
    private final CatalogPersistence db;
    private final CatalogPublicationServiceImpl previews;
    private final SyncRunServiceImpl runs;

    public CommerceRecoveryServiceImpl(
            CatalogPersistence db,
            CatalogPublicationServiceImpl previews,
            SyncRunServiceImpl runs) {
        this.db = db;
        this.previews = previews;
        this.runs = runs;
    }

    record Dispatch(
            String runNo,
            String entity,
            String store,
            String scopeNo,
            Publication publication,
            ChannelCatalogGateway gateway,
            long attempt,
            long fence,
            String task,
            boolean query) {}

    public RecoveryVO recoverDue(int limit) {
        require(limit >= 1 && limit <= 100);
        int automatic = 0, dispatched = 0, queried = 0, pending = 0, failed = 0;
        var policies =
                db.commands.query(
                        () ->
                                db.policies.selectList(
                                        Wrappers.<CimSyncPolicy>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("enabled", true)
                                                .le("next_check_at", now())
                                                .orderByAsc("next_check_at", "id")
                                                .last("LIMIT " + limit)));
        for (var p : policies)
            try {
                if (automatic(p)) automatic++;
            } catch (RuntimeException failure) {
                failed++;
                deferPolicy(p);
            }
        var due =
                db.commands.query(
                        () ->
                                db.runs.selectList(
                                        Wrappers.<CimSyncRun>query()
                                                .eq("tenant_no", "MAIN")
                                                .in(
                                                        "status",
                                                        List.of(
                                                                "QUEUED",
                                                                "RUNNING",
                                                                "SUBMITTED",
                                                                "UNKNOWN"))
                                                .le("next_recovery_at", now())
                                                // Blocked queued intents must not consume the query
                                                // budget of their original operation.
                                                .and(
                                                        q ->
                                                                q.ne("status", "QUEUED")
                                                                        .or()
                                                                        .inSql(
                                                                                "scope_no",
                                                                                "SELECT scope_no"
                                                                                    + " FROM"
                                                                                    + " cim_catalog_scope"
                                                                                    + " WHERE"
                                                                                    + " tenant_no='MAIN'"
                                                                                    + " AND deleted=0"
                                                                                    + " AND status='ACTIVE'"
                                                                                    + " AND active_operation_no"
                                                                                    + " IS NULL"))
                                                .orderByAsc("next_recovery_at", "id")
                                                .last("LIMIT " + limit)));
        for (var r : due)
            try {
                Dispatch d;
                if ("QUEUED".equals(r.getStatus())) {
                    var c =
                            db.commands.query(
                                    () ->
                                            db.scope(
                                                    r.getEntityNo(),
                                                    r.getStoreNo(),
                                                    r.getScopeNo(),
                                                    false));
                    if (c.scope().getActiveOperationNo() != null) {
                        deferRun(r);
                        pending++;
                        continue;
                    }
                    var snapshot =
                            db.commands.query(
                                    () ->
                                            db.snapshot(
                                                    r.getEntityNo(),
                                                    r.getScopeNo(),
                                                    r.getSnapshotNo()));
                    try {
                        db.revalidate(c, snapshot);
                    } catch (RuntimeException invalid) {
                        failQueued(r);
                        failed++;
                        continue;
                    }
                    d = db.commands.atomic(() -> claim(r, c, snapshot, false));
                } else d = db.commands.atomic(() -> claim(r, null, null, true));
                if (d == null) {
                    deferRun(r);
                    pending++;
                    continue;
                }
                Result result;
                try {
                    result =
                            d.query()
                                    ? d.gateway().query(d.publication(), d.task())
                                    : d.gateway().publish(d.publication());
                } catch (RuntimeException unknown) {
                    result = null;
                }
                if (d.query()) queried++;
                else dispatched++;
                Result observed = result;
                db.commands.atomic(
                        () -> {
                            finish(d, observed);
                            return null;
                        });
            } catch (RuntimeException failure) {
                failed++;
                deferRun(r);
            }
        return new RecoveryVO(dispatched, queried, automatic, pending, failed);
    }

    private void deferRun(CimSyncRun candidate) {
        db.commands.atomic(
                () -> {
                    db.scope(
                            candidate.getEntityNo(),
                            candidate.getStoreNo(),
                            candidate.getScopeNo(),
                            true);
                    db.runs.update(
                            Wrappers.<CimSyncRun>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", candidate.getId())
                                    .eq("version", candidate.getVersion())
                                    .in(
                                            "status",
                                            List.of("QUEUED", "RUNNING", "SUBMITTED", "UNKNOWN"))
                                    .set("next_recovery_at", now().plusMinutes(1)));
                    return null;
                });
    }

    private Dispatch claim(
            CimSyncRun candidate,
            CatalogPersistence.Context before,
            CimCatalogSnapshot snapshot,
            boolean query) {
        var c =
                db.scope(
                        candidate.getEntityNo(),
                        candidate.getStoreNo(),
                        candidate.getScopeNo(),
                        true);
        var r = db.run(candidate.getEntityNo(), candidate.getStoreNo(), candidate.getRunNo(), true);
        if (r.getNextRecoveryAt() == null || r.getNextRecoveryAt().isAfter(now())) return null;
        CimOperation op;
        long attempt, fence;
        String task = null;
        CimAuthorizationRevision grant;
        if (!query) {
            if (!"QUEUED".equals(r.getStatus()) || c.scope().getActiveOperationNo() != null)
                return null;
            db.unchanged(before, c);
            db.validSnapshot(c, snapshot);
            grant = db.ready(c);
            long generation = Math.addExact(c.scope().getGeneration(), 1);
            op = audit(new CimOperation(), "SYSTEM");
            op.setOperationNo(BizKey.next("CIOP"));
            op.setEntityNo(r.getEntityNo());
            op.setStoreNo(r.getStoreNo());
            op.setConnectionNo(r.getConnectionNo());
            op.setDestination("CHANNEL");
            op.setOperationType("CATALOG_REPLACE");
            op.setBusinessRef(r.getRunNo());
            op.setBusinessRevision(snapshot.getSnapshotNo());
            op.setSemanticKey(ChannelIdentity.hash(r.getRunNo()));
            op.setRequestHash(snapshot.getContentHash());
            op.setRequestRef("AUTH:" + grant.getAuthorizationNo());
            op.setStatus("RUNNING");
            op.setCurrentAttemptNo(1L);
            op.setFence(1L);
            op.setAccountNo(c.account().getAccountNo());
            op.setProfileNo(c.connection().getProfileNo());
            op.setTargetScopeKind("CONNECTION");
            op.setTargetScopeKey(
                    ChannelIdentity.hash(c.connection().getChannelStoreKey() + ":STORE"));
            op.setLeaseUntil(now().plusSeconds(60));
            op.setNextRecoveryAt(now().plusSeconds(60));
            db.operations.insert(op);
            r.setOperationNo(op.getOperationNo());
            r.setGeneration(generation);
            attempt = 1;
            fence = 1;
            db.scopes.update(
                    Wrappers.<CimCatalogScope>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", c.scope().getId())
                            .isNull("active_operation_no")
                            .set("active_operation_no", op.getOperationNo())
                            .set("generation", generation)
                            .set("remote_consistency", "PENDING"));
            db.runs.update(
                    Wrappers.<CimSyncRun>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", r.getId())
                            .set("generation", generation)
                            .set("operation_no", op.getOperationNo())
                            .set("started_at", now()));
        } else {
            if (!Set.of("RUNNING", "SUBMITTED", "UNKNOWN").contains(r.getStatus())
                    || !Objects.equals(r.getOperationNo(), c.scope().getActiveOperationNo()))
                return null;
            op =
                    db.operations.selectOne(
                            Wrappers.<CimOperation>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("operation_no", r.getOperationNo())
                                    .last("FOR UPDATE"));
            require(op != null);
            if (op.getLeaseUntil() != null && op.getLeaseUntil().isAfter(now())) return null;
            grant = db.ready(c);
            require(op.getRequestRef().equals("AUTH:" + grant.getAuthorizationNo()));
            var gateway = db.gateway(c);
            if (!gateway.supportsQuery()) {
                db.runs.update(
                        Wrappers.<CimSyncRun>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", r.getId())
                                .set("status", "UNKNOWN")
                                .set("failure_code", "QUERY_UNSUPPORTED")
                                .set("next_recovery_at", now().plusMinutes(5)));
                return null;
            }
            var old =
                    db.attempts.selectOne(
                            Wrappers.<CimOperationAttempt>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("operation_no", op.getOperationNo())
                                    .eq("attempt_no", op.getCurrentAttemptNo())
                                    .last("FOR UPDATE"));
            require(old != null);
            task = old.getProviderTaskRef();
            if (old.getFinishedAt() == null)
                db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", old.getId())
                                .isNull("finished_at")
                                .set("finished_at", now())
                                .set("outcome", "UNKNOWN")
                                .set("error_code", "LEASE_EXPIRED"));
            attempt = Math.addExact(op.getCurrentAttemptNo(), 1);
            fence = Math.addExact(op.getFence(), 1);
            snapshot = db.snapshot(r.getEntityNo(), r.getScopeNo(), r.getSnapshotNo());
        }
        var a = audit(new CimOperationAttempt(), "SYSTEM");
        a.setAttemptRef(BizKey.next("CIAT"));
        a.setEntityNo(r.getEntityNo());
        a.setOperationNo(op.getOperationNo());
        a.setAttemptNo(attempt);
        a.setFence(fence);
        a.setAttemptKind(query ? "QUERY" : "PUBLISH");
        a.setProviderRequestRef(op.getOperationNo());
        a.setProviderTaskRef(task);
        a.setStartedAt(now());
        a.setOutcome("RUNNING");
        db.attempts.insert(a);
        db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .set("current_attempt_no", attempt)
                        .set("fence", fence)
                        .set("status", "RUNNING")
                        .set("lease_until", now().plusSeconds(60))
                        .set("next_recovery_at", now().plusSeconds(60)));
        db.runs.update(
                Wrappers.<CimSyncRun>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", r.getId())
                        .set("status", "RUNNING")
                        .set("version", r.getVersion() + 1)
                        .set("next_recovery_at", now().plusSeconds(60)));
        var publication =
                new Publication(
                        op.getOperationNo(),
                        r.getGeneration(),
                        r.getEntityNo(),
                        r.getStoreNo(),
                        r.getConnectionNo(),
                        c.connection().getExternalStoreRef(),
                        "STORE",
                        grant.getAuthorizationNo(),
                        grant.getCredentialRef(),
                        snapshot.getContentHash(),
                        db.document(snapshot));
        return new Dispatch(
                r.getRunNo(),
                r.getEntityNo(),
                r.getStoreNo(),
                r.getScopeNo(),
                publication,
                db.gateway(c),
                attempt,
                fence,
                task,
                query);
    }

    private boolean valid(Dispatch d, Result result) {
        try {
            require(
                    result != null
                            && d.publication().operationNo().equals(result.operationNo())
                            && d.publication().generation() == result.generation()
                            && d.publication().contentHash().equals(result.contentHash())
                            && result.outcome() != null);
            text(result.evidenceRef(), 256);
            if (result.taskRef() != null) text(result.taskRef(), 128);
            require(result.items() != null && result.items().size() <= 10000);
            var keys = new HashSet<String>();
            for (var i : result.items()) {
                require(i != null);
                text(i.objectType(), 32);
                text(i.objectKey(), 128);
                text(i.result(), 24);
                text(i.evidenceRef(), 256);
                if (i.externalRef() != null) text(i.externalRef(), 128);
                if (i.sourceRef() != null) text(i.sourceRef(), 128);
                if (i.errorCode() != null) text(i.errorCode(), 64);
                require(keys.add(i.objectType() + ":" + i.objectKey()));
                require(
                        Set.of(
                                        "SUCCEEDED",
                                        "PARTIAL",
                                        "FAILED",
                                        "CANCELLED",
                                        "UNKNOWN",
                                        "SUBMITTED",
                                        "RUNNING")
                                .contains(i.result()));
                if (result.allWritesTerminal())
                    require(
                            Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED")
                                    .contains(i.result()));
                if (result.outcome() == Outcome.SUCCEEDED) require("SUCCEEDED".equals(i.result()));
            }
            return true;
        } catch (RuntimeException bad) {
            return false;
        }
    }

    private void finish(Dispatch d, Result raw) {
        Result result = valid(d, raw) ? raw : null;
        String outcome = result == null ? "UNKNOWN" : result.outcome().name();
        var c = db.scope(d.entity(), d.store(), d.scopeNo(), true);
        var r = db.run(d.entity(), d.store(), d.runNo(), true);
        var op =
                db.operations.selectOne(
                        Wrappers.<CimOperation>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", d.publication().operationNo())
                                .last("FOR UPDATE"));
        var a =
                db.attempts.selectOne(
                        Wrappers.<CimOperationAttempt>query()
                                .eq("tenant_no", "MAIN")
                                .eq("operation_no", op.getOperationNo())
                                .eq("attempt_no", d.attempt())
                                .last("FOR UPDATE"));
        require(a != null);
        boolean current =
                Objects.equals(op.getFence(), d.fence())
                        && Objects.equals(op.getCurrentAttemptNo(), d.attempt())
                        && Objects.equals(c.scope().getActiveOperationNo(), op.getOperationNo())
                        && Objects.equals(c.scope().getGeneration(), d.publication().generation());
        if (!current) {
            if (result != null)
                db.attempts.update(
                        Wrappers.<CimOperationAttempt>update()
                                .eq("tenant_no", "MAIN")
                                .eq("id", a.getId())
                                .isNull("late_evidence_ref")
                                .set("late_evidence_ref", result.evidenceRef())
                                .set("late_outcome", outcome));
            return;
        }
        if (a.getFinishedAt() != null) return;
        boolean terminal =
                result != null
                        && result.allWritesTerminal()
                        && Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(outcome);
        String status =
                terminal ? outcome : ("SUBMITTED".equals(outcome) ? "SUBMITTED" : "UNKNOWN");
        String task = result == null ? d.task() : result.taskRef();
        db.attempts.update(
                Wrappers.<CimOperationAttempt>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", a.getId())
                        .isNull("finished_at")
                        .set("finished_at", now())
                        .set("outcome", outcome)
                        .set("provider_task_ref", task)
                        .set("response_ref", result == null ? null : result.evidenceRef())
                        .set("error_code", result == null ? "UNCONFIRMED_RESPONSE" : null));
        db.operations.update(
                Wrappers.<CimOperation>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", op.getId())
                        .eq("fence", d.fence())
                        .set("status", status)
                        .set("lease_until", null)
                        .set("next_recovery_at", terminal ? null : now().plusSeconds(30))
                        .set(
                                "original_evidence_ref",
                                result == null ? null : result.evidenceRef()));
        db.runs.update(
                Wrappers.<CimSyncRun>update()
                        .eq("tenant_no", "MAIN")
                        .eq("id", r.getId())
                        .set("status", status)
                        .set("version", r.getVersion() + 1)
                        .set("next_recovery_at", terminal ? null : now().plusSeconds(30))
                        .set("finished_at", terminal ? now() : null)
                        .set("failure_code", result == null ? "UNCONFIRMED_RESPONSE" : null));
        if (result != null)
            for (var item : result.items()) {
                var row = audit(new CimSyncRunItem(), "SYSTEM");
                row.setItemNo(BizKey.next("CIRI"));
                row.setEntityNo(d.entity());
                row.setRunNo(d.runNo());
                row.setAttemptNo(d.attempt());
                row.setObjectType(item.objectType());
                row.setObjectKeyHash(ChannelIdentity.hash(item.objectKey()));
                row.setExternalRef(item.externalRef());
                row.setSourceRef(item.sourceRef());
                row.setResult(item.result());
                row.setErrorCode(item.errorCode());
                row.setEvidenceRef(item.evidenceRef());
                db.items.insert(row);
            }
        if (terminal) {
            var confirmed = db.snapshot(d.entity(), d.scopeNo(), r.getSnapshotNo());
            boolean currentConfiguration =
                    Objects.equals(confirmed.getScopeVersion(), c.scope().getVersion())
                            && Objects.equals(
                                    confirmed.getConnectionRevisionNo(),
                                    c.connection().getActiveRevisionNo());
            String consistency =
                    "SUCCEEDED".equals(outcome)
                            ? (!currentConfiguration
                                    ? "STALE"
                                    : (Objects.equals(
                                                    c.scope().getDesiredSnapshotNo(),
                                                    r.getSnapshotNo())
                                            ? "CONSISTENT"
                                            : "PENDING"))
                            : ("PARTIAL".equals(outcome) ? "PARTIAL" : "UNKNOWN");
            var update =
                    Wrappers.<CimCatalogScope>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", c.scope().getId())
                            .eq("active_operation_no", op.getOperationNo())
                            .set("active_operation_no", null)
                            .set("remote_consistency", consistency)
                            .set("remote_pending_evidence", null);
            if ("SUCCEEDED".equals(outcome))
                update.set("last_confirmed_snapshot_no", r.getSnapshotNo());
            db.scopes.update(update);
        } else
            db.scopes.update(
                    Wrappers.<CimCatalogScope>update()
                            .eq("tenant_no", "MAIN")
                            .eq("id", c.scope().getId())
                            .set("remote_consistency", status)
                            .set(
                                    "remote_pending_evidence",
                                    result == null
                                            ? "UNCONFIRMED_RESPONSE"
                                            : result.evidenceRef()));
    }

    private void failQueued(CimSyncRun r) {
        db.commands.atomic(
                () -> {
                    db.scope(r.getEntityNo(), r.getStoreNo(), r.getScopeNo(), true);
                    db.runs.update(
                            Wrappers.<CimSyncRun>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("run_no", r.getRunNo())
                                    .eq("status", "QUEUED")
                                    .set("status", "FAILED")
                                    .set("failure_code", "SNAPSHOT_STALE")
                                    .set("finished_at", now())
                                    .set("next_recovery_at", null)
                                    .setSql("version=version+1"));
                    return null;
                });
    }

    public void onPublished(
            String e, String store, Owner owner, String ref, String revision, String event) {
        text(e, 32);
        text(store, 32);
        text(ref, 128);
        text(revision, 128);
        text(event, 128);
        require(owner != null);
        var all =
                db.commands.query(
                        () ->
                                db.scopes.selectList(
                                        Wrappers.<CimCatalogScope>query()
                                                .eq("tenant_no", "MAIN")
                                                .eq("entity_no", e)
                                                .eq("store_no", store)
                                                .eq("status", "ACTIVE")
                                                .orderByAsc("id")
                                                .last("LIMIT 101")));
        require(all.size() <= 100);
        record Notice(Owner owner, String ref, String revision, String event) {}
        var notice = new Notice(owner, ref, revision, event);
        for (var scope : all) {
            String endpoint = "CATALOG_EVENT:" + scope.getScopeNo();
            var replay =
                    db.commands.replay(
                            e, store, "SYSTEM", endpoint, event, notice, SyncPolicyVO.class);
            if (replay.isPresent()) continue;
            var before = db.commands.query(() -> db.scope(e, store, scope.getScopeNo(), false));
            var p = db.commands.query(() -> db.policy(scope.getScopeNo()));
            var refs = db.commands.query(() -> db.sourceRows(scope.getScopeNo()));
            if (!p.getEnabled()
                    || !p.getOnPublish()
                    || refs.stream()
                            .noneMatch(s -> s.sourceOwner() == owner && s.sourceRef().equals(ref)))
                continue;
            var changed =
                    refs.stream()
                            .map(
                                    s ->
                                            s.sourceOwner() == owner && s.sourceRef().equals(ref)
                                                    ? new SourceReq(
                                                            owner, ref, revision, s.priority())
                                                    : s)
                            .toList();
            var proofs = db.validate(before, changed);
            db.commands.execute(
                    e,
                    store,
                    "SYSTEM",
                    endpoint,
                    event,
                    notice,
                    SyncPolicyVO.class,
                    () -> {
                        var c = db.scope(e, store, scope.getScopeNo(), true);
                        db.unchanged(before, c);
                        db.evidenceFresh(proofs);
                        var current = db.policy(scope.getScopeNo());
                        require(current.getEnabled() && current.getOnPublish());
                        db.writeSources(e, scope.getScopeNo(), changed);
                        db.scopes.update(
                                Wrappers.<CimCatalogScope>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", c.scope().getId())
                                        .set("version", c.scope().getVersion() + 1)
                                        .set("remote_consistency", "STALE"));
                        db.policies.update(
                                Wrappers.<CimSyncPolicy>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", current.getId())
                                        .set("pending_event_ref", event)
                                        .set(
                                                "next_check_at",
                                                now().plusSeconds(current.getDebounceSeconds()))
                                        .set("version", current.getVersion() + 1));
                        return db.view(db.policy(scope.getScopeNo()));
                    },
                    SyncPolicyVO::policyNo);
        }
    }

    private boolean automatic(CimSyncPolicy p) {
        var scope =
                db.commands.query(
                        () ->
                                db.scope(p.getEntityNo(), p.getStoreNo(), p.getScopeNo(), false)
                                        .scope());
        String key =
                "AUTO:"
                        + ChannelIdentity.hash(
                                p.getPolicyNo() + ":" + p.getVersion() + ":" + p.getNextCheckAt());
        var preview =
                previews.preview(
                        p.getEntityNo(),
                        p.getStoreNo(),
                        p.getScopeNo(),
                        "SYSTEM",
                        new PreviewCatalogReq(scope.getVersion()),
                        key);
        // Compare stable business content, not refreshed evidence timestamps; never duplicate a
        // still queued/active version.
        boolean same =
                db.commands.query(
                        () -> {
                            var context =
                                    db.scope(
                                            p.getEntityNo(), p.getStoreNo(), p.getScopeNo(), false);
                            var current = context.scope();
                            if (current.getDesiredSnapshotNo() == null) return false;
                            var previous =
                                    db.snapshot(
                                            p.getEntityNo(),
                                            p.getScopeNo(),
                                            current.getDesiredSnapshotNo());
                            return previous.getContentHash().equals(preview.hash())
                                    && Objects.equals(
                                            previous.getScopeVersion(), current.getVersion())
                                    && Objects.equals(
                                            previous.getConnectionRevisionNo(),
                                            context.connection().getActiveRevisionNo())
                                    && db.runs.selectCount(
                                                    Wrappers.<CimSyncRun>query()
                                                            .eq("tenant_no", "MAIN")
                                                            .eq("scope_no", p.getScopeNo())
                                                            .eq(
                                                                    "snapshot_no",
                                                                    previous.getSnapshotNo())
                                                            .in(
                                                                    "status",
                                                                    List.of(
                                                                            "QUEUED",
                                                                            "RUNNING",
                                                                            "SUBMITTED",
                                                                            "UNKNOWN",
                                                                            "SUCCEEDED")))
                                            > 0;
                        });
        if (!same)
            runs.submit(
                    p.getEntityNo(),
                    p.getStoreNo(),
                    "SYSTEM",
                    new SubmitSyncRunReq(
                            p.getScopeNo(),
                            preview.snapshotNo(),
                            preview.hash(),
                            scope.getVersion(),
                            "SOURCE_PUBLICATION"),
                    key,
                    "AUTOMATIC",
                    p.getPendingEventRef(),
                    p.getVersion());
        db.commands.atomic(
                () -> {
                    var c = db.scope(p.getEntityNo(), p.getStoreNo(), p.getScopeNo(), true);
                    var current = db.policy(p.getScopeNo());
                    if (Objects.equals(current.getVersion(), p.getVersion())
                            && current.getEnabled())
                        db.policies.update(
                                Wrappers.<CimSyncPolicy>update()
                                        .eq("tenant_no", "MAIN")
                                        .eq("id", p.getId())
                                        .eq("version", p.getVersion())
                                        .set("pending_event_ref", null)
                                        .set(
                                                "next_check_at",
                                                p.getScheduledCheck() ? now().plusMinutes(5) : null)
                                        .set("version", p.getVersion() + 1));
                    return null;
                });
        return !same;
    }

    private void deferPolicy(CimSyncPolicy p) {
        db.commands.atomic(
                () -> {
                    db.scope(p.getEntityNo(), p.getStoreNo(), p.getScopeNo(), true);
                    db.policies.update(
                            Wrappers.<CimSyncPolicy>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("id", p.getId())
                                    .eq("version", p.getVersion())
                                    .set("next_check_at", now().plusMinutes(1)));
                    return null;
                });
    }
}
