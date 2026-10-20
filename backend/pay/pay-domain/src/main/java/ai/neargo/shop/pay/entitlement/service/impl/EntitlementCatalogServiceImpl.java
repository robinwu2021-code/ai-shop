package ai.neargo.shop.pay.entitlement.service.impl;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.PublishedVersionView;
import ai.neargo.shop.pay.entitlement.dto.CatalogViews.TemplateView;
import ai.neargo.shop.pay.entitlement.entity.EntTemplate;
import ai.neargo.shop.pay.entitlement.entity.EntTemplateVersion;
import ai.neargo.shop.pay.entitlement.policy.EntitlementRuleValidator;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementCommandDeduplicator;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateVersionMapper;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Owns catalog transactions, stable command replies and immutable published definitions in pay. */
@Service
public class EntitlementCatalogServiceImpl implements EntitlementCatalogService {
    private final EntTemplateMapper heads;
    private final EntTemplateVersionMapper versions;
    private final EntitlementCommandDeduplicator deduplicator;
    private final ObjectMapper json;
    private final PayEntitlementFeatureGate features;
    private final EntitlementRuleValidator rules = new EntitlementRuleValidator();

    public EntitlementCatalogServiceImpl(EntTemplateMapper heads, EntTemplateVersionMapper versions,
                                         EntitlementCommandDeduplicator deduplicator, ObjectMapper json,
                                         PayEntitlementFeatureGate features) {
        this.heads = heads;
        this.versions = versions;
        this.deduplicator = deduplicator;
        this.json = json;
        this.features = features;
    }

    @Override
    @Transactional("payTxManager")
    public TemplateView create(CatalogScope scope, EntitlementDefinition draft, String requestKey) {
        validate(scope, draft);
        String no = BizKey.next(BizKey.ENTITLEMENT_TEMPLATE);
        return deduplicator.execute(scope, "CATALOG_CREATE", requestKey, draft, no, TemplateView.class, () -> {
            features.requireCatalog();
            EntTemplate head = new EntTemplate();
            audit(head, scope);
            head.setTemplateNo(no);
            head.setEntityNo(scope.entityNo());
            head.setTitle(draft.title());
            head.setKind(draft.kind());
            head.setFunder(draft.funder());
            head.setStatus("DRAFT");
            head.setDraftSnapshot(json.writeValueAsString(draft));
            heads.insert(head);
            return view(head);
        });
    }

    @Override
    @Transactional("payTxManager")
    public TemplateView saveDraft(CatalogScope scope, String templateNo, long expectedRevision,
                                   EntitlementDefinition draft, String requestKey) {
        validate(scope, draft);
        return deduplicator.execute(scope, "CATALOG_DRAFT", requestKey, List.of(templateNo, expectedRevision, draft),
                templateNo, TemplateView.class, () -> {
                    features.requireCatalog();
                    EntTemplate head = locked(scope, templateNo, expectedRevision);
                    if (!head.getKind().equals(draft.kind()) || !head.getFunder().equals(draft.funder())) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    if (head.getCurrentVersionNo() != null
                            && !view(head).draft().currency().equals(draft.currency())) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    changed(heads.saveDraft(scope.tenantNo(), scope.entityNo(), templateNo, expectedRevision,
                            draft.title(), json.writeValueAsString(draft), scope.actorNo(), now()));
                    return get(scope, templateNo);
                });
    }

    @Override
    @Transactional("payTxManager")
    public PublishedVersionView publish(CatalogScope scope, String templateNo, long expectedRevision,
                                         String requestKey) {
        return deduplicator.execute(scope, "CATALOG_PUBLISH", requestKey,
                List.of(templateNo, expectedRevision), templateNo,
                PublishedVersionView.class, () -> {
                    features.requireCatalog();
                    EntTemplate head = locked(scope, templateNo, expectedRevision);
                    EntitlementDefinition definition = view(head).draft();
                    validate(scope, definition);
                    rules.validatePublicationTime(definition, Instant.now());
                    EntTemplateVersion version = snapshot(scope, head, definition);
                    versions.insert(version);
                    changed(heads.publish(scope.tenantNo(), scope.entityNo(), templateNo, expectedRevision,
                            version.getVersionNo(), scope.actorNo(), now()));
                    return versionView(version);
                });
    }

