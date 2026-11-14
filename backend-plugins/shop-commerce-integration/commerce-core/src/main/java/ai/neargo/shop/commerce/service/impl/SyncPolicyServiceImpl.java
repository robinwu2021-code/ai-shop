package ai.neargo.shop.commerce.service.impl;

import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.now;
import static ai.neargo.shop.commerce.service.impl.CatalogPersistence.require;

import ai.neargo.shop.commerce.dto.CatalogContracts.SaveSyncPolicyReq;
import ai.neargo.shop.commerce.dto.CatalogContracts.SyncPolicyVO;
import ai.neargo.shop.commerce.entity.CimSyncPolicy;
import ai.neargo.shop.commerce.service.SyncPolicyService;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;

import java.time.LocalDateTime;

/** 策略只控制后续意图，不能用开关解除远端未决发布。 */
public final class SyncPolicyServiceImpl implements SyncPolicyService {
    private final CatalogPersistence db;

    public SyncPolicyServiceImpl(CatalogPersistence db) {
        this.db = db;
    }

    public SyncPolicyVO getPolicy(String e, String store, String no) {
        return db.commands.query(
                () -> {
                    db.scope(e, store, no, false);
                    return db.view(db.policy(no));
                });
    }

    public SyncPolicyVO savePolicy(
            String e, String store, String no, String actor, SaveSyncPolicyReq req, String key) {
        require(
                req != null
                        && req.version() != null
                        && req.enabled() != null
                        && req.onPublish() != null
                        && req.onAvailability() != null
                        && req.scheduledCheck() != null
                        && req.debounceSeconds() != null);
        require(
                !req.onAvailability()
                        && req.debounceSeconds() >= 0
                        && req.debounceSeconds() <= 3600
                        && "QUERY_ONLY".equals(req.recoveryProfile()));
        return db.commands.execute(
                e,
                store,
                actor,
                "CATALOG_POLICY:" + no,
                key,
                req,
                SyncPolicyVO.class,
                () -> {
                    var c = db.scope(e, store, no, true);
                    if (req.enabled()) db.ready(c);
                    var p = db.policy(no);
                    require(req.version().equals(p.getVersion()));
                    require(
                            db.policies.update(
                                            Wrappers.<CimSyncPolicy>update()
                                                    .eq("tenant_no", "MAIN")
                                                    .eq("id", p.getId())
                                                    .eq("version", req.version())
                                                    .set("version", req.version() + 1)
                                                    .set("enabled", req.enabled())
                                                    .set("on_publish", req.onPublish())
                                                    .set("on_availability", false)
                                                    .set("scheduled_check", req.scheduledCheck())
                                                    .set("debounce_seconds", req.debounceSeconds())
                                                    .set("recovery_profile", req.recoveryProfile())
                                                    .set("next_check_at", nextCheck(p, req))
                                                    .set("updated_by", actor)
                                                    .set("updated_at", now()))
                                    == 1);
                    return db.view(db.policy(no));
                },
                SyncPolicyVO::policyNo);
    }

    private static LocalDateTime nextCheck(CimSyncPolicy previous, SaveSyncPolicyReq request) {
        if (!request.enabled()) return null;
        if (request.onPublish() && (!previous.getEnabled() || !previous.getOnPublish()))
            return now().plusSeconds(request.debounceSeconds());
        if (previous.getEnabled() && previous.getNextCheckAt() != null)
            return previous.getNextCheckAt();
        return request.scheduledCheck() ? now().plusMinutes(5) : null;
    }
}
