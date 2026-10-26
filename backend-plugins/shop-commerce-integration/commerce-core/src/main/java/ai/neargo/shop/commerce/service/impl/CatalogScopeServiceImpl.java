package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.audit;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.text;

import ai.neargo.shop.commerce.dto.CatalogContracts.CatalogScopeVO;
import ai.neargo.shop.commerce.dto.CatalogContracts.CreateCatalogScopeReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SaveCatalogSourcesReq;
import ai.neargo.shop.commerce.entity.CimCatalogScope;
import ai.neargo.shop.commerce.entity.CimSyncPolicy;
import ai.neargo.shop.commerce.policy.CommerceValidation;
import ai.neargo.shop.commerce.service.CatalogScopeService;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.PageData;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.util.Set;

/** 单一全店范围拥有完整目录；来源配置修订不混入运行代次。 */
public final class CatalogScopeServiceImpl implements CatalogScopeService {
    private final CatalogPersistence db;

    public CatalogScopeServiceImpl(CatalogPersistence db) {
        this.db = db;
    }

    public CatalogScopeVO createScope(
            String e, String store, String actor, CreateCatalogScopeReq req, String key) {
        require(req != null);
        text(req.name(), 128);
        require("STORE".equals(req.remoteScopeKey()));
        var replay =
                db.commands.replay(
                        e, store, actor, "CATALOG_CREATE", key, req, CatalogScopeVO.class);
        if (replay.isPresent()) return replay.get();
        var before = db.commands.query(() -> db.connection(e, store, req.connectionNo(), false));
        var proofs = db.validate(before, req.sourceRefs());
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_CREATE",
                key,
                req,
                CatalogScopeVO.class,
                () -> {
                    var c = db.connection(e, store, req.connectionNo(), true);
                    db.unchanged(before, c);
                    db.evidenceFresh(proofs);
                    require(
                            db.scopes.selectCount(
                                            Wrappers.<CimCatalogScope>query()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("connection_no", req.connectionNo())
                                                    .eq("remote_scope_key", "STORE"))
                                    == 0);
                    var s = audit(new CimCatalogScope(), "SYSTEM");
                    s.setScopeNo(BizKey.next("CISCP"));
                    s.setEntityNo(e);
                    s.setStoreNo(store);
                    s.setConnectionNo(req.connectionNo());
                    s.setRemoteScopeKey("STORE");
                    s.setName(req.name());
                    s.setGeneration(0L);
                    s.setRemoteConsistency("UNKNOWN");
                    s.setStatus("ACTIVE");
                    s.setVersion(0L);
                    s.setCreatedBy(actor);
                    s.setUpdatedBy(actor);
                    db.scopes.insert(s);
                    db.writeSources(e, s.getScopeNo(), req.sourceRefs());
                    var p = audit(new CimSyncPolicy(), "SYSTEM");
                    p.setPolicyNo(BizKey.next("CISP"));
                    p.setEntityNo(e);
                    p.setStoreNo(store);
                    p.setScopeNo(s.getScopeNo());
                    p.setEnabled(false);
                    p.setOnPublish(true);
                    p.setOnAvailability(false);
                    p.setScheduledCheck(true);
                    p.setDebounceSeconds(30);
                    p.setRecoveryProfile("QUERY_ONLY");
                    db.policies.insert(p);
                    return db.view(s);
                },
                CatalogScopeVO::scopeNo);
    }

    public CatalogScopeVO getScope(String e, String store, String no) {
        return db.commands.query(() -> db.view(db.scope(e, store, no, false).scope()));
    }

    public PageData<CatalogScopeVO> listScopes(
            String e, String store, int page, int size, String connectionNo, String status) {
        text(e, 32);
        text(store, 32);
        long offset = CommerceValidation.offset(page, size);
        if (connectionNo != null) text(connectionNo, 32);
        if (status != null) require(Set.of("ACTIVE").contains(status));
        return db.commands.query(
                () -> {
                    var q =
                            Wrappers.<CimCatalogScope>query()
                                    .eq("tenant_no", "MAIN")
                                    .eq("entity_no", e)
                                    .eq("store_no", store)
                                    .eq(connectionNo != null, "connection_no", connectionNo)
                                    .eq(status != null, "status", status);
                    long n = db.scopes.selectCount(q);
                    return PageData.of(
                            db
                                    .scopes
                                    .selectList(
                                            q.orderByAsc("id")
                                                    .last("LIMIT " + size + " OFFSET " + offset))
                                    .stream()
                                    .map(db::view)
                                    .toList(),
                            n,
                            page,
                            size);
                });
    }

    public CatalogScopeVO saveSources(
            String e,
            String store,
            String no,
            String actor,
            SaveCatalogSourcesReq req,
            String key) {
        require(req != null && req.version() != null);
        var replay =
                db.commands.replay(
                        e, store, actor, "CATALOG_SOURCES:" + no, key, req, CatalogScopeVO.class);
        if (replay.isPresent()) return replay.get();
        var before = db.commands.query(() -> db.scope(e, store, no, false));
        require(req.version().equals(before.scope().getVersion()));
        var proofs = db.validate(before, req.sourceRefs());
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_SOURCES:" + no,
                key,
                req,
                CatalogScopeVO.class,
                () -> {
                    var c = db.scope(e, store, no, true);
                    db.unchanged(before, c);
                    db.evidenceFresh(proofs);
                    db.writeSources(e, no, req.sourceRefs());
                    require(
                            db.scopes.update(
                                            Wrappers.<CimCatalogScope>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("scope_no", no)
                                                    .eq("version", req.version())
                                                    .set("version", req.version() + 1)
                                                    .set("remote_consistency", "STALE")
                                                    .set("updated_by", actor)
                                                    .set("updated_at", now()))
                                    == 1);
                    return db.view(db.scope(e, store, no, false).scope());
                },
                CatalogScopeVO::scopeNo);
    }
}
