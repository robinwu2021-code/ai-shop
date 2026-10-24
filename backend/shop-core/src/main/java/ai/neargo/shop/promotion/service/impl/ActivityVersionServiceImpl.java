package ai.neargo.shop.promotion.service.impl;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.promotion.service.CoreMarketingDocumentNumbers;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.policy.ActivityPresetExecutionPolicy;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.ActivityView;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.VersionView;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityScope;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.entity.PmtRechargeTier;
import ai.neargo.shop.promotion.entity.PmtPresetTemplate;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityScopeMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PmtPresetTemplateMapper;
import ai.neargo.shop.promotion.mapper.PmtRechargeTierMapper;
import ai.neargo.shop.promotion.mapper.UnifiedActivityManagementMapper;
import ai.neargo.shop.promotion.policy.ActivityRuleValidator;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.product.ProductScopeFactsPort;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Published rules and participation budgets remain core facts, separate from pay issuance authorization. */
@Service
public class ActivityVersionServiceImpl implements ActivityVersionService {
    @org.springframework.beans.factory.annotation.Value("${shop.pay.stored-value.governance-enabled:false}")
    private boolean storedValueGovernance;
    @org.springframework.beans.factory.annotation.Autowired private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.promotion.service.BrandActivityOwnershipService> brandOwners;
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<ai.neargo.shop.promotion.service.StoredValueActivityJournal> storedValueJournal;
    private CoreMarketingDocumentNumbers documentNumbers;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDocumentNumbers(CoreMarketingDocumentNumbers documentNumbers) {
        this.documentNumbers = documentNumbers;
    }

    private final ActivityMapper heads;
    private final UnifiedActivityManagementMapper writes;
    private final PmtActivityVersionMapper versions;
    private final PmtActivityBudgetMapper budgets;
    private final PmtActivityScopeMapper scopes;
    private final PmtRechargeTierMapper tiers;
    private final PmtPresetTemplateMapper presets;
    private final ActivityCommandDeduplicator commands;
    private final ActivityRuleValidator validator;
    private final EntitlementScopeValidationService scopeValidation;
    private final ObjectProvider<EntitlementCatalogPort> catalog;
    private final ProductScopeFactsPort products;
    private final MemberQueryPort members;
    private final PromotionFeatureGate features;
    private final ObjectProvider<ActivityCapabilityEvidence> evidence;
    private final ObjectMapper json;

    public ActivityVersionServiceImpl(ActivityMapper heads, UnifiedActivityManagementMapper writes, PmtActivityVersionMapper versions,
            PmtActivityBudgetMapper budgets, PmtActivityScopeMapper scopes, PmtRechargeTierMapper tiers,
            PmtPresetTemplateMapper presets, ActivityCommandDeduplicator commands, ActivityRuleValidator validator,
            EntitlementScopeValidationService scopeValidation, ObjectProvider<EntitlementCatalogPort> catalog,
            ProductScopeFactsPort products, MemberQueryPort members, PromotionFeatureGate features,
            ObjectProvider<ActivityCapabilityEvidence> evidence, ObjectMapper json) {
        this.heads = heads; this.writes = writes; this.versions = versions; this.budgets = budgets; this.scopes = scopes;
        this.tiers = tiers; this.presets = presets; this.commands = commands; this.validator = validator;
        this.scopeValidation = scopeValidation; this.catalog = catalog; this.products = products;
        this.members = members; this.features = features; this.evidence = evidence; this.json = json;
    }

    @Override
    public ActivityView saveDraft(ActivityActor actor, SaveDraft command, String requestKey) {
        return documentNumbers.transaction(java.util.Map.of(DocumentType.MARKETING_ACTIVITY, 1), () -> saveDraftNumbered(actor, command, requestKey));
    }

