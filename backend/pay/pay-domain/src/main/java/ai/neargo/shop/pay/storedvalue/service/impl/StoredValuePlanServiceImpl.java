package ai.neargo.shop.pay.storedvalue.service.impl;

import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.audit;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.digest;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.instant;
import static ai.neargo.shop.pay.storedvalue.policy.StoredValueFacts.no;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargePlanMapper;
import ai.neargo.shop.pay.mapper.storedvalue.SvRechargePlanVersionMapper;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlan;
import ai.neargo.shop.pay.storedvalue.entity.SvRechargePlanVersion;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.pay.storedvalue.service.StoredValuePlanService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Create;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.Status;
import ai.neargo.shop.spi.storedvalue.StoredValuePlans.View;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class StoredValuePlanServiceImpl implements StoredValuePlanService {
    @org.springframework.beans.factory.annotation.Value("${shop.pay.stored-value.governance-enabled:false}")
    private boolean governance;

    private final SvRechargePlanMapper plans;
    private final SvRechargePlanVersionMapper versions;
    private final EntitlementCommandDeduplicator commands;
    private final StoredValueCursorCodec cursors;
    private final PayEntitlementFeatureGate features;
    private final ObjectMapper json;

    public StoredValuePlanServiceImpl(SvRechargePlanMapper plans, SvRechargePlanVersionMapper versions,
            EntitlementCommandDeduplicator commands, StoredValueCursorCodec cursors,
            PayEntitlementFeatureGate features, ObjectMapper json) {
        this.plans = plans; this.versions = versions; this.commands = commands;
        this.cursors = cursors; this.features = features; this.json = json;
    }

    @Override @Transactional("payTxManager")
    public View create(CatalogScope scope, Create c, String key) {
        merchant(scope); ContractChecks.id(key); features.requireStoredValue();
        String planNo = no("SP", List.of(scope.tenantNo(), scope.entityNo(), scope.actorNo(), key), json);
        return commands.execute(scope, "SV_PLAN_CREATE", key, c, planNo, View.class, () -> {
            if(governance)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var at = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
            var context = context(scope, key);
            var p = new SvRechargePlan(); audit(p, context, at);
            p.setPlanNo(planNo); p.setEntityNo(scope.entityNo()); p.setStoreNo(c.storeNo());
            p.setName(c.name()); p.setCurrency(c.currency()); p.setStatus("ACTIVE");
            p.setCurrentVersionNo(no("SPV", List.of(planNo, 1), json));
            var v = new SvRechargePlanVersion(); audit(v, context, at);
            v.setVersionNo(p.getCurrentVersionNo()); v.setPlanNo(planNo); v.setVersionIndex(1);
            v.setPaidAmountMinor(c.paidAmountMinor()); v.setBonusAmountMinor(c.bonusAmountMinor());
            v.setScopeSnapshot(json.writeValueAsString(new RedemptionScope("STORES", List.of(c.storeNo()))));
            v.setSchemaVersion(1); v.setSnapshotHash(digest(c, json));
            try { plans.insert(p); versions.insert(v); }
            catch (DuplicateKeyException duplicate) { throw BizException.of(ErrorCode.CONFLICT); }
            return view(p, v);
        });
    }

    @Override public View get(CatalogScope scope, String store, String no) {
        merchant(scope); return view(owned(scope, store, no, false));
    }

    @Override public CursorPage<View> list(CatalogScope scope, String store, String status, String cursor, int size) {
        merchant(scope); ContractChecks.id(store);
        ContractChecks.require(size >= 1 && size <= 100);
        ContractChecks.require(status == null || List.of("ACTIVE", "INACTIVE").contains(status));
        String filters = digest(List.of(scope.entityNo(), store, status == null ? "" : status), json);
        var after = cursors.decode(cursor, "SV_PLANS", scope.tenantNo(), scope.actorNo(), filters);
        var query = Wrappers.<SvRechargePlan>lambdaQuery().eq(SvRechargePlan::getTenantNo, scope.tenantNo())
                .eq(SvRechargePlan::getEntityNo, scope.entityNo()).eq(SvRechargePlan::getStoreNo, store)
                .eq(status != null, SvRechargePlan::getStatus, status);
        if(governance)query.apply("EXISTS (SELECT 1 FROM sv_release r WHERE CAST(r.tenant_no AS BINARY(256))=CAST(sv_recharge_plan.tenant_no AS BINARY(256)) AND CAST(r.release_no AS BINARY(256))=CAST(sv_recharge_plan.migrated_release_no AS BINARY(256)) AND r.source_kind='V2_ACTIVITY')");
        if (after != null) {
            var at = LocalDateTime.ofInstant(Instant.ofEpochMilli(after.occurredAtMillis()), ZoneOffset.UTC);
            query.and(q -> q.lt(SvRechargePlan::getCreatedAt, at).or(x -> x.eq(SvRechargePlan::getCreatedAt, at)
                    .lt(SvRechargePlan::getPlanNo, after.sortKey())));
        }
        var rows = plans.selectList(query.orderByDesc(SvRechargePlan::getCreatedAt, SvRechargePlan::getPlanNo)
                .last("LIMIT " + (size + 1)));
        boolean more = rows.size() > size;
        var shown = rows.subList(0, Math.min(size, rows.size()));
        String next = null;
        if (more) {
            var last = shown.getLast();
            next = cursors.encode("SV_PLANS", scope.tenantNo(), scope.actorNo(), filters,
                    instant(last.getCreatedAt()).toEpochMilli(), last.getPlanNo());
        }
        return new CursorPage<>(shown.stream().map(this::view).toList(), next);
    }

    @Override @Transactional("payTxManager")
    public View status(CatalogScope scope, String planNo, Status c, String key) {
        merchant(scope); ContractChecks.id(key);
        return commands.execute(scope, "SV_PLAN_STATUS", key, List.of(planNo, c), planNo, View.class, () -> {
            if(governance)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            var p = owned(scope, c.storeNo(), planNo, true);
            if (p.getVersion() != c.expectedRevision()) throw BizException.of(ErrorCode.CONFLICT);
            if (plans.updateStatus(scope.tenantNo(), scope.entityNo(), c.storeNo(), planNo,
                    c.status(), c.expectedRevision(), scope.actorNo(),
                    LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS)) != 1) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            return view(owned(scope, c.storeNo(), planNo, false));
        });
    }

    private SvRechargePlan owned(CatalogScope scope, String store, String no, boolean lock) {
        ContractChecks.id(store); ContractChecks.id(no);
        var q = Wrappers.<SvRechargePlan>lambdaQuery().eq(SvRechargePlan::getTenantNo, scope.tenantNo())
                .eq(SvRechargePlan::getEntityNo, scope.entityNo()).eq(SvRechargePlan::getStoreNo, store)
                .eq(SvRechargePlan::getPlanNo, no);
        if (lock) q.last("FOR UPDATE");
        var p = plans.selectOne(q);
        if (p == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return p;
    }

    private View view(SvRechargePlan p) {
        var v = versions.selectOne(Wrappers.<SvRechargePlanVersion>lambdaQuery()
                .eq(SvRechargePlanVersion::getTenantNo, p.getTenantNo())
                .eq(SvRechargePlanVersion::getPlanNo, p.getPlanNo())
                .eq(SvRechargePlanVersion::getVersionNo, p.getCurrentVersionNo()));
        if (v == null) throw BizException.of(ErrorCode.CONFLICT);
        return view(p, v);
    }
    private static View view(SvRechargePlan p, SvRechargePlanVersion v) {
        return new View(p.getPlanNo(), v.getVersionNo(), p.getVersion(), p.getStoreNo(), p.getName(), p.getCurrency(),
                v.getPaidAmountMinor(), v.getBonusAmountMinor(), Math.addExact(v.getPaidAmountMinor(), v.getBonusAmountMinor()),
                p.getStatus(), instant(p.getCreatedAt()), instant(p.getUpdatedAt()));
    }
    private static void merchant(CatalogScope scope) {
        if (scope.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
    }
    private static EntitlementContext context(CatalogScope scope, String key) {
        return new EntitlementContext(scope.tenantNo(), scope.actorNo(), key, "0".repeat(64));
    }
}
