package ai.neargo.shop.promotion.service.impl;

import static ai.neargo.shop.member.service.MemberScopeRepository.n;
import static ai.neargo.shop.member.service.MemberScopeRepository.s;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.service.BrandAudienceSnapshotService;
import ai.neargo.shop.member.service.MemberScopeRepository;
import ai.neargo.shop.promotion.cost.BrandPromotionCostService;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Completion;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Intent;
import ai.neargo.shop.promotion.dto.BrandCouponModels.SaveCoupon;
import ai.neargo.shop.promotion.dto.BrandCouponModels.Source;
import ai.neargo.shop.promotion.dto.BrandCouponModels.SourceSnapshot;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.BrandCouponService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort.Store;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Durable Core intentions survive unknown RPC outcomes without holding Core row locks across payment calls. */
@Service
public class BrandCouponServiceImpl implements BrandCouponService {
    private static final List<String> ACTIONS = List.of("saveCoupon", "publishCoupon", "previewAudience",
            "issueCoupon", "pauseCoupon");
    private final MemberScopeRepository db;
    private final BrandPromotionScopePort brands;
    private final BrandAudienceSnapshotService audiences;
    private final BrandPromotionCostService costs;
    private final ObjectMapper json;

    public BrandCouponServiceImpl(MemberScopeRepository db, BrandPromotionScopePort brands,
                                  BrandAudienceSnapshotService audiences, BrandPromotionCostService costs,
                                  ObjectMapper json) {
        this.db = db;
        this.brands = brands;
        this.audiences = audiences;
        this.costs = costs;
        this.json = json;
    }

    @Override
    public Optional<Intent> find(ActivityActor actor, String action, Map<String, Object> request, String key) {
        var row = commandByNamespace(actor, action, key, false);
        if (row == null) return Optional.empty();
        requireRequest(row, request);
        return Optional.of(intent(row));
    }