    @Override
    @Transactional("payTxManager")
    public TemplateView changeStatus(CatalogScope scope, String templateNo, long expectedRevision,
                                      String status, String requestKey) {
        if (status == null || !List.of("ACTIVE", "PAUSED", "ENDED").contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return deduplicator.execute(scope, "CATALOG_STATUS", requestKey,
                List.of(templateNo, expectedRevision, status), templateNo,
                TemplateView.class, () -> {
                    if ("ACTIVE".equals(status)) {
                        features.requireCatalog();
                    }
                    EntTemplate head = locked(scope, templateNo, expectedRevision);
                    if (head.getCurrentVersionNo() == null && !"ENDED".equals(status)) {
                        throw BizException.of(ErrorCode.CONFLICT);
                    }
                    changed(heads.changeStatus(scope.tenantNo(), scope.entityNo(), templateNo, expectedRevision,
                            status, scope.actorNo(), now()));
                    return get(scope, templateNo);
                });
    }

    @Override
    public TemplateView get(CatalogScope scope, String templateNo) {
        EntTemplate head = heads.findOwned(scope.tenantNo(), scope.entityNo(), templateNo);
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return view(head);
    }

    @Override
    public PublishedVersionView getVersion(CatalogScope scope, String versionNo) {
        EntTemplateVersion row = versions.selectOne(Wrappers.<EntTemplateVersion>lambdaQuery()
                .eq(EntTemplateVersion::getTenantNo, scope.tenantNo()).eq(EntTemplateVersion::getVersionNo, versionNo));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        get(scope, row.getTemplateNo());
        return versionView(row);
    }

    @Override
    public PageData<TemplateView> list(CatalogScope scope, String kind, String status, long page, long size) {
        if (page < 1 || size < 1 || size > 100 || (kind != null && !EntitlementRuleValidator.KINDS.contains(kind))
                || (status != null && !EntitlementRuleValidator.STATUSES.contains(status))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Page<EntTemplate> result = heads.selectPage(new Page<>(page, size), Wrappers.<EntTemplate>lambdaQuery()
                .eq(EntTemplate::getTenantNo, scope.tenantNo()).eq(EntTemplate::getEntityNo, scope.entityNo())
                .eq(kind != null, EntTemplate::getKind, kind).eq(status != null, EntTemplate::getStatus, status)
                .isNull(EntTemplate::getArchivedAt).orderByDesc(EntTemplate::getId));
        return PageData.of(result.getRecords().stream().map(this::view).toList(), result.getTotal(), page, size);
    }

    private EntTemplate locked(CatalogScope scope, String no, long revision) {
        EntTemplate head = heads.lockOwned(scope.tenantNo(), scope.entityNo(), no);
        if (head == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if ("PLATFORM".equals(head.getFunder()) && !scope.platformActor()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (revision < 0 || head.getVersion() != revision || "ENDED".equals(head.getStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return head;
    }

    private void validate(CatalogScope scope, EntitlementDefinition definition) {
        rules.validate(definition);
        if ("PLATFORM".equals(definition.funder()) && !scope.platformActor()) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }

    private TemplateView view(EntTemplate head) {
        return new TemplateView(head.getTemplateNo(), head.getStatus(), head.getVersion(), head.getCurrentVersionNo(),
                json.readValue(head.getDraftSnapshot(), EntitlementDefinition.class));
    }

    private PublishedVersionView versionView(EntTemplateVersion row) {
        return new PublishedVersionView(row.getVersionNo(), row.getTemplateNo(), row.getVersionIndex(),
                json.readValue(row.getPolicySnapshot(), EntitlementDefinition.class), row.getSnapshotHash(),
                row.getPublishedAt().toInstant(ZoneOffset.UTC));
    }

    private EntTemplateVersion snapshot(CatalogScope scope, EntTemplate head, EntitlementDefinition d) {
        EntTemplateVersion row = new EntTemplateVersion();
        audit(row, scope);
        row.setVersionNo(BizKey.next(BizKey.ENTITLEMENT_VERSION));
        row.setTemplateNo(head.getTemplateNo());
        row.setVersionIndex(Math.addExact(versions.lastIndex(scope.tenantNo(), head.getTemplateNo()), 1));
        row.setCurrency(d.currency());
        row.setBenefitMode(d.benefitMode());
        row.setBenefitValue(d.benefitValue());
        row.setBenefitCapMinor(d.benefitCapMinor());
        row.setMinAmountMinor(d.minAmountMinor());
        row.setUsageModel(d.usageModel());
        row.setTotalUnits(d.totalUnits());
        row.setDailyUnits(d.dailyUnits());
        row.setValidityMode(d.validity().mode());
        row.setValidFrom(utc(d.validity().startAt()));
        row.setValidUntil(utc(d.validity().endAt()));
        row.setValidDays(d.validity().validDays());
        row.setStartBasis(d.validity().startBasis());
        row.setPeriodBasis(d.validity().periodBasis());
        row.setMarketZone(d.validity().zone());
        row.setRedemptionScope(json.writeValueAsString(d.redemptionScope()));
        row.setAllowedIssueModes(json.writeValueAsString(d.allowedIssueModes()));
        String snapshot = json.writeValueAsString(d);
        row.setPolicySnapshot(snapshot);
        row.setSnapshotHash(sha256(snapshot));
        row.setPublishedBy(scope.actorNo());
        row.setPublishedAt(now());
        return row;
    }

    private void audit(BaseEntity row, CatalogScope scope) {
        row.setTenantNo(scope.tenantNo());
        row.setCreatedAt(now());
        row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(scope.actorNo());
        row.setUpdatedBy(scope.actorNo());
        row.setVersion(0L);
        row.setDeleted(0);
    }

    private static LocalDateTime utc(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at, ZoneOffset.UTC);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MILLIS);
    }

    private static void changed(int rows) {
        if (rows != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }
}
