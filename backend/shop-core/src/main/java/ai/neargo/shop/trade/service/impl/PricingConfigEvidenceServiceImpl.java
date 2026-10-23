package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Snapshot;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.entity.OrdPricingTemplateRevision;
import ai.neargo.shop.trade.entity.OrdStorePricingConfig;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateRevisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.service.PricingConfigEvidenceService;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingFundingCapabilityService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Narrow actual negative capability evidence: a missing owner is never interpreted as zero supported benefits. */
@Service
public class PricingConfigEvidenceServiceImpl implements PricingConfigEvidenceService {
    private final StorePricingConfigMapper configs;
    private final PricingTemplateRevisionMapper templates;
    private final StoreDirectoryPort stores;
    private final MerchantScopeFactsPort owners;
    private final MerchantQueryPort merchants;
    private final MarketPort markets;
    private final PricingFundingCapabilityService funding;
    private final PricingTaxSettingPort taxes;
    private final Clock clock;

    @Autowired
    public PricingConfigEvidenceServiceImpl(StorePricingConfigMapper configs, PricingTemplateRevisionMapper templates,
            StoreDirectoryPort stores, MerchantScopeFactsPort owners, MerchantQueryPort merchants, MarketPort markets,
            PricingFundingCapabilityService funding, PricingTaxSettingPort taxes) {
        this(configs, templates, stores, owners, merchants, markets, funding, taxes, Clock.systemUTC());
    }
    public PricingConfigEvidenceServiceImpl(StorePricingConfigMapper configs, PricingTemplateRevisionMapper templates,
            StoreDirectoryPort stores, MerchantScopeFactsPort owners, MerchantQueryPort merchants, MarketPort markets,
            PricingFundingCapabilityService funding, PricingTaxSettingPort taxes, Clock clock) {
        this.configs = configs; this.templates = templates; this.stores = stores; this.owners = owners;
        this.merchants = merchants; this.markets = markets; this.funding = funding; this.taxes = taxes; this.clock = clock;
    }
    @Override public Evidence load(Scope scope) {
        if (scope == null || scope.template() || TransactionSynchronizationManager.isActualTransactionActive()) {
            throw blocked();
        }
        try {
            PricingSourceFacts.key(scope.tenantNo()); PricingSourceFacts.key(scope.entityNo());
            PricingSourceFacts.key(scope.storeNo()); PricingSourceFacts.key(scope.resourceNo());
            if (!"MAIN".equals(scope.tenantNo())) throw blocked();
        } catch (IllegalArgumentException invalid) { throw blocked(); }
        var first = facts(scope);
        var current = facts(scope);
        if (!first.equals(current)) throw BizException.of(ErrorCode.CONFLICT);
        long bucket = Math.floorDiv(clock.instant().getEpochSecond(), 300);
        Instant expiry = Instant.ofEpochSecond(Math.multiplyExact(bucket + 1, 300));
        String reference = PricingSnapshotHash.of(List.of("FOOD_CART_NEGATIVE_CAPABILITY_V1", scope, current, bucket))
                .substring(0, 32);
        var sources = new ArrayList<PricingSourceRef>();
        // Source versions here are real descriptor schema versions; the owner receipt hash identifies its contents.
        sources.add(descriptor(current.coupons())); sources.add(descriptor(current.stored()));
        var tax = current.tax();
        sources.add(new PricingSourceRef("CORE", "PRICING_TAX", tax.ownerNo(), tax.sourceVersion(),
                scope.storeNo(), tax.managedVersion()));
        sources.add(new PricingSourceRef("CORE", current.resource().type(), current.resource().no(),
                current.resource().version(), null, null));
        return new Evidence(scope, new PricingCapabilities(0, 0, false, false, false), reference, expiry,
                Set.of(), sources);
    }
    private Facts facts(Scope scope) {
        if (!owners.entityActive(scope.tenantNo(), scope.entityNo())
                || !owners.storesOwned(scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo()))) throw blocked();
        var cards = stores.cards(List.of(scope.storeNo()));
        var card = cards == null ? null : cards.get(scope.storeNo());
        if (card == null || !scope.storeNo().equals(card.storeNo()) || !scope.entityNo().equals(card.entityNo())
                || !card.active()) throw blocked();
        String marketNo = merchants.marketOf(scope.entityNo());
        var market = markets.find(marketNo).filter(MarketPort.MarketBrief::enabled).orElseThrow(
                PricingConfigEvidenceServiceImpl::blocked);
        // The current FOOD price owner explicitly resolves CN SKUs; never re-label those prices in another market.
        if (!"CN".equals(market.market()) || !"CNY".equals(market.currency()) || market.currencyScale() != 2) throw blocked();
        var resource = resource(scope);
        var ownerScope = new PricingFundingCapabilities.Scope(scope.tenantNo(), scope.entityNo(), scope.storeNo(),
                "FOOD", "CART", market.currency());
        var coupons = unsupported(Family.ORDER_COUPON, ownerScope);
        var stored = unsupported(Family.STORED_VALUE, ownerScope);
        var tax = taxes.resolve(new PricingTaxSettingPort.Scope(scope.tenantNo(), scope.entityNo(), scope.storeNo()));
        if (tax == null || !scope.tenantNo().equals(tax.tenantNo()) || !scope.entityNo().equals(tax.entityNo())
                || !scope.storeNo().equals(tax.storeNo())) throw blocked();
        return new Facts(scope.tenantNo(), card.entityNo(), card.storeNo(), card.status(), market.market(),
                market.currency(), market.currencyScale(), resource, coupons, stored, tax);
    }
    private Snapshot unsupported(Family family, PricingFundingCapabilities.Scope scope) {
        var value = funding.inspect(family, scope);
        try { PricingFundingCapabilities.validate(value, family, scope); }
        catch (IllegalArgumentException invalid) { throw blocked(); }
        if (value.status() != Status.SOURCE_UNSUPPORTED) throw blocked();
        return value;
    }
    private Resource resource(Scope scope) {
        return DataScopeContext.executeWithoutScope(() -> {
            var config = configs.selectOne(new QueryWrapper<OrdStorePricingConfig>()
                    .eq("tenant_no", scope.tenantNo()).eq("entity_no", scope.entityNo())
                    .eq("store_no", scope.storeNo()).eq("config_no", scope.resourceNo()).eq("deleted", 0));
            if (config != null) {
                if (!scope.tenantNo().equals(config.getTenantNo()) || !scope.entityNo().equals(config.getEntityNo())
                        || !scope.storeNo().equals(config.getStoreNo()) || !scope.resourceNo().equals(config.getConfigNo())
                        || !"FOOD".equals(config.getIndustryCode()) || !"ACTIVE".equals(config.getStatus())
                        || !Integer.valueOf(3).equals(config.getDraftSchemaVersion()) || config.getVersion() == null) {
                    throw blocked();
                }
                var template = template(scope, config.getDraftTemplateRevisionNo());
                if (!Objects.equals(config.getTemplateNo(), template.getTemplateNo())) throw blocked();
                String hash = PricingSnapshotHash.of(Arrays.asList(config.getTemplateNo(), config.getActiveRevisionNo(),
                        config.getDraftTemplateRevisionNo(), config.getDraftOverride(), config.getDraftVersion(),
                        template.getDefinitionHash(), template.getRevision()));
                return new Resource("PRICING_CONFIG", config.getConfigNo(), config.getVersion(), hash);
            }
            var template = template(scope, scope.resourceNo());
            return new Resource("PRICING_TEMPLATE", template.getRevisionNo(), template.getRevision(),
                    template.getDefinitionHash());
        });
    }
    private OrdPricingTemplateRevision template(Scope scope, String no) {
        if (no == null) throw blocked();
        var template = templates.selectOne(new QueryWrapper<OrdPricingTemplateRevision>()
                .eq("tenant_no", scope.tenantNo()).eq("revision_no", no).eq("industry_code", "FOOD").eq("deleted", 0));
        if (template == null || !scope.tenantNo().equals(template.getTenantNo())
                || !no.equals(template.getRevisionNo()) || !"FOOD".equals(template.getIndustryCode())
                || !Integer.valueOf(3).equals(template.getSchemaVersion()) || template.getRevision() == null
                || template.getRevision() < 1 || template.getDefinitionHash() == null
                || !template.getDefinitionHash().matches("[0-9a-f]{64}")
                || template.getDefinitionSnapshot() == null
                || !template.getDefinitionHash().equals(PricingRuntimeConfigPolicy.sha256(
                        template.getDefinitionSnapshot()))) throw blocked();
        return template;
    }
    private static PricingSourceRef descriptor(Snapshot value) {
        return new PricingSourceRef("PAY", value.family().name(), value.snapshotHash().substring(0, 32),
                value.schemaVersion(), null, null);
    }
    private record Resource(String type, String no, long version, String hash) { }
    private record Facts(String tenantNo, String entityNo, String storeNo, String storeStatus, String market,
            String currency, int currencyScale, Resource resource, Snapshot coupons, Snapshot stored,
            MerchantTaxSnapshot tax) { }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
}