    @Override
    public Intent prepare(ActivityActor actor, String action, Map<String, Object> request, String key) {
        return db.write(() -> {
            var existing = commandByNamespace(actor, action, key, true);
            if (existing != null) {
                requireRequest(existing, request);
                return intent(existing);
            }
            if (db.one("SELECT command_no FROM pmt_brand_coupon_command WHERE tenant_no=? AND operation_hash=? "
                    + "FOR UPDATE", actor.tenantNo(), operationHash(actor, key)) != null) conflict();
            var row = db.base(actor.tenantNo(), actor.actorNo());
            var createdAt = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
            row.put("created_at", createdAt);
            row.put("updated_at", createdAt);
            String commandNo = BizKey.next(BizKey.PROMO_ISSUE);
            row.put("command_no", commandNo);
            row.put("namespace_hash", namespace(actor, action, key));
            row.put("operation_hash", operationHash(actor, key));
            row.put("management_no", actor.managementNo());
            row.put("brand_no", actor.brandNo());
            row.put("entity_no", actor.entityNo());
            row.put("actor_no", actor.actorNo());
            row.put("action", action);
            row.put("request_hash", requestHash(request));
            row.put("status", "PENDING");
            row.put("request_json", db.encode(request));
            row.put("checkpoint_json", "{}");
            if ("saveCoupon".equals(action)) {
                row.put("source_no", createSource(actor, request).sourceNo());
            } else {
                String no = (String) request.get("id");
                if (no == null) no = (String) request.get("couponId");
                var sourceRow = sourceRow(actor, no, true);
                var source = source(sourceRow);
                row.put("source_no", source.sourceNo());
                if (List.of("publishCoupon", "pauseCoupon").contains(action)
                        && db.one("SELECT command_no FROM pmt_brand_coupon_command WHERE tenant_no=? "
                        + "AND source_no=? AND action=? AND deleted=0 FOR UPDATE", actor.tenantNo(),
                        source.sourceNo(), action) != null) conflict();
                if ("publishCoupon".equals(action)) {
                    if (!List.of("DRAFT", "READY").contains(source.status()) || source.nativeTemplateNo() == null
                            || source.nativeRevision() != ((Number) request.get("expectedRevision")).longValue()) {
                        conflict();
                    }
                    String rule = (String) request.get("costRuleVersionNo");
                    if (source.costRuleVersionNo() != null && !source.costRuleVersionNo().equals(rule)) conflict();
                    db.update("UPDATE pmt_brand_coupon_source SET status='READY',cost_rule_version_no=?,"
                            + "version=version+1,updated_at=?,updated_by=? WHERE tenant_no=? AND source_no=?",
                            rule, Timestamp.from(Instant.now()), actor.actorNo(), actor.tenantNo(), source.sourceNo());
                } else if ("pauseCoupon".equals(action)) {
                    if (!"ACTIVE".equals(source.status()) || source.nativeRevision()
                            != ((Number) request.get("expectedRevision")).longValue()) conflict();
                } else {
                    if (!"ACTIVE".equals(source.status())) conflict();
                    if ("previewAudience".equals(action)) {
                        var snapshot = audiences.freeze(context(actor), source.sourceNo(), source.nativeVersionNo());
                        var reasons = new LinkedHashMap<String, Integer>();
                        snapshot.preSkipReasons()
                                .forEach(skip -> reasons.merge(skip.reason(), skip.count(), Integer::sum));
                        snapshot.recipients().stream().filter(recipient -> !recipient.eligible())
                                .forEach(recipient -> reasons.merge(recipient.reasonCode(), 1, Integer::sum));
                        int eligible = (int) snapshot.recipients().stream()
                                .filter(recipient -> recipient.eligible()).count();
                        var reply = new LinkedHashMap<String, Object>();
                        reply.put("success", true);
                        reply.put("state", "COMPLETED");
                        reply.put("previewToken", snapshot.snapshotNo());
                        reply.put("matched", snapshot.plannedCount());
                        reply.put("reachable", eligible);
                        reply.put("skipped", snapshot.plannedCount() - eligible);
                        reply.put("skips", reasons.entrySet().stream()
                                .map(entry -> new BrandAudienceSnapshotService.Skip(entry.getKey(), entry.getValue()))
                                .toList());
                        reply.put("expiresAt", snapshot.expiresAt().toString());
                        row.put("checkpoint_json", db.encode(Map.of("snapshotNo", snapshot.snapshotNo())));
                        row.put("response_json", db.encode(reply));
                        row.put("status", "COMPLETED");
                    } else if ("issueCoupon".equals(action)) {
                        audiences.claim(context(actor), (String) request.get("audienceSnapshotNo"), source.sourceNo(),
                                source.nativeVersionNo(), commandNo);
                    } else {
                        throw BizException.of(ErrorCode.BAD_REQUEST);
                    }
                }
            }
            db.insert("pmt_brand_coupon_command", row);
            return intent(row);
        });
    }

