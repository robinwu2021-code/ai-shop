package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.CatalogContracts.CancelQueuedSyncReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SubmitSyncRunReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunItemVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncRunVO;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimSyncRun;
import ai.neargo.shop.commerce.entity.CimSyncRunItem;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.SyncRunService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.PageData;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Set;

/** 提交不直接发送；同一不可变预览转为可审计的排队意图。 */
public final class SyncRunServiceImpl implements SyncRunService {
    private final CatalogPersistence db;

    public SyncRunServiceImpl(CatalogPersistence db) {
        this.db = db;
    }

    public SyncRunVO submit(
            String e, String store, String actor, SubmitSyncRunReq req, String key) {
        return submit(e, store, actor, req, key, "MANUAL", null, null);
    }

    SyncRunVO submit(
            String e,
            String store,
            String actor,
            SubmitSyncRunReq req,
            String key,
            String trigger,
            String event,
            Long expectedPolicyVersion) {
        require(req != null && req.expectedScopeVersion() != null);
        text(req.reason(), 256);
        text(req.hash(), 64);
        var replay =
                db.commands.replay(e, store, actor, "CATALOG_SUBMIT", key, req, SyncRunVO.class);
        if (replay.isPresent()) return replay.get();
        var before = db.commands.query(() -> db.scope(e, store, req.scopeNo(), false));
        var snap = db.commands.query(() -> db.snapshot(e, req.scopeNo(), req.snapshotNo()));
        require(
                req.expectedScopeVersion().equals(before.scope().getVersion())
                        && req.hash().equals(snap.getContentHash()));
        db.revalidate(before, snap);
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_SUBMIT",
                key,
                req,
                SyncRunVO.class,
                () -> {
                    var c = db.scope(e, store, req.scopeNo(), true);
                    db.unchanged(before, c);
                    db.validSnapshot(c, snap);
                    db.ready(c);
                    if ("AUTOMATIC".equals(trigger)) {
                        var policy = db.policy(req.scopeNo());
                        require(
                                policy.getEnabled()
                                        && expectedPolicyVersion != null
                                        && expectedPolicyVersion.equals(policy.getVersion()));
                    }
                    var r = audit(new CimSyncRun(), "SYSTEM");
                    r.setRunNo(BizKey.next("CISR"));
                    r.setEntityNo(e);
                    r.setStoreNo(store);
                    r.setConnectionNo(c.connection().getConnectionNo());
                    r.setScopeNo(req.scopeNo());
                    r.setRunType("CATALOG_REPLACE");
                    r.setTriggerType(trigger);
                    r.setSourceEventRef(event);
                    r.setSnapshotNo(snap.getSnapshotNo());
                    r.setRequestHash(snap.getContentHash());
                    r.setStatus("QUEUED");
                    r.setNextRecoveryAt(now());
                    r.setVersion(0L);
                    r.setCreatedBy(actor);
                    r.setUpdatedBy(actor);
                    db.runs.insert(r);
                    db.scopes.update(
                            Wrappers.<CimCatalogScope>update()
                                    .eq("tenant_no", "MAIN")
                                    .eq("scope_no", req.scopeNo())
                                    .set("desired_snapshot_no", snap.getSnapshotNo())
                                    .set("remote_consistency", "PENDING"));
                    return db.view(r);
                },
                SyncRunVO::runNo);
    }

    public SyncRunVO getRun(String e, String store, String no) {
        return db.commands.query(() -> db.view(db.run(e, store, no, false)));
    }

    public PageData<SyncRunVO> listRuns(
            String e, String store, int page, int size, String connection, String status) {
        text(e, 32);
        text(store, 32);
        long offset = CommerceValidation.offset(page, size);
        if (connection != null) text(connection, 32);
        if (status != null)
            require(
                    Set.of(
                                    "QUEUED",
                                    "RUNNING",
                                    "SUBMITTED",
                                    "UNKNOWN",
                                    "SUCCEEDED",
                                    "PARTIAL",
                                    "FAILED",
                                    "CANCELLED")
                            .contains(status));
        return db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimSyncRun>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", e)
                                    .eq("store_no", store)
                                    .eq(connection != null, "connection_no", connection)
                                    .eq(status != null, "status", status);
                    long total = db.runs.selectCount(q);
                    return PageData.of(
                            db
                                    .runs
                                    .selectList(
                                            q.orderByDesc("id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(db::view)
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    public PageData<SyncRunItemVO> listItems(
            String e, String store, String no, int page, int size) {
        long offset = CommerceValidation.offset(page, size);
        return db.commands.query(
                () -> {
                    db.run(e, store, no, false);
                    var q =
                            Wrappers.<CimSyncRunItem>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", e)
                                    .eq("run_no", no);
                    long total = db.items.selectCount(q);
                    return PageData.of(
                            db
                                    .items
                                    .selectList(
                                            q.orderByAsc("attempt_no", "id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(
                                            r ->
                                                    new SyncRunItemVO(
                                                            r.getItemNo(),
                                                            r.getAttemptNo(),
                                                            r.getObjectType(),
                                                            r.getExternalRef(),
                                                            r.getSourceRef(),
                                                            r.getResult(),
                                                            r.getErrorCode(),
                                                            r.getEvidenceRef()))
                                    .toList(),
                            total,
                            page,
                            size);
                });
    }

    public SyncRunVO cancelQueued(
            String e, String store, String no, String actor, CancelQueuedSyncReq req, String key) {
        require(req != null && req.version() != null);
        text(req.reason(), 256);
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_CANCEL:" + no,
                key,
                req,
                SyncRunVO.class,
                () -> {
                    var read = db.run(e, store, no, false);
                    db.scope(e, store, read.getScopeNo(), true);
                    var r = db.run(e, store, no, true);
                    require(req.version().equals(r.getVersion()) && "QUEUED".equals(r.getStatus()));
                    require(
                            db.runs.update(
                                            Wrappers.<CimSyncRun>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", r.getId())
                                                    .eq("version", req.version())
                                                    .eq("status", "QUEUED")
                                                    .set("version", req.version() + 1)
                                                    .set("status", "CANCELLED")
                                                    .set("finished_at", now())
                                                    .set("next_recovery_at", null)
                                                    .set("updated_by", actor))
                                    == 1);
                    return db.view(db.run(e, store, no, false));
                },
                SyncRunVO::runNo);
    }
}
