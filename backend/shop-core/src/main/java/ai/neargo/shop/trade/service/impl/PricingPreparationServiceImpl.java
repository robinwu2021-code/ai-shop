package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PricingSnapshotHash;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.trade.dto.PricingPreparation.Prepared;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.service.PricingPreparationService;
import ai.neargo.shop.trade.service.PricingRuntimeConfigService;
import ai.neargo.shop.trade.service.PricingSourceService;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Optimistic source/rule/tax consistency only; order persistence must independently revalidate these prerequisites. */
@Service
public class PricingPreparationServiceImpl implements PricingPreparationService {
    private final PricingSourceService sources;
    private final PricingRuntimeConfigService rules;
    private final PricingTaxSettingPort taxes;
    private final Clock clock;
    @Autowired
    public PricingPreparationServiceImpl(PricingSourceService sources, PricingRuntimeConfigService rules,
            PricingTaxSettingPort taxes) { this(sources, rules, taxes, Clock.systemUTC()); }
    public PricingPreparationServiceImpl(PricingSourceService sources, PricingRuntimeConfigService rules,
            PricingTaxSettingPort taxes, Clock clock) {
        this.sources = sources; this.rules = rules; this.taxes = taxes; this.clock = clock;
    }
    @Override public Prepared staff(Scope scope, PricingSourceRef source) {
        return prepare(() -> sources.staff(scope, source));
    }
    @Override public Prepared customer(PricingSourceRef source, Credentials credentials) {
        return prepare(() -> sources.customer(source, credentials));
    }
    @Override public Prepared confirmStaff(Scope scope, PricingSourceRef source, String expectedPreparationHash) {
        hash(expectedPreparationHash); return same(staff(scope, source), expectedPreparationHash);
    }
    @Override public Prepared confirmCustomer(PricingSourceRef source, Credentials credentials, String expectedHash) {
        hash(expectedHash); return same(customer(source, credentials), expectedHash);
    }
    private Prepared prepare(Supplier<Snapshot> read) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw blocked();
        var source = read.get();
        try { PricingSourceFacts.validate(source); }
        catch (RuntimeException invalid) { throw blocked(); }
        String industry = source.source().sourceSystem();
        if (!Set.of("FOOD", "RETAIL", "BEAUTY_SERVICE").contains(industry)) throw blocked();
        var scope = source.scope();
        var first = config(scope, industry);
        var tax = tax(scope);
        var current = read.get();
        try { PricingSourceFacts.validate(current); }
        catch (RuntimeException invalid) { throw blocked(); }
        if (current == null || !source.snapshotHash().equals(current.snapshotHash())) throw conflict();
        var currentConfig = config(scope, industry);
        var currentTax = tax(scope);
        if (!first.snapshotHash().equals(currentConfig.snapshotHash()) || !tax.equals(currentTax)
                || !clock.instant().isBefore(currentConfig.evidence().expiresAt())) throw conflict();
        String digest = PricingSnapshotHash.of(List.of("PRICING_PREPARATION_V1", current.snapshotHash(),
                currentConfig.snapshotHash(), currentTax, currentConfig.evidence().expiresAt()));
        return new Prepared(current, currentConfig, currentTax, currentConfig.evidence().expiresAt(), digest);
    }
    private RuntimeConfig config(Scope scope, String industry) {
        var result = rules.resolve(scope, industry);
        if (result == null || !scope.equals(result.scope()) || !industry.equals(result.industry())
                || result.evidence() == null || result.evidence().expiresAt() == null
                || result.snapshotHash() == null || !result.snapshotHash().matches("[0-9a-f]{64}")) throw blocked();
        return result;
    }
    private MerchantTaxSnapshot tax(Scope scope) {
        var value = taxes.resolve(new PricingTaxSettingPort.Scope(scope.tenantNo(), scope.entityNo(), scope.storeNo()));
        if (value == null || !scope.tenantNo().equals(value.tenantNo()) || !scope.entityNo().equals(value.entityNo())
                || !scope.storeNo().equals(value.storeNo())) throw blocked();
        return value;
    }
    private static Prepared same(Prepared current, String expected) {
        if (!current.preparationHash().equals(expected)) throw conflict();
        return current;
    }
    private static void hash(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) throw BizException.of(ErrorCode.BAD_REQUEST);
    }
    private static BizException blocked() { return BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED); }
    private static BizException conflict() { return BizException.of(ErrorCode.CONFLICT); }
}