    private Source createSource(ActivityActor actor, Map<String, Object> request) {
        SaveCoupon coupon = json.convertValue(request, SaveCoupon.class);
        var brand = brands.find(actor.tenantNo(), actor.managementNo(), actor.brandNo(), actor.entityNo());
        if (brand == null) throw BizException.of(ErrorCode.NOT_FOUND);
        var stores = new ArrayList<Store>();
        for (String no : coupon.storeIds().stream().distinct().sorted().toList()) {
            if (actor.allowedStores() != null && !actor.allowedStores().contains(no)) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            stores.add(brand.stores().stream().filter(store -> no.equals(store.storeNo())).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)));
        }
        String no = BizKey.next(BizKey.PROMO_COUPON);
        var definition = new EntitlementDefinition(coupon.name(), "ORDER_COUPON", "MERCHANT", coupon.currency(),
                "CASH", coupon.faceValueMinor(), null, null, coupon.thresholdMinor(), null, "COUNT", 1, null,
                new Validity("RELATIVE", null, null, coupon.validDays(), "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("BRAND_STORES", List.of(no)), List.of("TARGETED"));
        var row = db.base(actor.tenantNo(), actor.actorNo());
        row.put("source_no", no);
        row.put("management_no", actor.managementNo());
        row.put("brand_no", actor.brandNo());
        row.put("entity_no", actor.entityNo());
        row.put("actor_no", actor.actorNo());
        row.put("status", "DRAFT");
        row.put("definition_hash", ActivityCommandDeduplicator.hash(db.encode(definition)));
        row.put("source_json", db.encode(new SourceSnapshot(coupon, definition, stores)));
        db.insert("pmt_brand_coupon_source", row);
        return source(row);
    }

    @Override
    public Intent checkpoint(ActivityActor actor, String commandNo, Map<String, Object> checkpoint) {
        return db.write(() -> {
            var row = ownedCommand(actor, commandNo, true);
            var previous = db.decode(s(row, "checkpoint_json"));
            var merged = new LinkedHashMap<>(previous);
            checkpoint.forEach((key, value) -> {
                if (previous.containsKey(key) && !previous.get(key).equals(value)) conflict();
                merged.put(key, value);
            });
            db.update("UPDATE pmt_brand_coupon_command SET checkpoint_json=?,version=version+1,updated_at=? "
                    + "WHERE tenant_no=? AND command_no=?", db.encode(merged), Timestamp.from(Instant.now()),
                    actor.tenantNo(), commandNo);
            row.put("checkpoint_json", db.encode(merged));
            return intent(row);
        });
    }

    @Override
    public Intent finish(ActivityActor actor, String commandNo, Map<String, Object> response, Completion completion,
                         boolean completed) {
        return db.write(() -> {
            var row = ownedCommand(actor, commandNo, true);
            if ("COMPLETED".equals(s(row, "status"))) return intent(row);
            if (completion != null) {
                var sourceRow = sourceRow(actor, s(row, "source_no"), true);
                var source = source(sourceRow);
                if (source.nativeTemplateNo() != null && !source.nativeTemplateNo().equals(completion.templateNo())) {
                    conflict();
                }
                if (source.nativeVersionNo() != null && !source.nativeVersionNo().equals(completion.versionNo())) {
                    conflict();
                }
                if ("ACTIVE".equals(completion.sourceStatus())) {
                    costs.freeze(actor, source.costRuleVersionNo(), "COUPON", source.sourceNo(),
                            completion.versionNo(), source.storeNos());
                }
                db.update("UPDATE pmt_brand_coupon_source SET native_template_no=?,native_version_no=?,"
                        + "native_revision=?,status=?,version=version+1,updated_at=?,updated_by=? "
                        + "WHERE tenant_no=? AND source_no=?", completion.templateNo(), completion.versionNo(),
                        completion.nativeRevision(), completion.sourceStatus(), Timestamp.from(Instant.now()),
                        actor.actorNo(), actor.tenantNo(), source.sourceNo());
            }
            String status = completed ? "COMPLETED" : "PENDING";
            db.update("UPDATE pmt_brand_coupon_command SET status=?,response_json=?,version=version+1,"
                    + "updated_at=?,updated_by=? WHERE tenant_no=? AND command_no=?", status, db.encode(response),
                    Timestamp.from(Instant.now()), actor.actorNo(), actor.tenantNo(), commandNo);
            row.put("status", status);
            row.put("response_json", db.encode(response));
            return intent(row);
        });
    }

    @Override
    public Intent result(ActivityActor actor, String key) {
        var found = ACTIONS.stream().map(action -> commandByNamespace(actor, action, key, false))
                .filter(row -> row != null).toList();
        if (found.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
        if (found.size() > 1) conflict();
        return intent(found.getFirst());
    }

    @Override
    public String commandAction(ActivityActor actor, String key) { return result(actor, key).action(); }

    @Override
    public List<Intent> issues(ActivityActor actor) {
        return db.rows("SELECT * FROM pmt_brand_coupon_command WHERE tenant_no=? AND management_no=? AND brand_no=? "
                + "AND entity_no=? AND action='issueCoupon' AND deleted=0 ORDER BY id DESC", actor.tenantNo(),
                actor.managementNo(), actor.brandNo(), actor.entityNo()).stream().map(this::intent).toList();
    }

    @Override
    public Source get(ActivityActor actor, String sourceNo) { return source(sourceRow(actor, sourceNo, false)); }

    @Override
    public List<Source> list(ActivityActor actor) {
        return db.rows("SELECT * FROM pmt_brand_coupon_source WHERE tenant_no=? AND management_no=? AND brand_no=? "
                + "AND entity_no=? AND deleted=0 ORDER BY id DESC", actor.tenantNo(), actor.managementNo(),
                actor.brandNo(), actor.entityNo()).stream().map(this::source).toList();
    }

    @Override
    public Source sourceFact(String tenantNo, String entityNo, String sourceNo) {
        var row = db.one("SELECT * FROM pmt_brand_coupon_source WHERE tenant_no=? AND entity_no=? AND source_no=? "
                + "AND deleted=0", tenantNo, entityNo, sourceNo);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return source(row);
    }

    @Override
    public Intent issuedCommand(String tenantNo, String entityNo, String actorNo, String commandNo) {
        var row = db.one("SELECT * FROM pmt_brand_coupon_command WHERE tenant_no=? AND entity_no=? AND actor_no=? "
                + "AND command_no=? AND action='issueCoupon' AND deleted=0", tenantNo, entityNo, actorNo, commandNo);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return intent(row);
    }

    private Map<String, Object> sourceRow(ActivityActor actor, String no, boolean lock) {
        var row = db.one("SELECT * FROM pmt_brand_coupon_source WHERE tenant_no=? AND management_no=? AND brand_no=? "
                + "AND entity_no=? AND source_no=? AND deleted=0" + (lock ? " FOR UPDATE" : ""), actor.tenantNo(),
                actor.managementNo(), actor.brandNo(), actor.entityNo(), no);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private Source source(Map<String, Object> row) {
        return new Source(s(row, "source_no"), s(row, "tenant_no"), s(row, "management_no"), s(row, "brand_no"),
                s(row, "entity_no"), s(row, "actor_no"), s(row, "status"), s(row, "cost_rule_version_no"),
                s(row, "native_template_no"), s(row, "native_version_no"), n(row, "native_revision"),
                s(row, "definition_hash"), json.readValue(s(row, "source_json"), SourceSnapshot.class));
    }

    private Map<String, Object> ownedCommand(ActivityActor actor, String commandNo, boolean lock) {
        var row = db.one("SELECT * FROM pmt_brand_coupon_command WHERE tenant_no=? AND management_no=? AND brand_no=? "
                + "AND entity_no=? AND actor_no=? AND command_no=? AND deleted=0" + (lock ? " FOR UPDATE" : ""),
                actor.tenantNo(), actor.managementNo(), actor.brandNo(), actor.entityNo(), actor.actorNo(), commandNo);
        if (row == null) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private Map<String, Object> commandByNamespace(ActivityActor actor, String action, String key, boolean lock) {
        return db.one("SELECT * FROM pmt_brand_coupon_command WHERE tenant_no=? AND namespace_hash=? AND entity_no=? "
                + "AND deleted=0" + (lock ? " FOR UPDATE" : ""), actor.tenantNo(), namespace(actor, action, key),
                actor.entityNo());
    }

    private Intent intent(Map<String, Object> row) {
        String response = s(row, "response_json");
        return new Intent(s(row, "command_no"), s(row, "action"), s(row, "source_no"), s(row, "actor_no"),
                s(row, "status"),
                db.decode(s(row, "request_json")), db.decode(s(row, "checkpoint_json")),
                response == null ? null : db.decode(response), instant(row.get("created_at")));
    }

    private Instant instant(Object value) {
        LocalDateTime time = value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
        return time.toInstant(ZoneOffset.UTC);
    }

    private void requireRequest(Map<String, Object> row, Map<String, Object> request) {
        if (!requestHash(request).equals(s(row, "request_hash"))) conflict();
    }

    private String requestHash(Map<String, Object> request) {
        return ActivityCommandDeduplicator.hash(db.encode(new TreeMap<>(request)));
    }

    private String namespace(ActivityActor actor, String action, String key) {
        return ActivityCommandDeduplicator.hash(db.encode(List.of(actor.tenantNo(), actor.actorNo(),
                actor.managementNo(), actor.brandNo(), action, key)));
    }

    private String operationHash(ActivityActor actor, String key) {
        return ActivityCommandDeduplicator.hash(db.encode(List.of(actor.tenantNo(), actor.actorNo(),
                actor.managementNo(), actor.brandNo(), key)));
    }

    private BrandAudienceSnapshotService.Context context(ActivityActor actor) {
        return new BrandAudienceSnapshotService.Context(actor.tenantNo(), actor.managementNo(), actor.brandNo(),
                actor.entityNo(), actor.actorNo());
    }

    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
