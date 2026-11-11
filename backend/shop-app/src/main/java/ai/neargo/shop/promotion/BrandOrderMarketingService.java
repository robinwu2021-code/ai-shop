package ai.neargo.shop.promotion;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.*;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.*;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** App adaptation for the executable ordinary ORDER fixed-cut model. Core still owns publication and budgets. */
@Service
public class BrandOrderMarketingService {
    private final ActivityVersionService activities;
    private final BrandPromotionCostService costs;
    private final JdbcTemplate db;
    public BrandOrderMarketingService(ActivityVersionService activities, BrandPromotionCostService costs,
            JdbcTemplate db) {
        this.activities = activities; this.costs = costs; this.db = db;
    }

    public List<Map<String,Object>> list(ActivityActor actor) {
        var result = new ArrayList<Map<String,Object>>();
        for (long page = 1; ; page++) {
            var batch = activities.list(actor, "ORDER", null, page, 100);
            batch.records().forEach(v -> result.add(view(actor, v)));
            if (page * 100 >= batch.total()) break;
        }
        return result;
    }

    public ActivityView get(ActivityActor actor, String id) { return activities.get(actor, id); }

    public Map<String,Object> execute(ActivityActor actor, String action, Object payload, String key) {
        if ("saveActivity".equals(action)) {
            var command = (BrandOrderActivityInput) payload;
            var stores = command.storeIds();
            long threshold = command.thresholdMinor();
            var definition = new UnifiedActivityDefinition(command.name(), "ORDER", "AP009", 0,
                    new RedemptionScope("STORES", stores), null,
                    new Schedule("ONE_OFF", command.startsAt(), command.endsAt(), "Asia/Shanghai", List.of(), null, null),
                    new Rule(1, new Trigger(threshold == 0 ? "NONE" : "AMOUNT", threshold == 0 ? null : threshold, null),
                            List.of(new Benefit("CUT", command.amountMinor(), null, null, null)), List.of()),
                    command.quotaLimit(), command.budgetMinor(),
                    command.perPerson() == null ? Integer.MAX_VALUE : command.perPerson(), "CNY");
            String id = command.activityId();
            Long revision = command.activityRevision();
            return success(view(actor, activities.saveDraft(selected(actor, stores),
                    new SaveDraft(id, revision, definition), key)));
        }
        String id;
        long revision;
        String rule = null;
        if (payload instanceof BrandMarketingCommandInputs.PublishActivity command) {
            id = command.activityId(); revision = command.activityRevision(); rule = command.costRuleVersionNo();
        } else {
            var command = (BrandMarketingCommandInputs.PauseActivity) payload;
            id = command.activityId(); revision = command.activityRevision();
        }
        var current = activities.get(actor, id);
        var stores = current.definition().storeParticipationScope().refs();
        if ("publishActivity".equals(action)) {
            costs.validate(actor, rule, stores);
            var published = activities.publish(selected(actor, stores), id, revision, key);
            costs.freeze(actor, rule, "ACTIVITY", id, published.versionNo(), stores);
        } else if ("pauseActivity".equals(action)) {
            activities.changeStatus(actor, id, revision, "PAUSED", "MERCHANT_REQUEST", key);
        } else throw BizException.of(ErrorCode.BAD_REQUEST);
        return success(view(actor, activities.get(actor, id)));
    }

    public Map<String,Object> view(ActivityActor actor, ActivityView v) {
        var d = v.definition();
        var out = new LinkedHashMap<String,Object>();
        out.put("id", v.activityNo()); out.put("name", d.name()); out.put("type", "reduction");
        out.put("status", switch (v.status()) {
            case "DRAFT" -> "draft"; case "RUNNING" -> "active"; case "PAUSED" -> "paused";
            default -> "ended";
        });
        out.put("storeIds", d.storeParticipationScope().refs()); out.put("ruleLabel", d.name());
        out.put("startsAt", d.schedule().startAt().toString());
        out.put("endsAt", d.schedule().endAt() == null ? null : d.schedule().endAt().toString());
        out.put("budgetMinor", d.budgetLimitMinor()); out.put("currency", d.currency());
        out.put("currencyScale", Currency.getInstance(d.currency()).getDefaultFractionDigits());
        out.put("revision", Long.toString(v.revision()));
        var rows = v.currentVersionNo() == null ? List.<String>of() : db.queryForList(
                "SELECT rule_version_no FROM pmt_brand_cost_snapshot WHERE tenant_no=? AND management_no=? "
                + "AND brand_no=? AND issuer_entity_no=? AND resource_type='ACTIVITY' AND resource_version=?",
                String.class, actor.tenantNo(), actor.managementNo(), actor.brandNo(), actor.entityNo(), v.currentVersionNo());
        out.put("costRuleVersionNo", rows.isEmpty() ? null : rows.getFirst());
        return out;
    }

    private static ActivityActor selected(ActivityActor a, List<String> stores) {
        if (stores.isEmpty() || !a.allowedStores().containsAll(stores)) throw BizException.of(ErrorCode.FORBIDDEN);
        return new ActivityActor(a.tenantNo(), a.entityNo(), a.actorNo(), Set.copyOf(stores), a.managementNo(), a.brandNo());
    }
    public static Map<String,Object> success(Map<String,Object> value) {
        var out = new LinkedHashMap<String,Object>(); out.put("success", true); out.putAll(value); return out;
    }
    public static String text(Map<String,Object> p, String field) {
        String s = optionalText(p, field);
        if (s == null || s.isBlank()) throw BizException.of(ErrorCode.BAD_REQUEST);
        return s;
    }
    public static String optionalText(Map<String,Object> p, String field) {
        Object v = p.get(field); if (v == null) return null;
        if (!(v instanceof String s)) throw BizException.of(ErrorCode.BAD_REQUEST); return s;
    }
    public static long number(Map<String,Object> p, String field) {
        Object v = p.get(field);
        if (!(v instanceof Number n) || n instanceof Float || n instanceof Double) throw BizException.of(ErrorCode.BAD_REQUEST);
        return n instanceof java.math.BigInteger big ? big.longValueExact() : n.longValue();
    }
    public static long revision(Map<String,Object> p, String field) {
        Object v = p.get(field);
        if (v instanceof String s) try { return Long.parseLong(s); } catch (NumberFormatException e) { throw BizException.of(ErrorCode.BAD_REQUEST); }
        return number(p, field);
    }
    public static long optionalNumber(Map<String,Object> p, String field, long fallback) {
        return p.get(field) == null ? fallback : number(p, field);
    }
    public static List<String> strings(Map<String,Object> p, String field) {
        if (!(p.get(field) instanceof List<?> list) || list.stream().anyMatch(x -> !(x instanceof String))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return list.stream().map(String.class::cast).toList();
    }
}
