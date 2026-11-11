package ai.neargo.shop.promotion.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService.Skip;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService.Snapshot;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchView;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.payclient.EntitlementBatchAppService;
import ai.neargo.shop.payclient.EntitlementManagementAppService;
import ai.neargo.shop.promotion.BrandCouponApplicationService;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Completion;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Intent;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Source;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.BrandCouponService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** RPC orchestration owns unknown-outcome recovery; its durable Core methods never encompass a remote call. */
@Service
public class BrandCouponApplicationServiceImpl implements BrandCouponApplicationService {
    private final BrandCouponService core;
    private final BrandAudienceSnapshotService audiences;
    private final BrandPromotionCostService costs;
    private final EntitlementManagementAppService templates;
    private final EntitlementBatchAppService batches;

    public BrandCouponApplicationServiceImpl(BrandCouponService core, BrandAudienceSnapshotService audiences,
                                            BrandPromotionCostService costs,
                                            EntitlementManagementAppService templates,
                                            EntitlementBatchAppService batches) {
        this.core = core;
        this.audiences = audiences;
        this.costs = costs;
        this.templates = templates;
        this.batches = batches;
    }

    @Override
    public Map<String, Object> execute(ActivityActor actor, String action, Map<String, Object> payload, String key) {
        var request = payload;
        var original = core.find(actor, action, request, key);
        if (original.isPresent() && "COMPLETED".equals(original.get().status())) return original.get().response();
        if (original.isEmpty() && "publishCoupon".equals(action)) {
            Source source = core.get(actor, (String) request.get("id"));
            costs.validate(actor, (String) request.get("costRuleVersionNo"), source.storeNos());
        }
        Intent intent = original.orElseGet(() -> core.prepare(actor, action, request, key));
        if ("COMPLETED".equals(intent.status())) return intent.response();
        return switch (action) {
            case "saveCoupon" -> create(actor, intent);
            case "publishCoupon" -> publish(actor, intent);
            case "pauseCoupon" -> pause(actor, intent);
            case "issueCoupon" -> issue(actor, intent);
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }

    private Map<String, Object> create(ActivityActor actor, Intent intent) {
        Source source = core.get(actor, intent.sourceNo());
        var created = templates.create(scope(actor), source.snapshot().definition(), intent.commandNo());
        var reply = new LinkedHashMap<>(flat(source));
        reply.put("nativeTemplateNo", created.templateNo());
        reply.put("revision", created.revision());
        reply.put("success", true);
        reply.put("state", "COMPLETED");
        return core.finish(actor, intent.commandNo(), reply,
                new Completion(created.templateNo(), null, created.revision(), "DRAFT"), true).response();
    }

    private Map<String, Object> publish(ActivityActor actor, Intent intent) {
        Source source = core.get(actor, intent.sourceNo());
        if (!Boolean.TRUE.equals(intent.checkpoint().get("policyConfigured"))) {
            var coupon = source.snapshot().coupon();
            templates.policy(scope(actor), source.nativeTemplateNo(),
                    new SavePolicy(0, coupon.limit(), null, coupon.perPerson(), true),
                    ActivityCommandDeduplicator.hash(intent.commandNo() + ":policy"));
            core.checkpoint(actor, intent.commandNo(), Map.of("policyConfigured", true));
        }
        long revision = ((Number) intent.request().get("expectedRevision")).longValue();
        var published = templates.publish(scope(actor), source.nativeTemplateNo(), revision, intent.commandNo());
        var reply = new LinkedHashMap<>(flat(source));
        reply.put("status", "published");
        reply.put("nativeVersionNo", published.versionNo());
        reply.put("revision", revision + 1);
        reply.put("costRuleVersionNo", source.costRuleVersionNo());
        reply.put("success", true);
        reply.put("state", "COMPLETED");
        return core.finish(actor, intent.commandNo(), reply,
                new Completion(source.nativeTemplateNo(), published.versionNo(), revision + 1, "ACTIVE"), true)
                .response();
    }

    private Map<String, Object> pause(ActivityActor actor, Intent intent) {
        Source source = core.get(actor, intent.sourceNo());
        long revision = ((Number) intent.request().get("expectedRevision")).longValue();
        var stopped = templates.status(scope(actor), source.nativeTemplateNo(), revision, "ENDED", intent.commandNo());
        var reply = new LinkedHashMap<>(flat(source));
        reply.put("status", "paused");
        reply.put("revision", stopped.revision());
        reply.put("success", true);
        reply.put("state", "COMPLETED");
        return core.finish(actor, intent.commandNo(), reply,
                new Completion(source.nativeTemplateNo(), source.nativeVersionNo(), stopped.revision(), "STOPPED"),
                true)
                .response();
    }

    private Map<String, Object> issue(ActivityActor actor, Intent intent) {
        Source source = core.get(actor, intent.sourceNo());
        String reference = (String) intent.request().get("audienceSnapshotNo");
        Snapshot snapshot = audiences.get(context(actor), reference, source.sourceNo(), source.nativeVersionNo());
        String no = (String) intent.checkpoint().get("batchNo");
        if (no == null) {
            var created = batches.create(scope(actor), new CreateBatch(source.nativeVersionNo(), reference),
                    intent.commandNo());
            no = created.batchNo();
            core.checkpoint(actor, intent.commandNo(), Map.of("batchNo", no));
        }
        BatchView batch = batches.resume(scope(actor), no, 100);
        var reply = batchFlat(source, snapshot, batch, intent);
        reply.put("commandNo", intent.commandNo());
        return core.finish(actor, intent.commandNo(), reply, null, batch.pendingCount() == 0).response();
    }

    @Override
    public Map<String, Object> getResult(ActivityActor actor, String key) {
        Intent intent = core.result(actor, key);
        if (intent.response() != null) return intent.response();
        return Map.of("success", true, "state", "PENDING", "commandNo", intent.commandNo(),
                "action", intent.action(), "id", intent.sourceNo());
    }

    @Override
    public String commandAction(ActivityActor actor, String key) { return core.commandAction(actor, key); }

    @Override
    public Map<String, Object> detail(ActivityActor actor, String sourceNo) { return flat(core.get(actor, sourceNo)); }

    @Override
    public List<Map<String, Object>> list(ActivityActor actor) {
        return core.list(actor).stream().map(this::flat).toList();
    }

    @Override
    public List<Map<String, Object>> issues(ActivityActor actor) {
        var rows = new ArrayList<Map<String, Object>>();
        for (Intent intent : core.issues(actor)) {
            var source = core.get(actor, intent.sourceNo());
            String no = (String) intent.checkpoint().get("batchNo");
            if (no == null) {
                var row = new LinkedHashMap<String, Object>();
                row.put("id", intent.commandNo()); row.put("commandNo", intent.commandNo());
                row.put("couponId", source.sourceNo()); row.put("couponName", source.snapshot().coupon().name());
                row.put("audienceLabel", "ALL"); row.put("createdAt", intent.createdAt().toString());
                row.put("currency", source.snapshot().coupon().currency());
                row.put("currencyScale", source.snapshot().coupon().currencyScale());
                row.put("state", "PENDING"); row.put("status", "processing");
                // The native owner may have applied the unknown RPC; an absent local checkpoint is not zero issuance.
                for (String field : List.of("issued", "skipped", "pending", "failed", "redeemed", "redeemedMinor")) {
                    row.put(field, null);
                }
                rows.add(row);
            } else {
                var owner = new ActivityActor(actor.tenantNo(), actor.entityNo(),
                        intent.actorNo(), actor.allowedStores(), actor.managementNo(), actor.brandNo());
                var snapshot = audiences.get(context(owner), (String) intent.request().get("audienceSnapshotNo"),
                        source.sourceNo(), source.nativeVersionNo());
                var row = batchFlat(source, snapshot, batches.get(scope(actor), no), intent);
                row.put("commandNo", intent.commandNo());
                rows.add(row);
            }
        }
        return List.copyOf(rows);
    }

    private Map<String, Object> flat(Source source) {
        var coupon = source.snapshot().coupon();
        var row = new LinkedHashMap<String, Object>();
        row.put("id", source.sourceNo());
        row.put("name", coupon.name());
        row.put("type", "cashCoupon");
        row.put("status", "ACTIVE".equals(source.status()) ? "published"
                : "STOPPED".equals(source.status()) ? "paused" : "draft");
        row.put("storeIds", source.storeNos());
        row.put("ruleLabel", source.costRuleVersionNo() == null ? "" : source.costRuleVersionNo());
        row.put("startsAt", null);
        row.put("endsAt", null);
        row.put("faceValueMinor", coupon.faceValueMinor());
        row.put("thresholdMinor", coupon.thresholdMinor());
        row.put("validDays", coupon.validDays());
        row.put("limit", coupon.limit());
        row.put("perPerson", coupon.perPerson());
        row.put("currency", coupon.currency());
        row.put("currencyScale", coupon.currencyScale());
        row.put("issuedCount", null);
        row.put("redeemedCount", null);
        row.put("activityId", "");
        row.put("revision", source.nativeRevision());
        row.put("costRuleVersionNo", source.costRuleVersionNo());
        row.put("sourceNo", source.sourceNo());
        row.put("nativeTemplateNo", source.nativeTemplateNo());
        row.put("nativeVersionNo", source.nativeVersionNo());
        return row;
    }

    private Map<String, Object> batchFlat(Source source, Snapshot snapshot, BatchView batch, Intent intent) {
        var row = new LinkedHashMap<String, Object>();
        row.put("success", true);
        row.put("state", batch.pendingCount() == 0 ? "COMPLETED" : "PENDING");
        row.put("id", batch.batchNo());
        row.put("batchId", batch.batchNo());
        row.put("couponId", source.sourceNo());
        row.put("couponName", source.snapshot().coupon().name());
        row.put("currency", source.snapshot().coupon().currency());
        row.put("currencyScale", source.snapshot().coupon().currencyScale());
        row.put("audienceLabel", "ALL");
        row.put("createdAt", intent.createdAt().toString());
        row.put("redeemed", null);
        row.put("redeemedMinor", null);
        row.put("status", batch.pendingCount() > 0 ? "processing" : "completed");
        row.put("plannedCount", snapshot.plannedCount());
        row.put("issued", batch.issuedCount());
        row.put("skipped", snapshot.preSkippedCount() + batch.skippedCount());
        row.put("failed", batch.failedCount());
        row.put("pending", batch.pendingCount());
        var reasons = new LinkedHashMap<String, Integer>();
        snapshot.preSkipReasons().forEach(skip -> reasons.merge(skip.reason(), skip.count(), Integer::sum));
        batch.results().stream().filter(result -> result.reasonCode() != null)
                .forEach(result -> reasons.merge(result.reasonCode(), 1, Integer::sum));
        row.put("skips", skips(reasons));
        row.put("documentNo", batch.documentNo());
        return row;
    }

    private List<Skip> skips(Map<String, Integer> reasons) {
        return reasons.entrySet().stream().map(entry -> new Skip(entry.getKey(), entry.getValue())).toList();
    }

    private CatalogScope scope(ActivityActor actor) {
        return new CatalogScope(actor.tenantNo(), actor.entityNo(), actor.actorNo(), false);
    }

    private BrandAudienceSnapshotService.Context context(ActivityActor actor) {
        return new BrandAudienceSnapshotService.Context(actor.tenantNo(), actor.managementNo(), actor.brandNo(),
                actor.entityNo(), actor.actorNo());
    }
}
