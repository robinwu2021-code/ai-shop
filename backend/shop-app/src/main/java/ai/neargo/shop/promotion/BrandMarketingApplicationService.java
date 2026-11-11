package ai.neargo.shop.promotion;

import static ai.neargo.shop.promotion.BrandOrderMarketingService.*;
import static ai.neargo.shop.merchant.brand.ManagementRepository.str;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementScopeService;
import ai.neargo.shop.merchantbridge.BrandPromotionAuthority;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.*;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.ActivityPresetService;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Account entry for ordinary chain marketing; stored-value finance is a separate application boundary. */
@Service
public class BrandMarketingApplicationService {
    private static final Set<String> COUPON_ACTIONS = Set.of("saveCoupon", "publishCoupon", "pauseCoupon",
            "previewAudience", "issueCoupon");
    private final BrandPromotionAuthority authority;
    private final ManagementScopeService scopes;
    private final ManagementRepository db;
    private final BrandPromotionScopePort organization;
    private final ActivityPresetService presets;
    private final BrandOrderMarketingService orders;
    private final BrandPromotionCostService costs;
    private final BrandCouponApplicationService coupons;
    private final ObjectMapper json;
    public BrandMarketingApplicationService(BrandPromotionAuthority authority, ManagementScopeService scopes,
            ManagementRepository db, BrandPromotionScopePort organization, ActivityPresetService presets,
            BrandOrderMarketingService orders, BrandPromotionCostService costs, BrandCouponApplicationService coupons,
            ObjectMapper json) {
        this.authority = authority; this.scopes = scopes; this.db = db; this.organization = organization;
        this.presets = presets; this.orders = orders; this.costs = costs; this.coupons = coupons; this.json = json;
    }

