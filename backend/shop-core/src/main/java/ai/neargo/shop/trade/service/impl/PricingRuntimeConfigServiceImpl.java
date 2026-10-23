package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.entity.OrdStorePricingRevision;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateRevisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingRevisionMapper;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingRuntimeConfigService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Comparator;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Explicit tenant/store predicates surround cross-owner reads without holding a database transaction. */
@Service
public class PricingRuntimeConfigServiceImpl implements PricingRuntimeConfigService {
    private final StorePricingConfigMapper heads;
    private final StorePricingRevisionMapper revisions;
    private final PricingTemplateRevisionMapper templates;
    private final ObjectProvider<PricingConfigEvidencePort> providers;
    private final Clock clock;
    @Autowired
    public PricingRuntimeConfigServiceImpl(StorePricingConfigMapper heads, StorePricingRevisionMapper revisions,
            PricingTemplateRevisionMapper templates, ObjectProvider<PricingConfigEvidencePort> providers) {
        this(heads, revisions, templates, providers, Clock.systemUTC());
    }
    public PricingRuntimeConfigServiceImpl(StorePricingConfigMapper heads, StorePricingRevisionMapper revisions,
            PricingTemplateRevisionMapper templates, ObjectProvider<PricingConfigEvidencePort> providers, Clock clock) {
        this.heads = heads; this.revisions = revisions; this.templates = templates; this.providers = providers;
        this.clock = clock;
    }
    @Override public RuntimeConfig resolve(Scope scope, String industry) {
        if (scope == null || industry == null || !Set.of("FOOD", "RETAIL", "BEAUTY_SERVICE").contains(industry)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw blocked();
        return load(scope, industry);
    }
    private RuntimeConfig load(Scope scope, String industry) {
        var head = head(scope, industry);
        var row = DataScopeContext.executeWithoutScope(() -> revisions.selectOne(
                new QueryWrapper<OrdStorePricingRevision>()
                .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo())
                .eq("industry_code", industry).eq("config_no", head.getConfigNo())
                .eq("revision_no", head.getActiveRevisionNo()).eq("deleted", 0)));
        if (row == null || !Objects.equals(head.getActiveRevisionNo(), row.getRevisionNo())
                || !Objects.equals(head.getConfigNo(), row.getConfigNo()) || !scope.tenantNo().equals(row.getTenantNo())
                || !scope.entityNo().equals(row.getEntityNo()) || !scope.storeNo().equals(row.getStoreNo())
                || !industry.equals(row.getIndustryCode()) || row.getRevision() == null || row.getRevision() < 1) {
            throw blocked();
        }
        var template = DataScopeContext.executeWithoutScope(() -> templates.selectOne(
                new QueryWrapper<OrdPricingTemplateRevision>()
                .eq("tenant_no", scope.tenantNo()).eq("template_no", head.getTemplateNo())
                .eq("revision_no", row.getTemplateRevisionNo()).eq("industry_code", industry).eq("deleted", 0)));
        if (template == null || !scope.tenantNo().equals(template.getTenantNo())
                || !Objects.equals(head.getTemplateNo(), template.getTemplateNo())) throw blocked();
        var evidenceScope = new PricingConfigEvidencePort.Scope(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                head.getConfigNo(), false);
        Evidence evidence = evidence(evidenceScope);
        RuntimeConfig snapshot;
        try {
            var config = PricingRuntimeConfigPolicy.verify(row, template, evidence.capabilities());
            var raw = new RuntimeConfig(scope, industry, head.getConfigNo(), row.getRevisionNo(), row.getRevision(),
                    row.getTemplateRevisionNo(), row.getEngineSpecVersion(), row.getRuleCatalogVersion(), config,
                    evidence, null);
            snapshot = new RuntimeConfig(raw.scope(), raw.industry(), raw.configNo(), raw.revisionNo(), raw.revision(),
                    raw.templateRevisionNo(), raw.engineSpecVersion(), raw.ruleCatalogVersion(), raw.config(),
                    raw.evidence(), PricingSnapshotHash.of(List.of(raw.scope(), raw.industry(), raw.configNo(),
                            raw.revisionNo(), raw.revision(), raw.templateRevisionNo(), raw.engineSpecVersion(),
                            raw.ruleCatalogVersion(), raw.config(), List.of(evidence.scope(), evidence.capabilities(),
                                    evidence.reference(), evidence.expiresAt(),
                                    evidence.verifiedTaxProfiles().stream().sorted().toList(),
                                    evidence.sources().stream().sorted(Comparator.comparing(PricingSnapshotHash::of))
                                            .toList()))));
        } catch (RuntimeException invalid) { throw blocked(); }
        var current = head(scope, industry);
        if (!Objects.equals(head.getConfigNo(), current.getConfigNo())
                || !Objects.equals(head.getActiveRevisionNo(), current.getActiveRevisionNo())
                || !Objects.equals(head.getTemplateNo(), current.getTemplateNo())) throw conflict();
        if (!clock.instant().isBefore(evidence.expiresAt())) throw blocked();
        return snapshot;
    }
    private OrdStorePricingConfig head(Scope scope, String industry) {
        var found = DataScopeContext.executeWithoutScope(() -> heads.selectList(
                new QueryWrapper<OrdStorePricingConfig>().eq("tenant_no", scope.tenantNo())
                .eq("entity_no", scope.entityNo()).eq("store_no", scope.storeNo()).eq("industry_code", industry)
                .eq("status", "ACTIVE").eq("deleted", 0)));
        if (found == null || found.size() != 1) throw blocked();
        var row = found.getFirst();
        if (row == null || !scope.tenantNo().equals(row.getTenantNo()) || !scope.entityNo().equals(row.getEntityNo())
                || !scope.storeNo().equals(row.getStoreNo()) || !industry.equals(row.getIndustryCode())
                || !"ACTIVE".equals(row.getStatus())) throw blocked();
        try {
            PricingSourceFacts.key(row.getConfigNo()); PricingSourceFacts.key(row.getActiveRevisionNo());
            PricingSourceFacts.key(row.getTemplateNo());
        } catch (RuntimeException invalid) { throw blocked(); }
        return row;
    }
    private Evidence evidence(PricingConfigEvidencePort.Scope scope) {
        var candidates = providers.orderedStream().limit(2).toList();
        if (candidates.size() != 1) throw blocked();
        var value = candidates.getFirst().load(scope);
        var now = clock.instant();
        if (value == null || !scope.equals(value.scope()) || value.capabilities() == null || value.expiresAt() == null
                || !now.isBefore(value.expiresAt()) || value.expiresAt().isAfter(now.plusSeconds(300))
                || value.sources().isEmpty() || value.sources().size() > 64) throw blocked();
        try { PricingSourceFacts.key(value.reference()); }
        catch (RuntimeException invalid) { throw blocked(); }
        var identities = new HashSet<OwnerIdentity>();
        for (var source : value.sources()) {
            if (source == null || !identities.add(new OwnerIdentity(source.sourceSystem(), source.sourceType(),
                    source.sourceNo(), source.scopeNo()))) throw blocked();
        }
        return value;
    }
    private record OwnerIdentity(String system, String type, String number, String scope) { }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