    private ActivityView saveDraftNumbered(ActivityActor actor, SaveDraft command, String requestKey) {
        if (command == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var replay = commands.replay(actor, "ACTIVITY_DRAFT", requestKey, command, ActivityView.class);
        if (replay.isPresent()) {
            owned(actor,replay.get().activityNo(),false);
            return replay.get();
        }
        var resolved = defaults(actor, command.definition());
        validator.validate(resolved);
        references(actor, resolved);
        String no = command.activityNo() == null ? BizKey.next(BizKey.PROMO_ACTIVITY) : command.activityNo();
        return commands.execute(actor, "ACTIVITY_DRAFT", requestKey, command, no, ActivityView.class, () -> {
            features.requireActivity();
            PmtActivity row;
            if (command.activityNo() == null) {
                if (command.expectedRevision() != null && command.expectedRevision() != 0) {
                    throw BizException.of(ErrorCode.CONFLICT);
                }
                row = new PmtActivity(); audit(row, actor);
                row.setActivityNo(no); row.setEntityNo(actor.entityNo()); row.setOwner(actor.brandNo()==null?"MERCHANT":"BRAND");
                row.setStatus("DRAFT"); row.setTriggerType("NONE"); row.setBenefitType("COMBO");
                row.setQuotaUsed(0); row.setBudgetUsedMinor(0L);
            } else {
                row = owned(actor, no, true);
                revision(row, command.expectedRevision());
                if (!"DRAFT".equals(row.getStatus())) throw BizException.of(ErrorCode.CONFLICT);
                if (!row.getScene().equals(resolved.scene())) throw BizException.of(ErrorCode.CONFLICT);
            }
            var d = resolved;
            row.setName(d.name()); row.setScene(d.scene());
            row.setScheduleType(d.schedule().type()); row.setStartAt(d.schedule().startAt().toEpochMilli());
            row.setEndAt(d.schedule().endAt() == null ? null : d.schedule().endAt().toEpochMilli());
            row.setDraftSnapshot(json.writeValueAsString(d));
            row.setUpdatedAt(now()); row.setUpdatedBy(actor.actorNo());
            if (command.activityNo() == null) changed(documentNumbers.bind(DocumentType.MARKETING_ACTIVITY, "PMT", "pmt_activity", row, row.getActivityNo(), heads::insert, PmtActivity::setDocumentNo));
            else persist(row);
            if(command.activityNo()==null&&actor.brandNo()!=null){var owner=brandOwners.getIfAvailable();if(owner==null)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);owner.register(actor,no);}
            return view(row);
        });
    }