    public Map<String,Object> context(Actor a, String m, String b) {
        var actor = authority.context(a, m, b);
        var facts = organization.find(a.tenantNo(), m, b, actor.entityNo());
        var actions = new ArrayList<String>();
        var readable = new ArrayList<String>();
        if (authority.can(a, m, b, "promotion.activity.read")) readable.add("activities");
        if (authority.can(a, m, b, "promotion.coupon.read")) { readable.add("coupons"); readable.add("coupon-issues"); }
        if (authority.can(a, m, b, "promotion.cost.rule.manage")) {
            readable.add("cost-rules"); actions.add("saveRule"); actions.add("publishRule");
        } else if (authority.can(a, m, b, "promotion.activity.publish")
                || authority.can(a, m, b, "promotion.coupon.manage")) readable.add("cost-rules");
        if (authority.can(a, m, b, "promotion.activity.manage")) { actions.add("saveActivity"); actions.add("pauseActivity"); }
        if (authority.can(a, m, b, "promotion.activity.publish")) actions.add("publishActivity");
        if (authority.can(a, m, b, "promotion.coupon.manage")) {
            actions.add("saveCoupon"); actions.add("publishCoupon"); actions.add("pauseCoupon");
        }
        if (authority.can(a, m, b, "promotion.coupon.issue")) { actions.add("previewAudience"); actions.add("issueCoupon"); }
        var entities = costEntities(a, m, b, actor);
        if (!entities.isEmpty()) readable.add("cost-statements");
        if (entities.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("canGenerate")))) actions.add("generateStatement");
        if (entities.stream().anyMatch(e -> Boolean.TRUE.equals(e.get("canConfirm")))) {
            actions.add("confirmStatement"); actions.add("voidStatement");
        }
        var executable = presets.list(actor, "ACTIVITY", "ORDER", null, null, 1, 100).records().stream()
                .anyMatch(p -> p.templateCode().equals("AP009") && p.available());
        var result = new LinkedHashMap<String,Object>();
        result.put("managementNo", m); result.put("brandNo", b); result.put("legalEntityNo", actor.entityNo());
        result.put("revision", Long.toString(facts.authorityRevision())); result.put("currency", "CNY");
        result.put("currencyScale", 2); result.put("marketZone", "Asia/Shanghai"); result.put("actions", actions); result.put("readable", readable);
        var capabilities = new ArrayList<String>();
        if (readable.contains("activities") || readable.contains("coupons") || actions.contains("saveActivity")
                || actions.contains("saveCoupon")) capabilities.add("marketing");
        if (actions.contains("issueCoupon") || readable.contains("coupon-issues")) capabilities.add("issue");
        if (readable.contains("cost-rules") || readable.contains("cost-statements")) capabilities.add("finance");
        result.put("capabilities", capabilities);
        result.put("supportedActivityTypes", executable ? List.of("reduction") : List.of());
        result.put("supportedCouponTypes", List.of("cashCoupon"));
        result.put("audiences", List.of(Map.of("type", "ALL", "ref", "", "label", "全部品牌会员")));
        result.put("costEntities", entities);
        result.put("stores", facts.stores().stream().map(s -> Map.of("id", s.storeNo(), "entityNo", s.entityNo(),
                "entityName", entityName(a.tenantNo(), s.entityNo()), "name", storeName(a.tenantNo(), s.storeNo()),
                "status", "active", "canParticipate", "CN".equals(s.market()) && authority.canParticipate(a, m, s.storeNo(), s.entityNo()))).toList());
        return result;
    }

    public List<Map<String,Object>> list(Actor a, String m, String b, String kind) {
        return switch (kind) {
            case "activities" -> orders.list(authority.resolve(a, m, b, "promotion.activity.read"));
            case "coupons" -> coupons.list(authority.resolve(a, m, b, "promotion.coupon.read"));
            case "coupon-issues" -> coupons.issues(authority.resolve(a, m, b, "promotion.coupon.read"));
            case "cost-rules" -> {
                boolean manage = authority.can(a, m, b, "promotion.cost.rule.manage");
                var actor = authority.resolve(a, m, b, manage ? "promotion.cost.rule.manage"
                        : authority.can(a, m, b, "promotion.activity.publish") ? "promotion.activity.publish"
                        : "promotion.coupon.manage");
                yield costs.listRules(actor).stream().filter(r -> manage || r.status().equals("PUBLISHED"))
                        .map(this::ruleView).toList();
            }
            case "cost-statements" -> {
                var actor = authority.context(a, m, b);
                var rows = new ArrayList<Map<String,Object>>();
                for (var entity : costEntities(a, m, b, actor)) {
                    String no = (String)entity.get("entityNo");
                    costs.list(authority.financial(a, m, b, no, "promotion.cost.read"), no)
                            .forEach(s -> rows.add(statementView(a, m, s)));
                }
                yield rows;
            }
            default -> throw BizException.of(ErrorCode.NOT_FOUND);
        };
    }

    public Map<String,Object> execute(Actor a, String m, String b, String action, Object payload,
            String expectedRevision, String key) {
        Map<String,Object> p = json.convertValue(payload, new TypeReference<>() { });
        ContractChecks.id(key);
        var actor = authorize(a, m, b, action, p);
        if (COUPON_ACTIONS.contains(action)) {
            boolean original = false;
            try { coupons.commandAction(actor, key); original = true; }
            catch (BizException missing) { if (missing.errorCode() != ErrorCode.NOT_FOUND) throw missing; }
            if (!original && !Long.toString(ManagementRepository.num(scopes.space(a, m), "auth_revision"))
                    .equals(expectedRevision)) throw BizException.of(ErrorCode.CONFLICT);
            if (original && "COMPLETED".equals(coupons.getResult(actor, key).get("state"))) {
                return coupons.execute(actor, action, p, key);
            }
            publication(a, m, b, actor, action, p);
            return coupons.execute(actor, action, p, key);
        }
        return db.write(() -> {
            scopes.lock(a, m);
            actorCheck(a, m, b, action, p);
            String id = receiptId(a, m, b, key);
            String hash = ManagementRepository.hash(db.encode(List.of(action, new TreeMap<>(p), expectedRevision)));
            var saved = receipt(a, m, b, id);
            if (saved != null) {
                if (!hash.equals(str(saved, "request_hash"))) throw BizException.of(ErrorCode.CONFLICT);
                return db.decode(saved.get("response_json"));
            }
            // Current authority is checked again under the management authority lock before any mutation.
            if (!Long.toString(ManagementRepository.num(scopes.space(a, m), "auth_revision")).equals(expectedRevision)) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            Map<String,Object> response;
            String ownerKey = ActivityCommandDeduplicator.hash(id + "|" + action);
            if (Set.of("saveActivity", "publishActivity", "pauseActivity").contains(action)) {
                publication(a, m, b, actor, action, p);
                response = orders.execute(actor, action, payload, ownerKey);
            } else response = costCommand(a, m, b, actor, action, payload, ownerKey);
            db.update("INSERT INTO pmt_brand_marketing_command(tenant_no,command_id,management_no,brand_no,entity_no,"
                    + "actor_no,action,request_hash,response_json,created_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    a.tenantNo(), id, m, b, actor.entityNo(), a.actorKey(), action, hash, db.encode(response),
                    Timestamp.from(Instant.now()));
            return response;
        });
    }

    public Map<String,Object> result(Actor a, String m, String b, String key) {
        ContractChecks.id(key); scopes.resolve(a, m);
        var row = receipt(a, m, b, receiptId(a, m, b, key));
        if (row != null) {
            String action = str(row, "action");
            var response = db.decode(row.get("response_json"));
            if (Set.of("generateStatement", "confirmStatement", "voidStatement").contains(action)) {
                authority.financial(a, m, b, text(response, "entityNo"), "promotion.cost.read");
            } else authority.resolve(a, m, b, permission(action));
            return response;
        }
        // This is a read of the original accepted intent. No RPC or command re-execution occurs here.
        var actor = authority.context(a, m, b);
        String action = coupons.commandAction(actor, key);
        return coupons.getResult(authority.resolve(a, m, b, permission(action)), key);
    }

    private void actorCheck(Actor a, String m, String b, String action, Map<String,Object> p) { authorize(a, m, b, action, p); }
    private ActivityActor authorize(Actor a, String m, String b, String action, Map<String,Object> p) {
        if (Set.of("generateStatement", "confirmStatement", "voidStatement").contains(action)) {
            return authority.financial(a, m, b, text(p, "entityNo"),
                    action.equals("generateStatement") ? "promotion.cost.authorize" : "promotion.cost.confirm");
        }
        return authority.resolve(a, m, b, permission(action));
    }
    private static String permission(String action) {
        return switch (action) {
            case "saveActivity", "pauseActivity" -> "promotion.activity.manage";
            case "publishActivity" -> "promotion.activity.publish";
            case "saveCoupon", "publishCoupon", "pauseCoupon" -> "promotion.coupon.manage";
            case "previewAudience", "issueCoupon" -> "promotion.coupon.issue";
            case "saveRule", "publishRule" -> "promotion.cost.rule.manage";
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }
    private void publication(Actor a, String m, String b, ActivityActor actor, String action, Map<String,Object> p) {
        if (action.equals("publishActivity")) {
            var v = orders.get(actor, text(p, "activityId"));
            authority.authorizePublication(a, m, b, v.definition().storeParticipationScope().refs(), text(p, "costRuleVersionNo"));
        } else if (action.equals("publishCoupon")) {
            var v = coupons.detail(actor, text(p, "id"));
            authority.authorizePublication(a, m, b, strings(v, "storeIds"), text(p, "costRuleVersionNo"));
        }
    }
    private Map<String,Object> costCommand(Actor a, String m, String b, ActivityActor actor, String action,
            Object payload, String key) {
        return switch (action) {
            case "saveRule" -> {
                var input = (BrandMarketingCommandInputs.SaveRule) payload;
                yield success(ruleView(costs.saveRule(actor, new SaveRule(input.name(), input.currency(),
                        input.headquartersBasisPoints(), input.storeBasisPoints()), key)));
            }
            case "publishRule" -> {
                var input = (BrandMarketingCommandInputs.PublishRule) payload;
                authority.requireEntity(a, m, actor.entityNo(), "promotion.cost.authorize");
                yield success(ruleView(costs.publishRule(actor, input.ruleId(), input.ruleRevision(), key)));
            }
            case "generateStatement" -> {
                var input = (BrandMarketingCommandInputs.GenerateStatement) payload;
                yield success(statementView(a, m, costs.generate(actor, input.entityNo(),
                        new GenerateStatement(input.currency(), input.throughAt()), key)));
            }
            case "confirmStatement" -> {
                var input = (BrandMarketingCommandInputs.StatementRevision) payload;
                yield success(statementView(a, m, costs.confirm(actor, input.entityNo(), input.statementId(),
                        new RevisionCommand(input.statementRevision()), key)));
            }
            case "voidStatement" -> {
                var input = (BrandMarketingCommandInputs.StatementRevision) payload;
                yield success(statementView(a, m, costs.voidStatement(actor, input.entityNo(), input.statementId(),
                        new RevisionCommand(input.statementRevision()), key)));
            }
            default -> throw BizException.of(ErrorCode.BAD_REQUEST);
        };
    }
    private Map<String,Object> ruleView(RuleView r) {
        var v = new LinkedHashMap<String,Object>();
        v.put("id", r.ruleVersionNo()); v.put("versionNo", r.ruleVersionNo()); v.put("name", r.title());
        v.put("type", r.headquartersBasisPoints() == 10000 ? "HQ" : r.storeBasisPoints() == 10000 ? "STORE" : "RATIO");
        v.put("status", r.status().equals("PUBLISHED") ? "confirmed" : "draft");
        v.put("headquartersBasisPoints", r.headquartersBasisPoints()); v.put("storeBasisPoints", r.storeBasisPoints());
        v.put("revision", Long.toString(r.revision())); v.put("currency", r.currency());
        v.put("effectiveAt", r.publishedAt() == null ? null : r.publishedAt().toString()); return v;
    }
    private Map<String,Object> statementView(Actor a, String m, StatementView s) {
        var v = new LinkedHashMap<String,Object>(); v.put("id", s.statementNo());
        v.put("period", s.throughAt().toString()); v.put("status", switch (s.status()) {
            case "DRAFT" -> "pending_confirmation"; case "CONFIRMED" -> "confirmed"; default -> "void";
        });
        v.put("currency", s.currency()); v.put("currencyScale", Currency.getInstance(s.currency()).getDefaultFractionDigits());
        v.put("totalMinor", s.totalMinor()); v.put("headquartersMinor", s.headquartersMinor()); v.put("storeMinor", s.storeMinor());
        v.put("discrepancyMinor", s.discrepancyMinor()); v.put("entityNo", s.expenseEntityNo());
        v.put("entityName", entityName(a.tenantNo(), s.expenseEntityNo())); v.put("revision", Long.toString(s.revision()));
        v.put("canConfirm", s.status().equals("DRAFT") && authority.canEntity(a, m, s.expenseEntityNo(), "promotion.cost.confirm"));
        v.put("lines", s.facts().stream().map(f -> {
            var line = new LinkedHashMap<String,Object>(); line.put("id", f.factNo()); line.put("storeId", f.storeNo());
            line.put("storeName", storeName(a.tenantNo(), f.storeNo())); line.put("entityName", entityName(a.tenantNo(), f.consumerEntityNo()));
            line.put("sourceId", f.sourceNo()); line.put("sourceType", f.sourceType()); line.put("originalSourceId", f.orderNo());
            line.put("refundSourceId", f.eventKind().equals("REFUND") ? f.eventNo() : null);
            line.put("ruleRevision", f.costRuleVersionNo()); line.put("totalMinor", f.amountMinor());
            line.put("headquartersMinor", f.headquartersMinor()); line.put("storeMinor", f.storeMinor());
            line.put("discrepancyMinor", 0); return line;
        }).toList()); return v;
    }
    private List<Map<String,Object>> costEntities(Actor a, String m, String b, ActivityActor actor) {
        var ids = new TreeSet<String>(); ids.add(actor.entityNo());
        organization.find(a.tenantNo(), m, b, actor.entityNo()).stores().forEach(s -> ids.add(s.entityNo()));
        db.rows("SELECT DISTINCT s.consumer_entity_no AS entity_no FROM pmt_brand_cost_source s JOIN pmt_brand_cost_snapshot v "
                + "ON v.tenant_no=s.tenant_no AND v.resource_type=s.source_type AND v.resource_version=s.resource_version "
                + "WHERE v.tenant_no=? AND v.management_no=? AND v.brand_no=? AND v.issuer_entity_no=?",
                a.tenantNo(), m, b, actor.entityNo()).forEach(r -> ids.add(str(r, "entity_no")));
        return ids.stream().filter(e -> authority.canEntity(a, m, e, "promotion.cost.read")).map(e -> {
            var item = new LinkedHashMap<String,Object>(); item.put("entityNo", e); item.put("entityName", entityName(a.tenantNo(), e));
            item.put("canGenerate", authority.canEntity(a, m, e, "promotion.cost.authorize"));
            item.put("canConfirm", authority.canEntity(a, m, e, "promotion.cost.confirm")); return (Map<String,Object>)item;
        }).toList();
    }
    private String entityName(String tenant, String entity) {
        var row = db.one("SELECT name FROM mch_entity WHERE tenant_no=? AND entity_no=? AND deleted=0", tenant, entity);
        return row == null ? entity : str(row, "name");
    }
    private String storeName(String tenant, String store) {
        var row = db.one("SELECT name FROM mch_store WHERE tenant_no=? AND store_no=? AND deleted=0", tenant, store);
        return row == null ? store : str(row, "name");
    }
    private Map<String,Object> receipt(Actor a, String m, String b, String id) {
        return db.one("SELECT * FROM pmt_brand_marketing_command WHERE tenant_no=? AND command_id=? "
                + "AND management_no=? AND brand_no=? AND actor_no=?", a.tenantNo(), id, m, b, a.actorKey());
    }
    private static String receiptId(Actor a, String m, String b, String key) {
        return ManagementRepository.hash(String.join("|", a.tenantNo(), a.actorKey(), m, b, key));
    }
}