    @Override
    @Transactional("transactionManager")
    public VersionView publish(ActivityActor actor, String activityNo, long expectedRevision, String requestKey) {
        owned(actor, activityNo, false);
        var replay = commands.replay(actor, "ACTIVITY_PUBLISH", requestKey,
                List.of(activityNo, expectedRevision), VersionView.class);
        if (replay.isPresent()) return replay.get();
        // Resolve owner references before taking the activity lock; revision and exact draft are rechecked below.
        PmtActivity candidate = owned(actor, activityNo, false);
        var definition = definition(candidate);
        validator.validatePublication(definition, Instant.now());
        references(actor, definition);
        String audience = definition.audienceRef() == null ? null
                : members.segmentSnapshot(actor.entityNo(), definition.audienceRef());
        requireReady(actor, definition);
        return commands.execute(actor, "ACTIVITY_PUBLISH", requestKey, List.of(activityNo, expectedRevision),
                activityNo, VersionView.class, () -> {
            features.requireActivity();
            PmtActivity row = owned(actor, activityNo, true);
            revision(row, expectedRevision);
            if (!"DRAFT".equals(row.getStatus())
                    || !candidate.getDraftSnapshot().equals(row.getDraftSnapshot())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if((storedValueGovernance||"BRAND".equals(row.getOwner()))&&"RECHARGE".equals(definition.scene())) {
                var journal=storedValueJournal.getIfAvailable();
                if(journal==null)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                journal.requirePublish(actor.tenantNo(),activityNo,expectedRevision,journal.hash(definition));
            }
            var version = new PmtActivityVersion(); audit(version, actor);
            version.setVersionNo(BizKey.next(BizKey.ACTIVITY_VERSION));
            int versionIndex = versions.lastIndex(actor.tenantNo(), activityNo) + 1;
            version.setActivityNo(activityNo); version.setVersionIndex(versionIndex); version.setSchemaVersion(1);
            version.setCurrency(definition.currency()); version.setScheduleType(definition.schedule().type());
            version.setStartsAt(utc(definition.schedule().startAt()));
            version.setEndsAt(definition.schedule().endAt() == null ? null : utc(definition.schedule().endAt()));
            version.setMarketZone(definition.schedule().zone());
            String snapshot = json.writeValueAsString(new PublishedSnapshot(definition, audience));
            version.setRuleSnapshot(snapshot); version.setRuleHash(ActivityCommandDeduplicator.hash(snapshot));
            version.setPublishedBy(actor.actorNo()); version.setPublishedAt(now());
            changed(versions.insert(version));
            writeScopeAndTiers(actor, version.getVersionNo(), definition);
            var budget = new PmtActivityBudget(); audit(budget, actor);
            budget.setActivityNo(activityNo); budget.setCurrency(definition.currency());
            budget.setQuotaLimit(definition.quotaLimit()); budget.setQuotaHeld(0L); budget.setQuotaUsed(0L);
            budget.setBudgetLimitMinor(definition.budgetLimitMinor()); budget.setBudgetHeldMinor(0L);
            budget.setBudgetUsedMinor(0L); budget.setPerUserLimit(definition.perUserLimit());
            // Initial publication creates one lifetime budget. Never reset accumulated exposure.
            if (budgets.lock(actor.tenantNo(), activityNo) != null) throw BizException.of(ErrorCode.CONFLICT);
            changed(budgets.insert(budget));
            row.setCurrentVersionNo(version.getVersionNo()); row.setStatus("RUNNING");
            row.setUpdatedAt(now()); row.setUpdatedBy(actor.actorNo());
            persist(row);
            return new VersionView(activityNo, version.getVersionNo(), versionIndex, version.getRuleHash(),
                    version.getPublishedAt().toInstant(ZoneOffset.UTC), definition);
        });
    }

    @Override
    @Transactional("transactionManager")
    public ActivityView changeStatus(ActivityActor actor, String activityNo, long expectedRevision,
                                      String status, String reason, String requestKey) {
        if (status == null || !Set.of("RUNNING", "PAUSED", "ENDED").contains(status)
                || reason == null || reason.isBlank() || reason.length() > 500) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        owned(actor, activityNo, false);
        var replay = commands.replay(actor, "ACTIVITY_STATUS", requestKey,
                List.of(activityNo, expectedRevision, status, reason), ActivityView.class);
        if (replay.isPresent()) return replay.get();
        var candidate = owned(actor, activityNo, false);
        boolean publishDraft = "DRAFT".equals(candidate.getStatus()) && "RUNNING".equals(status);
        if((storedValueGovernance||"BRAND".equals(candidate.getOwner()))&&"RECHARGE".equals(candidate.getScene())&&!publishDraft)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        if (publishDraft) {
            // Same resource/transaction as explicit publish; the status command has its own stable reply.
            publish(actor, activityNo, expectedRevision, "PUB" + ActivityCommandDeduplicator.hash(requestKey).substring(0, 48));
        } else if ("RUNNING".equals(status)) {
            features.requireActivity();
            validator.validatePublication(definition(candidate), Instant.now());
            references(actor, definition(candidate));
            requireReady(actor, definition(candidate));
        }
        return commands.execute(actor, "ACTIVITY_STATUS", requestKey,
                List.of(activityNo, expectedRevision, status, reason), activityNo, ActivityView.class, () -> {
            var row = owned(actor, activityNo, true); revision(row, publishDraft ? expectedRevision + 1 : expectedRevision);
            if (publishDraft) return view(row);
            if ("ENDED".equals(row.getStatus()) || ("DRAFT".equals(row.getStatus()) && !"ENDED".equals(status))) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            if (!publishDraft && !candidate.getVersion().equals(row.getVersion())) {
                throw BizException.of(ErrorCode.CONFLICT);
            }
            row.setStatus(status); row.setUpdatedAt(now()); row.setUpdatedBy(actor.actorNo());
            if ("ENDED".equals(status)) row.setEndedReason("MANUAL");
            persist(row);
            return view(row);
        });
    }

    @Override public ActivityView get(ActivityActor actor, String activityNo) {
        return view(owned(actor, activityNo, false));
    }

    @Override public PageData<ActivityView> list(ActivityActor actor, String scene, String status, long page, long size) {
        if (page < 1 || size < 1 || size > 100 || page > Integer.MAX_VALUE / size
                || (scene != null && !Set.of("ORDER", "ENTITLEMENT_GRANT", "RECHARGE").contains(scene))
                || (status != null && !Set.of("DRAFT", "RUNNING", "PAUSED", "ENDED").contains(status))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var rows = DataScopeContext.executeWithoutScope(() -> documentNumbers.load(DocumentType.MARKETING_ACTIVITY, "PMT", "pmt_activity", heads.selectList(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getTenantNo, actor.tenantNo()).eq(PmtActivity::getEntityNo, actor.entityNo())
                .isNotNull(PmtActivity::getDraftSnapshot).isNull(PmtActivity::getArchivedAt)
                .eq(scene != null, PmtActivity::getScene, scene).eq(status != null, PmtActivity::getStatus, status)
                .orderByDesc(PmtActivity::getId)), PmtActivity::getActivityNo, PmtActivity::setDocumentNo));
        List<ActivityView> visible = new ArrayList<>();
        for (var row : rows) if (ownerVisible(actor,row)&&visible(actor,definition(row))) visible.add(view(row));
        int start = (int) Math.min((page - 1) * size, visible.size());
        int end = (int) Math.min(start + size, visible.size());
        return PageData.of(List.copyOf(visible.subList(start, end)), visible.size(), page, size);
    }

    private PmtActivity owned(ActivityActor actor, String no, boolean lock) {
        var row = DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.MARKETING_ACTIVITY, "PMT", "pmt_activity", heads.selectOne(Wrappers.<PmtActivity>lambdaQuery()
                .eq(PmtActivity::getTenantNo, actor.tenantNo()).eq(PmtActivity::getEntityNo, actor.entityNo())
                .eq(PmtActivity::getActivityNo, no).isNotNull(PmtActivity::getDraftSnapshot)
                .isNull(PmtActivity::getArchivedAt).last(lock ? "FOR UPDATE" : "")), PmtActivity::getActivityNo, PmtActivity::setDocumentNo));
        if (row == null || !ownerVisible(actor,row)||!visible(actor,definition(row))) throw BizException.of(ErrorCode.NOT_FOUND);
        return row;
    }

    private boolean ownerVisible(ActivityActor actor,PmtActivity row){if(!"BRAND".equals(row.getOwner()))return actor.brandNo()==null;var owner=brandOwners.getIfAvailable();return owner!=null&&owner.visible(actor,row);}
    private boolean visible(ActivityActor actor, UnifiedActivityDefinition d) {
        if(actor.brandNo()!=null)return "RECHARGE".equals(d.scene())&&"ALL".equals(d.storeParticipationScope().mode())&&d.rule().benefits().isEmpty()&&d.rule().tiers().stream().allMatch(t->"ALL".equals(t.redemptionScope().mode()));
        return actor.allowedStores() == null || ("STORES".equals(d.storeParticipationScope().mode())
                && actor.allowedStores().containsAll(d.storeParticipationScope().refs()));
    }

    private void references(ActivityActor actor, UnifiedActivityDefinition d) {
        if (!visible(actor, d)) throw BizException.of(ErrorCode.FORBIDDEN);
        scopeValidation.validate(actor.tenantNo(), actor.entityNo(), d.storeParticipationScope());
        for (var tier : d.rule().tiers()) {
            scopeValidation.validate(actor.tenantNo(), actor.entityNo(), tier.redemptionScope());
        }
        for (var benefit : d.rule().benefits()) {
            if (benefit.templateVersionNo() != null) {
                var owner = catalog.getIfAvailable();
                if (owner == null) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
                owner.validateReference(new ReferenceQuery(actor.tenantNo(), actor.entityNo(),
                        benefit.templateVersionNo(), d.currency(), "ACTIVITY_GRANT"));
            }
            if (benefit.goodsNo() != null
                    && !products.goodsOwned(actor.tenantNo(), actor.entityNo(), Set.of(benefit.goodsNo()))) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
        }
        if (d.audienceRef() != null) members.segmentSnapshot(actor.entityNo(), d.audienceRef());
        preset(actor, d);
    }

    private void requireReady(ActivityActor actor, UnifiedActivityDefinition d) {
        var preset = preset(actor, d);
        if (!new ActivityPresetExecutionPolicy().missing(actor, d).isEmpty()) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        // Phase one does not publish rules for executors which have not implemented this schema.
        if ("ORDER".equals(d.scene()) && !UnifiedOrderActivityPricingServiceImpl.supportsDefinition(d)) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
        Set<ActivityCapability> ready = new HashSet<>();
        evidence.orderedStream().forEach(provider -> ready.addAll(provider.verifiedFor(actor)));
        List<String> required = new ArrayList<>();
        json.readTree(preset.getRequiredCapabilities()).forEach(code -> required.add(code.asText()));
        if (!Integer.valueOf(1).equals(preset.getEnabled())
                || !features.assessPreset(required, ready).available()) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }

    /** Seed metadata supplies defaults only; prices, benefits, scope and schedules are merchant inputs. */
    private UnifiedActivityDefinition defaults(ActivityActor actor, UnifiedActivityDefinition d) {
        if (d == null || d.templateCode() == null) throw BizException.of(ErrorCode.BAD_REQUEST);
        var preset = presets.catalog(actor.tenantNo(), "ACTIVITY").stream()
                .filter(row -> d.templateCode().equals(row.getTemplateCode()))
                .filter(row -> d.templateVersion() == 0 || d.templateVersion() == row.getTemplateVersion())
                .findFirst().orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
        var schema = json.readTree(preset.getDraftSchema());
        String scene = schema.path("scene").asText();
        if ("COUPON_DISTRIBUTION".equals(scene)) scene = "ENTITLEMENT_GRANT";
        return new UnifiedActivityDefinition(d.name() == null ? preset.getName() : d.name(),
                d.scene() == null ? scene : d.scene(), preset.getTemplateCode(), preset.getTemplateVersion(),
                d.storeParticipationScope(), d.audienceRef(), d.schedule(), d.rule(), d.quotaLimit(),
                d.budgetLimitMinor(), d.perUserLimit(), d.currency());
    }

    private PmtPresetTemplate preset(ActivityActor actor, UnifiedActivityDefinition d) {
        return presets.catalog(actor.tenantNo(), "ACTIVITY").stream()
                .filter(row -> row.getTemplateCode().equals(d.templateCode())
                        && row.getTemplateVersion() == d.templateVersion())
                .filter(row -> {
                    String scene = json.readTree(row.getDraftSchema()).path("scene").asText();
                    var schema = json.readTree(row.getDraftSchema());
                    if (!d.scene().equals("COUPON_DISTRIBUTION".equals(scene) ? "ENTITLEMENT_GRANT" : scene)) return false;
                    String base = schema.path("baseTemplate").asText();
                    if ("A01".equals(base) && !"AMOUNT".equals(d.rule().trigger().type())) return false;
                    if ("A02".equals(base) && !"QTY".equals(d.rule().trigger().type())) return false;
                    String effect = schema.path("effect").asText();
                    if ("RECHARGE".equals(d.scene())) return "STORED_VALUE_BONUS".equals(effect);
                    String expected = "COUPON".equals(effect) ? "ENTITLEMENT" : effect;
                    return d.rule().benefits().stream().allMatch(benefit -> expected.equals(benefit.kind()));
                }).findFirst().orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
    }

    private void writeScopeAndTiers(ActivityActor actor, String versionNo, UnifiedActivityDefinition d) {
        var scope = d.storeParticipationScope();
        for (String ref : "ALL".equals(scope.mode()) ? List.of("*") : scope.refs()) {
            var row = new PmtActivityScope(); audit(row, actor); row.setVersionNo(versionNo);
            row.setScopeKind("ALL".equals(scope.mode()) ? "ALL" : "STORE"); row.setRefNo(ref);
            changed(scopes.insert(row));
        }
        for (var tier : d.rule().tiers()) {
            var row = new PmtRechargeTier(); audit(row, actor); row.setVersionNo(versionNo);
            row.setTierNo(tier.tierNo()); row.setPayAmountMinor(tier.payAmountMinor());
            row.setBonusAmountMinor(tier.bonusAmountMinor());
            row.setRedemptionScope(json.writeValueAsString(tier.redemptionScope())); changed(tiers.insert(row));
        }
    }

    private UnifiedActivityDefinition definition(PmtActivity row) {
        return json.readValue(row.getDraftSnapshot(), UnifiedActivityDefinition.class);
    }
    private ActivityView view(PmtActivity row) {
        return new ActivityView(row.getActivityNo(), row.getVersion(), row.getStatus(),
                row.getCurrentVersionNo(), definition(row)).withDocumentNo(row.getDocumentNo());
    }
    private void persist(PmtActivity row) {
        changed(DataScopeContext.executeWithoutScope(() -> writes.update(row)));
        row.setVersion(row.getVersion() + 1);
    }
    private static void revision(PmtActivity row, Long revision) {
        if (revision == null || !revision.equals(row.getVersion())) throw BizException.of(ErrorCode.CONFLICT);
    }
    private static void changed(int rows) { if (rows != 1) throw BizException.of(ErrorCode.CONFLICT); }
    private static LocalDateTime utc(Instant instant) { return LocalDateTime.ofInstant(instant, ZoneOffset.UTC); }
    private static LocalDateTime now() { return utc(Instant.now()); }
    private static void audit(BaseEntity row, ActivityActor actor) {
        row.setTenantNo(actor.tenantNo()); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(now()); row.setUpdatedAt(now());
        row.setCreatedBy(actor.actorNo()); row.setUpdatedBy(actor.actorNo());
    }
    public record PublishedSnapshot(UnifiedActivityDefinition definition, String audienceRuleSnapshot) { }
}
